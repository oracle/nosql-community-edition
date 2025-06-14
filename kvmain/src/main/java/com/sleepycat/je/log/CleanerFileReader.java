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

package com.sleepycat.je.log;

import static com.sleepycat.je.utilint.VLSN.INVALID_VLSN;
import static com.sleepycat.je.utilint.VLSN.NULL_VLSN;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.cleaner.BaseUtilizationTracker;
import com.sleepycat.je.cleaner.ExpirationTracker;
import com.sleepycat.je.cleaner.FileProcessor;
import com.sleepycat.je.cleaner.FileSummary;
import com.sleepycat.je.cleaner.INSummary;
import com.sleepycat.je.dbi.DatabaseId;
import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.log.entry.BINDeltaLogEntry;
import com.sleepycat.je.log.entry.INLogEntry;
import com.sleepycat.je.log.entry.LNLogEntry;
import com.sleepycat.je.log.entry.LogEntry;
import com.sleepycat.je.tree.BIN;
import com.sleepycat.je.tree.IN;
import com.sleepycat.je.utilint.DbLsn;

/**
 * CleanerFileReader scans log files for INs and LNs.
 */
public class CleanerFileReader extends FileReader {
    private static final byte IS_LN = 0;
    private static final byte IS_IN = 1;
    private static final byte IS_BIN_DELTA = 2;
    private static final byte IS_OLD_BIN_DELTA = 3;
    private static final byte IS_DBTREE = 4;
    private static final byte IS_FILEHEADER = 5;
    private static final byte IS_ERASED = 6;

    private final Map<LogEntryType, EntryInfo> targetEntryMap;
    private LogEntry targetLogEntry;
    private byte targetCategory;
    private final FileSummary fileSummary;
    private final INSummary inSummary;
    private final ExpirationTracker expTracker;
    private ByteBuffer tempEntryBuffer;

    /** The first VLSN, or null if none has been found */
    private long firstVLSN = INVALID_VLSN;

    private long lastVLSN = NULL_VLSN;

    /**
     * Create this reader to start at a given LSN.
     * @param env The relevant EnvironmentImpl.
     * @param readBufferSize buffer size in bytes for reading in log.
     * @param startLsn where to start in the log, or null for the beginning.
     * @param fileNum single file number.
     * @param fileSummary returns true utilization.
     * @param inSummary returns IN utilization.
     * @param expTracker if non-null, returns expiration info and disables
     * checkum verification (see {@link FileProcessor#processFile}).
     */
    public CleanerFileReader(EnvironmentImpl env,
                             int readBufferSize,
                             long startLsn,
                             Long fileNum,
                             FileSummary fileSummary,
                             INSummary inSummary,
                             ExpirationTracker expTracker) {
        super(env,
              readBufferSize,
              true,                     // forward
              startLsn,
              fileNum,                  // single file number
              DbLsn.NULL_LSN,           // endOfFileLsn
              DbLsn.NULL_LSN,           // finishLsn
              true);      // doChecksumOnRead

        this.fileSummary = fileSummary;
        this.inSummary = inSummary;
        this.expTracker = expTracker;

        targetEntryMap = new HashMap<LogEntryType, EntryInfo>();

        for (LogEntryType entryType : LogEntryType.getAllTypes()) {
            if (entryType.isLNType()) {
                addTargetType(IS_LN, entryType);
            }

            if (entryType.isINType()) {
                addTargetType(IS_IN, entryType);
            }
        }
        addTargetType(IS_BIN_DELTA, LogEntryType.LOG_BIN_DELTA);
        addTargetType(IS_DBTREE, LogEntryType.LOG_DBTREE);
        addTargetType(IS_FILEHEADER, LogEntryType.LOG_FILE_HEADER);
        addTargetType(IS_ERASED, LogEntryType.LOG_ERASED);
    }

    private void addTargetType(byte category, LogEntryType entryType)
        throws DatabaseException {

        targetEntryMap.put(entryType,
                           new EntryInfo(entryType.getNewLogEntry(),
                                         category));
    }

    /**
     * Process the header to track the last VLSN and count true utilization.
     * Then read the entry and return true if the LogEntryType is of interest.
     * <p>
     * We don't override isTargetEntry so it always returns true and we can
     * count utilization correctly here in processEntry.  We call getLastLsn to
     * count utilization and this is not allowed from isTargetEntry.
     * <p>
     * When true is returned, the entry is not read from the buffer by this
     * method. Instead, the caller is responsible for either calling
     * {@link #skipEntry()} or {@link #readEntry()}. This allows the caller to
     * optimize by skipping materialization of the entry when it isn't needed.
     */
    @Override
    protected boolean processEntry(ByteBuffer entryBuffer)
        throws DatabaseException {

        final LogEntryType type =
            LogEntryType.findType(currentEntryHeader.getType());
        final int size = getLastEntrySize();

        /* Count true utilization for new log entries. */
        if (currentEntryHeader.getType() !=
            LogEntryType.LOG_FILE_HEADER.getTypeNum()) {
            fileSummary.totalCount += 1;
            fileSummary.totalSize += size;
            if (BaseUtilizationTracker.trackObsoleteInfo(type)) {
                if (BaseUtilizationTracker.isLNType(type)) {
                    fileSummary.totalLNCount += 1;
                    fileSummary.totalLNSize += size;
                } else {
                    fileSummary.totalINCount += 1;
                    fileSummary.totalINSize += size;
                    if (type.isINType()) {
                        inSummary.totalINCount += 1;
                        inSummary.totalINSize += size;
                    }
                    if (type.equals(LogEntryType.LOG_BIN_DELTA)) {
                        inSummary.totalBINDeltaCount += 1;
                        inSummary.totalBINDeltaSize += size;
                    }
                }
            }
        }

        /* Invisible entries should not be processed further. */
        if (currentEntryHeader.isInvisible()) {
            skipEntry(entryBuffer);
            countObsolete();
            return false;
        }

        /*
         * Maintain first and last VLSN encountered. Note that this includes
         * VLSNs in Erased entries.
         */
        if (currentEntryHeader.getReplicated()) {
            final long vlsn = currentEntryHeader.getVLSN();
            if (vlsn != INVALID_VLSN) {

                /* Use a null comparison in this inner loop, for speed */
                if (firstVLSN == INVALID_VLSN) {
                    firstVLSN = vlsn;
                }
                if (lastVLSN != NULL_VLSN && vlsn != lastVLSN + 1) {
                    throw EnvironmentFailureException.unexpectedState(
                        "vlsns out of order, lastVLSN=" + lastVLSN +
                        " currentVLSN=" + vlsn +
                        " lsn=" +  DbLsn.getNoFormatString(getLastLsn()) +
                        " entryType=" + type +
                        " invisible=" + currentEntryHeader.isInvisible() +
                        " envName=" + envImpl.getName());
                }
                lastVLSN = vlsn;
            }
        }

        /*
         * Call readEntry and return true if this is a LogEntryType of
         * interest.
         */
        final EntryInfo info = targetEntryMap.get(type);
        if (info == null) {
            skipEntry(entryBuffer);
            countObsolete();
            return false;
        }
        targetCategory = info.targetCategory;
        targetLogEntry = info.targetLogEntry;
        this.tempEntryBuffer = entryBuffer;
        return true;
    }

    public void readEntry() {
        if (tempEntryBuffer == null) {
            return;
        }
        targetLogEntry.readEntry(envImpl, currentEntryHeader, tempEntryBuffer);
        tempEntryBuffer = null;
    }

    public void skipEntry() {
        if (tempEntryBuffer == null) {
            return;
        }
        skipEntry(tempEntryBuffer);
        tempEntryBuffer = null;
    }

    /**
     * Records the current log entry as obsolete in the FileSummary used to
     * count true utilization.
     */
    public void countObsolete() {
        final LogEntryType type =
            LogEntryType.findType(currentEntryHeader.getType());
        if (!BaseUtilizationTracker.trackObsoleteInfo(type)) {
            return;
        }
        final int size = getLastEntrySize();
        if (BaseUtilizationTracker.isLNType(type)) {
            fileSummary.obsoleteLNCount += 1;
            fileSummary.obsoleteLNSize += size;
            fileSummary.obsoleteLNSizeCounted += 1;
        } else {
            fileSummary.obsoleteINCount += 1;
            if (type.isINType()) {
                inSummary.obsoleteINCount += 1;
                inSummary.obsoleteINSize += size;
            }
            if (type.equals(LogEntryType.LOG_BIN_DELTA)) {
                inSummary.obsoleteBINDeltaCount += 1;
                inSummary.obsoleteBINDeltaSize += size;
            }
        }
    }

    public void countExpired() {
        if (expTracker != null) {
            expTracker.track(envImpl, targetLogEntry, getLastEntrySize());
        }
    }

    /**
     * @return true if the last entry was an IN.
     */
    public boolean isIN() {
        return (targetCategory == IS_IN);
    }

    /**
     * @return true if the last entry was a live BIN delta.
     */
    public boolean isBINDelta() {
        return (targetCategory == IS_BIN_DELTA);
    }

    /**
     * @return true if the last entry was a LN.
     */
    public boolean isLN() {
        return (targetCategory == IS_LN);
    }

    /**
     * @return true if the last entry was a DbTree entry.
     */
    public boolean isDbTree() {
        return (targetCategory == IS_DBTREE);
    }

    public boolean isFileHeader() {
        return (targetCategory == IS_FILEHEADER);
    }

    public boolean isErased() {
        return (targetCategory == IS_ERASED);
    }

    public WholeEntry getWholeEntry() {
        return new WholeEntry(currentEntryHeader, targetLogEntry);
    }

    /**
     * Get the last LN log entry seen by the reader.  Note that
     * LNLogEntry.postFetchInit must be called before calling certain
     * LNLogEntry methods.
     */
    public LNLogEntry<?> getLNLogEntry() {
        return (LNLogEntry<?>) targetLogEntry;
    }

    /**
     * Get the last entry seen by the reader as an IN.
     */
    public IN getIN(DatabaseImpl dbImpl) {
        return ((INLogEntry<?>) targetLogEntry).getIN(dbImpl);
    }

    public BIN getBINDelta() {
        return ((BINDeltaLogEntry) targetLogEntry).getMainItem();
    }

    public FileHeader getFileHeader() {
        return (FileHeader) (targetLogEntry.getMainItem());
    }

    /**
     * Get the last databaseId seen by the reader.
     */
    public DatabaseId getDatabaseId() {
        if (targetCategory == IS_LN) {
            return ((LNLogEntry<?>) targetLogEntry).getDbId();
        } else if ((targetCategory == IS_IN) ||
            (targetCategory == IS_BIN_DELTA)) {
            return ((INLogEntry<?>) targetLogEntry).getDbId();
        } else {
            return null;
        }
    }

    /**
     * Returns the first VLSN encountered, or NULL_VLSN if no entries were
     * replicated.
     */
    public long getFirstVLSN() {
        return (firstVLSN != INVALID_VLSN) ? firstVLSN : NULL_VLSN;
    }

    /**
     * Returns the last VLSN encountered, or NULL_VLSN if no entries were
     * replicated.
     */
    public long getLastVLSN() {
        return lastVLSN;
    }

    private static class EntryInfo {
        public LogEntry targetLogEntry;
        public byte targetCategory;

        EntryInfo(LogEntry targetLogEntry, byte targetCategory) {
            this.targetLogEntry = targetLogEntry;
            this.targetCategory = targetCategory;
        }
    }
}
