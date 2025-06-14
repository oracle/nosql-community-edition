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

package com.sleepycat.je.txn;

import static com.sleepycat.je.utilint.VLSN.INVALID_VLSN;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.log.LogUtils;
import com.sleepycat.je.log.Loggable;
import com.sleepycat.je.util.TimeSupplier;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.je.utilint.Timestamp;

/**
 * This class indicates the end of a partial rollback at syncup. This is a
 * non-replicated entry.  Although this is a replication related class, it
 * resides in the utilint package because it is referenced in
 * LogEntryType.java, and is used in a general way at recovery.
 */
public class RollbackStart implements Loggable {

    /* The matchpoint that is the logical start of this rollback period. */
    private long matchpointVLSN = INVALID_VLSN;
    private long matchpointLSN;

    /* 
     * The active txn list are unfinished transactions that will be rolled back
     * by syncup.
     */
    private Set<Long> activeTxnIds;

    /* For debugging in the field */
    private Timestamp time;

    public RollbackStart(long matchpointVLSN,
                         long matchpointLSN,
                         Set<Long> activeTxnIds) {
        this.matchpointVLSN = matchpointVLSN;
        this.matchpointLSN = matchpointLSN;
        this.activeTxnIds = activeTxnIds;
        time = new Timestamp(TimeSupplier.currentTimeMillis());
    }

    /**
     * For constructing from the log.
     */
    public RollbackStart() {
    }

    public long getMatchpoint() {
        return matchpointLSN;
    }

    public Set<Long> getActiveTxnIds() {
        return activeTxnIds;
    }
    
    public long getMatchpointVLSN() {
        return matchpointVLSN;
    }

    /**
     * @see Loggable#getLogSize
     */
    public int getLogSize() {
        int size = LogUtils.getPackedLongLogSize(matchpointVLSN) +
            LogUtils.getPackedLongLogSize(matchpointLSN) +
            LogUtils.getTimestampLogSize(time) +
            LogUtils.getPackedIntLogSize(activeTxnIds.size());

        for (Long id : activeTxnIds) {
            size += LogUtils.getPackedLongLogSize(id);
        }
        
        return size;
    }

    /**
     * @see Loggable#writeToLog
     */
    public void writeToLog(ByteBuffer buffer) {
        LogUtils.writePackedLong(buffer, matchpointVLSN);
        LogUtils.writePackedLong(buffer, matchpointLSN);
        LogUtils.writeTimestamp(buffer, time);
        LogUtils.writePackedInt(buffer, activeTxnIds.size());
        for (Long id : activeTxnIds) {
            LogUtils.writePackedLong(buffer, id);
        }
    }

    /**"
     * @see Loggable#readFromLog
     */
    public void readFromLog(EnvironmentImpl envImpl,
                            ByteBuffer buffer,
                            int entryVersion) {
        matchpointVLSN = LogUtils.readPackedLong(buffer);
        matchpointLSN = LogUtils.readPackedLong(buffer);
        time = LogUtils.readTimestamp(buffer);
        int setSize = LogUtils.readPackedInt(buffer);
        activeTxnIds = new HashSet<Long>(setSize);
        for (int i = 0; i < setSize; i++) {
            activeTxnIds.add(LogUtils.readPackedLong(buffer));
        }
    }

    /**
     * @see Loggable#dumpLog
     */
    public void dumpLog(StringBuilder sb, boolean verbose) {
        sb.append(" matchpointVLSN=").append(matchpointVLSN);
        sb.append(" matchpointLSN=");
        sb.append(DbLsn.getNoFormatString(matchpointLSN));
        
        /* Make sure the active txns are listed in order, partially for the sake
         * of the LoggableTest unit test, which expects the toString() for two
         * equivalent objects to always display the same, and partially for 
         * ease of debugging.
         */
        List<Long> displayTxnIds = new ArrayList<Long>(activeTxnIds);
        Collections.sort(displayTxnIds);
        sb.append(" activeTxnIds=") .append(displayTxnIds);
        sb.append("\" time=\"").append(time);
    }

    /**
     * @see Loggable#getTransactionId
     */
    public long getTransactionId() {
        return 0;
    }

    /**
     * @see Loggable#logicalEquals
     */
    public boolean logicalEquals(Loggable other) {

        if (!(other instanceof RollbackStart)) {
            return false;
        }

        RollbackStart otherRS = (RollbackStart) other;

        return ((matchpointVLSN == otherRS.matchpointVLSN) &&
                (matchpointLSN == otherRS.matchpointLSN) &&
                time.equals(otherRS.time) &&
                activeTxnIds.equals(otherRS.activeTxnIds));
    }

    @Override
        public String toString() {
        StringBuilder sb = new StringBuilder();
        dumpLog(sb, true);
        return sb.toString();
    }
}
