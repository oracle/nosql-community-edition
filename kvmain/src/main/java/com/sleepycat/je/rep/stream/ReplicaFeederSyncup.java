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
package com.sleepycat.je.rep.stream;

import static com.sleepycat.je.utilint.VLSN.FIRST_VLSN;
import static com.sleepycat.je.utilint.VLSN.NULL_VLSN;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.cleaner.FileProtector.ProtectedFileSet;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.DbConfigManager;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.je.log.FileManager;
import com.sleepycat.je.rep.InsufficientLogException;
import com.sleepycat.je.rep.LogFileRewriteListener;
import com.sleepycat.je.rep.RollbackException;
import com.sleepycat.je.rep.RollbackProhibitedException;
import com.sleepycat.je.rep.SyncupProgress;
import com.sleepycat.je.rep.impl.RepImpl;
import com.sleepycat.je.rep.impl.RepParams;
import com.sleepycat.je.rep.impl.node.RepNode;
import com.sleepycat.je.rep.impl.node.Replay;
import com.sleepycat.je.rep.impl.node.Replica;
import com.sleepycat.je.rep.impl.node.Replica.HardRecoveryElectionException;
import com.sleepycat.je.rep.stream.BaseProtocol.AlternateMatchpoint;
import com.sleepycat.je.rep.stream.BaseProtocol.Entry;
import com.sleepycat.je.rep.stream.BaseProtocol.EntryNotFound;
import com.sleepycat.je.rep.stream.BaseProtocol.RestoreResponse;
import com.sleepycat.je.rep.stream.ReplicaSyncupReader.SkipGapException;
import com.sleepycat.je.rep.utilint.BinaryProtocol.Message;
import com.sleepycat.je.rep.utilint.NamedChannel;
import com.sleepycat.je.rep.vlsn.VLSNIndex;
import com.sleepycat.je.rep.vlsn.VLSNRange;
import com.sleepycat.je.util.TimeSupplier;
import com.sleepycat.je.utilint.DbLsn;
import com.sleepycat.je.utilint.LoggerUtils;
import com.sleepycat.je.utilint.TestHookExecute;
import com.sleepycat.je.utilint.VLSN;

/**
 * Establish where the replication stream should start for a replica and feeder
 * pair. The replica compares what is in its log with what the feeder has, to
 * determine the latest common log entry matchpoint
 *
 * - If the replica has applied log entries after that matchpoint, roll them
 *   back
 * - If a common matchpoint can't be found, the replica will need to do
 *   a network restore.
 */
public class ReplicaFeederSyncup {

    private final Logger logger;

    private final NamedChannel namedChannel;
    private final Protocol protocol;
    private final RepNode repNode;
    private final VLSNIndex vlsnIndex;
    private final Replay replay;
    private final RepImpl repImpl;
    private ReplicaSyncupReader backwardsReader;

    /* The VLSN, lsn and log entry at which a match was made. */
    private long matchpointVLSN = NULL_VLSN;
    private Long matchedVLSNTime = 0L;

    private final boolean hardRecoveryNeedsElection;

    /*
     * searchResults are the bundled outputs from the backwards scan by the
     * ReplicaSyncReader during its search for a matchpoint.
     */
    private final MatchpointSearchResults searchResults;

    /**
     * For unit tests only.
     */
    private static TestHook<Object> globalSyncupEndHook;
    private final TestHook<Object> syncupEndHook;
    private static com.sleepycat.je.utilint.TestHook<ReplicaFeederSyncup>
        rollbackHook;
    private static com.sleepycat.je.utilint.TestHook<ReplicaFeederSyncup>
        sendPingHook;

    public ReplicaFeederSyncup(RepNode repNode,
                               Replay replay,
                               NamedChannel namedChannel,
                               Protocol protocol,
                               boolean hardRecoveryNeedsElection) {
        this.replay = replay;
        logger = LoggerUtils.getLogger(getClass());
        this.repNode = repNode;
        this.vlsnIndex = repNode.getVLSNIndex();
        this.namedChannel = namedChannel;
        this.protocol = protocol;
        this.repImpl = repNode.getRepImpl();
        this.hardRecoveryNeedsElection = hardRecoveryNeedsElection;
        searchResults = new MatchpointSearchResults(repNode.getRepImpl());
        syncupEndHook = repNode.replica().getReplicaFeederSyncupHook();
    }

    public long getMatchedVLSNTime() {
        return matchedVLSNTime;
    }

    public long getMatchedVLSN() {
        return matchpointVLSN;
    }

    /**
     * The replica's side of the protocol.
     * @throws InterruptedException
     * @throws InsufficientLogException
     * @throws HardRecoveryElectionException
     */
    public void execute()
        throws IOException,
               DatabaseException,
               InterruptedException,
               InsufficientLogException,
               HardRecoveryElectionException {

        final long startTime = TimeSupplier.currentTimeMillis();
        String feederName = namedChannel.getNameIdPair().getName();
        LoggerUtils.info(logger, repImpl,
                         "Replica-feeder " + feederName +
                         " syncup started. Replica range: " +
                         repNode.getVLSNIndex().getRange());

        /*
         * Prevent the VLSNIndex range from being changed and protect all files
         * in the range. To search the index and read files within this range
         * safely, VLSNIndex.getRange must be called after syncupStarted.
         */
        final ProtectedFileSet protectedFileSet =
            repNode.syncupStarted(namedChannel.getNameIdPair());

        try {

            /*
             * Find a replication stream matchpoint and a place to start
             * the replication stream. If the feeder cannot service this
             * protocol because it has run out of replication stream,
             * findMatchpoint will throw a InsufficientLogException. If the
             */
            VLSNRange range = vlsnIndex.getRange();
            findMatchpoint(range);

            /*
             * If we can't rollback to the found matchpoint, verifyRollback
             * will throw the appropriate exception.
             */
            verifyRollback(range);

            replay.rollback(matchpointVLSN, searchResults.getMatchpointLSN());

            /* Update the vlsnIndex, it will commit synchronously. */
            long startVLSN = VLSN.getNext(matchpointVLSN);
            vlsnIndex.truncateFromTail(startVLSN,
                                       searchResults.getMatchpointLSN());

            protocol.write(protocol.new
                           StartStream(startVLSN, repImpl.getFeederFilter()),
                           namedChannel);
            LoggerUtils.info(logger, repImpl,
                             "Replica-feeder " + feederName +
                             " start stream at VLSN: " + startVLSN);
            /*
             * If rollback/findMatchpoint successed, reset the localDurableVLSN
             *  of the repNode to the matchpointVLSN, also, reset the highest
             * vlsn and highest vlsn in queue of endOfLog in FileManager. Since
             *  during syncup process these two value won't be accessed and
             * modified, so it is safe to reset them.
             */
            repImpl.getRepNode().setLocalDurableVLSN(matchpointVLSN);
            repImpl.getFileManager().resetEndOfLogHighestVLSN(matchpointVLSN);

        } finally {

            /* For unit test support only. */
            assert runHook();

            repNode.syncupEnded(protectedFileSet);

            LoggerUtils.info
                (logger, repImpl,
                 String.format
                 ("Replica-feeder " + feederName +
                  " syncup ended. Elapsed time: %,dms",
                  (TimeSupplier.currentTimeMillis() - startTime)));

            repImpl.setSyncupProgress(SyncupProgress.END);
        }
    }

    /**
     * A matchpoint has been found. What happens next depends on the position
     * of the matchpoint and its relationship to the last transaction end
     * record.
     *
     * In following table,
     *    M = some non-null matchpoint VLSN value,
     *    T = some non-null last txn end value
     *    S = some non-null last sync value
     *
     * Note that currently T == S, since a sync points is always a txn end.
     *
     * txn end                last sync   found        action
     *  VLSN                  VLSN        matchpoint
     * ----------             ---------   ---------    ------------------------
     * NULL_VLSN              NULL_VLSN   NULL_VLSN    rollback everything
     * NULL_VLSN              NULL_VLSN      M         can't occur
     * NULL_VLSN                 S        NULL_VLSN    rollback everything
     * NULL_VLSN                 S           M         rollback to M
     *   T                    NULL_VLSN   NULL_VLSN    can't occur
     *   T                    NULL_VLSN      M         can't occur
     *   T                       S        NULL_VLSN    network restore, though
     *                                                 could also do hard recov
     *   T <= M                  S           M         rollback to matchpoint
     *   T > M, truncate not ok  S           M         network restore
     *   T > M, rollback limit
     *          exceeded or      S           M         throw RollbackProhibited
     *          disabled
     *   T > M, truncate ok      S           M         hard recovery
     *
     * @throws IOException
     * @throws HardRecoveryElectionException
     */
    private void verifyRollback(VLSNRange range)
        throws RollbackException, InsufficientLogException,
        HardRecoveryElectionException, IOException {
        repImpl.setSyncupProgress(SyncupProgress.CHECK_FOR_ROLLBACK);
        final long lastTxnEnd = range.getLastTxnEnd();
        final long lastSync = range.getLastSync();

        LoggerUtils.finest(logger, repImpl, "verify rollback" +
                           " vlsn range=" + range +
                           " searchResults=" + searchResults);

        /* Test a rollback exception is thrown when sync up */
        TestHookExecute.doHookIfSet(rollbackHook, this);

        /*
         * If the lastTxnEnd VLSN is null, we don't have to worry about hard
         * recovery. See truth table above.
         */
        if (VLSN.isNull(lastTxnEnd)) {
            if (VLSN.isNull(range.getLastSync()) && !VLSN.isNull(matchpointVLSN)) {
                throw EnvironmentFailureException.unexpectedState
                    (repNode.getRepImpl(), "Shouldn't be possible to find a "+
                     "matchpoint of " + matchpointVLSN +
                     " when the sync VLSN is null. Range=" + range);
            }

            /* We'll be doing a normal rollback. */
            LoggerUtils.fine(logger, repImpl, "normal rollback, no txn end");
            return;
        }

        if (VLSN.isNull(lastSync)) {
            throw EnvironmentFailureException.unexpectedState
                (repNode.getRepImpl(),
                 "Shouldn't be possible to have a null sync VLSN when the "
                 + " lastTxnVLSN " + lastTxnEnd + " is not null. Range=" +
                 range);
        }

        /*
         * There is a non-null lastTxnEnd VLSN, so check if the found
         * matchpoint precedes it. If it doesn't, we can't rollback.
         */
        if (VLSN.isNull(matchpointVLSN)) {

            /*
             * We could actually also try to do a hard recovery and truncate
             * all committed txns, but for now, let's assume that it will cost
             * less to copy the log files.
             */
            LoggerUtils.info(logger, repImpl,
                             "This node had a txn end at vlsn = " + lastTxnEnd +
                             "but no matchpoint found.");
            throw setupLogRefresh(matchpointVLSN);
        }

        /*
         * The matchpoint is after or equal to the last txn end, no problem
         * with doing a normal rollback.
         */
        if ((lastTxnEnd <= matchpointVLSN) &&
            (searchResults.getNumPassedCommits() == 0)) {
            LoggerUtils.fine(logger, repImpl, "txn end vlsn of " + lastTxnEnd +
                             "<= matchpointVLSN of " + matchpointVLSN +
                             ", normal rollback");
            return;
        }

        /* Rolling back past a commit or abort. */

        if (hardRecoveryNeedsElection) {
            throw new Replica.HardRecoveryElectionException
                    (repNode.getMasterStatus().getNodeMasterNameId(),
                     lastTxnEnd,  matchpointVLSN);
        }

        /*
         * We're planning on rolling back past a commit or abort. The more
         * optimal course of action is to truncate the log and run a hard
         * recovery, but if the matchpoint precedes a checkpoint that deleted
         * log files, the truncation is not permissible because the resulting
         * log might be missing needed files. Instead, we have to do a network
         * restore.
         *
         * A checkpoint that resulted in deleted log files will have flushed
         * references to entries migrated from the deleted file, and the
         * original entries might still be referenced if we truncate the log
         * prior to the CkptEnd. For example:
         *
         *    3/100 LN-A
         *    4/100 BIN parent of LN-1 at 3/100
         *    5/100 cleaning of file 3 begins
         *    5/200 LN-A migrated from 3/100, parent BIN is dirtied
         *    5/300 CkptStart
         *    5/400 matchpoint
         *    5/500 BIN parent of LN-A at 5/200
         *    5/600 CkptEnd, after which file 3 is deleted
         *
         * If we truncate the log at the matchpoint, the BIN at 5/500 is
         * truncated. The BIN at 4/100 will be used, which refers to an LN in a
         * now deleted file. Note that the cleaner guarantees that a checkpoint
         * occurs after migration and before file deletion.
         *
         * However, because files become reserved after a checkpoint and are
         * not deleted immediately, we could trucate at a matchpoint that
         * precedes such a checkpoint, when these reserved files have not yet
         * been deleted. In the future we could enable such a feature by
         * keeping track (persistently) of the earliest point at which the log
         * can be truncated, taking into account the deletion of reserved
         * files. Then we could use this information to validate a matchpoint.
         *
         * TODO From [KVSTORE-257]:
         * This old check is based on the assumption that a CkptEnd that
         * completed cleaning of a file will delete that file. That is no
         * longer true.At CkptEnd we make a file reserved and delete it later
         * based on disk usage. So it seems we could improve this by keeping
         * track of the file numbers in the CkptEnd record, and checking here
         * whether any file has been deleted. We would also have to prevent
         * such files from being deleted until it is safe to do so.
         */
        if (searchResults.getPassedCheckpointEnd()) {
            LoggerUtils.info(logger, repImpl, "matchpointVLSN of " +
                             matchpointVLSN + " precedes a checkpoint end, " +
                             "needs network restore.");
            throw setupLogRefresh(matchpointVLSN);
        }

        /*
         * The truncateLSN must be later in the logs then the
         * firstActiveLSN or it could resurrect an erased or
         * cleaned log [KVSTORE-1211]
         */
        final long firstActiveLSN = searchResults.getFirstActiveLSN();
        final long truncateLSN = searchResults.getTruncateLSN();
        if (DbLsn.compareTo(firstActiveLSN, truncateLSN) > 0) {
            LoggerUtils.info(logger, repImpl, "truncateLSN of " +
                DbLsn.getNoFormatString(truncateLSN) +
                " precedes the firstActiveLSN " +
                DbLsn.getNoFormatString(firstActiveLSN) + 
                ", do network restore.");
            throw setupLogRefresh(matchpointVLSN);
        }
        LoggerUtils.info(logger, repImpl, "truncateLSN of " +
            DbLsn.getNoFormatString(truncateLSN) +
            " after the firstActiveLSN " +
            DbLsn.getNoFormatString(firstActiveLSN));

        /*
         * Likewise, if we skipped over a gap in the log files, we can't be
         * sure if we passed a ckpt with deleted log files. Do a network
         * restore rather than a hard recovery.
         */
        if (searchResults.getSkippedGap()) {
            LoggerUtils.info(logger, repImpl, "matchpointVLSN of " +
                             matchpointVLSN + " was found in a replica log " +
                             "with gaps. Since we can't be sure if it " +
                             "precedes a checkpoint end, do network restore.");
            throw setupLogRefresh(matchpointVLSN);
        }

        /*
         * We're planning on rolling back past a commit or abort, and we know
         * that we have not passed a barrier checkpoint. See if we have
         * exceeded the number of rolledback commits limit.
         */
        EnvironmentImpl envImpl = repNode.getRepImpl();
        DbConfigManager configMgr = envImpl.getConfigManager();
        final int rollbackTxnLimit =
            configMgr.getInt(RepParams.TXN_ROLLBACK_LIMIT);
        final boolean rollbackDisabled =
            configMgr.getBoolean(RepParams.TXN_ROLLBACK_DISABLED);

        final int numPassedDurableCommits =
            searchResults.getNumPassedDurableCommits();
        final int numPassedCommits =
            searchResults.getNumPassedCommits();
        final long dtvlsn = searchResults.getDTVLSN();
        LoggerUtils.info(logger, repImpl,
                         String.format("Rollback info. " +
                                       "Number of passed commits:%,d. " +
                                       "(durable commits:%,d). " +
                                       "Durable commit VLSN:%,d " +
                                       "matchpoint VLSN:%,d " +
                                       "lastTxnEnd VLSN:%,d " +
                                       "lastSync VLSN:%,d " +
                                       "Rollback transaction limit:%,d",
                                       numPassedCommits,
                                       numPassedDurableCommits,
                                       dtvlsn,
                                       matchpointVLSN,
                                       lastTxnEnd,
                                       lastSync,
                                       rollbackTxnLimit));

        if (numPassedDurableCommits > rollbackTxnLimit || rollbackDisabled) {

            LoggerUtils.severe(logger, repImpl,
                               "Limited list of transactions that would " +
                               " be truncated for hard recovery:\n" +
                               searchResults.dumpPassedTxns());

            throw new RollbackProhibitedException(repNode.getRepImpl(),
                                                  rollbackTxnLimit,
                                                  rollbackDisabled,
                                                  matchpointVLSN,
                                                  searchResults);
        }

        /*
         * After passing all the earlier qualifications, do a truncation and
         * hard recovery.
         */
        throw setupHardRecovery();
    }

    /**
     * Find a matchpoint, which is a log entry in the replication stream which
     * is the same on feeder and replica. Assign the matchpointVLSN field. The
     * matchpoint log entry must be be tagged with an environment id. If no
     * matching entry is found, the matchpoint is effectively the NULL_VLSN.
     *
     * To determine the matchpoint, exchange messages with the feeder and
     * compare log entries. If the feeder does not have enough log entries,
     * throw InsufficientLogException.
     * @throws InsufficientLogException
     */
    private void findMatchpoint(VLSNRange range)
        throws IOException,
               InsufficientLogException {

        int matchCounter = 0;
        repImpl.setSyncupProgress(SyncupProgress.FIND_MATCHPOINT,
                                  matchCounter++, -1);
        long candidateMatchpoint = range.getLastSync();
        if (candidateMatchpoint == NULL_VLSN) {

            /*
             * If the replica has no sync-able log entries at all, the
             * matchpoint is the NULL_VLSN, and we should start the replication
             * stream at VLSN 1. Check if the feeder has the VLSN 1. If it
             * doesn't, getFeederRecord() will throw a
             * InsufficientLogException. We can assume that a non-cleaned
             * feeder always has VLSN 1, because a ReplicatedEnvironment always
             * creates a few replicated vlsns, such as the name db, at
             * initial startup.
             */
            getFeederRecord(range, FIRST_VLSN,
                            false /*acceptAlternative*/);
            return;
        }

        /*
         * CandidateMatchpoint is not null, so ask the feeder for the log
         * record at that vlsn.
         */
        InputWireRecord feederRecord =
            getFeederRecord(range, candidateMatchpoint,
                            true /*acceptAlternative*/);

        /*
         * The feeder may have suggested an alternative matchpoint, so reset
         * candidate matchpoint.
         */
        candidateMatchpoint = feederRecord.getVLSN();
        if (logger.isLoggable(Level.FINE)) {
            LoggerUtils.fine(logger, repImpl,
                             "first candidate matchpoint: " +
                             candidateMatchpoint);
        }
        /*
         * Start comparing feeder records to replica records. Instead of using
         * the VLSNIndex to direct our search, we must scan from the end of the
         * log, recording entries that have an impact on our ability to
         * rollback, like checkpoints.
         *
         * Start by finding the candidate matchpoint in the Replica.
         */
        backwardsReader = setupBackwardsReader
            (candidateMatchpoint,
             repNode.getRepImpl().getFileManager().getLastUsedLsn());
        OutputWireRecord replicaRecord = getReplicaRecord(candidateMatchpoint);

        while (!replicaRecord.match(feederRecord)) {
            repImpl.setSyncupProgress(SyncupProgress.FIND_MATCHPOINT,
                                      matchCounter++, -1);

            /*
             * That first bid didn't match, now just keep looking at all
             * potential matchpoints.
             */
            replicaRecord = scanMatchpointEntries();

            if (replicaRecord == null) {

                /*
                 * The search for the previous sync log entry went past our
                 * available contiguous VLSN range, so there is no
                 * matchpoint.
                 */
                LoggerUtils.info(logger, repImpl,
                                 "Looking at candidate matchpoint vlsn " +
                                 candidateMatchpoint +
                                 " but this node went past its available" +
                                 " contiguous VLSN range, need network" +
                                 " restore.");
                throw setupLogRefresh(candidateMatchpoint);
            }

            /*
             * Ask the feeder for the record. If the feeder doesn't have
             * it, we'll throw out and do a network restore.
             */
            candidateMatchpoint = replicaRecord.getVLSN();
            if (logger.isLoggable(Level.FINE)) {
                LoggerUtils.fine(logger, repImpl,
                                 "Next candidate matchpoint: " +
                                 candidateMatchpoint);
            }
            feederRecord = getFeederRecord(range, candidateMatchpoint,
                                           false);
        }

        /* We've found the matchpoint. */
        matchedVLSNTime = replicaRecord.getTimeStamp();
        matchpointVLSN = candidateMatchpoint;
        final long matchpointLSN = backwardsReader.getLastLsn();
        searchResults.setMatchpoint(matchpointLSN);
        /*
         * We need to preserve the matchpoint record so truncate the record
         * after the matchpoint LSN, which is the matchpointLSN plus its size.
         * It is okay if the matchpoint is the last log in the file, that
         * just means the truncate will be a no-op and any log files after it
         * will be deleted.
         */
        final long truncateOffset = DbLsn.getFileOffset(matchpointLSN) +
            backwardsReader.getLastEntrySize();
        searchResults.setTruncateLSN(
            DbLsn.makeLsn(DbLsn.getFileNumber(matchpointLSN), truncateOffset));
        LoggerUtils.finest(logger, repImpl,
                           "after setting  matchpoint, searchResults=" +
                           searchResults);
    }

    private ReplicaSyncupReader setupBackwardsReader(long startScanVLSN,
                                                     long startScanLsn) {

        EnvironmentImpl envImpl = repNode.getRepImpl();
        int readBufferSize = envImpl.getConfigManager().
            getInt(EnvironmentParams.LOG_ITERATOR_READ_SIZE);

        return new ReplicaSyncupReader
            (envImpl,
             repNode.getVLSNIndex(),
             startScanLsn,
             readBufferSize,
             startScanVLSN,
             DbLsn.makeLsn(vlsnIndex.getProtectedRangeStartFile(), 0),
             searchResults,
             this);
    }

    /**
     * Search backwards for the replica's log record at this target VLSN. The
     * target record is either the replica's first suggestion for a matchpoint,
     * or feeder's counter offer. We have checked earlier that the counter
     * offer is within the replica's vlsn range.
     */
    private OutputWireRecord getReplicaRecord(long candidateMatchpoint) {

        OutputWireRecord replicaRecord = null;
        do {
            try {
                replicaRecord =
                    backwardsReader.scanBackwards(candidateMatchpoint);

                /*
                 * We're hunting for a VLSN that should be in the VLSN range,
                 * and it should exist.
                 */
                if (replicaRecord == null) {
                    throw EnvironmentFailureException.unexpectedState
                        (repImpl,
                         "Searching for candidate matchpoint " +
                         candidateMatchpoint +
                         " but got null record back ");
                }

                /* We've found the record at candidateMatchpoint */
                return replicaRecord;
            } catch (SkipGapException e) {
                /*
                 * The ReplicaSyncupReader will throw a SkipGapException if it
                 * encounters a cleaned files gap in the log. There can be
                 * multiple gaps on its way toward finding the candidate
                 * vlsn. The ReplicaSyncupReader is obliged to traverse the
                 * log, in order to note checkpoints, rather than simply using
                 * the vlsn index. When a gap is detected, the vlsn on the left
                 * side of the gap is used to re-init a new reader. For
                 * example, suppose the log looks like this:
                 *
                 * file 100 has vlsns 41-50
                 * file 200 has vlsns 51-60
                 * file 300 has vlsns 61-70
                 *
                 * and the candidate matchpoint is 45, the search will start at
                 * vlsn 70.
                 *  t1: SkipGapException thrown at gap between file 200 & 300,
                 *      create new reader positioned at vlsn 60
                 *  t2: SkipGapException thrown at gap between file 100 & 200,
                 *      create new reader positioned at vlsn 50
                 */
                long gapRepositionVLSN = e.getVLSN();
                if (gapRepositionVLSN < candidateMatchpoint) {
                    throw EnvironmentFailureException.unexpectedState
                        ("Gap reposition point of " + gapRepositionVLSN +
                         " should always be >= candidate matchpoint VLSN of " +
                         candidateMatchpoint);
                }

                long startScanLsn = vlsnIndex.getGTELsn(gapRepositionVLSN);
                backwardsReader = setupBackwardsReader(candidateMatchpoint,
                                                       startScanLsn);
                /*
                 * If we skip a gap, there is a chance that we will have passed
                 * a checkpoint which had deleted log files. This has no impact
                 * if we are doing a soft rollback, but if we do a hard
                 * recovery, it would prevent us from truncating the log. It
                 * would require doing a network restore if we need to rollback
                 * committed txns.
                 */
                searchResults.noteSkippedGap();
            }
        } while (true);
    }

    /**
     * Search backwards for potential matchpoints in the replica log,
     * accounting for potential gaps.
     */
    private OutputWireRecord scanMatchpointEntries() {
        OutputWireRecord replicaRecord = null;
        boolean firstAttempt = true;
        do {
            try {
                /*
                 * The first time around, when firstAttempt is true, ask the
                 * reader to search for the vlsn before the currentVLSN,
                 * because we entered this method having searched to a given
                 * target matchpoint. All subsequent times, we are in search of
                 * the reader's currentVLSN, but haven't found it yet, because
                 * we hit a gap, so leave the currentVLSN alone.
                 */
                replicaRecord =
                    backwardsReader.findPrevSyncEntry(firstAttempt);

                /*
                 * Either se've found a possible matchpoint, or we've come to
                 * the end and the replicaRecord is null. One way or another,
                 * return the results of the scan.
                 */
                return replicaRecord;
            } catch (SkipGapException e) {
                /*
                 * The ReplicaSyncupReader will throw a SkipGapException if it
                 * encounters a cleaned files gap in the log. There can be
                 * multiple gaps on its way toward finding the next potential
                 * matchpoint. The ReplicaSyncupReader is obliged to traverse
                 * the log, in order to note checkpoints, rather than simply
                 * using the vlsn index. When a gap is detected, the vlsn on
                 * the left side of the gap is used to re-init a new
                 * reader. For example, suppose the log looks like this and the
                 * search starts at vlsn 70
                 *
                 * file 100 has vlsns 51-60
                 * file 200 has no vlsns
                 * file 300 has no vlsns
                 * file 400 has vlsns 61-70
                 *
                 *  SkipGapException thrown at gap between file 300 & 400,
                 *  when the reader's currentVLSN is 60. Create a new reader,
                 *  positioned at vlsn 60, skipping over files 200 and 300.
                 */

                long gapRepositionVLSN = e.getVLSN();
                backwardsReader = setupBackwardsReader
                    (gapRepositionVLSN,
                     vlsnIndex.getGTELsn(gapRepositionVLSN));
                firstAttempt = false;
                searchResults.noteSkippedGap();
            }
        } while(true);
    }

    /**
     * Ask the feeder for information to add to InsufficientLogException,
     * and then throw the exception.
     *
     * The endVLSN marks the last VLSN that this node will want from
     * the network restore. That information helps ensure that the restore
     * source has enough vlsns to satisfy this replica.
     *
     * The replication node list identifies possible log provider members.
     * @throws IOException
     */
    private InsufficientLogException setupLogRefresh(long failedMatchpoint)
        throws IOException {

        protocol.write(protocol.new RestoreRequest(failedMatchpoint),
                       namedChannel);
        RestoreResponse response =
            (RestoreResponse) protocol.read(namedChannel);

        return new InsufficientLogException(
            repNode,
            new HashSet<>(Arrays.asList(response.getLogProviders())));
    }

    /**
     * Hard recovery:  truncate the files, repeat recovery.
     * If this hard recovery came about before the ReplicatedEnvironment was
     * fully instantiated, we will recreate the environment under the
     * covers. If this came while the replica was up and supporting existing
     * Environment handles, we must invalidate the environment, and ask the
     * application to reopen.
     */
    public RollbackException setupHardRecovery()
        throws IOException {

        /* Creating the exception invalidates the environment. */
        RollbackException r = new RollbackException(repImpl,
                                                    matchpointVLSN,
                                                    searchResults);
        Level level = Level.WARNING;
        if (searchResults.getNumPassedDurableCommits() > 0) {
            level = Level.SEVERE;
        }
        final String passedTxn = searchResults.dumpPassedTxns();
        if (passedTxn != null && passedTxn.length() > 0) {
            LoggerUtils.logMsg(logger, repImpl, level,
                               "Limited list of transactions truncated for " +
                               "hard recovery:\n" +
                               searchResults.dumpPassedTxns());
        } else {
            LoggerUtils.info(logger, repImpl, r.getMessage());
        }

        /*
         * Truncate after the environment is invalidated, which happens
         * when we instantiate RollbackException.  The truncateLSN is the LSN
         * of the record directly after the matchpoint.
         */
        long truncateLSN = searchResults.getTruncateLSN();
        /*
         * Collect the list of files being truncated to pass to
         * LogFileRewriteListener.
         */
        final LogFileRewriteListener listener =
            repImpl.getLogRewriteListener();
        if (listener != null) {
            final FileManager fileMgr = repImpl.getFileManager();
            final long truncateFile = DbLsn.getFileNumber(truncateLSN);
            Set<File> truncatedFiles = new HashSet<>();
            for (long i = fileMgr.getLastFileNum(); i >= truncateFile; i--) {
                if (fileMgr.isFileValid(i)) {
                    truncatedFiles.add(new File(FileManager.getFileName(i)));
                }
            }
            listener.rewriteLogFiles(truncatedFiles);
        }
        repImpl.getFileManager().truncateLog
            (DbLsn.getFileNumber(truncateLSN),
             DbLsn.getFileOffset(truncateLSN));

        /*
         * Truncation might affect existing Backup snapshots, so after
         * truncation need to check whether the latest backup includes file
         * that just got truncated
         */
        try {
            repImpl.getBackupManager().invalidateBackup(DbLsn.getFileNumber(truncateLSN));
        } catch (Exception e) {
            LoggerUtils.info(logger, repImpl, e.getMessage() + " Encountered" +
                " this when trying to invalidate the backup after truncating" +
                " files during replica feeder syncup. This could be due to" +
                " the absence of snapshot files.");
        }
        return r;
    }

    /**
     * Request a log entry from the feeder at this VLSN. The Feeder will only
     * return the log record or say that it isn't available.
     *
     * @throws InsufficientLogException
     */
    private InputWireRecord getFeederRecord(VLSNRange range,
                                            long requestVLSN,
                                            boolean acceptAlternative)
        throws IOException, InsufficientLogException {

        /* Ask the feeder for the matchpoint log record. */
        protocol.write(protocol.new EntryRequest(requestVLSN), namedChannel);

        /*
         * Expect
         *  a) the requested log record
         *  b) message that says this feeder doesn't have RequestVLSN
         *  c) if acceptAlternative == true and the feeder didn't have
         *     requestVLSN, but had an earlier entry, the feeder may send an
         *      earlier, alternative matchpoint
         *  d) ping message which means feeder side is taking a long time
         *  scanning log files, and ping message will be needed to keep
         *  the channel alive
         */
        Message message = protocol.read(namedChannel);
        while(message instanceof Protocol.SyncupPing) {
            protocol.write(protocol.new SyncupPing(),namedChannel);
            message = protocol.read(namedChannel);
        }
        if (message instanceof Entry) {
            Entry entry = (Entry) message;
            return entry.getWireRecord();
        }

        if (message instanceof EntryNotFound) {
            LoggerUtils.info(logger, repImpl, "Requested " + requestVLSN +
                             " from " + namedChannel.getNameIdPair() +
                             " but that node did not have that vlsn.");
            throw setupLogRefresh(requestVLSN);
        }

        if ((acceptAlternative) &&
            (message instanceof AlternateMatchpoint)) {

            AlternateMatchpoint alt = (AlternateMatchpoint) message;
            InputWireRecord feederRecord = alt.getAlternateWireRecord();
            long altMatchpoint = feederRecord.getVLSN();
            if (range.getFirst() > altMatchpoint) {

                /*
                 * The feeder suggest a different matchpoint, but it's outside
                 * the replica's range. Give up and do a network restore.
                 */
                throw setupLogRefresh(altMatchpoint);
            }
            return feederRecord;
        }

        throw EnvironmentFailureException.unexpectedState
            (repNode.getRepImpl(),
             "Sent EntryRequest, got unexpected response of " + message);
    }

    public boolean isNameChannelOpen() {
        return namedChannel.isOpen();
    }

    /**
     * Ping messages will be sent in a fixed frequency during a long scanning
     * of log files to keep the channel alive
     * <p>
     * See ReplicaSyncupReader.sendPingMessage()
     */
    public void sendPingMessage() throws IOException {
        if(repNode.getReplica().canSendPingMessage()) {
            protocol.write(protocol.new SyncupPing(), namedChannel);
            Message message = protocol.read(namedChannel);
            assert message instanceof Protocol.SyncupPing;
        }
    }

    public static void setGlobalSyncupEndHook(TestHook<Object> syncupEndHook) {
        ReplicaFeederSyncup.globalSyncupEndHook = syncupEndHook;
    }

    public static void setSendPingHook(com.sleepycat.je.utilint.TestHook<ReplicaFeederSyncup> syncupEndHook) {
        ReplicaFeederSyncup.sendPingHook = syncupEndHook;
    }

    private boolean runHook()
        throws InterruptedException {

        if (syncupEndHook != null) {
            syncupEndHook.doHook();
        }

        if (globalSyncupEndHook != null) {
            globalSyncupEndHook.doHook();
        }

        if (sendPingHook != null) {
            sendPingHook.doHook(this);
        }
        return true;
    }

    /**
     * This interface is used instead of com.sleepycat.je.utilint.TestHook
     * because the doHook method needs to throw InterruptedException.
     */
    public interface TestHook<T> {
        public void doHook() throws InterruptedException;
    }

    /* Setup the static rollback test hook, test use only */
    public static void setRollbackTestHook(
        com.sleepycat.je.utilint.TestHook<ReplicaFeederSyncup> rollbackHook) {
        ReplicaFeederSyncup.rollbackHook = rollbackHook;
    }
}
