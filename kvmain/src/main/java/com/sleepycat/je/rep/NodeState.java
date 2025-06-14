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
package com.sleepycat.je.rep;

import com.sleepycat.je.JEVersion;
import com.sleepycat.je.rep.ReplicatedEnvironment.State;

/**
 * The current state of a replication node and the application this node is
 * running in.
 * <p>
 * This includes the following information:
 * <ul>
 * <li>the replication {@link ReplicatedEnvironment.State state} of this 
 * node</li>
 * <li>the name of the current master, as known by this node</li>
 * <li>the time when this node joined the replication group</li>
 * <li>the latest transaction end (abort or commit) VLSN on this node</li>
 * <li>the transaction end (abort or commit) VLSN on the master known by this 
 * node. The difference between transaction end VLSNs on the master versus on
 * this node gives an indication of how current this node's data is. The gap
 * in VLSN values indicates the number of replication records that must be 
 * processed by this node, to be caught up to the master.</li>
 * <li>the number of feeders running on this node</li>
 * <li>the system load average for the last minute</li>
 * </ul>
 * @since 5.0
 */
public class NodeState {

    /* The name of the node requested. */
    private final String nodeName;

    /* The name of the group which this node joins. */
    private final String groupName;

    /* The current state of the node. */
    private final State currentState;

    /* The name of the current master in the group. */
    private final String masterName;

    /* The JEVersion this node runs on. */
    private final JEVersion jeVersion;

    /* The time when this node last joined the group. */
    private final long joinTime;

    /* The current transaction end VLSN on this node. */
    private final long currentTxnEndVLSN;

    /* The master transaction end VLSN known by this node. */
    private final long masterTxnEndVLSN;

    /* The number of active feeders that running on this node. */
    private final int activeFeeders;  

    /* The current log version of this node. */
    private final int logVersion;

    /* The system load average for the last minute. */
    private final double systemLoad;

    /**
     * @hidden
     * Internal use only.
     */
    public NodeState(String nodeName,
                     String groupName,
                     State currentState, 
                     String masterName,
                     JEVersion jeVersion,
                     long joinTime,
                     long currentTxnEndVLSN,
                     long masterTxnEndVLSN,
                     int activeFeeders,
                     int logVersion,
                     double systemLoad) {
        this.nodeName = nodeName;
        this.groupName = groupName;
        this.currentState = currentState;
        this.masterName = masterName;
        this.jeVersion = jeVersion;
        this.joinTime = joinTime;
        this.currentTxnEndVLSN = currentTxnEndVLSN;
        this.masterTxnEndVLSN = masterTxnEndVLSN;
        this.activeFeeders = activeFeeders;
        this.logVersion = logVersion;
        this.systemLoad = systemLoad;
    }

    /**
     * Returns the name of the node whose state is requested.
     *
     * @return the name of the node.
     */
    public String getNodeName() {
        return nodeName;
    }

    /**
     * Returns the name of the group which the node joins.
     *
     * @return name of the group which the node joins
     */
    public String getGroupName() {
        return groupName;
    }

    /**
     * Returns the replication {@link ReplicatedEnvironment.State state} of 
     * this node.
     *
     * @return the replication state of this node.
     */
    public State getNodeState() {
        return currentState;
    }

    /**
     * Returns the name of the current 
     * {@link State#MASTER master} known by this node.
     *
     * @return the name of the current master
     */
    public String getMasterName() {
        return masterName;
    }

    /**
     * Returns the current JEVersion that this node runs on.
     *
     * @return the current JEVersion used by this node.
     */
    public JEVersion getJEVersion() {
        return jeVersion;
    }

    /**
     * Returns the time when this node joins the replication group.
     *
     * @return the time when this node joins the group
     */
    public long getJoinTime() {
        return joinTime;
    }

    /**
     * Returns the latest transaction end VLSN on this replication node.
     *
     * @return the commit VLSN on this node
     */
    public long getCurrentTxnEndVLSN() {
        return currentTxnEndVLSN;
    }

    /**
     * Returns the transaction end VLSN on the master known by this node.
     *
     * @return the known commit VLSN on master
     */
    public long getKnownMasterTxnEndVLSN() {
        return masterTxnEndVLSN;
    }

    /**
     * Returns the number of current active Feeders running on this node.
     *
     * @return the number of running Feeders on the node
     */
    public int getActiveFeeders() {
        return activeFeeders;
    }

    /**
     * Returns the log version of this node.
     *
     * @return the log version of this node.
     */
    public int getLogVersion() {
        return logVersion;
    }

    /**
     * Returns the system load average for the last minute.
     *
     * @return the system average load, -1.0 if the node is running on jdk5 or 
     * exceptions thrown while getting this information.
     */
    public double getSystemLoad() {
        return systemLoad;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Current state of node: " + nodeName + 
                  " from group: " + groupName + "\n");
        sb.append("  Current state: " + currentState + "\n");
        sb.append("  Current master: " + masterName + "\n");
        sb.append("  Current JE version: " + jeVersion + "\n");
        sb.append("  Current log version: " + logVersion + "\n");
        sb.append("  Current transaction end (abort or commit) VLSN: " + 
                  currentTxnEndVLSN + "\n");
        sb.append("  Current master transaction end (abort or commit) VLSN: " + 
                  masterTxnEndVLSN + "\n");
        sb.append("  Current active feeders on node: " + activeFeeders + "\n");
        sb.append("  Current system load average: " + systemLoad + "\n");

        return sb.toString();
    }
}
