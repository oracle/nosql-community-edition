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

import java.nio.ByteBuffer;
import java.util.Calendar;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.VersionMismatchException;
import com.sleepycat.je.dbi.EnvironmentFailureReason;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.statcap.StatUtils;
import com.sleepycat.je.utilint.Timestamp;

/**
 * A FileHeader embodies the header information at the beginning of each log
 * file.
 */
public class FileHeader implements Loggable {

    /*
     * fileNum is the number of file, starting at 0. An unsigned int, so stored
     * in a long in memory, but in 4 bytes on disk
     */
    private long fileNum;
    private long lastEntryInPrevFileOffset;
    private Timestamp time;
    private int logVersion;

    FileHeader(long fileNum, long lastEntryInPrevFileOffset) {
        this.fileNum = fileNum;
        this.lastEntryInPrevFileOffset = lastEntryInPrevFileOffset;
        Calendar now = Calendar.getInstance();
        time = new Timestamp(now.getTimeInMillis());
        logVersion = LogEntryType.LOG_VERSION;
    }

    /**
     * For logging only.
     */
    public FileHeader() {
    }

    public int getLogVersion() {
        return logVersion;
    }

    /**
     * @return file header log version.
     */
    int validate(EnvironmentImpl envImpl,
                 String fileName,
                 long expectedFileNum)
        throws DatabaseException {

        if (logVersion > LogEntryType.LOG_VERSION ||
            logVersion < LogEntryType.FIRST_LOG_VERSION) {
            throw new VersionMismatchException
                (envImpl,
                 "Expected log version LTE " + LogEntryType.LOG_VERSION +
                     " and GTE " + LogEntryType.FIRST_LOG_VERSION +
                     " but found version=" + logVersion);
        }

        if (fileNum != expectedFileNum) {
            throw new EnvironmentFailureException
                (envImpl, EnvironmentFailureReason.LOG_INTEGRITY,
                 "Wrong filenum in header for file " +
                 fileName + " expected " +
                 expectedFileNum + " got " + fileNum);
        }

        return logVersion;
    }

    /**
     * @return the offset of the last entry in the previous file.
     */
    long getLastEntryInPrevFileOffset() {
        return lastEntryInPrevFileOffset;
    }

    Timestamp getTimestamp() {
        return time;
    }

    /*
     * Logging support
     */

    /**
     * A header is always a known size.
     */
    public static int entrySize() {
        return
            LogUtils.LONG_BYTES +                // time
            LogUtils.UNSIGNED_INT_BYTES +        // file number
            LogUtils.LONG_BYTES +                // lastEntryInPrevFileOffset
            LogUtils.INT_BYTES;                  // logVersion
    }

    /**
     * @see Loggable#getLogSize
     * @return number of bytes used to store this object
     */
    public int getLogSize() {
        return entrySize();
    }

    /**
     * @see Loggable#writeToLog
     * Serialize this object into the buffer. Update cksum with all
     * the bytes used by this object
     * @param logBuffer is the destination buffer
     */
    public void writeToLog(ByteBuffer logBuffer) {
        LogUtils.writeLong(logBuffer, time.getTime());
        LogUtils.writeUnsignedInt(logBuffer, fileNum);
        LogUtils.writeLong(logBuffer, lastEntryInPrevFileOffset);
        LogUtils.writeInt(logBuffer, logVersion);
    }

    /**
     * @see Loggable#readFromLog
     * Initialize this object from the data in itemBuf.
     * @param logBuffer the source buffer
     */
    public void readFromLog(EnvironmentImpl envImpl,
                            ByteBuffer logBuffer,
                            int unusableEntryVersion) {

        /* Timestamp is always unpacked. */
        time = new Timestamp(LogUtils.readLong(logBuffer));
        fileNum = LogUtils.readUnsignedInt(logBuffer);
        lastEntryInPrevFileOffset = LogUtils.readLong(logBuffer);
        logVersion = LogUtils.readInt(logBuffer);

        /*
         * The log version is unknown until reading it.  If there are
         * version-specific fields in this entry, they must follow the log
         * version and use it, not the entryVersion param, for conditionals.
         */
    }

    /**
     * @see Loggable#dumpLog
     * @param sb destination string buffer
     * @param verbose if true, dump the full, verbose version
     */
    public void dumpLog(StringBuilder sb, boolean verbose) {
        sb.append("<FileHeader num=\"0x");
        sb.append(Long.toHexString(fileNum));
        sb.append("\" lastEntryInPrevFileOffset=\"0x");
        sb.append(Long.toHexString(lastEntryInPrevFileOffset));
        sb.append("\" logVersion=\"0x");
        sb.append(Integer.toHexString(logVersion));
        sb.append("\" time=\"").append(StatUtils.getDate(time));
        sb.append("\"/>");
    }

    /**
     * @see Loggable#getTransactionId
     */
    public long getTransactionId() {
        return 0;
    }

    /**
     * @see Loggable#logicalEquals
     * Always return false, this item should never be compared.
     */
    public boolean logicalEquals(Loggable other) {
        return false;
    }

    /**
     * Print in xml format
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        dumpLog(sb, true);
        return sb.toString();
    }
}
