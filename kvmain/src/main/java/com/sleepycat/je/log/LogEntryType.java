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

import java.util.HashSet;
import java.util.Set;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.beforeimage.BeforeImageLNLogEntry;
import com.sleepycat.je.log.entry.AbortLogEntry;
import com.sleepycat.je.log.entry.BINDeltaLogEntry;
import com.sleepycat.je.log.entry.CommitLogEntry;
import com.sleepycat.je.log.entry.ErasedLogEntry;
import com.sleepycat.je.log.entry.FileHeaderEntry;
import com.sleepycat.je.log.entry.INLogEntry;
import com.sleepycat.je.log.entry.LNLogEntry;
import com.sleepycat.je.log.entry.LogEntry;
import com.sleepycat.je.log.entry.MatchpointLogEntry;
import com.sleepycat.je.log.entry.NameLNLogEntry;
import com.sleepycat.je.log.entry.ObsoleteLogEntry;
import com.sleepycat.je.log.entry.ReplicableLogEntry;
import com.sleepycat.je.log.entry.SingleItemEntry;
import com.sleepycat.je.log.entry.TraceLogEntry;

/**
 * LogEntryType is an enumeration of all log entry types.
 *
 * <p>Log entries are versioned. When changing the persistent form of a log
 * entry in any way that is incompatible with prior releases, make sure the
 * LogEntry instance is capable of reading in older versions from the log and
 * be sure to increment LOG_VERSION.  The LogEntry.readEntry and
 * Loggable.readFromLog methods should check the actual version of the entry.
 * If it is less than LOG_VERSION, the old version should be converted to the
 * current version.
 *
 * <p>Prior to LOG_VERSION 6, each log entry type had a separate version number
 * that was incremented only when that log version changed.  From LOG_VERSION 6
 * onward, all types use the same version, the LOG_VERSION constant.  For
 * versions prior to 6, the readEntry and readFromLog methods will be checking
 * the old per-type version.  There is no overlap between the old per-type
 * versions and the LOG_VERSION values, because the per-type values are all
 * below 6. [#15365]</p>

 * <p>The LogEntry instance must be sure that older versions are converted in
 * memory into a correct instance of the newest version, so when that LogEntry
 * object is written again as the result of migration, eviction, the resulting
 * new log entry conforms to the requirements of the new version.  If context
 * objects are required for data conversion, the conversion can be done in the
 * Node.postFetchInit method.</p>
 *
 * <p>Starting with LOG_VERSION 9, log entries that can be included in the
 * replication stream must be able to write themselves in the format for the
 * immediately previous log version, to allow replication during an upgrade
 * when the master has been upgraded and a replica has not.  Starting with
 * LOG_VERSION 8, log entries that support replication must implement {@link
 * ReplicableLogEntry}.  When changes are made to replicable log entries for
 * LOG_VERSION 9 and later, those entries need to support writing in the
 * previous version's format.</p>
 */
public class LogEntryType {

    /**
     * Version of the file header, which identifies the version of all entries
     * in that file.
     *
     * Changes to log entries for each version are:
     *
     * Version 3
     * ---------
     * [12328] Add main and dupe tree fanout values for DatabaseImpl.
     * [12557] Add IN LSN array compression.
     * [11597] Add a change to FileSummaryLNs: obsolete offset tracking was
     * added and multiple records are stored for a single file rather than a
     * single record.  Each record contains the offsets that were tracked since
     * the last record was written.
     * [11597] Add the full obsolete LSN in LNLogEntry.
     *
     * Version 4
     * ---------
     * [#14422] Bump MapLN version from 1 to 2.  Instead of a String for the
     * comparator class name, store either a serialized string or Comparator.
     *
     * Version 5
     * ---------
     * [#15195] FileSummaryLN version 3.  Add FileSummary.obsoleteLNSize and
     * obsoleteLNSizeCounted fields.
     *
     * Version 6 (in JE 3.3.X)
     * ---------
     * [#15365] From this point onward, all log entries have the same version,
     * LOG_VERSION, rather than using per-type versions.
     * [#15365] DatabaseImpl stores a map of DbFileSummaries.
     *
     * [#13467] Convert duplicatesAllowed boolean to DUPS_ALLOWED_BIT flag in
     * DatabaseImpl. Add REPLICATED_BIT flag to DatabaseImpl.
     * [#13467] Add REPLICATED_BIT to DbTree.
     * [#13467] Add ReplicatedDatabaseConfig to NameLN_TX to support
     * replication of database operations.
     *
     * [#15581] Add lastAllocateReplicatedDbId to DbTree
     * [#16083] Add replication master node ID to txn commit/abort
     *
     * Version 7 (in JE 4.0)
     * ---------------------
     * Add the invisible bit in the entry header version field.
     * Add the RollbackStart log entry type
     * Add the RollbackEnd log entry type
     * Add the Matchpoint log entry type.
     *
     * Version 8 (in JE 5.0)
     * ---------------------
     * Made provisions for storing Triggers in a DatabaseImpl.
     *
     * Database IDs enlarged from int or packed int, to long or packed long
     * (note that packed int and packed long are compatible).  [#18540]
     *
     * Add new log entry types for LN delete, insert and update. [#18055]
     *
     * Apply optimization to omit key size for some internal LNs, in addition
     * to user LNs. [#18055]
     *
     * LN no longer has node ID. [#18633]
     *
     * Add FileSummary.maxLNSize. [#18633]
     *
     * VLSN is optionally maintained in LogEntryHeader for cleaner migrated LNs
     * and a new VLSN_PRESENT entry header flag is used to signify the presence
     * of the VLSN.  PRESERVE_VLSN_BIT was added to DbTree to correspond to the
     * je.env.preserveRecordVersion environment config param. [#19476]
     *
     * Dup tree representation changed to use two-part keys. Deprecated: DIN,
     * DBIN, DupCountLN, INDeleteInfo, INDupDeleteInfo.  Removed
     * DatabaseImpl.maxDupTreeEntriesPerNode.  [#19165]
     *
     * Version 9 (in JE 6.0)
     * ---------------------
     * See comment above about ReplicableLogEntry.
     *
     * BIN-deltas are now represented as BINs using the new BINDeltaLogEntry
     * (log entry type NewBINDelta).
     *
     * Version 10 (in JE 6.2)
     * ----------------------
     * Each BIN-delta stores the total and max number of entries in the
     * previous full version of the same BIN. A BIN-delta may also store a
     * bloom filter for the keys in the full BIN.
     *
     * Version 11 (in JE 6.3)
     * ----------------------
     * LN log records have additional info to handle embedded records. See
     * LNLogEntry for details. Also, BIN log records include the VLSNs of
     * embedded records.
     *
     * Added LOG_IMMUTABLE_FILE entry type.
     *
     * Version 12 (in JE 7.0)
     * ----------------------
     * Added expiration info to LNs and BIN slots. The LN's expiration time is
     * replicated, so this changes the replication format. Also added the "have
     * abort LSN" flag, to avoid writing a byte for a null LSN, and moved the
     * flags to the front to support the new forReplication format.
     *
     * For ReplicableLogEntry and VersionedWriteLoggable, added the
     * forReplication parameter to write methods to support a replication
     * format variation. This allows omitting some fields from an entry when
     * they are not needed by replication.
     *
     * LNLogEntry in forReplication mode no longer includes the abortLSN or the
     * abortKnownDeleted flag. This is a format change because these were
     * used by Replay in earlier versions.
     *
     * Txn and VersionedWriteTxnEnd (Commit and Abort) in forReplication mode
     * now always includes a null prevLsn field, which only occupies one byte.
     * However, this is not strictly a format change, because this field has
     * never been used by Replay.
     *
     * Version 13 (in JE 7.1)
     * ----------------------
     *
     * Added dtvlsn field to LOG_TXN_END entry to support efficient persistent
     * tracking of the DTVLN (Durable Transaction VLSN).
     *
     * Version 14 (in JE 7.3)
     * ----------------------
     *
     * Added LOG_RESTORE_REQUIRED to indicate that the environment's log is no
     * longer consistent, and some curative action must happen before the
     * environment can be recovered.
     *
     * Version 15 (in JE 7.5)
     * ----------------------
     *
     * Fixed a bug in mutation of a BIN-delta to a full BIN where the
     * identifierKey was not set correctly. The identifierKey can only be
     * checked by the BtreeVerifier when the log was initially created using
     * log version 15 or greater. Added new field DbTree.initialLogVersion
     * to support this.
     *
     * The GlobalCBVLSN is no longer updated in the rep group DB when all
     * nodes in a rep group have been updated to 7.5 or later. The network
     * restore protocol no longer relies on the GlobalCBVLSN.
     *
     * The _jeReservedFilesDb internal DB (DbType.RESERVED_FILES) was added.
     * Uses a new LN log entry type: LOG_RESERVED_FILE_LN. Contains metadata
     * used to manage reserved files.
     *
     * Version 16 (in JE 18.1)
     * ----------------------
     *
     * The _jeExtinctScansDb internal DB (DbType.EXTINCT_SCANS) was added.
     * Uses new LN log entry types: LOG_EXTINCT_SCAN_LN_TRANSACTIONAL and
     * LOG_EXTINCT_SCAN_LN. Contains a record for each outstanding
     * record and database extinction scan.
     *
     * The databaseName persistent field was added to DatabaseImpl.
     *
     * LNLogEntry now contains flags2, priorSize and priorFile.
     *
     * Version 17 (in JE 18.2)
     * ----------------------
     * [#26973] Two fields were added to CheckpointEnd:
     *   lastLocalExtinctionId, lastReplicatedExtinctionId.
     *
     * [#26954] LOG_ERASED type was added. Also added new _jeMetadata
     * internal DB.
     *
     * Version 18 (in JE 19.3)
     * -----------------------
     * [#26741] IN format changes:
     *   - Removed compact LSN representation fields.
     *   - Added compactMaxKeySize field.
     *
     * Version 19 (in JE 19.5)
     * -----------------------
     * [#27680] active-active support
     *   - Added tombstone flag to BIN slots.
     *   - Added tombstone and abortTombstone flags to LNLogEntry.
     *   - Added modificationTime, abortModificationTime and
     *     haveAbortModificationTime flag to LNLogEntry.
     *   - Added modificationTimeBase to BIN and modificationTime to BIN
     *     slots, for embedded LNs.
     *   - Rearrange order of LNLogEntry fields to support partial reads.
     *
     * [#27597] erase obsolete records
     *   - added FileSummaryLN.anchorLsn
     *
     * Version 20 (in JE 20.1)
     * -----------------------
     * [#27992] Blind index deletions
     *   - Added blindDeletion flag to LNLogEntry
     *   - Aborts of blind deletions are done differently, which requires the
     *     flag above and is therefore incompatible with prior versions.
     *
     * Version 21 (in JE 20.2)
     * -----------------------
     * [KVSTORE-135] Sized IN fetches
     *  - Added last logged IN size to UIN slots.
     *  - Added last logged size of full/delta BIN to the INLogEntry.
     *
     *  Version 22 (in JE 21.3)
     * -----------------------
     *  [KVSTORE-521] Added master term in commit/abort records
     *
     *  Version 23 (in JE 23.1)
     * -----------------------
     *  [KVSTORE-718] Add extinction scan IDs to CkptEnd
     *
     *  Version 24 (in JE 24.2)
     * -----------------------
     *  [KVSTORE-2316] Remove support for OldBINDelta
     *
     *  Version 25 (in JE 24.2)
     * -----------------------
     *  [KVSTORE-2302] Add support for Before Images
     *
     */
    public static final int LOG_VERSION = 25;

    /**
     * The latest log version for which the replicated log format of any
     * replicable log entry class changed.  Replication uses this value to
     * determine if the latest version of the replication stream can be
     * understood by an earlier software version.  This field is needed to
     * account for cases where log entry format changes only apply to
     * non-replicable entries, or only to the local, not replicated, form of
     * replicable entries, the as was the case for log versions 9, 10, and 11.
     */
    public static final int LOG_VERSION_HIGHEST_REPLICABLE = 25;

    /**
     * Log versions prior to 8 (JE 5.0) are no longer supported as of JE 20.1.
     * An attempt to open a jdb file with version 7 or earlier will throw
     * LogVersionException. The first released version of KVS used version 8.
     */
    public static final int FIRST_LOG_VERSION = 8;

    /**
     * The earliest log version for which replicable log entries support
     * writing themselves in older versions, to support replication to
     * older nodes during upgrades.
     */
    public static final int LOG_VERSION_REPLICATE_OLDER = 9;

    /*
     * The log version that added expiration info to LNs and BIN slots for JE
     * 7.0.
     */
    public static final int LOG_VERSION_EXPIRE_INFO = 12;

    /*
     * The log version that introduced the dtvlsn field in commit log entries.
     */
    public static final int LOG_VERSION_DURABLE_VLSN = 13;

    /*
     * The log version that introduced the master term field in commit log
     * entries
     */
    public static final int LOG_VERSION_MASTER_TERM = 22;

    /*
     * The log version that introduced the scan id list in checkpointend log
     * entries
     */
    public static final int LOG_VERSION_CKPT_SCAN_IDS = 23;

    /**
     * Should be used for reading the entry header of the file header, since
     * the actual version is not known until the FileHeader item is read.
     */
    public static final int UNKNOWN_FILE_HEADER_VERSION = -1;

    /*
     * Collection of log entry type classes, used to read the log.  Note that
     * this must be declared before any instances of LogEntryType, since the
     * constructor uses this map. Each statically defined LogEntryType should
     * register itself with this collection.
     */
    private static final int MAX_TYPE_NUM = 45;
    private static LogEntryType[] LOG_TYPES = new LogEntryType[MAX_TYPE_NUM];

    /*
     * Enumeration of log entry types. The log entry type represents the 2 byte
     * field that starts every log entry. The top byte is the log type, the
     * bottom byte holds the version value, provisional bit, replicated bit,
     * and invisible bit.
     *
     * Log type(8 bits)
     * Provisional(2 bits) Replicated(1 bit) Invisible(1 bit) Version(5 bits)
     *
     * The top byte (log type) identifies the type and can be used to lookup
     * the LogEntryType object, while the bottom byte has information about the
     * entry (instance) of this type.  The bottom byte is effectively entry
     * header information that is common to all types and is managed by methods
     * in LogEntryHeader. See LogEntryHeader.java
     */

    /*
     * Node types
     *
     * The following types have been removed and their type numbers could be
     * in theory be reused. They were obsolete in version 8 and are no longer
     * supported as of JE 20.1.
     *   1 "LN_TX"
     *   2 "LN"
     *   7 "DelDupLN_TX"
     *   8 "DelDupLN"
     *   9 "DupCountLN_TX"
     *  10 "DupCountLN"
     *  14 "DIN"
     *  15 "DBIN"
     *  21 "INDelete"
     *  23 "DupBINDelta"
     *  26 "INDupDelete"
     */

    /* The mapping DB is currently non-transactional. */
    public static final LogEntryType LOG_MAPLN_TRANSACTIONAL =
        new LogEntryType
        ((byte) 3, "MapLN_TX",
         LNLogEntry.create(com.sleepycat.je.tree.MapLN.class),
         Txnal.TXNAL,
         Marshall.OUTSIDE_LATCH,
         NodeType.LN_INTERNAL);

    public static final LogEntryType LOG_MAPLN =
        new LogEntryType
        ((byte) 4, "MapLN",
         LNLogEntry.create(com.sleepycat.je.tree.MapLN.class),
         Txnal.NON_TXNAL,
         Marshall.OUTSIDE_LATCH,
         NodeType.LN_INTERNAL);

    public static final LogEntryType LOG_NAMELN_TRANSACTIONAL =
        createReplicableLogEntryType(
            (byte) 5, "NameLN_TX",
            new NameLNLogEntry(),
            Txnal.TXNAL,
            Marshall.OUTSIDE_LATCH,
            Replicable.REPLICABLE_NO_MATCH,
            NodeType.LN_INTERNAL);

    public static final LogEntryType LOG_NAMELN =
        createReplicableLogEntryType(
            (byte) 6, "NameLN",
            new NameLNLogEntry(),
            Txnal.NON_TXNAL,
            Marshall.OUTSIDE_LATCH,
            Replicable.REPLICABLE_NO_MATCH,
            NodeType.LN_INTERNAL);

    public static final LogEntryType LOG_FILESUMMARYLN =
        new LogEntryType
        ((byte) 11, "FileSummaryLN",
         LNLogEntry.create(com.sleepycat.je.tree.FileSummaryLN.class),
         Txnal.NON_TXNAL,
         Marshall.INSIDE_LATCH, /* Logging changes file utilization. */
         NodeType.LN_INTERNAL);

    public static final LogEntryType LOG_IN =
        new LogEntryType
        ((byte) 12, "IN",
         INLogEntry.create(com.sleepycat.je.tree.IN.class),
         Txnal.NON_TXNAL,
         Marshall.OUTSIDE_LATCH,
         NodeType.IN);

    public static final LogEntryType LOG_BIN =
        new LogEntryType
        ((byte) 13, "BIN",
         INLogEntry.create(com.sleepycat.je.tree.BIN.class),
         Txnal.NON_TXNAL,
         Marshall.OUTSIDE_LATCH,
         NodeType.IN);

    /*
     * The root entry of the DbTree, it saves the root information for name
     * and id database.
     */
    public static final LogEntryType LOG_DBTREE =
        new LogEntryType
        ((byte) 16, "DbTree",
         SingleItemEntry.create(
             com.sleepycat.je.dbi.DbTree.class),
         Txnal.NON_TXNAL,
         Marshall.OUTSIDE_LATCH,
         NodeType.NONE);

    /* Transactional entries */
    public static final LogEntryType LOG_TXN_COMMIT =
        createReplicableLogEntryType(
            (byte) 17, "Commit",
            new CommitLogEntry(),
            Txnal.TXNAL,
            Marshall.INSIDE_LATCH, /* To ensure DTVLSN is in sync with VLSN */
            Replicable.REPLICABLE_MATCH,
            NodeType.NONE);

    public static final LogEntryType LOG_TXN_ABORT =
        createReplicableLogEntryType(
            (byte) 18, "Abort",
            new AbortLogEntry(),
            Txnal.TXNAL,
            Marshall.INSIDE_LATCH, /* To ensure DTVLSN is in sync with VLSN */
            Replicable.REPLICABLE_MATCH,
            NodeType.NONE);

    public static final LogEntryType LOG_CKPT_START =
        new LogEntryType
        ((byte) 19, "CkptStart",
         SingleItemEntry.create(
             com.sleepycat.je.recovery.CheckpointStart.class),
         Txnal.NON_TXNAL,
         Marshall.OUTSIDE_LATCH,
         NodeType.NONE);

    public static final LogEntryType LOG_CKPT_END =
        new LogEntryType
        ((byte) 20, "CkptEnd",
         SingleItemEntry.create(
             com.sleepycat.je.recovery.CheckpointEnd.class),
         Txnal.NON_TXNAL,
         Marshall.OUTSIDE_LATCH,
         NodeType.NONE);

    /* Although this is replicable, it is never replicated except in tests. */
    public static final LogEntryType LOG_TRACE =
        createReplicableLogEntryType(
            (byte) 24, "Trace",
            new TraceLogEntry(),
            Txnal.NON_TXNAL,
            Marshall.OUTSIDE_LATCH,
            Replicable.REPLICABLE_NO_MATCH,
            NodeType.NONE);

    /* File header */
    public static final LogEntryType LOG_FILE_HEADER =
        new LogEntryType
        ((byte) 25, "FileHeader",
         new FileHeaderEntry
         (com.sleepycat.je.log.FileHeader.class),
         Txnal.NON_TXNAL,
         Marshall.OUTSIDE_LATCH,
         NodeType.NONE);

    /*
     * LOG_TXN_PREPARE is obsolete as of JE 20.1, but actually has not been
     * supported since JE 7.5 and has never been used in KVS, meaning that it
     * has never and will never appear in the KVS transaction log. Could not be
     * remove because it appears in older data used by LogEntryVersionTest.
     */
    public static final LogEntryType LOG_TXN_PREPARE =
        new LogEntryType
        ((byte) 27, "Prepare",
         new ObsoleteLogEntry(),
         Txnal.TXNAL,
         Marshall.OUTSIDE_LATCH,
         NodeType.NONE);

    public static final LogEntryType LOG_ROLLBACK_START =
        new LogEntryType
        ((byte) 28, "RollbackStart",
         SingleItemEntry.create(
             com.sleepycat.je.txn.RollbackStart.class),
         Txnal.NON_TXNAL,
         Marshall.OUTSIDE_LATCH,
         NodeType.NONE);

    public static final LogEntryType LOG_ROLLBACK_END =
        new LogEntryType
        ((byte) 29, "RollbackEnd",
         SingleItemEntry.create(
             com.sleepycat.je.txn.RollbackEnd.class),
         Txnal.NON_TXNAL,
         Marshall.OUTSIDE_LATCH,
         NodeType.NONE);

    public static final LogEntryType LOG_MATCHPOINT =
        createReplicableLogEntryType(
            (byte) 30, "Matchpoint",
            new MatchpointLogEntry(),
            Txnal.NON_TXNAL,
            Marshall.OUTSIDE_LATCH,
            Replicable.REPLICABLE_MATCH,
            NodeType.NONE);

    public static final LogEntryType LOG_DEL_LN_TRANSACTIONAL =
        createUserLNLogEntryType((byte) 31, "DEL_LN_TX", Txnal.TXNAL);

    public static final LogEntryType LOG_DEL_LN =
        createUserLNLogEntryType((byte) 32, "DEL_LN", Txnal.NON_TXNAL);

    public static final LogEntryType LOG_INS_LN_TRANSACTIONAL =
        createUserLNLogEntryType((byte) 33, "INS_LN_TX", Txnal.TXNAL);

    public static final LogEntryType LOG_INS_LN =
        createUserLNLogEntryType((byte) 34, "INS_LN", Txnal.NON_TXNAL);

    public static final LogEntryType LOG_UPD_LN_TRANSACTIONAL =
        createUserLNLogEntryType((byte) 35, "UPD_LN_TX", Txnal.TXNAL);

    public static final LogEntryType LOG_UPD_LN =
        createUserLNLogEntryType((byte) 36, "UPD_LN", Txnal.NON_TXNAL);

    public static final LogEntryType LOG_BIN_DELTA =
        new LogEntryType(
            (byte) 37, "NewBINDelta",
            new BINDeltaLogEntry(com.sleepycat.je.tree.BIN.class),
            Txnal.NON_TXNAL,
            Marshall.OUTSIDE_LATCH,
            NodeType.IN);

    public static final LogEntryType LOG_IMMUTABLE_FILE =
        new LogEntryType(
            (byte) 38, "ImmutableFile",
            SingleItemEntry.create(
                com.sleepycat.je.log.entry.EmptyLogEntry.class),
            Txnal.NON_TXNAL,
            Marshall.OUTSIDE_LATCH,
            NodeType.NONE);

    public static final LogEntryType LOG_RESTORE_REQUIRED =
        new LogEntryType(
            (byte) 39, "RestoreRequired",
            SingleItemEntry.create(
                com.sleepycat.je.log.entry.RestoreRequired.class),
            Txnal.NON_TXNAL,
            Marshall.OUTSIDE_LATCH,
            NodeType.NONE);

    public static final LogEntryType LOG_RESERVED_FILE_LN =
        new LogEntryType
            ((byte) 40, "ReservedFileLN",
            LNLogEntry.create(com.sleepycat.je.tree.LN.class),
            Txnal.NON_TXNAL,
            Marshall.OUTSIDE_LATCH,
            NodeType.LN_INTERNAL);

    public static final LogEntryType LOG_EXTINCT_SCAN_LN =
        new LogEntryType
            ((byte) 41, "ExtinctScanLN",
            LNLogEntry.create(com.sleepycat.je.tree.LN.class),
            Txnal.NON_TXNAL,
            Marshall.OUTSIDE_LATCH,
            NodeType.LN_INTERNAL);

    public static final LogEntryType LOG_EXTINCT_SCAN_LN_TRANSACTIONAL =
        new LogEntryType
            ((byte) 42, "ExtinctScanLN_TX",
            LNLogEntry.create(com.sleepycat.je.tree.LN.class),
            Txnal.TXNAL,
            Marshall.OUTSIDE_LATCH,
            Replicable.REPLICABLE_NO_MATCH,
            NodeType.LN_INTERNAL);

    public static final LogEntryType LOG_ERASED =
        new LogEntryType(
            (byte) 43, "Erased",
            new ErasedLogEntry(),
            Txnal.NON_TXNAL,
            Marshall.OUTSIDE_LATCH,
            NodeType.NONE);

    public static final LogEntryType
        LOG_UPD_LN_TRANSACTIONAL_WITH_BEFORE_IMAGE =
        createUserLNBeforeImageLogEntryType(
            (byte) 44, "UPD_LN_TX_WITH_BEFORE_IMAGE", Txnal.TXNAL);

    public static final LogEntryType
        LOG_DEL_LN_TRANSACTIONAL_WITH_BEFORE_IMAGE =
        createUserLNBeforeImageLogEntryType(
            (byte) 45, "DEL_LN_TX_WITH_BEFORE_IMAGE", Txnal.TXNAL);


    /*** If you add new types, be sure to update MAX_TYPE_NUM at the top.***/

    /* Persistent fields */
    private final byte typeNum; // persistent value for this entry type

    /* Transient fields */
    private final String displayName;
    private final LogEntry logEntry;

    /*
     * Attributes
     */

    /* Whether the log entry holds a transactional information. */
    private Txnal isTransactional;

    /*
     * Does this log entry be marshalled outside or inside the log write
     * latch.
     */
    private Marshall marshallBehavior;

    /* Can this log entry be put in the replication stream? */
    private Replicable replicationPossible;

    private NodeType nodeType;

    /*
     * Constructors
     */

    /**
     * For base class support.
     */

    /*
     * This constructor only used when the LogEntryType is being used as a key
     * for a map. No log types can be defined outside this package.
     */
    LogEntryType(byte typeNum) {
        this.typeNum = typeNum;
        displayName = null;
        logEntry = null;
    }

    /**
     * Create a non-replicable log type.
     *
     * @param isTransactional whether this type of log entry holds data
     * involved in a transaction. For example, transaction commit and LN data
     * records are transactional, but INs are not.
     * @param marshallBehavior whether this type of log entry may be serialized
     * outside the log write latch. This is true of the majority of
     * types. Certain types like the FileSummaryLN rely on the log write latch
     * to enforce serial semantics.
     */
    private LogEntryType(final byte typeNum,
                         final String displayName,
                         final LogEntry logEntry,
                         final Txnal isTransactional,
                         final Marshall marshallBehavior,
                         final NodeType nodeType) {

        this(typeNum, displayName, logEntry, isTransactional, marshallBehavior,
             Replicable.LOCAL, nodeType);
    }

    /**
     * Create a replicable log type.
     *
     * @param isTransactional whether this type of log entry holds data
     * involved in a transaction
     * @param marshallBehavior whether this type of log entry may be serialized
     * outside the log write latch
     * @param replicationPossible whether this type of log entry can be shared
     * with a replication group
     */
    private static LogEntryType createReplicableLogEntryType(
        final byte typeNum,
        final String displayName,
        final ReplicableLogEntry logEntry,
        final Txnal isTransactional,
        final Marshall marshallBehavior,
        final Replicable replicationPossible,
        final NodeType nodeType) {

        return new LogEntryType(typeNum, displayName, logEntry,
                                isTransactional, marshallBehavior,
                                replicationPossible, nodeType);
    }

    private static LogEntryType createUserLNLogEntryType(
        final byte typeNum,
        final String displayName,
        final Txnal txnal) {

        return new LogEntryType(typeNum, displayName,
            LNLogEntry.create(com.sleepycat.je.tree.LN.class),
            txnal, Marshall.OUTSIDE_LATCH,
            Replicable.REPLICABLE_NO_MATCH, NodeType.LN_USER);
    }

    private static LogEntryType createUserLNBeforeImageLogEntryType(
        final byte typeNum,
        final String displayName,
        final Txnal txnal) {

        return new LogEntryType(typeNum, displayName,
            new BeforeImageLNLogEntry(), txnal, Marshall.OUTSIDE_LATCH,
            Replicable.REPLICABLE_NO_MATCH, NodeType.LN_USER);
    }

    /**
     * Internal constructor for all log types.  Don't create instances using
     * this directly, to improve error checking.
     */
    private LogEntryType(final byte typeNum,
                         final String displayName,
                         final LogEntry logEntry,
                         final Txnal isTransactional,
                         final Marshall marshallBehavior,
                         final Replicable replicationPossible,
                         final NodeType nodeType) {

        assert logEntry != null && replicationPossible != null;
        assert !replicationPossible.isReplicable() ||
            logEntry instanceof ReplicableLogEntry
            : "Replicable log types must have replicable log entries";

        this.typeNum = typeNum;
        this.displayName = displayName;
        this.logEntry = logEntry;
        this.isTransactional = isTransactional;
        this.marshallBehavior = marshallBehavior;
        this.replicationPossible = replicationPossible;
        this.nodeType = nodeType;
        logEntry.setLogType(this);
        LOG_TYPES[typeNum - 1] = this;
    }

    /**
     * @return the static version of this type
     */
    public static LogEntryType findType(byte typeNum) {
        if (typeNum <= 0 || typeNum > MAX_TYPE_NUM) {
            return null;
        }
        return LOG_TYPES[typeNum - 1];
    }

    /**
     * Get a copy of all types for unit testing.
     */
    public static Set<LogEntryType> getAllTypes() {
        HashSet<LogEntryType> ret = new HashSet<>();

        for (int i = 0; i < MAX_TYPE_NUM; i++) {
            final LogEntryType type = LOG_TYPES[i];
            if (type != null) {
                ret.add(type);
            }
        }
        return ret;
    }

    /**
     * @return the log entry type owned by the shared, static version
     */
    public LogEntry getSharedLogEntry() {
        return logEntry;
    }

    /**
     * @return a clone of the log entry type for a given log type.
     */
    public LogEntry getNewLogEntry()
        throws DatabaseException {

        return logEntry.clone();
    }

    public byte getTypeNum() {
        return typeNum;
    }

    /**
     * @return true if type number is valid.
     */
    static boolean isValidType(byte typeNum) {
        return findType(typeNum) != null;
    }

    public String toStringNoVersion() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /**
     * Check for equality without making a new object.
     */
    public boolean equalsType(byte type) {
        return (this.typeNum == type);
    }

    /*
     * Override Object.equals. Ignore provisional bit when checking for
     * equality.
     */
    @Override
    public boolean equals(Object obj) {
        /* Same instance? */
        if (this == obj) {
            return true;
        }

        /* Is it the right type of object? */
        if (!(obj instanceof LogEntryType)) {
            return false;
        }

        return typeNum == ((LogEntryType) obj).typeNum;
    }

    /**
     * This is used as a hash key.
     */
    @Override
    public int hashCode() {
        return typeNum;
    }

    enum Txnal {
        TXNAL(true),
        NON_TXNAL(false);

        private final boolean isTxnal;

        Txnal(boolean isTxnal) {
            this.isTxnal = isTxnal;
        }

        boolean isTransactional() {
            return isTxnal;
        }
    }

    /**
     * Return true if this log entry has transactional information in it,
     * like a commit or abort record, or a transactional LN.
     */
    public boolean isTransactional() {
        return isTransactional.isTransactional();
    }

    enum Marshall {
        OUTSIDE_LATCH(true),
        INSIDE_LATCH(false);

        private final boolean marshallOutsideLatch;

        Marshall(boolean marshallOutsideLatch) {
            this.marshallOutsideLatch = marshallOutsideLatch;
        }

        boolean marshallOutsideLatch() {
            return marshallOutsideLatch;
        }
    }

    /**
     * Return true if this log entry should be marshalled into a buffer outside
     * the log write latch. Currently, the FileSummaryLN and MapLN (which
     * contains DbFileSummary objects) and the commit and abort log entries
     * (due to their DTVLSN fields) need to be logged inside the log write
     * latch.
     */
    boolean marshallOutsideLatch() {
        return marshallBehavior.marshallOutsideLatch();
    }

    /*
     * Indicates whether this type of log entry is shared in a replicated
     * environment or not, and whether it can be used as a replication
     * matchpoint.
     */
    enum Replicable {
        REPLICABLE_MATCH(true, true),
        REPLICABLE_NO_MATCH(true, false),
        LOCAL(false, false);

        private final boolean isReplicable;
        private final boolean isMatchable;

        Replicable(boolean isReplicable, boolean isMatchable) {
            this.isReplicable = isReplicable;
            this.isMatchable = isMatchable;
        }

        boolean isReplicable() {
            return isReplicable;
        }

        boolean isMatchable() {
            return isMatchable;
        }
    }

    /**
     * Return true if this type of log entry can be part of the replication
     * stream. For example, INs can never be replicated, while LNs are
     * replicated only if their owning database is replicated.
     */
    public boolean isReplicationPossible() {
        return replicationPossible.isReplicable();
    }

    /**
     * Return true if this type of log entry can serve as the synchronization
     * matchpoint for the replication stream. That generally means that this
     * log entry contains an replication node ID.
     */
    private boolean isSyncPoint() {
        return replicationPossible.isMatchable();
    }

    /**
     * Return true if this type of log entry can serve as the synchronization
     * matchpoint for the replication stream.
     */
    public static boolean isSyncPoint(byte entryType) {
        final LogEntryType type = findType(entryType);
        assert type != null;
        return type.isSyncPoint();
    }

    /* Type of Btree node. */
    enum NodeType {

        /* Not a Btree node. */
        NONE,

        /* Internal node, including BINs and UINs. */
        IN,

        /* LNs representing records in internal databases. */
        LN_INTERNAL,

        /* LNs representing ordinary user records.  */
        LN_USER,
    }

    public boolean isNodeType() {
        return nodeType != NodeType.NONE;
    }

    public boolean isUserLNType() {
        return nodeType == NodeType.LN_USER;
    }

    public boolean isLNType() {
        return nodeType == NodeType.LN_INTERNAL || isUserLNType();
    }

    boolean isINType() {
        return nodeType == NodeType.IN;
    }

	public static boolean isBeforeImageType(byte typeNum) {
		LogEntryType t = findType(typeNum);
		return t != null && (t == LOG_UPD_LN_TRANSACTIONAL_WITH_BEFORE_IMAGE
				|| t == LOG_DEL_LN_TRANSACTIONAL_WITH_BEFORE_IMAGE);
	}
}
