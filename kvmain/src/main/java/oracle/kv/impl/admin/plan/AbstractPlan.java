/*-
 * Copyright (C) 2011, 2025 Oracle and/or its affiliates. All rights reserved.
 *
 * This file was distributed by Oracle as part of a version of Oracle NoSQL
 * Database made available at:
 *
 * http://www.oracle.com/technetwork/database/database-technologies/nosqldb/downloads/index.html
 *
 * Please see the LICENSE file included in the top-level directory of the
 * appropriate version of Oracle NoSQL Database for a copy of the license and
 * additional information.
 */

package oracle.kv.impl.admin.plan;

import java.io.EOFException;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.Formatter;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import oracle.kv.impl.admin.Admin;
import oracle.kv.impl.admin.AdminServiceParams;
import oracle.kv.impl.admin.CommandResult;
import oracle.kv.impl.admin.IllegalCommandException;
import oracle.kv.impl.admin.PlanLocksHeldException;
import oracle.kv.impl.admin.PlanWaiter;
import oracle.kv.impl.admin.plan.ExecutionState.ExceptionTransfer;
import oracle.kv.impl.admin.plan.task.ParallelBundle;
import oracle.kv.impl.admin.plan.task.Task;
import oracle.kv.impl.admin.plan.task.TaskList;
import oracle.kv.impl.metadata.Metadata;
import oracle.kv.impl.security.ResourceOwner;
import oracle.kv.impl.security.login.LoginManager;
import oracle.kv.impl.security.util.SecurityUtils;
import oracle.kv.impl.util.FormatUtils;
import oracle.nosql.common.json.JsonUtils;
import oracle.kv.impl.util.RateLimitingLogger;
import oracle.kv.util.ErrorMessage;

import oracle.nosql.common.json.ObjectNode;

/**
 * Encapsulates a definition and mechanism for making a change to the KV
 * Store. Subclasses of AbstractPlan will define the different types of
 * AbstractPlan that can be carried out.
 *
 * Synchronization
 * ---------------
 * Any modifications to the plan at execution time, including the execution
 * state, plan run, and task run instances contained within it, must
 * synchronize on the plan instance. At execution time, there will be multiple
 * thread modifying these state fields, as well as threads that are trying
 * to save the instance to its database.
 *
 * The synchronization locking order is
 *  1. the Admin service (oracle.kv.impl.admin.Admin)
 *  2. the plan instance
 *  3. JE locks in the AdminDB
 *
 * Because of that, any synchronized plan methods must be careful not to try to
 * acquire the Admin mutex after already owning the plan mutex, lest a deadlock
 * occur. [#22161]
 *
 * Likewise, the mutex on the plan instance must be taken before any JE locks
 * are acquired. [#23134] Creating a hierarchy between database locks and
 * mutexes is regrettable, but is needed to prevent modification of the plan
 * instance while it is being serialized as a precursor to the database
 * operation. For example, the plan mutex is taken by the persist() method, in
 * this way:
 *  - obtain plan mutex
 *  - put (serialize plan, acquire write lock on plan record)
 *
 *  or the DeployTopoPlan.incrementEndCount()
 *  - obtain plan mutex
 *  - read current topology from Admin DB (acquire a topo read lock, then
 *  release)
 *  - update field in plan based on current topology
 *
 * Because of this, we must refrain from starting Admin transactions that
 * acquire JE locks and then attempt to obtain the plan mutex.
 */
public abstract class AbstractPlan implements Plan, Serializable {

    private static final long serialVersionUID = 1L;

    /* Version for plans created before R3.1.0 */
    private static final int INITIAL_PLAN_VERSION = 1;

    private static final int CURRENT_PLAN_VERSION = 2;

    /**
     * Plan name prefix for plans generated by the Admin (vs. plans created
     * as a result of external actions).
     */
    private static final String SYS_PLAN_PREFIX = "SYS$";

    /**
     * A unique sequence id for a plan.
     */
    private final int id;

    /**
     * A user defined name.
     */
    private final String name;

    /**
     * The time this plan was created.
     */
    protected final long createTime;

    /**
     * A list of tasks that will be executed to carry out this plan.
     */
    protected final TaskList taskList;

    /**
     * Plans may be executed multiple times. ExecutionState tracks the status
     * of each run.
     */
    private final ExecutionState executionState;

    /**
     * A transient reference to the owning Planner.  This field must be set
     * whenever a Plan is constructed or retrieved from the database.
     */
    protected transient Planner planner;

    /**
     * The plan execution listeners enable monitoring, testing, and
     * asynchronous plan execution. Their invocation must be ordered, because
     * the optional PlanWaiter signifies that all plan related execution is
     * done, and it must be the last callback executed. Listeners may be added
     * and viewed concurrently, so access to the list should be synchronized.
     */
    private transient LinkedList<ExecutionListener> listeners;

    /* Set only when the plan is run. */
    protected transient Logger logger;

    /* Rate limiting logger, created on-demand */
    private transient RateLimitingLogger<String> rateLimitingLogger = null;

    /**
     * From R3.Q3, as a part of authorization, each plan object will be
     * associated with its creator as the owner. If a plan is created in an
     * earlier version, or is created in a store without security, the owner
     * will be null. We make it transient to be compatible with nodes using DPL
     * storage during upgrade.
     */
    private transient ResourceOwner owner;

    /*
     * Version of this plan object.
     * TODO - can this be made persistent?
     */
    private transient Integer version;

    /**
     * Base plan object.
     *
     * @param name
     * @param planner
     */
    protected AbstractPlan(String name, Planner planner) {
        this(name, planner, false);
    }

    /**
     * Base plan object.
     */
    protected AbstractPlan(String name, Planner planner, boolean systemPlan) {
        version = CURRENT_PLAN_VERSION;
        id = planner.getAndIncrementPlanId();

        if (name == null) {
            this.name = getDefaultName();
        } else {
            if (name.startsWith(SYS_PLAN_PREFIX)) {
                throw new IllegalCommandException("Plan names cannot start" +
                                                  " with " + SYS_PLAN_PREFIX);
            }
            this.name = systemPlan ? SYS_PLAN_PREFIX + name : name;
        }

        createTime = System.currentTimeMillis();
        taskList = new TaskList(TaskList.ExecutionStrategy.SERIAL);

        initTransientFields();

        this.planner = planner;

        executionState = new ExecutionState(name);

        initWithParams(planner.getAdmin().getParams());
        this.owner = SecurityUtils.currentUserAsOwner();
    }

    protected synchronized void initTransientFields() {
        listeners = new LinkedList<>();
    }

    private void initWithParams(AdminServiceParams aServiceParams) {
        addListener(new PlanTracker(aServiceParams));
    }

    @Override
    public void initializePlan(Planner planner1,
                                 AdminServiceParams aServiceParams) {
        planner = planner1;
        initWithParams(aServiceParams);
    }

    /**
     * Loggers are set just before plan execution.
     */
    void setLogger(Logger logger1) {
        this.logger = logger1;
    }

    @Override
    public int getId() {
        return id;
    }

    synchronized void validateStartOfRun() {
        executionState.validateStartOfNewRun(this);
    }

    synchronized PlanRun startNewRun() {
        return executionState.startNewRun();
    }

    /**
     * Gets the current state of this plan, as far as it is known by the
     * system.  In the event that this Admin has failed and the plan has been
     * read from the database, a plan may temporarily be in a RUNNING state
     * before moving to INTERRUPTED.
     *
     * @return the most recently computed state
     */
    @Override
    public synchronized State getState() {
        return executionState.getLatestState();
    }

    /**
     * Note that checking and changing the state must be atomic, and is
     * synchronized.
     */
    synchronized void requestApproval() {
        final State currentState = getState();
        if (currentState.approvedAndCanExecute()) {
            /* We're just trying to retry a plan, no need to approve again. */
            return;
        }

        executionState.setPlanState(planner, this,
                                    State.APPROVED, "approval requested");
    }

    /**
     * Called whenever a plan is being canceled.  Note that checking and
     * changing the state must be atomic, and is synchronized.
     */
    synchronized void requestCancellation() {

        executionState.setPlanState(planner, this, State.CANCELED,
                                    "cancellation requested");
    }

    /**
     * Check if is possible to directly cancel this plan without doing
     * interrupt processing, because it has not
     * started, or was already canceled.
     * @return true if the plan was not started, and has been canceled,
     * false if this plan is running, and if steps must be taken to interrupt
     * it.
     */
     synchronized boolean cancelIfNotStarted() {

        /* Already canceled. */
        final State state = executionState.getLatestState();
        if (state == State.CANCELED) {
            return true;
        }

        if ((state == State.PENDING) ||
            (state == State.APPROVED)) {
            requestCancellation();
            return true;
        }
        return false;
    }

    /**
     * Change a RUNNING or INTERRUPT_REQUESTED plan to INTERRUPTED.
     */
    @Override
    public synchronized void markAsInterrupted() {

        final State state = executionState.getLatestState();
        if (state == State.RUNNING) {
            executionState.setPlanState(planner, this,
                                        State.INTERRUPT_REQUESTED,
                                        "plan recovery");
            executionState.setPlanState(planner, this, State.INTERRUPTED,
                                        "plan recovery");
       } else if (state == State.INTERRUPT_REQUESTED) {
           executionState.setPlanState(planner, this, State.INTERRUPTED,
                                       "plan recovery");
       }
    }

    /**
     * Be sure to synchronize properly when setting plan state, so other methods
     * which check plan state, like addWaiter(), will be correct.
     */
    synchronized void setState(PlanRun planRun,
                               Planner plr,
                               State newState,
                               String msg) {
        planRun.setState(plr, this, newState, msg);
    }

    /**
     * Set the request flag to start a plan interruption. CALLER must
     * synchronize on the Admin first, because the requestInterrupted may
     * attempt to save the plan to the database. Doing so requires the Admin
     * mutex. Since the lock hierachy is {@literal Admin->Plan}, the caller
     * must synchronize on Admin, and then acquire the plan mutex with this
     * call.
     */
    synchronized void requestInterrupt() {
        final PlanRun planRun = executionState.getLatestPlanRun();

        /* This plan isn't running */
        if (planRun == null) {
            return;
        }

        if (getState() == State.RUNNING) {
            executionState.setPlanState
                (planner, this, State.INTERRUPT_REQUESTED,
                 "plan interrupt");
            planner.getAdmin().savePlan(this, Admin.CAUSE_INTERRUPT_REQUEST);
        }

        planRun.requestInterrupt();
    }

    /**
     * @see Plan#addTask
     */
    @Override
    public synchronized void addTask(Task t) {
        if ((t instanceof ParallelBundle) && ((ParallelBundle)t).isEmpty()) {
            return;
        }
        taskList.add(t);
    }

    /**
     * @return the TaskList for this plan.
     */
    @Override
    public TaskList getTaskList() {
        return taskList;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "Plan " + id + " [" + getName() + "]";
    }

    /**
     * @return the createTime
     */
    @Override
    public Date getCreateTime() {
        return new Date(createTime);
    }

    /**
     * @return the startTime
     */
    @Override
    public Date getStartTime() {
        return executionState.getLatestStartTime();
    }

    /**
     * @return the endTime
     */
    @Override
    public Date getEndTime() {
        return executionState.getLatestEndTime();
    }

    @Override
    public Planner getPlanner() {
        return planner;
    }

    /**
     * Override to save data at the beginning of execution. Default
     * implementation is a no-op.
     */
    void preExecutionSave() {
    }

    public Logger getLogger() {
        return logger;
    }

    /**
     * Gets a rate limiting logger that logs a given message no more that once
     * per minute.
     */
    public RateLimitingLogger<String> getRateLimitingLogger() {
        if (rateLimitingLogger == null) {
            rateLimitingLogger = new RateLimitingLogger<>(60 * 1000, 10, logger);
        }
        return rateLimitingLogger;
    }

    @Override
    public void stripForDisplay() {
        /* Default implementation is a noop */
    }

    /**
     * Return the Admin to which this planner belongs.
     */
    public Admin getAdmin() {
        if (planner != null) {
            return planner.getAdmin();
        }
        return null;
    }

    /**
     * ExecutionListeners are used to generate monitoring information about
     * plan execution, and for testing.
     */
    synchronized void addListener(ExecutionListener listener) {
        listeners.addFirst(listener);
    }

    /**
     * Listeners must be called in a specific order.
     */
    synchronized List<ExecutionListener> getListeners() {
        /* Return a new list, to guard against concurrent modification. */
        return new ArrayList<>(listeners);
    }

    /**
     * A PlanWaiter should be added at the end of the listener list, so it
     * executes after all the other listeners, because it signifies the end of
     * all plan related execution.
     */
    @Override
    public synchronized PlanRun addWaiter(PlanWaiter waiter) {
        listeners.addLast(waiter);

        if (logger != null) {
            logger.log(Level.FINE,
                       "Adding plan waiter to {0}/{1}, state={2}",
                       new Object[]{getId(), getName(), getState()});
        }

        /* If the plan has ended already, release the waiter. */
        if (getState().planExecutionFinished()) {
            waiter.planEnd(this);
        }

        return getExecutionState().getLatestPlanRun();
    }

    @Override
    public synchronized void removeWaiter(PlanWaiter waiter) {
        listeners.remove(waiter);
    }

    /**
     * @return any failures from the most recent run, for display.
     */
    @Override
    public String getLatestRunFailureDescription() {
        return executionState.getLatestRunFailureDescription();
    }

    /**
     * For unit test support. Get the saved Exception and description for a
     * plan failure.
     */
    ExceptionTransfer getExceptionTransfer() {
        return executionState.getLatestExceptionTransfer();
    }

    /**
     * Return a formatted string representing the history of execution attempts
     * for this plan.
     */
    @Override
    public String showRuns() {
        return executionState.showRuns();
    }

    /**
     * Return the execution state object, from which we can extract detailed
     * information about the plan's execution history.
     */
    @Override
    public ExecutionState getExecutionState() {
        return executionState;
    }

    /**
     * Return the total number of tasks in the plan, including nested tasks.
     */
    @Override
    public int getTotalTaskCount() {
        return taskList.getTotalTaskCount();
    }

    /**
     * Return true if an interrupt has been requested. Long running tasks
     * must check this and return promptly if it's set.
     */
    public synchronized boolean isInterruptRequested() {
        return executionState.getLatestPlanRun().isInterruptRequested();
    }

    /**
     * Return true if an interrupt has been requested after the cleanup
     * phase started, and therefore we should actually interrupt task cleanup.
     */
    public synchronized boolean cleanupInterrupted() {
        return executionState.getLatestPlanRun().cleanupInterrupted();
    }

    synchronized void setCleanupStarted() {
        executionState.getLatestPlanRun().setCleanupStarted();
    }

    /**
     * Describe all finished tasks, for a status report. Plans can override
     * this to provide a more informative, user friendly report for specific
     * plans.
     */
    @Override
    public void describeFinished(final Formatter fm,
                                 final List<TaskRun> finished,
                                 int errorCount,
                                 final boolean verbose) {
        if (verbose) {
            /* show all tasks */
            for (TaskRun tRun : finished) {
                if (tRun.getState() == Task.State.ERROR) {
                    describeOneFailedTask(fm, tRun);
                    continue;
                }

                fm.format("   Task %3d %" + Task.LONGEST_STATE +
                          "s at %25s: %s\n",
                          tRun.getTaskNum(),
                          tRun.getState(),
                          FormatUtils.formatDateTime(tRun.getEndTime()),
                          tRun.getTask());

                final String details =
                        tRun.displayTaskDetails("              ");
                if (details != null) {
                    fm.format("%s\n", details);
                }
            }
            return;
        }

        /* If not verbose, only list the errors */
        if (errorCount > 0) {
            fm.format("\nFailures:\n");
            for (TaskRun tRun : finished) {
                if (tRun.getState() == Task.State.ERROR) {
                    describeOneFailedTask(fm, tRun);
                }
            }
        }
    }

    void describeOneFailedTask(final Formatter fm,
                               TaskRun tRun) {
        final String failDesc = tRun.getFailureDescription();
        if (failDesc == null) {
            fm.format("   Task %3d %" + Task.LONGEST_STATE +
                      "s at %25s: %s\n",
                      tRun.getTaskNum(),
                      tRun.getState(),
                      FormatUtils.formatDateTime(tRun.getEndTime()),
                      tRun.getTask());
        } else {
            fm.format("   Task %3d %" + Task.LONGEST_STATE +
                      "s at %25s: %s: %s\n",
                      tRun.getTaskNum(),
                      tRun.getState(),
                      FormatUtils.formatDateTime(tRun.getEndTime()),
                      tRun.getTask(), failDesc);
        }
    }

    /**
     * Describe all running tasks, for a status report. Plans can override
     * this to provide a more informative, user friendly report for specific
     * plans.
     */
    @Override
    public void describeRunning(Formatter fm,
                                final List<TaskRun> running,
                                boolean verbose) {

        for (TaskRun tRun : running) {
            fm.format("   Task %d/%s started at %s\n",
                      tRun.getTaskNum(), tRun.getTask(),
                      FormatUtils.formatDateTime(tRun.getStartTime()));

        }
    }

    /**
     * Describe all pending tasks, for a status report. Plans can override
     * this to provide a more informative, user friendly report for specific
     * plans.
     */
    @Override
    public void describeNotStarted(Formatter fm,
                                   final List<Task> notStarted,
                                   boolean verbose) {
        for (Task t : notStarted) {
            fm.format("   Task %s\n", t);
        }
    }

    /**
     * Gets the component locks for this plan. Locks are acquired by calling
     * acquireLocks() on the plan, and then on each task.
     *
     * @throws PlanLocksHeldException
     */
    @Override
    public final void getCatalogLocks() throws PlanLocksHeldException {
        acquireLocks();
        for (Task t : taskList.getTasks()) {
            t.acquireLocks(planner);
        }
    }

    /**
     * Obtains any required plan specific locks before plan execution to avoid
     * conflicts with concurrent plans. Plans override to add additional locking
     * beyond locks taken by individual tasks.
     *
     * @throws PlanLocksHeldException
     */
    @SuppressWarnings("unused")
    protected void acquireLocks() throws PlanLocksHeldException {
    }

    /**
     * By default, no checks to be done. A logger is supplied, instead of
     * using the plan's logger, because the plan's logger is not set yet.
     */
    @Override
    public void preExecuteCheck(boolean force, Logger plannerlogger) {
    }

    /**
     * Synchronize when updating task execution information.
     */
    synchronized void saveFailure(TaskRun taskRun,
                                  Throwable t,
                                  String problemDescription,
                                  ErrorMessage errorMsg,
                                  String[] cleanupJobs,
                                  Logger logger2) {
        taskRun.saveFailure(this, t, problemDescription, errorMsg, cleanupJobs,
                            logger2);
    }

    /**
     * Synchronize when updating task execution information.
     */
    synchronized void setTaskState(TaskRun taskRun,
                                          Task.State taskState,
                                          Logger logger2) {
        taskRun.setState(taskState, logger2);
    }

    @Override
    public synchronized void saveFailure(PlanRun planRun,
                                         Throwable t,
                                         String problem,
                                         ErrorMessage errorMsg,
                                         String[] cleanupJobs,
                                         Logger logger2) {
        planRun.saveFailure(t, problem, errorMsg, cleanupJobs, logger2);
    }

    synchronized TaskRun startTask(PlanRun planRun,
                                          Task task,
                                          Logger logger2) {
        return planRun.startTask(task, logger2);
    }

    synchronized void setEndTime(PlanRun planRun) {
        planRun.setEndTime();
    }

    synchronized void incrementEndCount(PlanRun planRun,
                                        Task.State state) {
        planRun.incrementEndCount(state);
        final PlanProgress planProgress = new PlanProgress(this);
        planner.getAdmin().getMonitor().publish(planProgress);
    }

    synchronized void cleanupEnded(TaskRun taskRun) {
        taskRun.cleanupEnded();
    }

    synchronized void cleanupStarted(TaskRun taskRun) {
        taskRun.cleanupStarted();
    }

    synchronized void saveCleanupFailure(TaskRun taskRun, String info) {
        taskRun.saveCleanupFailure(info);
    }

    @Override
    public synchronized ExceptionTransfer getLatestRunExceptionTransfer() {
        final PlanRun run = executionState.getLatestPlanRun();
        if (run == null) {
            return null;
        }

        return run.getExceptionTransfer();
    }

    /**
     * Default implementation. The default behavior is that a plan is not
     * persisted when metadata is updated. Specific plans should override this
     * method if they maintain persistent state based on metadata.
     *
     * @param metadata the metadata being updated
     * @return false
     */
    @Override
    public boolean updatingMetadata(Metadata<?> metadata) {
        return false;
    }

    public LoginManager getLoginManager() {
        return getAdmin().getLoginManager();
    }

    /**
     * Must be supported by any plan that will modify and persist a topology.
     * TODO: this is an accommodation to deal with the fact that TopologyPlan,
     * introduced in R1 and DeployTopoPlan, introduced in R2, represent
     * different class hierarchies. An alternative is to remove this from
     * AbstractPlan and introduce a new Plan interface, implemented by both
     * TopologyPlan and DeployTopoPlan which would support this method, but
     * interfaces have their own issues.
     */
    public DeploymentInfo getDeployedInfo() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ResourceOwner getOwner() {
        return owner;
    }

    /**
     * If a plan has its own return value, it can override this method to
     * add fields in the return ObjectNode.
     */
    @Override
    public ObjectNode getPlanJson() {
        return JsonUtils.createObjectNode();
    }

    /**
     * By default operation name is the same as plan name.
     */
    @Override
    public String getOperation() {
        return getName();
    }

    /**
     * If plan state is SUCCEEDED, return CommandSucceeds.
     * If plan state is ERROR or INTERRUPTED, return CommandFails.
     * Otherwise, return null.
     */
    @Override
    public CommandResult getCommandResult() {
        final State state = executionState.getLatestState();
        if (state == State.SUCCEEDED) {
            return new CommandResult.CommandSucceeds(getPlanJson().toString());
        }
        if (state  == State.ERROR || state == State.INTERRUPTED) {
            final ExceptionTransfer fault =
                executionState.getLatestExceptionTransfer();
            if (fault == null) {
                /* State may be updated ahead of adding ExceptionTransfer.
                 * No ExceptionTransfer yet. */
                return null;
            }
            return new CommandResult.CommandFails(fault.getDescription(),
                                                  fault.getErrorMessage(),
                                                  fault.getCleanupJobs(),
                                                  getPlanJson().toString());
        }

        /* Plan hasn't finished yet or was canceled, no result. */
        return null;
    }

    /**
     * Returns true if the this plan is a plan generated by the Admin.
     *
     * @return true if the specified plan is a plan generated by the Admin
     */
    @Override
    public boolean isSystemPlan() {
        return getName().startsWith(SYS_PLAN_PREFIX);
    }

    @Override
    public boolean logicalCompare(Plan other) {

        if (getClass() != other.getClass()) {
            return false;
        }

        /*
         * For plans to be identical, they need logically equivalent tasks, in
         * the same order.
         */
        final List<Task> tasks = PlanExecutor.getFlatTaskList(this, 0);
        final List<Task> otherTasks = PlanExecutor.getFlatTaskList(other, 0);
        if (tasks.size() != otherTasks.size()) {
            return false;
        }

        for (int i = 0; i < tasks.size(); i++) {
            final Task myTask = tasks.get(i);
            final Task otherTask = otherTasks.get(i);

            if (!myTask.logicalCompare(otherTask)) {
                return false;
            }
        }
        return true;
    }

    private void readObject(java.io.ObjectInputStream in)
        throws IOException, ClassNotFoundException {

        in.defaultReadObject();

        try {
            this.version = Integer.valueOf(in.readByte());
        } catch (EOFException eofe) {
            /*
             * Reaches the end, regards it as an initial version plan from
             * nodes earlier R3.1.0.
             */
            initTransientFields();
            return;
        }

        if (version < INITIAL_PLAN_VERSION || version > CURRENT_PLAN_VERSION) {
            throw new IOException("Unsupported plan version: " + version);
        }

        final boolean hasOwner = in.readBoolean();
        if (hasOwner) {
            this.owner = (ResourceOwner) in.readObject();
        }

        /* Initialize transient fields from deserialization */
        initTransientFields();
    }

    private void writeObject(java.io.ObjectOutputStream out)
        throws IOException {

        out.defaultWriteObject();

        if (version == null) {
            /* Re-writing an old plan object created before R3.1.0 */
            version = INITIAL_PLAN_VERSION;
        }
        out.write(version);

        if (owner != null) {
            out.writeBoolean(true);
            out.writeObject(owner);
        } else {
            out.writeBoolean(false);
        }
    }
}
