/*-
 * Copyright (C) 2002, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.sleepycat.je.dbi;

import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_OP_BIN_DELTA_DELETES;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_OP_BIN_DELTA_GETS;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_OP_BIN_DELTA_INSERTS;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_OP_BIN_DELTA_UPDATES;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_OP_GROUP_DESC;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_OP_GROUP_NAME;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_OP_RELATCHES_REQUIRED;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_OP_ROOT_SPLITS;
import static com.sleepycat.je.dbi.DbiStatDefinition.BACKUP_COPY_FILES_COUNT;
import static com.sleepycat.je.dbi.DbiStatDefinition.BACKUP_COPY_FILES_MS;
import static com.sleepycat.je.dbi.DbiStatDefinition.BACKUP_GROUP_DESC;
import static com.sleepycat.je.dbi.DbiStatDefinition.BACKUP_GROUP_NAME;
import static com.sleepycat.je.dbi.DbiStatDefinition.ENV_CREATION_TIME;
import static com.sleepycat.je.dbi.DbiStatDefinition.ENV_GROUP_DESC;
import static com.sleepycat.je.dbi.DbiStatDefinition.ENV_GROUP_NAME;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_GROUP_DESC;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_GROUP_NAME;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_PRI_DELETE;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_PRI_DELETE_FAIL;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_PRI_INSERT;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_PRI_INSERT_FAIL;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_PRI_POSITION;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_PRI_SEARCH;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_PRI_SEARCH_FAIL;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_PRI_UPDATE;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SEC_DELETE;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SEC_INSERT;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SEC_POSITION;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SEC_SEARCH;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SEC_SEARCH_FAIL;
import static com.sleepycat.je.dbi.DbiStatDefinition.THROUGHPUT_SEC_UPDATE;
import static com.sleepycat.je.util.DbVerifyStatDefinition.DB_VERIFY_GROUP_NAME;
import static com.sleepycat.je.util.DbVerifyStatDefinition.DB_VERIFY_GROUP_DESC;
import static com.sleepycat.je.util.DbVerifyStatDefinition.DBVERIFY_RUNS;
import static com.sleepycat.je.util.DbVerifyStatDefinition.DBVERIFY_RUN_TIME;
import static com.sleepycat.je.util.DbVerifyStatDefinition.DBVERIFY_PROBLEMS_FOUND;
import static com.sleepycat.je.utilint.VLSN.INVALID_VLSN;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.SortedSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sleepycat.je.CacheMode;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.DbInternal;
import com.sleepycat.je.DiskLimitException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.EnvironmentLockedException;
import com.sleepycat.je.EnvironmentMutableConfig;
import com.sleepycat.je.EnvironmentNotFoundException;
import com.sleepycat.je.EnvironmentStats;
import com.sleepycat.je.EnvironmentWedgedException;
import com.sleepycat.je.ExceptionListener;
import com.sleepycat.je.ExtinctionFilter;
import com.sleepycat.je.ExtinctionFilter.ExtinctionStatus;
import com.sleepycat.je.ProgressListener;
import com.sleepycat.je.RecoveryProgress;
import com.sleepycat.je.ReplicaConsistencyPolicy;
import com.sleepycat.je.StatsConfig;
import com.sleepycat.je.ThreadInterruptedException;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.je.TransactionStats;
import com.sleepycat.je.TransactionStats.Active;
import com.sleepycat.je.VerifyConfig;
import com.sleepycat.je.VerifySummary;
import com.sleepycat.je.VersionMismatchException;
import com.sleepycat.je.beforeimage.BeforeImageIndex;
import com.sleepycat.je.cleaner.Cleaner;
import com.sleepycat.je.cleaner.DataEraser;
import com.sleepycat.je.cleaner.ExpirationProfile;
import com.sleepycat.je.cleaner.ExtinctionScanner;
import com.sleepycat.je.cleaner.FileProtector;
import com.sleepycat.je.cleaner.UtilizationProfile;
import com.sleepycat.je.cleaner.UtilizationTracker;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.StartupTracker.Phase;
import com.sleepycat.je.evictor.Evictor;
import com.sleepycat.je.incomp.INCompressor;
import com.sleepycat.je.latch.Latch;
import com.sleepycat.je.latch.LatchFactory;
import com.sleepycat.je.latch.LatchSupport;
import com.sleepycat.je.log.FileManager;
import com.sleepycat.je.log.LogEntryHeader;
import com.sleepycat.je.log.LogEntryType;
import com.sleepycat.je.log.LogFlusher;
import com.sleepycat.je.log.LogItem;
import com.sleepycat.je.log.LogManager;
import com.sleepycat.je.log.Provisional;
import com.sleepycat.je.log.ReplicationContext;
import com.sleepycat.je.log.Trace;
import com.sleepycat.je.log.entry.LogEntry;
import com.sleepycat.je.log.entry.RestoreRequired;
import com.sleepycat.je.log.entry.SingleItemEntry;
import com.sleepycat.je.log.entry.TraceLogEntry;
import com.sleepycat.je.recovery.Checkpointer;
import com.sleepycat.je.recovery.RecoveryInfo;
import com.sleepycat.je.recovery.RecoveryManager;
import com.sleepycat.je.recovery.VLSNRecoveryProxy;
import com.sleepycat.je.rep.ReplicationConfig;
import com.sleepycat.je.statcap.EnvStatsLogger;
import com.sleepycat.je.statcap.StatCapture;
import com.sleepycat.je.statcap.StatCaptureDefinitions;
import com.sleepycat.je.statcap.StatManager;
import com.sleepycat.je.tree.BIN;
import com.sleepycat.je.tree.BINReference;
import com.sleepycat.je.tree.IN;
import com.sleepycat.je.txn.LockType;
import com.sleepycat.je.txn.LockUpgrade;
import com.sleepycat.je.txn.ThreadLocker;
import com.sleepycat.je.txn.Txn;
import com.sleepycat.je.txn.TxnManager;
import com.sleepycat.je.util.DbBackup;
import com.sleepycat.je.util.TimeSupplier;
import com.sleepycat.je.util.verify.BtreeVerifier;
import com.sleepycat.je.util.verify.BtreeVerifyContext;
import com.sleepycat.je.util.verify.DataVerifier;
import com.sleepycat.je.util.verify.ReservedFilesAutoRepair;
import com.sleepycat.je.util.verify.VerifierUtils;
import com.sleepycat.je.utilint.AtomicLongStat;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.je.utilint.IntStat;
import com.sleepycat.je.utilint.JETaskCoordinator;
import com.sleepycat.je.utilint.LoggerUtils;
import com.sleepycat.je.utilint.LongStat;
import com.sleepycat.je.utilint.RateLimitingLogger;
import com.sleepycat.je.utilint.StatDefinition;
import com.sleepycat.je.utilint.StatGroup;
import com.sleepycat.je.utilint.TaskCoordinator;
import com.sleepycat.je.utilint.TestHook;
import com.sleepycat.je.utilint.TestHookExecute;
import com.sleepycat.je.utilint.TracerFormatter;

/**
 * Underlying Environment implementation. There is a single instance for any
 * database environment opened by the application.
 */
public class EnvironmentImpl implements EnvConfigObserver {

    /*
     * Set true and run unit tests for NO_LOCKING_MODE test.
     * EnvironmentConfigTest.testInconsistentParams will fail. [#13788]
     */
    private static final boolean TEST_NO_LOCKING_MODE = false;

    private static final String TEST_MODE_SYS_PROP =
        System.getProperty("je.testMode", null);

    private static final String INITIAL_JAVA_ERROR_MSG =
        EnvironmentFailureReason.JAVA_ERROR.toString();

    /* Attributes of the entire environment */
    private volatile DbEnvState envState;
    private volatile boolean closing;// true if close has begun
    private final File envHome;
    private final AtomicInteger backupCount = new AtomicInteger(0);
    // count of in-progress dbBackup
    private boolean isTransactional; // true if env opened with DB_INIT_TRANS
    private boolean isNoLocking;     // true if env has no locking
    private boolean isReadOnly;   // true if env opened with the read only flag.
    private boolean isMemOnly;       // true if je.log.memOnly=true
    private boolean sharedCache;     // true if je.sharedCache=true
    private boolean dbEviction;
    private boolean expirationEnabled;
    private boolean exposeUserData;
    private boolean testMode;

    private boolean allowBlindOps = false;
    private boolean allowBlindPuts = false;

    private int maxEmbeddedLN = -1;

    private CacheMode cacheMode;

    /*
     * Represents whether this environment needs to be converted from
     * standalone to replicated.
     */
    protected boolean needRepConvert = false;

    private MemoryBudget memoryBudget;
    private static int adler32ChunkSize;

    /* Save so we don't have to look it up in the config manager frequently. */
    private long lockTimeout;
    private long txnTimeout;

    /* Deadlock detection. */
    private boolean deadlockDetection;
    private long deadlockDetectionDelay;

    /* Directory of databases */
    protected DbTree dbMapTree;
    private long mapTreeRootLsn = DbLsn.NULL_LSN;
    private final Latch mapTreeRootLatch;

    private final INList inMemoryINs;

    /* Services */
    protected DbConfigManager configManager;
    private final List<EnvConfigObserver> configObservers;
    protected final Logger envLogger;
    private final RateLimitingLogger<EnvironmentImpl> reservedFileRefLogger;
    private final LogManager logManager;
    private final LogFlusher logFlusher;
    private final MetadataStore metadataStore;
    private final DataVerifier dataVerifier;
    private final ReservedFilesAutoRepair reservedFilesAutoRepair;
    private final FileManager fileManager;
    private final TxnManager txnManager;
    private final TaskCoordinator taskCoordinator;
    private BeforeImageIndex beforeImageIndex;
    protected final StatManager statManager;

    /* Daemons */
    private final Evictor evictor;
    private final INCompressor inCompressor;
    private final Checkpointer checkpointer;
    private final Cleaner cleaner;
    private final ExtinctionScanner extinctionScanner;
    private final DataEraser dataEraser;
    private final BackupManager backupManager;
    private final StatCapture statCapture;

    /* Stats, debug information */
    protected final StartupTracker startupTracker;

    /* If true, call Thread.yield() at strategic points (stress test aid) */
    private static boolean forcedYield = false;

    /*
     * Used by Database, SecondaryDatabase and Cursor to protect changes to
     * secondary associations during operations that use the associations.  A
     * single latch for all databases is used to prevent deadlocks and to
     * support associations involving multiple primary databases.
     *
     * A ReentrantReadWriteLock is used directly rather than via a SharedLatch.
     * This is because reentrancy is required but not supported by SharedLatch.
     */
    private final ReentrantReadWriteLock secondaryAssociationLock;

    /**
     * The exception listener for this environment, if any has been specified.
     */
    private ExceptionListener exceptionListener = null;

    /**
     * The recovery progress listener for this environment, if any has been
     * specified.
     */
    private ProgressListener<RecoveryProgress> recoveryProgressListener = null;

    /**
     * ClassLoader used to load user-supplied classes by name.
     */
    private ClassLoader classLoader = null;

    private final ThreadLocal<BtreeVerifyContext> btreeVerifyContext =
        new ThreadLocal<>();

    /*
     * Configuration and tracking of background IO limits.  Managed by the
     * updateBackgroundReads, updateBackgroundWrites and sleepAfterBackgroundIO
     * methods.  The limits and the backlog are volatile because we check them
     * outside the synchronized block.  Other fields are updated and checked
     * while synchronized on the tracking mutex object.  The sleep mutex is
     * used to block multiple background threads while sleeping.
     */
    private volatile int backgroundSleepBacklog;
    private volatile int backgroundReadLimit;
    private volatile int backgroundWriteLimit;
    private long backgroundSleepInterval;
    private int backgroundReadCount;
    private long backgroundWriteBytes;
    private TestHook<?> backgroundSleepHook;
    private final Object backgroundTrackingMutex = new Object();
    private final Object backgroundSleepMutex = new Object();

    /*
     * ThreadLocal.get() is not cheap so we want to minimize calls to it.  We
     * only use ThreadLocals for the TreeStatsAccumulator which are only called
     * in limited circumstances.  Use this reference count to indicate that a
     * thread has set a TreeStatsAccumulator.  When it's done, it decrements
     * the counter.  It's static so that we don't have to pass around the
     * EnvironmentImpl.
     */
    private static int threadLocalReferenceCount = 0;

    /* Used to prevent multiple full thread dumps. */
    private boolean didFullThreadDump = false;

    /**
     * DbPrintLog doesn't need btree and dup comparators to function properly
     * don't require any instantiations.  This flag, if true, indicates that
     * we've been called from DbPrintLog or a similar utility.
     */
    private boolean noComparators = false;

    /*
     * A preallocated EnvironmentFailureException that is used in OOME and
     * other java.lang.Error situations so that allocation does not need to be
     * done in the OOME context.
     */
    private final EnvironmentFailureException preallocatedEFE =
        EnvironmentFailureException.makeJavaErrorException(
            INITIAL_JAVA_ERROR_MSG);

    /*
     * If the env was invalidated (even if envState is now CLOSED) this
     * contains the first EFE that invalidated it. Contains null if the env
     * was not invalidated.
     */
    private final AtomicReference<EnvironmentFailureException>
        invalidatingEFE = new AtomicReference<>();

    /*
     * The first EWE that occurred, or null. If this EWE was the first EFE to
     * invalidate the env, then wedgedEFE.get() == invalidatingEFE.get().
     */
    private final AtomicReference<EnvironmentWedgedException> wedgedEFE =
        new AtomicReference<>();

    public static final boolean USE_JAVA5_ADLER32;

    private static final String DISABLE_JAVA_ADLER32_NAME =
        "je.disable.java.adler32";

    static {
        USE_JAVA5_ADLER32 =
            System.getProperty(DISABLE_JAVA_ADLER32_NAME) == null;
    }

    /*
     * Log handlers used in java.util.logging. Handlers are per-environment,
     * and must not be static, because the output is tagged with an identifier
     * that associates the information with that environment. Handlers should
     * be closed to release resources when the environment is closed.
     *
     * Note that handlers are not statically attached to loggers. See
     * LoggerUtils.java for information on how redirect loggers are used.
     */
    private static final String INFO_FILES = "je.info";
    private static final int FILEHANDLER_LIMIT = 10000000;
    private static final int FILEHANDLER_COUNT = 10;
    private final ConsoleHandler consoleHandler;
    private final FileHandler fileHandler;

    /*
     * A Handler that was specified by the application through
     * EnvironmentConfig
     */
    private final Handler configuredHandler;
    /* cache this value as a performance optimization. */
    private boolean dbLoggingDisabled;

    /* Formatter for java.util.logging. */
    protected final Formatter formatter;

    /*
     * The internal environment handle that is passed to triggers invoked as a
     * result of AutoTransactions where no environment handle is available, and
     * in all cases of triggers involving replicated environments.
     */
    protected Environment envInternal;

    /*
     * Used to coordinate getting stats and shutting down the threads
     * that provide the stats. The shutdown of the statistics capture
     * thread will get statistics right before shutting down. The
     * acquisition of stats must be done without synchronizing on the
     * EnvironmentImpl to avoid a deadlock between the shutdown thread
     * (has the EnvironmentImpl lock) and the stat capture thread calling
     * getStats().
     */
    private final Object statSynchronizer = new Object();

    /* Stat base key used for loadStats api */
    protected Integer statKey;

    /* NodeId sequence counters */
    private final NodeSequence nodeSequence;

    /* Stats */
    private final StatGroup envStats;
    private final StatGroup backupStats;
    private final StatGroup dbVerifyStats;
    private final LongStat nDbVerifyRuns;
    private final LongStat dbVerifyRunTime;
    private final LongStat nDbVerifyProblemsFound;
    private final LongStat openTime;
    private final IntStat backupCopyFilesCount;
    private final LongStat backupCopyFilesMs;
    private final StatGroup btreeOpStats;
    private final AtomicLongStat rootSplits;
    private final AtomicLongStat relatchesRequired;
    private final AtomicLongStat binDeltaGets;
    private final AtomicLongStat binDeltaInserts;
    private final AtomicLongStat binDeltaUpdates;
    private final AtomicLongStat binDeltaDeletes;
    private final StatGroup thrputStats;
    private final AtomicLongStat priSearchOps;
    private final AtomicLongStat priSearchFailOps;
    private final AtomicLongStat secSearchOps;
    private final AtomicLongStat secSearchFailOps;
    private final AtomicLongStat priPositionOps;
    private final AtomicLongStat secPositionOps;
    private final AtomicLongStat priInsertOps;
    private final AtomicLongStat priInsertFailOps;
    private final AtomicLongStat secInsertOps;
    private final AtomicLongStat priUpdateOps;
    private final AtomicLongStat secUpdateOps;
    private final AtomicLongStat priDeleteOps;
    private final AtomicLongStat priDeleteFailOps;
    private final AtomicLongStat secDeleteOps;

    protected EnvStatsLogger envStatLogger = null;

    /* Refer to comment near declaration of these static LockUpgrades. */
    static {
        LockUpgrade.ILLEGAL.setUpgrade(null);
        LockUpgrade.EXISTING.setUpgrade(null);
        LockUpgrade.WRITE_PROMOTE.setUpgrade(LockType.WRITE);
    }

    /* May be null, see getOptionalNodeName. */
    private final String optionalNodeName;

    /* EnvironmentConfig.TREE_COMPACT_MAX_KEY_LENGTH. */
    private int compactMaxKeyLength;

    /* EnvironmentParams.ENV_LATCH_TIMEOUT. */
    private int latchTimeoutMs;

    /** {@link EnvironmentParams#ENV_TTL_CLOCK_TOLERANCE}. */
    private int ttlClockTolerance;

    /** {@link EnvironmentParams#ENV_TTL_MAX_TXN_TIME}. */
    private int ttlMaxTxnTime;

    /** {@link EnvironmentParams#ENV_TTL_LN_PURGE_DELAY}. */
    private int ttlLnPurgeDelay;

    /** {@link EnvironmentConfig#setExtinctionFilter}. */
    private ExtinctionFilter extinctionFilter;

    public EnvironmentImpl(File envHome,
                           EnvironmentConfig envConfig,
                           EnvironmentImpl sharedCacheEnv)
        throws EnvironmentNotFoundException, EnvironmentLockedException {

        this(envHome, envConfig, sharedCacheEnv, null);
    }

    /**
     * Create a database environment to represent the data in envHome.
     * dbHome. Properties from the je.properties file in that directory are
     * used to initialize the system wide property bag. Properties passed to
     * this method are used to influence the open itself.
     *
     * @param envHome absolute path of the database environment home directory
     * @param envConfig is the configuration to be used. It's already had
     *                  the je.properties file applied, and has been validated.
     * @param sharedCacheEnv if non-null, is another environment that is
     * sharing the cache with this environment; if null, this environment is
     * not sharing the cache or is the first environment to share the cache.
     *
     * @throws DatabaseException on all other failures
     *
     * @throws IllegalArgumentException via Environment ctor.
     */
    protected EnvironmentImpl(File envHome,
                              EnvironmentConfig envConfig,
                              EnvironmentImpl sharedCacheEnv,
                              ReplicationConfig repConfig)
        throws EnvironmentNotFoundException, EnvironmentLockedException {

        boolean success = false;
        startupTracker = new StartupTracker(this);
        startupTracker.start(Phase.TOTAL_ENV_OPEN);

        try {
            this.envHome = envHome;
            envState = DbEnvState.INIT;
            mapTreeRootLatch = LatchFactory.createExclusiveLatch(
                this, "MapTreeRoot", false /*collectStats*/);

            envStats = new StatGroup(ENV_GROUP_NAME, ENV_GROUP_DESC);

            openTime = new LongStat(envStats, ENV_CREATION_TIME);
            openTime.set(TimeSupplier.currentTimeMillis());

            backupStats = new StatGroup(BACKUP_GROUP_NAME, BACKUP_GROUP_DESC);
            backupCopyFilesCount =
                new IntStat(backupStats, BACKUP_COPY_FILES_COUNT);
            backupCopyFilesMs =
                new LongStat(backupStats, BACKUP_COPY_FILES_MS);

            btreeOpStats = new StatGroup(BT_OP_GROUP_NAME, BT_OP_GROUP_DESC);

            rootSplits =
                new AtomicLongStat(btreeOpStats, BT_OP_ROOT_SPLITS);
            relatchesRequired =
                new AtomicLongStat(btreeOpStats, BT_OP_RELATCHES_REQUIRED);
            binDeltaGets =
                new AtomicLongStat(btreeOpStats, BT_OP_BIN_DELTA_GETS);
            binDeltaInserts =
                new AtomicLongStat(btreeOpStats, BT_OP_BIN_DELTA_INSERTS);
            binDeltaUpdates =
                new AtomicLongStat(btreeOpStats, BT_OP_BIN_DELTA_UPDATES);
            binDeltaDeletes =
                new AtomicLongStat(btreeOpStats, BT_OP_BIN_DELTA_DELETES);

            thrputStats = new StatGroup(
                THROUGHPUT_GROUP_NAME, THROUGHPUT_GROUP_DESC);

            priSearchOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_PRI_SEARCH);
            priSearchFailOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_PRI_SEARCH_FAIL);
            secSearchOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_SEC_SEARCH);
            secSearchFailOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_SEC_SEARCH_FAIL);
            priPositionOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_PRI_POSITION);
            secPositionOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_SEC_POSITION);
            priInsertOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_PRI_INSERT);
            priInsertFailOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_PRI_INSERT_FAIL);
            secInsertOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_SEC_INSERT);
            priUpdateOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_PRI_UPDATE);
            secUpdateOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_SEC_UPDATE);
            priDeleteOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_PRI_DELETE);
            priDeleteFailOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_PRI_DELETE_FAIL);
            secDeleteOps =
                new AtomicLongStat(thrputStats, THROUGHPUT_SEC_DELETE);


            dbVerifyStats =
                new StatGroup(DB_VERIFY_GROUP_NAME,
                              DB_VERIFY_GROUP_DESC);
            nDbVerifyRuns =
                new LongStat(dbVerifyStats, DBVERIFY_RUNS);
            dbVerifyRunTime =
                new LongStat(dbVerifyStats, DBVERIFY_RUN_TIME);
            nDbVerifyProblemsFound =
                new LongStat(dbVerifyStats, DBVERIFY_PROBLEMS_FOUND);
            
            

            /* Set up configuration parameters */
            configManager = initConfigManager(envConfig, repConfig);
            configObservers = new ArrayList<>();
            addConfigObserver(this);
            initConfigParams(envConfig, repConfig);

            /*
             * Create essential services that must exist before recovery.
             */

            /*
             * Set up java.util.logging handlers and their environment specific
             * formatters. These are used by the redirect handlers, rather
             * than specific loggers.
             */
            formatter = initFormatter();
            consoleHandler =
                new com.sleepycat.je.util.ConsoleHandler(formatter, this);
            fileHandler = initFileHandler();
            configuredHandler = envConfig.getLoggingHandler();
            envLogger = LoggerUtils.getLogger(getClass());
            reservedFileRefLogger = new RateLimitingLogger<>(
                (int) TimeUnit.MINUTES.toMillis(1), 1, envLogger);

            taskCoordinator = (envConfig.getTaskCoordinator() != null) ?
                envConfig.getTaskCoordinator() :
                new JETaskCoordinator(envLogger, this, Collections.emptySet(),
                                      true /* active */);

            /*
             * Decide on memory budgets based on environment config params and
             * memory available to this process.
             */
            memoryBudget =
                new MemoryBudget(this, sharedCacheEnv, configManager);

            fileManager = new FileManager(this, envHome, isReadOnly);

            if (!envConfig.getAllowCreate() && !fileManager.filesExist() &&
                !configManager.getBoolean(EnvironmentParams.ENV_SETUP_LOGGER)) {

                throw new EnvironmentNotFoundException
                    (this, "Home directory: " + envHome);
            }

            optionalNodeName = envConfig.getNodeName();

            logManager = new LogManager(this, isReadOnly);

            inMemoryINs = new INList(this);
            txnManager = new TxnManager(this);
            metadataStore = new MetadataStore(this);
            statManager = createStatManager();

            /*
             * Daemons are always made here, but only started after recovery.
             * We want them to exist so we can call them programatically even
             * if the daemon thread is not started.
             */
            if (sharedCacheEnv != null) {
                /* The evictor may be shared by multiple envs. */
                assert sharedCache;
                evictor = sharedCacheEnv.evictor;
            } else {
                evictor = new Evictor(this);
            }

            long bytePeriod = configManager.getLong
                (EnvironmentParams.CHECKPOINTER_BYTES_INTERVAL);

            /* Checkpointing period must be set by log size.*/
            if (bytePeriod == 0) {
                throw new IllegalArgumentException
                    (EnvironmentParams.CHECKPOINTER_BYTES_INTERVAL.getName() +
                     " cannot be 0. ");
            }

            checkpointer = new Checkpointer(
                this, Environment.CHECKPOINTER_NAME);

            inCompressor = new INCompressor(
                this,
                configManager.getDuration(
                    EnvironmentParams.COMPRESSOR_WAKEUP_INTERVAL),
                Environment.INCOMP_NAME);

            cleaner = new Cleaner(this, Environment.CLEANER_NAME);

            extinctionScanner = new ExtinctionScanner(this);
            dataEraser = new DataEraser(this);

            backupManager = new BackupManager(this);

            statCapture = new StatCapture(
                this, Environment.STATCAPTURE_NAME,
                configManager.getDuration(
                    EnvironmentParams.STATS_COLLECT_INTERVAL),
                envConfig.getCustomStats(), getStatCaptureProjections(),
                statManager);

            logFlusher = new LogFlusher(this);

            dataVerifier = new DataVerifier(this);

            reservedFilesAutoRepair = new ReservedFilesAutoRepair(this);

            /*
             * The node sequences are not initialized until after the DbTree is
             * created below.
             */
            nodeSequence = new NodeSequence(this);

            /*
             * Instantiate a new, blank dbtree. If the environment already
             * exists, recovery will recreate the dbMapTree from the log and
             * overwrite this instance.
             */
            dbMapTree = new DbTree(this, isReplicated());

            secondaryAssociationLock =
                new ReentrantReadWriteLock(false /*fair*/);

            /*
             * Allocate node sequences before recovery. We expressly wait to
             * allocate it after the DbTree is created, because these sequences
             * should not be used by the DbTree before recovery has
             * run. Waiting until now to allocate them will make errors more
             * evident, since there will be a NullPointerException.
             */
            nodeSequence.initRealNodeId();

            statKey = statManager.registerStatContext();
            if ((!isReadOnly() || isArbiter() || isSubscriber()) &&
            	!isMemOnly() &&
                configManager.getBoolean(EnvironmentParams.STATS_COLLECT)) {
                envStatLogger = new EnvStatsLogger(this);
                addConfigObserver(envStatLogger);
                envStatLogger.log();
            }
            success = true;
        } finally {
            if (!success) {
                /* Release any environment locks if there was a problem. */
                clearFileManager();
                closeHandlers();
            }
        }
    }

    /**
     * Create a config manager that holds the configuration properties that
     * have been passed in. These properties are already validated, and have
     * had the proper order of precedence applied; that is, the je.properties
     * file has been applied. The configuration properties need to be available
     * before the rest of environment creation proceeds.
     *
     * This method is overridden by replication environments.
     *
     * @param envConfig is the environment configuration to use
     * @param repConfig is the replication configurations to use.
     */
    protected DbConfigManager initConfigManager(EnvironmentConfig envConfig,
                                                ReplicationConfig repConfig) {
        return new DbConfigManager(envConfig);
    }

    /**
     * Init configuration params during environment creation.
     *
     * This method is overridden by RepImpl to get init params also.  This
     * allows certain rep params to be accessed from the EnvironmentImpl
     * constructor using methods such as getPreserveVLSN. The overridden method
     * calls this method first.
     */
    protected void initConfigParams(EnvironmentConfig envConfig,
                                    ReplicationConfig repConfig) {

        configureTestMode();

        forcedYield =
            configManager.getBoolean(EnvironmentParams.ENV_FORCED_YIELD);
        isTransactional =
            configManager.getBoolean(EnvironmentParams.ENV_INIT_TXN);
        isNoLocking = !(configManager.getBoolean
                        (EnvironmentParams.ENV_INIT_LOCKING));
        if (isTransactional && isNoLocking) {
            if (TEST_NO_LOCKING_MODE) {
                isNoLocking = !isTransactional;
            } else {
                throw new IllegalArgumentException
                    ("Can't set 'je.env.isNoLocking' and " +
                     "'je.env.isTransactional';");
            }
        }

        isReadOnly = configManager.getBoolean(
            EnvironmentParams.ENV_RDONLY);

        isMemOnly = configManager.getBoolean(
            EnvironmentParams.LOG_MEMORY_ONLY);

        dbEviction = configManager.getBoolean(
            EnvironmentParams.ENV_DB_EVICTION);

        adler32ChunkSize = configManager.getInt(
            EnvironmentParams.ADLER32_CHUNK_SIZE);

        sharedCache = configManager.getBoolean(
            EnvironmentParams.ENV_SHARED_CACHE);

        dbLoggingDisabled = !configManager.getBoolean(
            EnvironmentParams.JE_LOGGING_DBLOG);

        compactMaxKeyLength = configManager.getInt(
            EnvironmentParams.TREE_COMPACT_MAX_KEY_LENGTH);

        latchTimeoutMs = configManager.getDuration(
            EnvironmentParams.ENV_LATCH_TIMEOUT);

        ttlClockTolerance = configManager.getDuration(
            EnvironmentParams.ENV_TTL_CLOCK_TOLERANCE);

        ttlMaxTxnTime = configManager.getDuration(
            EnvironmentParams.ENV_TTL_MAX_TXN_TIME);

        ttlLnPurgeDelay = configManager.getDuration(
            EnvironmentParams.ENV_TTL_LN_PURGE_DELAY);

        allowBlindOps = configManager.getBoolean(
            EnvironmentParams.BIN_DELTA_BLIND_OPS);

        allowBlindPuts = configManager.getBoolean(
            EnvironmentParams.BIN_DELTA_BLIND_PUTS);

        maxEmbeddedLN = configManager.getInt(
            EnvironmentParams.TREE_MAX_EMBEDDED_LN);

        deadlockDetection = configManager.getBoolean(
            EnvironmentParams.LOCK_DEADLOCK_DETECT);

        deadlockDetectionDelay = configManager.getDuration(
            EnvironmentParams.LOCK_DEADLOCK_DETECT_DELAY);

        recoveryProgressListener = envConfig.getRecoveryProgressListener();
        classLoader = envConfig.getClassLoader();
        extinctionFilter = envConfig.getExtinctionFilter();
    }

    /**
     * Initialize the environment, including running recovery.
     */
    public synchronized void finishInit(EnvironmentConfig envConfig)
        throws DatabaseException {

        boolean success = false;
        try {

            /*
             * Do not do recovery if this environment is for a utility that
             * reads the log directly.
             */
            final boolean doRecovery =
                configManager.getBoolean(EnvironmentParams.ENV_RECOVERY);
            if (doRecovery) {

                /*
                 * Run recovery.  Note that debug logging to the database log
                 * is disabled until recovery is finished.
                 */
                boolean recoverySuccess = false;
                try {
                    RecoveryManager recoveryManager =
                        new RecoveryManager(this);
                    recoveryManager.recover(isReadOnly);

                    postRecoveryConversion();
                    recoverySuccess = true;
                } finally {
                    try {

                        /*
                         * Flush to get all exception tracing out to the log.
                         */
                        logManager.flushSync();
                        fileManager.clear();
                    } catch (IOException e) {
                        /* Ignore second order exceptions. */
                        if (recoverySuccess) {
                            throw new EnvironmentFailureException
                                (this, EnvironmentFailureReason.LOG_INTEGRITY,
                                 e);
                        }
                    } catch (Exception e) {
                        if (recoverySuccess) {
                            throw EnvironmentFailureException.
                                unexpectedException(this, e);
                        }
                    }
                }
            } else {
                isReadOnly = true;

                /*
                 * Normally when recovery is skipped, we don't need to
                 * instantiate comparators.  But even without recovery, some
                 * utilities such as DbScavenger need comparators.
                 */
                if (!configManager.getBoolean
                        (EnvironmentParams.ENV_COMPARATORS_REQUIRED)) {
                    noComparators = true;
                }
            }

            /*
             * TODO: Move these into initConfigParams for consistency. Blocking
             *  locks taken during recovery should use an explicit timeout
             *  since we cannot rely on the user's configured timeout.
             */
            lockTimeout =
                configManager.getDuration(EnvironmentParams.LOCK_TIMEOUT);
            txnTimeout =
                configManager.getDuration(EnvironmentParams.TXN_TIMEOUT);

            /*
             * Initialize the environment memory usage number. Must be called
             * after recovery, because recovery determines the starting size of
             * the in-memory tree.
             */
            memoryBudget.initCacheMemoryUsage();
            /*
             * Call config observer and start daemons last after everything
             * else is initialized. Note that all config parameters, both
             * mutable and non-mutable, needed by the memoryBudget have already
             * been initialized when the configManager was instantiated.
             */
            envConfigUpdate(configManager, envConfig);

            if (doRecovery) {
                envInternal = createInternalEnvironment();
            }

            /*
             * Mark as open before starting daemons. Note that this will allow
             * background eviction threads to run, so it should not be done
             * until we are ready for multi-threaded access.
             */
            open();

            runOrPauseDaemons(configManager);
            beforeImageIndex = new BeforeImageIndex(this);
            success = true;
        } finally {
            if (!success) {
                /* Release any environment locks if there was a problem. */
                clearFileManager();
                closeHandlers();
            }

            /*
             * DbEnvPool.addEnvironment is called by RecoveryManager.buildTree
             * during recovery above, to enable eviction during recovery.  If
             * we fail to create the environment, we must remove it.
             */
            if (!success && sharedCache) {
                evictor.removeSharedCacheEnv(this);
            }

            startupTracker.stop(Phase.TOTAL_ENV_OPEN);
            startupTracker.setProgress(RecoveryProgress.RECOVERY_FINISHED);
        }
    }

    /**
     * Called after initialization is fully complete, including joinGroup in a
     * replicated environment. At this point, writes are allowed on a master.
     */
    public void fullyInitialized() {

        extinctionScanner.executeRecoveredTasks();
    }

    /**
     * Is overridden in RepImpl to create a ReplicatedEnvironment.
     */
    protected Environment createInternalEnvironment() {
        return new InternalEnvironment(getEnvironmentHome(), cloneConfig(),
                                       this);
    }

    /**
     * Returns the default consistency policy for this EnvironmentImpl.
     *
     * When a Txn is created directly for internal use, the default consistency
     * is needed.  For example, SyncDB uses this method.
     *
     * This method returns null for a standalone Environment, and returns the
     * default consistency policy for a ReplicatedEnvironment.
     */
    public ReplicaConsistencyPolicy getDefaultConsistencyPolicy() {
        return null;
    }

    /**
     * Allows standalone JE code to construct a consistency policy using the
     * CONSISTENCY_POLICY property value format.
     *
     * This method returns null for a standalone Environment, and returns the
     * given consistency policy for a ReplicatedEnvironment.
     */
    public ReplicaConsistencyPolicy getConsistencyPolicy(String propValue) {
        return null;
    }

    /**
     * Returns the end of the log.
     *
     * Returned value is a Lsn if it's a standalone Environment, otherwise it's
     * a VLSN.
     */
    public long getEndOfLog() {
        return fileManager.getLastUsedLsn();
    }

    /* Get replication statistics. */
    public Collection<StatGroup> getRepStatGroups(StatsConfig config,
                                                  Integer statkey) {
        throw new UnsupportedOperationException
            ("Standalone Environment doesn't support replication statistics.");
    }

    public SortedSet<String> getStatCaptureProjections() {
        return new StatCaptureDefinitions().getStatisticProjections();
    }

    public StatManager createStatManager() {
        return new StatManager(this);
    }

    /*
     * Release and close the FileManager when there are problems during the
     * initialization of this EnvironmentImpl.  An exception is already in
     * flight when this method is called.
     */
    private void clearFileManager()
        throws DatabaseException {

        if (fileManager == null) {
            return;
        }

        try {
            /*
             * Clear again, in case an exception in logManager.flush()
             * caused us to skip the earlier call to clear().
             */
            fileManager.clear();
        } catch (Throwable e) {
            /*
             * Klockwork - ok
             * Eat it, we want to throw the original exception.
             */
        }

        try {
            fileManager.close();
        } catch (Throwable e) {
            /*
             * Klockwork - ok
             * Eat it, we want to throw the original exception.
             */
        }
    }

    /**
     * Respond to config updates.
     */
    @Override
    public void envConfigUpdate(DbConfigManager mgr,
                                EnvironmentMutableConfig newConfig) {
        configureTestMode();

        backgroundReadLimit = mgr.getInt
            (EnvironmentParams.ENV_BACKGROUND_READ_LIMIT);
        backgroundWriteLimit = mgr.getInt
            (EnvironmentParams.ENV_BACKGROUND_WRITE_LIMIT);
        backgroundSleepInterval = mgr.getDuration
            (EnvironmentParams.ENV_BACKGROUND_SLEEP_INTERVAL);

        /* Reset logging levels if they're set in EnvironmentMutableConfig. */
        if (newConfig.isConfigParamSet
                (EnvironmentConfig.CONSOLE_LOGGING_LEVEL)) {
            Level newConsoleHandlerLevel =
                Level.parse(mgr.get(EnvironmentParams.JE_CONSOLE_LEVEL));
            consoleHandler.setLevel(newConsoleHandlerLevel);
        }

        if (newConfig.isConfigParamSet
                (EnvironmentConfig.FILE_LOGGING_LEVEL)) {
            Level newFileHandlerLevel =
                Level.parse(mgr.get(EnvironmentParams.JE_FILE_LEVEL));
            if (fileHandler != null) {
                fileHandler.setLevel(newFileHandlerLevel);
            }
        }

        exceptionListener = newConfig.getExceptionListener();

        cacheMode = newConfig.getCacheMode();

        expirationEnabled = mgr.getBoolean(
            EnvironmentParams.ENV_EXPIRATION_ENABLED);

        exposeUserData = mgr.getBoolean(
            EnvironmentParams.ENV_EXPOSE_USER_DATA);

        if (mgr.getBoolean(EnvironmentParams.STATS_COLLECT)) {
            if (envStatLogger == null &&
                !isReadOnly() &&
                !isMemOnly() ) {
                envStatLogger = new EnvStatsLogger(this);
                addConfigObserver(envStatLogger);

                /*
                 * Need to log env stats because stats were off and are now on.
                 * Since stats were off there was no event observer registered.
                 */
                envStatLogger.log();
            }
        } else {
            if (envStatLogger != null) {
                removeConfigObserver(envStatLogger);
            }
            envStatLogger = null;
        }

        /*
         * Start daemons last, after all other parameters are set.  Do not
         * start the daemons during the EnvironmentImpl constructor's call
         * (before open() has been called), to allow finishInit to run.
         */
        if (isValid()) {
            runOrPauseDaemons(mgr);
        }
    }

    /**
     * Run or pause daemons, depending on config properties.
     */
    private void runOrPauseDaemons(DbConfigManager mgr) {

        if (isReadOnly) {
            if (isSubscriber()) {
        		statCapture.runOrPause(
                        mgr.getBoolean(EnvironmentParams.STATS_COLLECT),
                                       getFileManager().getMiscStatsCollector());
        	}
            return;
        }

        statCapture.runOrPause(
                mgr.getBoolean(EnvironmentParams.STATS_COLLECT),
                               getFileManager().getMiscStatsCollector());
        inCompressor.runOrPause(
            mgr.getBoolean(EnvironmentParams.ENV_RUN_INCOMPRESSOR),
                           getFileManager().getCompressorStatsCollector());

        cleaner.runOrPause(
            mgr.getBoolean(EnvironmentParams.ENV_RUN_CLEANER) &&
            !isMemOnly,
            getFileManager().getCleanerStatsCollector());

        checkpointer.runOrPause(
            mgr.getBoolean(EnvironmentParams.ENV_RUN_CHECKPOINTER),
                           getFileManager().getCheckpointerStatsCollector());

        logFlusher.configFlushTask(mgr);

        if (!reservedFilesAutoRepair.startOrCheck(mgr)) {
            dataVerifier.configVerifyTask(mgr);
        }

        dataEraser.startThread();

        backupManager.startThreads();
    }

    /**
     * Return the incompressor. In general, don't use this directly because
     * it's easy to forget that the incompressor can be null at times (i.e
     * during the shutdown procedure. Instead, wrap the functionality within
     * this class, like lazyCompress.
     */
    public INCompressor getINCompressor() {
        return inCompressor;
    }

    /**
     * Returns the FileProtector.
     */
    public FileProtector getFileProtector() {
        return cleaner.getFileProtector();
    }

    /**
     * Returns the UtilizationTracker.
     */
    public UtilizationTracker getUtilizationTracker() {
        return cleaner.getUtilizationTracker();
    }

    /**
     * Returns the UtilizationProfile.
     */
    public UtilizationProfile getUtilizationProfile() {
        return cleaner.getUtilizationProfile();
    }

    /**
     * Returns the ExpirationProfile.
     */
    public ExpirationProfile getExpirationProfile() {
        return cleaner.getExpirationProfile();
    }

    /**
     * Returns the default cache mode for this environment. If the environment
     * has a null cache mode, CacheMode.DEFAULT is returned.  Null is never
     * returned.
     */
    CacheMode getDefaultCacheMode() {
        if (cacheMode != null) {
            return cacheMode;
        }
        return CacheMode.DEFAULT;
    }

    /**
     * Returns EnvironmentConfig.TREE_COMPACT_MAX_KEY_LENGTH.
     */
    public int getCompactMaxKeyLength() {
        return compactMaxKeyLength;
    }

    /**
     * Returns EnvironmentConfig.ENV_LATCH_TIMEOUT.
     */
    public int getLatchTimeoutMs() {
        return latchTimeoutMs;
    }

    /**
     * Returns {@link EnvironmentParams#ENV_TTL_CLOCK_TOLERANCE}.
     */
    public int getTtlClockTolerance() {
        return ttlClockTolerance;
    }

    /**
     * Returns {@link EnvironmentParams#ENV_TTL_MAX_TXN_TIME}.
     */
    public int getTtlMaxTxnTime() {
        return ttlMaxTxnTime;
    }

    /**
     * Returns {@link EnvironmentParams#ENV_TTL_LN_PURGE_DELAY}.
     */
    public int getTtlLnPurgeDelay() {
        return ttlLnPurgeDelay;
    }

    /**
     * If a background read limit has been configured and that limit is
     * exceeded when the cumulative total is incremented by the given number of
     * reads, increment the sleep backlog to cause a sleep to occur.  Called by
     * background activities such as the cleaner after performing a file read
     * operation.
     *
     * @see #sleepAfterBackgroundIO
     */
    public void updateBackgroundReads(int nReads) {

        /*
         * Make a copy of the volatile limit field since it could change
         * between the time we check it and the time we use it below.
         */
        int limit = backgroundReadLimit;
        if (limit > 0) {
            synchronized (backgroundTrackingMutex) {
                backgroundReadCount += nReads;
                if (backgroundReadCount >= limit) {
                    backgroundSleepBacklog += 1;
                    /* Remainder is rolled forward. */
                    backgroundReadCount -= limit;
                    assert backgroundReadCount >= 0;
                }
            }
        }
    }

    /**
     * If a background write limit has been configured and that limit is
     * exceeded when the given amount written is added to the cumulative total,
     * increment the sleep backlog to cause a sleep to occur.  Called by
     * background activities such as the checkpointer and evictor after
     * performing a file write operation.
     *
     * <p>The number of writes is estimated by dividing the bytes written by
     * the log buffer size.  Since the log write buffer is shared by all
     * writers, this is the best approximation possible.</p>
     *
     * @see #sleepAfterBackgroundIO
     */
    public void updateBackgroundWrites(int writeSize, int logBufferSize) {

        /*
         * Make a copy of the volatile limit field since it could change
         * between the time we check it and the time we use it below.
         */
        int limit = backgroundWriteLimit;
        if (limit > 0) {
            synchronized (backgroundTrackingMutex) {
                backgroundWriteBytes += writeSize;
                int writeCount = (int) (backgroundWriteBytes / logBufferSize);
                if (writeCount >= limit) {
                    backgroundSleepBacklog += 1;
                    /* Remainder is rolled forward. */
                    backgroundWriteBytes -= (limit * logBufferSize);
                    assert backgroundWriteBytes >= 0;
                }
            }
        }
    }

    /**
     * If the sleep backlog is non-zero (set by updateBackgroundReads or
     * updateBackgroundWrites), sleep for the configured interval and decrement
     * the backlog.
     *
     * <p>If two threads call this method and the first call causes a sleep,
     * the call by the second thread will block until the first thread's sleep
     * interval is over.  When the call by the second thread is unblocked, if
     * another sleep is needed then the second thread will sleep again.  In
     * other words, when lots of sleeps are needed, background threads may
     * backup.  This is intended to give foreground threads a chance to "catch
     * up" when background threads are doing a lot of IO.</p>
     */
    public void sleepAfterBackgroundIO() {
        if (backgroundSleepBacklog > 0) {
            synchronized (backgroundSleepMutex) {
                /* Sleep. Rethrow interrupts if they occur. */
                try {
                    /* FindBugs: OK that we're sleeping with a mutex held. */
                    Thread.sleep(backgroundSleepInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                /* Assert has intentional side effect for unit testing. */
                assert TestHookExecute.doHookIfSet(backgroundSleepHook);
            }
            synchronized (backgroundTrackingMutex) {
                /* Decrement backlog last to make other threads wait. */
                if (backgroundSleepBacklog > 0) {
                    backgroundSleepBacklog -= 1;
                }
            }
        }
    }

    /* For unit testing only. */
    public void setBackgroundSleepHook(TestHook<?> hook) {
        backgroundSleepHook = hook;
    }

    /**
     * Logs the map tree root and saves the LSN.
     */
    public void logMapTreeRoot()
        throws DatabaseException {

        logMapTreeRoot(DbLsn.NULL_LSN);
    }

    /**
     * Logs the map tree root, but only if its current LSN is before the
     * ifBeforeLsn parameter or ifBeforeLsn is NULL_LSN.
     */
    public void logMapTreeRoot(long ifBeforeLsn)
        throws DatabaseException {

        mapTreeRootLatch.acquireExclusive();
        try {
            if (ifBeforeLsn == DbLsn.NULL_LSN ||
                DbLsn.compareTo(mapTreeRootLsn, ifBeforeLsn) < 0) {

                mapTreeRootLsn = logManager.log(
                    SingleItemEntry.create(LogEntryType.LOG_DBTREE, dbMapTree),
                    ReplicationContext.NO_REPLICATE);
            }
        } finally {
            mapTreeRootLatch.release();
        }
    }

    /**
     * Force a rewrite of the map tree root if required.
     */
    public void rewriteMapTreeRoot(long cleanerTargetLsn)
        throws DatabaseException {

        mapTreeRootLatch.acquireExclusive();
        try {
            if (DbLsn.compareTo(cleanerTargetLsn, mapTreeRootLsn) == 0) {

                /*
                 * The root entry targetted for cleaning is in use.  Write a
                 * new copy.
                 */
                mapTreeRootLsn = logManager.log(
                    SingleItemEntry.create(LogEntryType.LOG_DBTREE, dbMapTree),
                    ReplicationContext.NO_REPLICATE);
            }
        } finally {
            mapTreeRootLatch.release();
        }
    }

    /**
     * @return the mapping tree root LSN.
     */
    public long getRootLsn() {
        return mapTreeRootLsn;
    }

    /**
     * Set the mapping tree from the log. Called during recovery.
     */
    public void readMapTreeFromLog(long rootLsn)
        throws DatabaseException {

        if (dbMapTree != null) {
            dbMapTree.close();
        }
        dbMapTree = (DbTree) logManager.getEntryHandleNotFound(rootLsn, -1);

        /* Set the dbMapTree to replicated when converted. */
        if (!dbMapTree.isReplicated() && getAllowRepConvert()) {
            dbMapTree.setIsReplicated();
            dbMapTree.setIsRepConverted();
            needRepConvert = true;
        }

        dbMapTree.initExistingEnvironment(this);

        /* Set the map tree root */
        mapTreeRootLatch.acquireExclusive();
        try {
            mapTreeRootLsn = rootLsn;
        } finally {
            mapTreeRootLatch.release();
        }
    }

    /**
     * Tells the asynchronous IN compressor thread about a BIN with a deleted
     * entry.
     */
    public void addToCompressorQueue(BIN bin) {
        inCompressor.addBinToQueue(bin);
    }

    /**
     * Tells the asynchronous IN compressor thread about a collections of
     * BINReferences with deleted entries.
     */
    public void addToCompressorQueue(Collection<BINReference> binRefs) {
        inCompressor.addMultipleBinRefsToQueue(binRefs);
    }

    public void lazyCompress(IN in) {

        if (!in.isBIN()) {
            return;
        }

        final BIN bin = (BIN) in;

        lazyCompress(bin, !bin.shouldLogDelta() /*compressDirtySlots*/);
    }

    public void lazyCompress(IN in, boolean compressDirtySlots) {
        inCompressor.lazyCompress(in, compressDirtySlots);
    }

    /**
     * Reset the logging level for specified loggers in a JE environment.
     *
     * @throws IllegalArgumentException via JEDiagnostics.OP_RESET_LOGGING
     */
    public void resetLoggingLevel(String changedLoggerName, Level level) {

        /*
         * Go through the loggers registered in the global log manager, and
         * set the new level. If the specified logger name is not valid, throw
         * an IllegalArgumentException.
         */
        java.util.logging.LogManager loggerManager =
            java.util.logging.LogManager.getLogManager();
        Enumeration<String> loggers = loggerManager.getLoggerNames();
        boolean validName = false;

        while (loggers.hasMoreElements()) {
            String loggerName = loggers.nextElement();
            Logger logger = loggerManager.getLogger(loggerName);

            if ("all".equals(changedLoggerName) ||
                loggerName.endsWith(changedLoggerName) ||
                loggerName.endsWith(changedLoggerName +
                                    LoggerUtils.NO_ENV) ||
                loggerName.endsWith(changedLoggerName +
                                    LoggerUtils.FIXED_PREFIX) ||
                loggerName.startsWith(changedLoggerName)) {

                logger.setLevel(level);
                validName = true;
            }
        }

        if (!validName) {
            throw new IllegalArgumentException
                ("The logger name parameter: " + changedLoggerName +
                 " is invalid!");
        }
    }

    /* Initialize the handler's formatter. */
    protected Formatter initFormatter() {
        return new TracerFormatter(getName());
    }

    private FileHandler initFileHandler()
        throws DatabaseException {

        /*
         * Note that in JE 3.X and earlier, file logging encompassed both
         * logging to a java.util.logging.FileHandler and our own JE log files
         * and logging was disabled for read only and in-memory environments.
         * Now that these two concepts are separated, file logging is supported
         * for in-memory environments. File logging can be supported as long as
         * there is a valid environment home.
         */
        boolean setupLoggers =
            configManager.getBoolean(EnvironmentParams.ENV_SETUP_LOGGER);
        if ((envHome == null) || (!envHome.isDirectory()) ||
            (isReadOnly && !setupLoggers)) {

            /*
             * Return null if no environment home directory(therefore no place
             * to put file handler output files), or if the Environment is read
             * only.
             */
            return null;
        }

        final String logFilePattern = getLogFilePattern();

        String handlerName = com.sleepycat.je.util.FileHandler.class.getName();

        /* Log with a rotating set of files, use append mode. */
        int limit = FILEHANDLER_LIMIT;
        String logLimit =
            LoggerUtils.getLoggerProperty(handlerName + ".limit");
        if (logLimit != null) {
            limit = Integer.parseInt(logLimit);
        }

        /* Limit the number of files. */
        int count = FILEHANDLER_COUNT;
        String logCount =
            LoggerUtils.getLoggerProperty(handlerName + ".count");
        if (logCount != null) {
            count = Integer.parseInt(logCount);
        }

        try {
            return new com.sleepycat.je.util.FileHandler(logFilePattern,
                                                         limit,
                                                         count,
                                                         formatter,
                                                         this);
        } catch (IOException e) {
            throw EnvironmentFailureException.unexpectedException
                ("Problem creating output files in: " + logFilePattern, e);
        }
    }

    public ConsoleHandler getConsoleHandler() {
        return consoleHandler;
    }

    public FileHandler getFileHandler() {
        return fileHandler;
    }

    public String getLogFileDirectory() {
        return configManager.getLoggingDir(envHome.getAbsolutePath());
    }

    public String getLogFileNamePrefix() {
        return configManager.getPrefixedFileName(INFO_FILES);
    }

    public String getLogFilePattern() {
        final String traceFileDir = getLogFileDirectory();

        final String traceFileName = getLogFileNamePrefix();

        return traceFileDir + File.separator + traceFileName;
    }

    public Handler getConfiguredHandler() {
        return configuredHandler;
    }

    public void closeHandlers() {
        if (consoleHandler != null) {
            consoleHandler.close();
        }

        if (fileHandler != null) {
            fileHandler.close();
        }
    }

    /**
     * Not much to do, mark state.
     */
    public void open() {

        assert invalidatingEFE.get() == null;

        envState = DbEnvState.OPEN;
    }

    /**
     * Invalidate the environment when a Java Error is thrown.
     *
     * <p>Do this without allocating any memory so that the env state is
     * updated without throwing (another) OOME. We also do our best to set
     * invalidatingEFE to honor the contract of {@link
     * Environment#getInvalidatingException}.</p>
     */
    public void invalidate(Error error) {

        final EnvironmentFailureException efe = preallocatedEFE;

        if (!invalidatingEFE.compareAndSet(null, efe)) {
            return;
        }

        efe.setJavaErrorMessage(error.toString());

        setInvalidState();
    }

    /**
     * Invalidate the environment when a EnvironmentFailureException) is
     * thrown.
     */
    public void invalidate(EnvironmentFailureException efe) {

        /*
         * Remember the wedged exception even if invalidatingEFE != null.
         * The EWE takes priority over other exceptions during close().
         */
        if (efe instanceof EnvironmentWedgedException) {
            wedgedEFE.compareAndSet(null, (EnvironmentWedgedException) efe);
        }

        if (!invalidatingEFE.compareAndSet(null, efe)) {
            return;
        }

        setInvalidState();
    }

    private void setInvalidState() {

        /*
         * Set state to invalid *after* setting invalidatingEFE, to maintain
         * invariant:
         *   if (envState == INVALID) then (invalidatingEFE.get() != null)
         *
         * It is safe to check and set without a mutex, because the state never
         * transitions away from CLOSED.
         */
        if (envState != DbEnvState.CLOSED) {
            envState = DbEnvState.INVALID;
        }

        requestShutdownDaemons();
    }

    public EnvironmentFailureException getInvalidatingException() {
        return invalidatingEFE.get();
    }

    public AtomicReference<EnvironmentFailureException>
        getInvalidatingExceptionReference() {

        return invalidatingEFE;
    }

    /**
     * Returns true if the environment is currently invalid or was invalidated
     * and closed.
     */
    public boolean wasInvalidated() {
        return invalidatingEFE.get() != null;
    }

    /**
     * @return true if environment is fully open (not being constructed and not
     * closed), and has not been invalidated by an EnvironmentFailureException.
     */
    public boolean isValid() {
        return (envState == DbEnvState.OPEN);
    }

    /**
     * Returns the test mode as documented under {@link
     * EnvironmentConfig#TEST_MODE}.
     */
    public boolean isTestMode() {
        return testMode;
    }

    private void configureTestMode() {
        testMode =
            (TEST_MODE_SYS_PROP != null &&
             !configManager.isSpecified(EnvironmentParams.TEST_MODE)) ?
            Boolean.valueOf(TEST_MODE_SYS_PROP) :
            configManager.getBoolean(EnvironmentParams.TEST_MODE);
    }

    /**
     * @return true if environment is still in init
     */
    public boolean isInInit() {
        return (envState == DbEnvState.INIT);
    }

    /**
     * @return true if close has begun, although the state may still be open.
     */
    public boolean isClosing() {
        return closing;
    }

    public boolean isClosed() {
        return (envState == DbEnvState.CLOSED);
    }

    /**
     * When a EnvironmentFailureException occurs or the environment is closed,
     * further writing can cause log corruption.
     */
    public boolean mayNotWrite() {
        return (envState == DbEnvState.INVALID) ||
               (envState == DbEnvState.CLOSED);
    }

    public void checkIfInvalid()
        throws EnvironmentFailureException {

        if (envState != DbEnvState.INVALID) {
            return;
        }

        final EnvironmentFailureException efe = invalidatingEFE.get();
        assert efe != null;

        /*
         * It is acceptable for an Error/OOME to be thrown due to allocations
         * below, because it will indicate the true problem anyway.
         */
        throw efe.wrapSelf(efe.getMessage(), efe.clone());
    }

    public void checkOpen()
        throws DatabaseException {

        /*
         * Allow OPEN and INIT states, but not INVALID and CLOSED.
         */
        checkIfInvalid();

        /*
         * The CLOSED state should not occur when the Environnment handle is
         * closed, because its environmentImpl field is null, but we check
         * anyway to be safe.
         */
        if (envState == DbEnvState.CLOSED) {
            throw new IllegalStateException
                ("Attempt to use a Environment that has been closed.");
        }
    }

    /**
     * Closes the environment after performing a checkpoint.
     */
    public void close() {
        close(true, false);
    }

    /**
     * Closes the environment; a checkpoint when closing is optional.
     */
    public void close(boolean doCheckpoint) {
        close(doCheckpoint, false);
    }

    /**
     * Used by error handling to forcibly close an environment, and by tests to
     * close an environment to simulate a crash.  Database handles do not have
     * to be closed before calling this method.  A checkpoint is not performed.
     */
    public void abnormalClose() {
        /* Discard the internal handle, for an abnormal close. */
        closeInternalEnvHandle(true);

        close(false, true);
    }

    private void close(boolean doCheckpoint, boolean isAbnormalClose) {
        try {
            doClose(doCheckpoint, isAbnormalClose);
        } finally {
            DbEnvPool.getInstance().removeEnvironment(this);
        }
    }

    /**
     * Closes the environment, optionally performing a checkpoint and checking
     * for resource leaks.  This method must be called while synchronized on
     * DbEnvPool.
     *
     * @throws IllegalStateException if the environment is already closed.
     *
     * @throws EnvironmentFailureException if leaks or other problems are
     * detected while closing.
     */
    private synchronized void doClose(boolean doCheckpoint,
                                      boolean isAbnormalClose) {

        /* Discard the internal handle. */
        closeInternalEnvHandle(isAbnormalClose);

        StringWriter errorStringWriter = new StringWriter();
        PrintWriter errors = new PrintWriter(errorStringWriter);
        DiskLimitException diskLimitEx = null;

        try {
            Trace.traceLazily
                (this, "Close of environment " + envHome + " started");
            LoggerUtils.fine(envLogger,
                             this,
                             "Close of environment " + envHome + " started");

            envState.checkState(DbEnvState.VALID_FOR_CLOSE,
                                DbEnvState.CLOSED);

            try {
                setupClose(errors);
            } catch (Exception e) {
                appendException(errors, e, "releasing resources");
            }

            /*
             * Begin shutdown of the deamons before checkpointing.  Cleaning
             * during the checkpoint is wasted and slows down the checkpoint.
             */
            requestShutdownDaemons();

            /* Checkpoint to bound recovery time. */
            boolean checkpointHappened = false;
            if (doCheckpoint &&
                !isReadOnly &&
                (envState != DbEnvState.INVALID) &&
                logManager.getLastLsnAtRecovery() !=
                fileManager.getLastUsedLsn()) {

                /*
                 * Force a checkpoint. Flush all the way to the root, i.e.,
                 * minimize recovery time.
                 */
                CheckpointConfig ckptConfig = new CheckpointConfig();
                ckptConfig.setForce(true);
                ckptConfig.setMinimizeRecoveryTime(true);
                try {
                    invokeCheckpoint(ckptConfig, "close");
                    checkpointHappened = true;
                } catch (DiskLimitException e) {
                    diskLimitEx = e;
                } catch (Exception e) {
                    appendException(errors, e, "performing checkpoint");
                }
            }

            try {
                postCheckpointClose(checkpointHappened);
            } catch (Exception e) {
                appendException(errors, e, "after checkpoint");
            }

            LoggerUtils.fine(envLogger,
                             this,
                             "About to shutdown daemons for Env " + envHome);
            shutdownDaemons();

            /*
             * If backups are in progress, warn the caller that it was a
             * mistake to close the environment at this time. Wait to make this
             * check until after shutting down daemons so that we know that
             * automatic backups are no longer in progress, since we don't want
             * errors for those.
             */
            if (getBackupCount() > 0) {
                errors.append("\nThere are backups in progress so the ");
                errors.append("Environment should not have been closed.");
                errors.println();
            }

            /* Flush log. */
            if (!isAbnormalClose) {
                try {
                    logManager.flushSync();
                } catch (Exception e) {
                    appendException(errors, e, "flushing log manager");
                }
            }

            try {
                fileManager.clear();
            } catch (Exception e) {
                appendException(errors, e, "clearing file manager");
            }

            try {
                fileManager.close();
            } catch (Exception e) {
                appendException(errors, e, "closing file manager");
            }

            try {
                // TODO BI CHECK ABNORMAL CLOSE 
                if (getBeforeImageIndex() != null) {
                    getBeforeImageIndex().close();
                }
            } catch (Exception e) {
                appendException(errors, e, "closing beforeimage index");
            }


            /*
             * Close the memory budgets on these components before the
             * INList is forcibly released and the treeAdmin budget is
             * cleared.
             */
            dbMapTree.close();
            inMemoryINs.clear();

            closeHandlers();

            if (!isAbnormalClose &&
                (envState != DbEnvState.INVALID)) {

                try {
                    checkLeaks();
                } catch (Exception e) {
                    appendException(errors, e, "performing validity checks");
                }
            }
        } finally {
            envState = DbEnvState.CLOSED;

            /*
             * Last ditch effort to clean up so that tests can continue and
             * re-open the Environment in the face of an Exception or even an
             * Error.  Note that this was also attempted above.  [#21929]
             */
            clearFileManager();
            closeHandlers();
        }

        /*
         * Throwing the wedged exception is the first priority. This is done
         * even for an abnormal close, since HA may have created threads.
         */
        final EnvironmentWedgedException ewe = wedgedEFE.get();
        if (ewe != null) {
            throw ewe.wrapSelf(ewe.getMessage(), ewe.clone());
        }

        /* Don't whine again if we've already whined. */
        if (errorStringWriter.getBuffer().length() > 0 &&
            invalidatingEFE.get() == null) {
            throw EnvironmentFailureException.unexpectedState
                (errorStringWriter.toString());
        }

        /* If no other errors, throw DiskLimitException. */
        if (diskLimitEx != null) {
            throw diskLimitEx;
        }
    }

    /*
     * For security reasons, you cannot do a printStackTrace() in a
     * production environment, so only call this as part of an assert.
     */
    private boolean debugPrintStack(Exception e, PrintWriter pw) {
        e.printStackTrace(pw);
        return true;
    }

    protected void appendException(PrintWriter pw,
                                   Exception e,
                                   String doingWhat) {
        pw.append("\nException " + doingWhat + ": ");
        assert debugPrintStack(e, pw);
        pw.println();
    }

    /**
     * Release any resources from a subclass that need to be released before
     * close is called on regular environment components.
     * @throws DatabaseException
     */
    protected synchronized void setupClose(@SuppressWarnings("unused")
                                           PrintWriter errors)
        throws DatabaseException {
    }

    /**
     * Release any resources from a subclass that need to be released after
     * the closing checkpoint.
     * @param checkpointed if true, a checkpoint as issued before the close
     * @throws DatabaseException
     */
    protected synchronized void postCheckpointClose(boolean checkpointed)
        throws DatabaseException {
    }

    /**
     * Called after recovery but before any other initialization. Is overridden
     * by ReplImpl to convert user defined databases to replicated after doing
     * recovery.
     */
    protected void postRecoveryConversion() {
    }

    /*
     * Clear as many resources as possible, even in the face of an environment
     * that has received a fatal error, in order to support reopening the
     * environment in the same JVM.
     */
    public void closeAfterInvalid() {
        try {
            doCloseAfterInvalid();
        } finally {
            DbEnvPool.getInstance().removeEnvironment(this);
        }
    }

    /**
     * This method must be called while synchronized on DbEnvPool.
     */
    public synchronized void doCloseAfterInvalid() {

        shutdownDaemons();

        try {
            fileManager.clear();
        } catch (Throwable e) {
            /* Klockwork - ok */
        }

        try {
            fileManager.close();
        } catch (Throwable e) {
            /* Klockwork - ok */
        }

        /*
         * Release resources held by handlers, such as memory and file
         * descriptors
         */
        closeHandlers();

        envState = DbEnvState.CLOSED;

        /*
         * The wedged exception must be thrown even when the environment is
         * invalid, since the app must restart the process.
         */
        final EnvironmentWedgedException ewe = wedgedEFE.get();
        if (ewe != null) {
            throw ewe.wrapSelf(ewe.getMessage(), ewe.clone());
        }
    }

    void incBackupCount() {
        backupCount.incrementAndGet();
    }

    void decBackupCount() {
        backupCount.decrementAndGet();
    }

    /**
     * Returns a count of the number of in-progress DbBackups.
     */
    protected int getBackupCount() {
        return backupCount.get();
    }

    public static int getThreadLocalReferenceCount() {
        return threadLocalReferenceCount;
    }

    public static synchronized void incThreadLocalReferenceCount() {
        threadLocalReferenceCount++;
    }

    public static synchronized void decThreadLocalReferenceCount() {
        threadLocalReferenceCount--;
    }

    public boolean getDidFullThreadDump() {
        return didFullThreadDump;
    }

    public void setDidFullThreadDump(boolean val) {
        didFullThreadDump = val;
    }

    public boolean getNoComparators() {
        return noComparators;
    }

    /**
     * Debugging support. Check for leaked locks and transactions.
     */
    @SuppressWarnings("deprecation")
    private void checkLeaks()
        throws DatabaseException {

        /* Only enabled if this check leak flag is true. */
        if (!configManager.getBoolean(EnvironmentParams.ENV_CHECK_LEAKS)) {
            return;
        }

        boolean clean = true;
        StatsConfig statsConfig = new StatsConfig();

        /* Fast stats will not return NTotalLocks below. */
        statsConfig.setFast(false);

        TransactionStats txnStat = txnStat(statsConfig);
        if (txnStat.getNActive() != 0) {
            clean = false;
            System.err.println("Problem: " + txnStat.getNActive() +
                               " txns left");
            TransactionStats.Active[] active = txnStat.getActiveTxns();
            if (active != null) {
                for (Active element : active) {
                    System.err.println(element);
                }
            }
        }

        if (LatchSupport.TRACK_LATCHES) {
            if (LatchSupport.nBtreeLatchesHeld() > 0) {
                clean = false;
                System.err.println("Some latches held at env close.");
                System.err.println(LatchSupport.btreeLatchesHeldToString());
            }
        }

        long memoryUsage = memoryBudget.getVariableCacheUsage();
        if (memoryUsage != 0) {
            clean = false;
            System.err.println("Local Cache Usage = " + memoryUsage);
            System.err.println(memoryBudget.loadStats());
        }

        boolean assertionsEnabled = false;
        assert assertionsEnabled = true; // Intentional side effect.
        if (!clean && assertionsEnabled) {
            throw EnvironmentFailureException.unexpectedState
                ("Lock, transaction, latch or memory " +
                 "left behind at environment close");
        }
    }

    /**
     * Invoke a checkpoint programmatically. Note that only one checkpoint may
     * run at a time.
     */
    public void invokeCheckpoint(CheckpointConfig config,
                                 String  invokingSource) {
        checkpointer.doCheckpoint(
            config, invokingSource, false /*invokedFromDaemon*/);
    }

    /**
     * Coordinates an eviction with an in-progress checkpoint and returns
     * whether provisional logging is needed.
     *
     * @return the provisional status to use for logging the target.
     */
    public Provisional coordinateWithCheckpoint(
        final DatabaseImpl dbImpl,
        final int targetLevel,
        final IN parent) {

        return checkpointer.coordinateEvictionWithCheckpoint(
            dbImpl, targetLevel, parent);
    }

    /**
     * Flush the log buffers and write to the log, and optionally fsync.
     * [#19111]
     */
    public void flushLog(boolean fsync) {
        if (fsync) {
            logManager.flushSync();
        } else {
            logManager.flushNoSync();
        }
    }

    /**
     * Flip the log to a new file, forcing an fsync.  Return the LSN of the
     * trace record in the new file.
     */
    public long forceLogFileFlip()
        throws DatabaseException {

        return logManager.logForceFlip(
            new TraceLogEntry(new Trace("File Flip")));
    }

    /**
     * Invoke a compress programmatically. Note that only one compress may run
     * at a time.
     */
    public void invokeCompressor() {
        inCompressor.doCompress(false);
    }

    public void invokeEvictor() {
        evictor.doManualEvict();
    }

    /**
     * @throws UnsupportedOperationException if read-only or mem-only.
     */
    public int invokeCleaner(boolean cleanMultipleFiles) {

        if (isReadOnly || isMemOnly) {
            throw new UnsupportedOperationException
                ("Log cleaning not allowed in a read-only or memory-only " +
                 "environment");
        }

        return cleaner.doClean(
            cleanMultipleFiles, false /*forceCleaning*/);
    }

    public void requestShutdownDaemons() {

        closing = true;

        inCompressor.requestShutdown();

        /*
         * Don't shutdown the shared cache evictor here.  It is shutdown when
         * the last shared cache environment is removed in DbEnvPool.
         */
        if (!sharedCache) {
            evictor.requestShutdown();
        }

        checkpointer.requestShutdown();
        cleaner.requestShutdown();
        extinctionScanner.requestShutdown();
        dataEraser.initiateSoftShutdown();
        backupManager.initiateSoftShutdown();
        statCapture.requestShutdown();
        logFlusher.requestShutdown();
        dataVerifier.requestShutdown();
        reservedFilesAutoRepair.requestShutdown();
        taskCoordinator.close();
    }

    /**
     * Ask all daemon threads to shut down.
     */
    public void shutdownDaemons() {

        /* Shutdown stats capture thread first so we can access stats. */
        statCapture.shutdown();

        synchronized (statSynchronizer) {

            inCompressor.shutdown();

            /*
             * Cleaner has to be shutdown before checkpointer because former
             * calls the latter.
             */
            cleaner.shutdown();
            extinctionScanner.shutdown();
            dataEraser.shutdownThread(dataEraser.getLogger());
            backupManager.shutdownThreads();
            checkpointer.shutdown();

            /*
             * The evictors have to be shutdown last because the other daemons
             * might create changes to the memory usage which result in a
             * notify to eviction.
             */
            if (sharedCache) {

                /*
                 * Don't shutdown the SharedEvictor here.  It is shutdown when
                 * the last shared cache environment is removed in DbEnvPool.
                 * Instead, remove this environment from the SharedEvictor's
                 * list so we won't try to evict from a closing/closed
                 * environment. Note that we do this after the final checkpoint
                 * so that eviction is possible during the checkpoint, and just
                 * before deconstructing the environment. Leave the evictor
                 * field intact so DbEnvPool can get it.
                 */
                evictor.removeSharedCacheEnv(this);
            } else {
                evictor.shutdown();
            }

            logFlusher.shutdown();
            dataVerifier.shutdown();
            reservedFilesAutoRepair.shutdownThread(envLogger);
            taskCoordinator.close();
        }
    }

    public boolean isNoLocking() {
        return isNoLocking;
    }

    public boolean isTransactional() {
        return isTransactional;
    }

    public boolean isReadOnly() {
        return isReadOnly;
    }

    public boolean isMemOnly() {
        return isMemOnly;
    }

    public boolean isChecksumErrorFatal() {
        return configManager.getBoolean(EnvironmentParams.LOG_CHECKSUM_FATAL);
    }

    public boolean isSecondaryIntegrityErrorFatal() {
        return configManager.getBoolean(
            EnvironmentParams.TREE_SECONDARY_INTEGRITY_FATAL);
    }

    /**
     * Named "optional" because the nodeName property in EnvironmentConfig is
     * optional may be null.  {@link #getName()} should almost always be used
     * instead for messages, exceptions, etc.
     */
    public String getOptionalNodeName() {
        return optionalNodeName;
    }

    public String makeDaemonThreadName(String daemonName) {

        if (optionalNodeName == null) {
            return daemonName;
        }

        return daemonName + " (" + optionalNodeName + ")";
    }

    /**
     * Returns whether DB/MapLN eviction is enabled.
     */
    public boolean getDbEviction() {
        return dbEviction;
    }

    public static int getAdler32ChunkSize() {
        return adler32ChunkSize;
    }

    public boolean getSharedCache() {
        return sharedCache;
    }

    public boolean allowBlindOps() {
        return allowBlindOps;
    }

    public boolean allowBlindPuts() {
        return allowBlindPuts;
    }

    public int getMaxEmbeddedLN() {
        return maxEmbeddedLN;
    }

    /**
     * Transactional services.
     */
    public Txn txnBegin(Transaction parent, TransactionConfig txnConfig)
        throws DatabaseException {

        return txnManager.txnBegin(parent, txnConfig);
    }

    /* Services. */
    public LogManager getLogManager() {
        return logManager;
    }

    public LogFlusher getLogFlusher() {
        return logFlusher;
    }

    public DataVerifier getDataVerifier() {
        return dataVerifier;
    }

    public FileManager getFileManager() {
        return fileManager;
    }

    public DbTree getDbTree() {
        return dbMapTree;
    }

    public MetadataStore getMetadataStore() {
        return metadataStore;
    }

    /**
     * Returns the config manager for the current base configuration.
     *
     * <p>The configuration can change, but changes are made by replacing the
     * config manager object with a enw one.  To use a consistent set of
     * properties, call this method once and query the returned manager
     * repeatedly for each property, rather than getting the config manager via
     * this method for each property individually.</p>
     */
    public DbConfigManager getConfigManager() {
        return configManager;
    }

    public NodeSequence getNodeSequence() {
        return nodeSequence;
    }

    /**
     * Returns the current configuration.
     */
    public EnvironmentConfig getConfig() {
        return configManager.getEnvironmentConfig();
    }

    /**
     * Clones the current configuration.
     */
    public EnvironmentConfig cloneConfig() {
        return configManager.getEnvironmentConfig().clone();
    }

    /**
     * Clones the current mutable configuration.
     */
    public EnvironmentMutableConfig cloneMutableConfig() {
        return DbInternal.cloneMutableConfig
            (configManager.getEnvironmentConfig());
    }

    /**
     * Changes the mutable config properties that are present in the given
     * config, and notifies all config observer.
     */
    public void setMutableConfig(EnvironmentMutableConfig config)
        throws DatabaseException {

        /* Calls doSetMutableConfig while synchronized on DbEnvPool. */
        DbEnvPool.getInstance().setMutableConfig(this, config);
    }

    /**
     * This method must be called while synchronized on DbEnvPool.
     */
    synchronized void doSetMutableConfig(EnvironmentMutableConfig config)
        throws DatabaseException {

        /* Clone the current config. */
        EnvironmentConfig newConfig =
            configManager.getEnvironmentConfig().clone();

        /* Copy in the mutable props. */
        DbInternal.copyMutablePropsTo(config, newConfig);

        /*
         * Update the current config and notify observers.  The config manager
         * is replaced with a new instance that uses the new configuration.
         * This avoids synchronization issues: other threads that have a
         * reference to the old configuration object are not impacted.
         *
         * Notify listeners in reverse order of registration so that the
         * environment listener is notified last and can start daemon threads
         * after they are configured.
         */
        configManager = resetConfigManager(newConfig);
        for (int i = configObservers.size() - 1; i >= 0; i -= 1) {
            EnvConfigObserver o = configObservers.get(i);
            o.envConfigUpdate(configManager, newConfig);
        }
    }

    /**
     * Make a new config manager that has all the properties needed. More
     * complicated for subclasses.
     */
    protected DbConfigManager resetConfigManager(EnvironmentConfig newConfig) {
        return new DbConfigManager(newConfig);
    }

    public ExceptionListener getExceptionListener() {
        return exceptionListener;
    }

    /**
     * Adds an observer of mutable config changes.
     */
    public synchronized void addConfigObserver(EnvConfigObserver o) {
        configObservers.add(o);
    }

    /**
     * Removes an observer of mutable config changes.
     */
    public synchronized void removeConfigObserver(EnvConfigObserver o) {
        configObservers.remove(o);
    }

    public INList getInMemoryINs() {
        return inMemoryINs;
    }

    public TxnManager getTxnManager() {
        return txnManager;
    }

    public TaskCoordinator getTaskCoordinator() {
        return taskCoordinator;
    }

    public Checkpointer getCheckpointer() {
        return checkpointer;
    }

    public Cleaner getCleaner() {
        return cleaner;
    }

    public DataEraser getDataEraser() {
        return dataEraser;
    }

    public BackupManager getBackupManager() {
        return backupManager;
    }

    public ReservedFilesAutoRepair getReservedFilesAutoRepair() {
        return reservedFilesAutoRepair;
    }

    public MemoryBudget getMemoryBudget() {
        return memoryBudget;
    }

    /**
     * Uses cached disk usage info to determine whether disk space limits are
     * currently violated. This method simply returns a volatile field. The
     * cached information is updated frequently enough to prevent violating the
     * limits by a large amount.
     *
     * @return a non-null message (appropriate for an exception) if a disk
     * limit is currently violated, else null.
     */
    public String getDiskLimitViolation() {
        return cleaner.getDiskLimitViolation();
    }

    /**
     * Uses cached disk usage info to determine whether disk space limits are
     * currently violated. This method simply checks a volatile field. The
     * cached information is updated frequently enough to prevent violating the
     * limits by a large amount.
     *
     * @throws DiskLimitException if a disk limit is currently violated.
     */
    public void checkDiskLimitViolation() throws DiskLimitException {
        final String violation = cleaner.getDiskLimitViolation();
        if (violation != null) {
            throw new DiskLimitException(null, violation);
        }
    }

    /**
     * @return environment Logger, for use in debugging output.
     */
    public Logger getLogger() {
        return envLogger;
    }

    /**
     * Returns a logger for logging references to reserved files at most once
     * per minute.
     */
    public RateLimitingLogger<EnvironmentImpl> getReservedFileRefLogger() {
        return reservedFileRefLogger;
    }

    public boolean isDbLoggingDisabled() {
        return dbLoggingDisabled;
    }

    /**
     * Called to set thread-local context during a verify. Must be called with
     * null when verification method exits.
     */
    public void setBtreeVerifyContext(final BtreeVerifyContext context) {
        assert (context == null) != (btreeVerifyContext.get() == null);
        btreeVerifyContext.set(context);
    }

    /**
     * Returns non-null context, or null if a Btree verification is not
     * currently active in this thread.
     */
    public BtreeVerifyContext getBtreeVerifyContext() {
        return btreeVerifyContext.get();
    }

    /*
     * Verification, must be run while system is quiescent.
     */
    public VerifySummary verify(VerifyConfig config)
        throws DatabaseException {

        final BtreeVerifier verifier = new BtreeVerifier(this);
        verifier.setBtreeVerifyConfig(config);
        return verifier.verifyAll();
    }

    public void verifyCursors()
        throws DatabaseException {

        inCompressor.verifyCursors();
    }

    /**
     * The data/key bytes of records in non-internal DBs should not be logged
     * unless this method returns true. True is returned only if
     * je.env.exposeUserData or je.testMode has been set to true (false is the
     * default).
     */
    public boolean getExposeUserData() {
        return exposeUserData || testMode;
    }

    /*
     * Statistics
     */

    /**
     * Retrieve and return stat information.
     */
    public EnvironmentStats loadStats(StatsConfig config)
        throws DatabaseException {
        return statManager.loadStats(config, statKey);
    }

    /**
     * Retrieve and return stat information.
     */
    public EnvironmentStats loadStatsInternal(StatsConfig config)
        throws DatabaseException {

        EnvironmentStats stats = new EnvironmentStats();

        synchronized (statSynchronizer) {
            stats.setINCompStats(inCompressor.loadStats(config));
            stats.setCkptStats(checkpointer.loadStats(config));
            stats.setCleanerStats(cleaner.loadStats(config));
            stats.setLogStats(logManager.loadStats(config));
            stats.setMBAndEvictorStats(
                memoryBudget.loadStats(), evictor.loadStats(config));
            stats.setLockStats(txnManager.loadStats(config));
            stats.setEnvStats(envStats);
            stats.setBackupStats(backupStats);
            stats.setBtreeOpStats(btreeOpStats.cloneGroup(config.getClear()));
            stats.setThroughputStats(
                thrputStats.cloneGroup(config.getClear()));
            stats.setTaskCoordinatorStats(taskCoordinator.getStats(config));
            stats.setEraserStats(dataEraser.loadStats(config));
            stats.setDbVerifyStats(dbVerifyStats);
            if (getBeforeImageIndex() != null) {
                stats.setBImgStats(getBeforeImageIndex().getStats(config));
            }
        }
        return stats;
    }

    public void incSearchOps(final DatabaseImpl dbImpl) {
        if (dbImpl.isInternalDb()) {
            return;
        }
        if (dbImpl.isKnownSecondary()) {
            secSearchOps.increment();
        } else {
            priSearchOps.increment();
        }
    }

    public void incSearchFailOps(final DatabaseImpl dbImpl) {
        if (dbImpl.isInternalDb()) {
            return;
        }
        if (dbImpl.isKnownSecondary()) {
            secSearchFailOps.increment();
        } else {
            priSearchFailOps.increment();
        }
    }

    public void incPositionOps(final DatabaseImpl dbImpl) {
        if (dbImpl.isInternalDb()) {
            return;
        }
        if (dbImpl.isKnownSecondary()) {
            secPositionOps.increment();
        } else {
            priPositionOps.increment();
        }
    }

    public void incInsertOps(final DatabaseImpl dbImpl) {
        if (dbImpl.isInternalDb()) {
            return;
        }
        if (dbImpl.isKnownSecondary()) {
            secInsertOps.increment();
        } else {
            priInsertOps.increment();
        }
    }

    public void incInsertFailOps(final DatabaseImpl dbImpl) {
        if (dbImpl.isInternalDb()) {
            return;
        }
        if (!dbImpl.isKnownSecondary()) {
            priInsertFailOps.increment();
        }
    }

    public void incUpdateOps(final DatabaseImpl dbImpl) {
        if (dbImpl.isInternalDb()) {
            return;
        }
        if (dbImpl.isKnownSecondary()) {
            secUpdateOps.increment();
        } else {
            priUpdateOps.increment();
        }
    }

    public void incDeleteOps(final DatabaseImpl dbImpl) {
        if (dbImpl.isInternalDb()) {
            return;
        }
        if (dbImpl.isKnownSecondary()) {
            secDeleteOps.increment();
        } else {
            priDeleteOps.increment();
        }
    }

    public void incDeleteFailOps(final DatabaseImpl dbImpl) {
        if (dbImpl.isInternalDb()) {
            return;
        }
        /* Deletion failure always counted as primary DB deletion. */
        priDeleteFailOps.increment();
    }

    public void incRootSplits() {
        rootSplits.increment();
    }

    public void incRelatchesRequired() {
        relatchesRequired.increment();
    }

    public void incBinDeltaGets() {
        binDeltaGets.increment();
    }

    public void incBinDeltaInserts() {
        binDeltaInserts.increment();
    }

    public void incBinDeltaUpdates() {
        binDeltaUpdates.increment();
    }

    public void incBinDeltaDeletes() {
        binDeltaDeletes.increment();
    }

    /**
     * For replicated environments only; just return true for a standalone
     * environment.
     */
    public boolean addDbBackup(@SuppressWarnings("unused") DbBackup backup) {
        incBackupCount();
        return true;
    }

    /**
     * For replicated environments only; do nothing for a standalone
     * environment.
     */
    public void removeDbBackup(@SuppressWarnings("unused") DbBackup backup) {
        decBackupCount();
    }

    /**
     * Note the number of files copied to the archive by the most recent
     * automatic backup.
     *
     * @param count the number of files copied
     */
    public void noteBackupCopyFilesCount(final int count) {
        backupCopyFilesCount.set(count);
    }

    public void dbVerifyRunIncrement() {
        nDbVerifyRuns.increment();
    }

    public void dbVerifyRunTimeIncrementBy(final long time) {
        dbVerifyRunTime.add(time);
    }

    public void dbVerifyProblemsIncrementBy(final long count) {
        nDbVerifyProblemsFound.add(count);
    }

    /**
     * Note the total amount of time in milliseconds taken to copy files to the
     * archive by the most recent automatic backup.
     *
     * @param timeMs the total copy time in milliseconds
     */
    public void noteBackupCopyFilesMs(final long timeMs) {
        backupCopyFilesMs.set(timeMs);
    }

    /**
     * Retrieve txn statistics
     */
    public synchronized TransactionStats txnStat(StatsConfig config) {
        return txnManager.txnStat(config);
    }

    public int getINCompressorQueueSize() {
        return inCompressor.getBinRefQueueSize();
    }

    public StartupTracker getStartupTracker() {
        return startupTracker;
    }

    /**
     * Get the environment home directory.
     */
    public File getEnvironmentHome() {
        return envHome;
    }

    public Environment getInternalEnvHandle() {
        return envInternal;
    }

    /**
     * Closes the internally maintained environment handle. If the close is
     * an abnormal close, it just does cleanup work instead of trying to close
     * the internal environment handle which may result in further errors.
     */
    private synchronized void closeInternalEnvHandle(boolean isAbnormalClose) {

        if (envInternal == null) {
            return;
        }

        if (isAbnormalClose) {
            envInternal = null;
        } else {
            final Environment savedEnvInternal = envInternal;
            /* Blocks recursions resulting from the close operation below */
            envInternal = null;
            DbInternal.closeInternalHandle(savedEnvInternal);
        }
    }

    /**
     * Get an environment name, for tagging onto logging and debug message.
     * Useful for multiple environments in a JVM, or for HA.
     */
    public String getName() {
        if (optionalNodeName == null){
            return envHome.toString();
        }
        return getOptionalNodeName();
    }

    /**
     * Get the node name if this is a replicated environment or else return
     * null.
     */
    public String getNodeName() {
        return null;
    }

    /**
     * Returns whether this is a replicated environment that is the master,
     * else false.
     */
    public boolean getIsMaster() {
        return false;
    }

    public long getTxnTimeout() {
        return txnTimeout;
    }

    public long getLockTimeout() {
        return lockTimeout;
    }

    /*
     * Only used for unit test com.sleepycat.je.test.SecondaryTest.
     */
    public void setLockTimeout(long timeout) {
        lockTimeout = timeout;
    }

    public boolean getDeadlockDetection() {
        return deadlockDetection;
    }

    public long getDeadlockDetectionDelay() {
        return deadlockDetectionDelay;
    }

    public long getReplayTxnTimeout() {
        if (lockTimeout != 0) {
            return lockTimeout;
        }
        /* It can't be disabled, so make it the minimum. */
        return 1;
    }

    public void acquireSecondaryAssociationsReadLock() {
        try {
            secondaryAssociationLock.readLock().lockInterruptibly();
        } catch (InterruptedException e) {
            throw new ThreadInterruptedException(this, e);
        }
    }

    public void releaseSecondaryAssociationsReadLock() {
        secondaryAssociationLock.readLock().unlock();
    }

    public void acquireSecondaryAssociationsWriteLock() {
        try {
            secondaryAssociationLock.writeLock().lockInterruptibly();
        } catch (InterruptedException e) {
            throw new ThreadInterruptedException(this, e);
        }
    }

    public void releaseSecondaryAssociationsWriteLock() {
        secondaryAssociationLock.writeLock().unlock();
    }

    /**
     * Returns {@link EnvironmentParams#ENV_EXPIRATION_ENABLED}.
     */
    public boolean isExpirationEnabled() {
        return expirationEnabled;
    }

    /**
     * Returns whether a given expiration time precedes the current system
     * time, i.e., the expiration time has passed.
     */
    public boolean isExpired(final int expiration, final boolean hours) {
        return expirationEnabled &&
            TTL.isExpired(expiration, hours);
    }

    /**
     * Returns whether a given expiration time precedes the current system
     * time, i.e., the expiration time has passed.
     */
    public boolean isExpired(final long expirationTime) {
        return expirationEnabled &&
            TTL.isExpired(expirationTime);
    }

    /**
     * Returns whether a given expiration time precedes the current system time
     * plus withinMs, i.e., the expiration time will pass within withinMs, or
     * earlier. If withinMs is negative, this is whether the expiration time
     * passed withinMs ago, or earlier.
     */
    public boolean expiresWithin(final int expiration,
                                 final boolean hours,
                                 final long withinMs) {
        return expirationEnabled &&
            TTL.expiresWithin(expiration, hours, withinMs);
    }

    /**
     * Same as {@link #expiresWithin(int, boolean, long)} but with a single
     * expirationTime param.
     */
    public boolean expiresWithin(final long expirationTime,
                                 final long  withinMs) {
        return expirationEnabled &&
            TTL.expiresWithin(expirationTime, withinMs);
    }

    public ExtinctionScanner getExtinctionScanner() {
        return extinctionScanner;
    }

    /**
     * Returns the extinction status for the purpose of adding to an
     * exception message. Null is returned if either param is null.
     */
    public static ExtinctionStatus getExtinctionStatus(
        final DatabaseImpl priDb,
        final DatabaseEntry priKey) {

        if (priDb == null || priKey == null) {
            return null;
        }

        final byte[] keyBytes;

        if (priKey.getOffset() == 0 &&
            priKey.getSize() == priKey.getData().length) {
            keyBytes = priKey.getData();
        } else {
            keyBytes = new byte[priKey.getSize()];
            System.arraycopy(
                priKey.getData(), priKey.getOffset(),
                keyBytes, 0, priKey.getSize());
        }

        return priDb.getEnv().getExtinctionStatus(priDb, keyBytes);
    }

    /**
     * Returns whether the given key in the given DB is extinct (can be purged
     * by the cleaner) according to the extinction filter.
     */
    public ExtinctionStatus getExtinctionStatus(final DatabaseImpl db,
                                                final byte[] fullKey) {
        return getExtinctionStatus(
            db.getName(), db.getSortedDuplicates(),
            db.getDbType().isInternal(), fullKey, null);
    }

    /**
     * If priKey is null it is derived from fullKey. If priKey is non-null,
     * fullKey is unused and may be null.
     */
    public ExtinctionStatus getExtinctionStatus(final String dbName,
                                                final boolean dups,
                                                final boolean isInternalDb,
                                                final byte[] fullKey,
                                                byte[] priKey) {
        if (isInternalDb) {
            return ExtinctionStatus.NOT_EXTINCT;
        }

        if (extinctionFilter == null) {
            return ExtinctionStatus.NOT_EXTINCT;
        }

        if (priKey == null) {
            priKey = dups ?
                DupKeyData.getData(fullKey, 0, fullKey.length) :
                fullKey;
        }

        try {
            final ExtinctionStatus status =
                extinctionFilter.getExtinctionStatus(dbName, dups, priKey);

            if (status == null) {
                throw EnvironmentFailureException.unexpectedState(
                    "ExtinctionFilter.getExtinctionStatus returned null");
            }

            return status;

        } catch (Throwable e) {
            LoggerUtils.severe(envLogger, this,
                "ExtinctionFilter callback threw: " +
                LoggerUtils.getStackTraceForSevereLog(e));
            return ExtinctionStatus.MAYBE_EXTINCT;
        }
    }

    public boolean hasExtinctionFilter() {
        return extinctionFilter != null;
    }

    public Evictor getEvictor() {
        return evictor;
    }

    /**
     * Wake up the eviction threads when the cache is full or close to full.
     */
    void alertEvictor() {
        evictor.alert();
    }

    /**
     * Performs critical eviction if necessary.  Is called before and after
     * each cursor operation. We prefer to have the application thread do as
     * little eviction as possible, to reduce the impact on latency, so
     * critical eviction has an explicit set of criteria for determining when
     * this should run.
     *
     * WARNING: The action performed here should be as inexpensive as possible,
     * since it will impact app operation latency.  Unconditional
     * synchronization must not be performed, since that would introduce a new
     * synchronization point for all app threads.
     *
     * An overriding method must call super.criticalEviction.
     *
     * No latches are held or synchronization is in use when this method is
     * called.
     */
    public void criticalEviction(boolean backgroundIO) {
        evictor.doCriticalEviction(backgroundIO);
    }

    /**
     * Do eviction if the memory budget is over. Called by JE daemon
     * threads that do not have the same latency concerns as application
     * threads.
     */
    public void daemonEviction(boolean backgroundIO) {
        evictor.doDaemonEviction(backgroundIO);
    }

    /**
     * Performs special eviction (eviction other than standard IN eviction)
     * for this environment.  This method is called once per eviction batch to
     * give other components an opportunity to perform eviction.  For a shared
     * cached, it is called for only one environment (in rotation) per batch.
     *
     * An overriding method must call super.specialEviction and return the sum
     * of the long value it returns and any additional amount of budgeted
     * memory that is evicted.
     *
     * No latches are held when this method is called, but it is called while
     * synchronized on the evictor.
     *
     * @return the number of bytes evicted from the JE cache.
     */
    public long specialEviction() {
        return cleaner.getUtilizationTracker().evictMemory();
    }

    /**
     * For stress testing.  Should only ever be called from an assert.
     */
    public static boolean maybeForceYield() {
        if (forcedYield) {
            Thread.yield();
        }
        return true;      // so assert doesn't fire
    }

    /**
     * Return true if this environment is part of a replication group.
     */
    public boolean isReplicated() {
        return false;
    }

    /**
     * Return true if this environment is used as an Arbiter.
     */
    public boolean isArbiter() {
        return false;
    }

    /**
     * Returns true if the VLSN is both preserved and cached.  Always false in
     * a standalone environment.  Overridden by RepImpl.
     */
    public boolean getCacheVLSN() {
        return false;
    }

    /**
     * Returns whether optimizing for size of replicated entries is more
     * important than the extra memory allocation required to do so.
     */
    public boolean optimizeRepStreamForSize() {
        return false;
    }

    public void incReplayPreprocessMiss() {
    }

    /**
     * True if ReplicationConfig set allowConvert as true. Standalone
     * environment is prohibited from doing a conversion, return false.
     */
    public boolean getAllowRepConvert() {
        return false;
    }

    /**
     * True if this environment is converted from non-replicated to
     * replicated.
     */
    public boolean isRepConverted() {
        return dbMapTree.isRepConverted();
    }

    public boolean needRepConvert() {
        return needRepConvert;
    }

    /**
     * Computes and assigns VLSNs as needed to this log item for a replicated
     * log record. This method must be invoked under the LWL to ensure that the
     * VLSNs it generates are correctly serialized with respect to their
     * locations in the log.
     *
     * The method must be invoked before any calls are made to determine the
     * log entry size, since some of the underlying values used to determine
     * the size of the entry will only be finalized after this call has
     * completed.
     *
     * This method is only invoked when the log is being written as the master,
     * since the replica merely reuses the VLSN values computed by the master.
     *
     * Since this method is being written under the LWL it must not block.
     *
     * @param entry the log entry, an in/out argument: the entry is
     * modified with an updated DTVLSN for commit and abort log entries.
     *
     * @return a non-null VLSN for all replicated log items
     */
    public long assignVLSNs(LogEntry entry) {
        /* NOP for non-replicated environment. */
        return INVALID_VLSN;
    }

    public long setReplayVLSNCounter(long vlsn) {
        /* NOP for non-replicated environment. */
        return INVALID_VLSN;
    }

    public VLSNRecoveryProxy getVLSNProxy() {
        return new NoopVLSNProxy();
    }

    public boolean isMaster() {
        /* NOP for non-replicated environment. */
        return false;
    }

    public void preRecoveryCheckpointInit(RecoveryInfo recoveryInfo) {
        /* NOP for non-replicated environment. */
    }

    public void registerVLSN(LogItem logItem) {
        /* NOP for non-replicated environment. */
    }

    /**
     * Truncate the head of the VLSNIndex to allow file deletion, if possible.
     */
    public boolean tryVlsnHeadTruncate(long bytesNeeded) {
        /* NOP for non-replicated environment. */
        return false;
    }

    /**
     * Truncate the head of the VLSNIndex to unprotect the given file, if
     * possible.
     */
    public boolean tryTruncateVlsnHead(long deleteEnd, long deleteFileNum) {
        /* NOP for non-replicated environment. */
        return false;
    }

    /**
     * Clears the cached checksum for a file when it may be overwritten
     * (e.g., entries may be erased).
     */
    public void clearedCachedFileChecksum(String fileName) {
        /* NOP for non-replicated environment. */
    }

    /**
     * Do any work that must be done before the checkpoint end is written, as
     * as part of the checkpoint process.
     */
    public void preCheckpointEndFlush() {
        /* NOP for non-replicated environment. */
    }

    /**
     * For replicated environments only; only the overridden method should
     * ever be called.
     */
    public Txn createReplayTxn(long txnId) {
        throw EnvironmentFailureException.unexpectedState
            ("Should not be called on a non replicated environment");
    }

    /**
     * For replicated environments only; only the overridden method should
     * ever be called.
     */
    public ThreadLocker createRepThreadLocker() {
        throw EnvironmentFailureException.unexpectedState
            ("Should not be called on a non replicated environment");
    }

    /**
     * For replicated environments only; only the overridden method should
     * ever be called.
     */
    public Txn createRepUserTxn(TransactionConfig config) {
        throw EnvironmentFailureException.unexpectedState
            ("Should not be called on a non replicated environment");
    }

    /**
     * Returns the deprecated HA REPLAY_FREE_DISK_PERCENT parameter, or zero
     * if this is not an HA env.
     */
    public int getReplayFreeDiskPercent() {
        return 0;
    }

    /**
     * Check whether this environment can be opened on an existing environment
     * directory.
     *
     * @throws UnsupportedOperationException via Environment ctor.
     */
    public void checkRulesForExistingEnv(boolean dbTreeReplicatedBit)
        throws UnsupportedOperationException {

        /*
         * We only permit standalone Environment construction on an existing
         * environment when we are in read only mode, to support command
         * line utilities. We prohibit read/write opening, because we don't
         * want to chance corruption of the environment by writing non-VLSN
         * tagged entries in.
         */
        if (dbTreeReplicatedBit && (!isReadOnly())) {
            throw new UnsupportedOperationException
                ("This environment was previously opened for replication." +
                 " It cannot be re-opened for in read/write mode for" +
                 " non-replicated operation.");
        }
    }

    /**
     * Ensure that the in-memory vlsn index encompasses all logged entries
     * before it is flushed to disk. A No-Op for non-replicated systems.
     * [#19754]
     */
    public void awaitVLSNConsistency() {
        /* Nothing to do in a non-replicated system. */
    }

    public void setReplayRollback(final boolean rollback) {
        /* No-op in a non-replicated system. */
    }

    public boolean isReplayRollbackRunning() {
        /* Non-replicated systems never perform rollback. */
        return false;
    }

    /**
     * The VLSNRecoveryProxy is only needed for replicated environments.
     */
    private class NoopVLSNProxy implements VLSNRecoveryProxy {

        @Override
        public void trackMapping(long lsn,
                                 LogEntryHeader currentEntryHeader,
                                 LogEntry targetLogEntry) {
            /* intentional no-op */
        }
    }

    public AtomicLongStat getThroughputStat(StatDefinition def) {
        return thrputStats.getAtomicLongStat(def);
    }

    /**
     * Private class to prevent used of the close() method by the application
     * on an internal handle.
     */
    private static class InternalEnvironment extends Environment {

        public InternalEnvironment(File envHome,
                                   EnvironmentConfig configuration,
                                   EnvironmentImpl envImpl)
            throws EnvironmentNotFoundException,
                   EnvironmentLockedException,
                   VersionMismatchException,
                   DatabaseException,
                   IllegalArgumentException {
            super(envHome, configuration, null /*repConfig*/, envImpl);
        }

        @Override
        protected boolean isInternalHandle() {
            return true;
        }

        @Override
        public synchronized void close() {
            throw EnvironmentFailureException.unexpectedState
                ("close() not permitted on an internal environment handle");
        }
    }

    public ProgressListener<RecoveryProgress> getRecoveryProgressListener() {
        return recoveryProgressListener;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * Checks that writing records with a TTL is allowed.
     *
     * @throws IllegalStateException if any node in the group is less than
     * {@link TTL#MIN_JE_VERSION}.
     */
    public void checkTTLAvailable() {
        /* Do nothing when not overridden by RepImpl. */
    }

    /**
     * Checks that using the record extinction feature.
     *
     * @throws IllegalStateException if any node in the group is less than
     * {@link ExtinctionScanner#MIN_JE_VERSION}.
     */
    public void checkRecordExtinctionAvailable() {
        /* Do nothing when not overridden by RepImpl. */
    }

    /**
     * Recovery encountered a RestoreRequired marker file, so recovery is
     * halted and some intervention must be taken.
     *
     * @param restoreRequired getFailureType() is used to indicate the how
     * the environment can be healed.
     */
    public void handleRestoreRequired(RestoreRequired restoreRequired) {
        switch (restoreRequired.getFailureType()) {
        case LOG_CHECKSUM:
            throw new EnvironmentFailureException(
                this,
                EnvironmentFailureReason.LOG_CHECKSUM,
                VerifierUtils.getRestoreRequiredMessage(restoreRequired));
        case BTREE_CORRUPTION:
            throw new EnvironmentFailureException(
                this,
                EnvironmentFailureReason.BTREE_CORRUPTION,
                VerifierUtils.getRestoreRequiredMessage(restoreRequired));
        default:
            throw EnvironmentFailureException.unexpectedState(
                this, restoreRequired.toString());
        }
    }

    /* For testing only. */
    public StatCapture getStatCapture() {
        return statCapture;
    }

	public boolean isSubscriber() {
		return false;
	}

    public BeforeImageIndex getBeforeImageIndex() {
        return beforeImageIndex;
    }

}
