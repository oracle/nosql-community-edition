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

package com.sleepycat.je.rep.util.ldiff;

import java.io.IOException;
import java.util.Iterator;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseNotFoundException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentFailureException;
import com.sleepycat.je.rep.impl.RepImpl;
import com.sleepycat.je.rep.impl.node.NameIdPair;
import com.sleepycat.je.rep.net.DataChannel;
import com.sleepycat.je.rep.util.ldiff.Protocol.DbBlocks;
import com.sleepycat.je.rep.util.ldiff.Protocol.EnvDiff;
import com.sleepycat.je.rep.util.ldiff.Protocol.RemoteDiffRequest;
import com.sleepycat.je.rep.utilint.ServiceDispatcher;
import com.sleepycat.je.rep.utilint.BinaryProtocol.Message;
import com.sleepycat.je.rep.utilint.BinaryProtocol.ProtocolException;
import com.sleepycat.je.rep.utilint.ServiceDispatcher.ExecutingService;

/**
 * Implementation of the LDiff service that process requests from the LDiff
 * client. It's the network level interface to the remote processing done as
 * part of the ldiff implementation.
 *
 * Note that the service only processes one request at a time, so as not to
 * overload the node.
 */
public class LDiffService extends ExecutingService {

    /* The service name. */
    public static final String NAME = "LDiff";

    /*
     * Determines whether the service is busy and will accept a new
     * request.
     */
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final RepImpl repImpl;
    private final ServiceDispatcher dispatcher;

    public LDiffService(ServiceDispatcher dispatcher, RepImpl repImpl) {
        super(NAME, dispatcher);
        this.repImpl = repImpl;
        this.dispatcher = dispatcher;
        dispatcher.register(this);
    }

    public void shutdown() {
        dispatcher.cancel(NAME);
    }

    /*
     * Returns busy if we are already processing a request.
     */
    @Override
    public boolean isBusy() {
        return busy.get();
    }

    @Override
    public Runnable getRunnable(DataChannel dataChannel) {
        if (!busy.compareAndSet(false, true)) {
            throw EnvironmentFailureException.unexpectedState
                ("Service is already busy");
        }
        return new LDiffServiceRunnable(dataChannel);
    }

    class LDiffServiceRunnable implements Runnable {
        final DataChannel channel;
        private Environment env;
        private DatabaseConfig dbConfig = new DatabaseConfig();

        LDiffServiceRunnable(DataChannel dataChannel) {
            this.channel = dataChannel;
            dbConfig.setReadOnly(true);
            dbConfig.setAllowCreate(false);
        }

        public void runLDiff(DbBlocks request, Protocol protocol)
            throws IOException {

            Database db = null;
            Cursor cursor = null;
            try{
                db = env.openDatabase
                    (null, request.getDbName(), dbConfig);
                protocol.write(protocol.new BlockListStart(), channel);
                LDiffConfig cfg = new LDiffConfig();
                cfg.setBlockSize(request.getBlockSize());
                LDiff ldf = new LDiff(cfg);
                /* Use the Iterator to stream the blocks across the wire. */
                Iterator<Block> blocks = ldf.iterator(db);
                while (blocks.hasNext()) {
                    protocol.write(protocol.new BlockInfo(blocks.next()),
                            channel);
                }
                protocol.write(protocol.new BlockListEnd(), channel);

                /* Start to do the record difference analysis. */
                Message msg = protocol.read(channel);
                if (msg.getOp().getMessageClass() ==
                    Protocol.REMOTE_DIFF_REQUEST_CLASS) {
                    cursor = db.openCursor(null, null);
                    sendDiffArea(cursor, (RemoteDiffRequest) msg, protocol);
                    runDiffAnalysis(cursor, protocol);
                } else if (msg.getOp().getMessageClass() !=
                    Protocol.DONE_CLASS) {
                    protocol.write(protocol.new ProtocolError
                            ("Invalid message: " + msg), channel);
                }
            } catch (DatabaseNotFoundException e) {
                protocol.write(protocol.new DbMismatch(e.getMessage()),
                               channel);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
                if (db != null) {
                    db.close();
                }
            }
        }

        /* Get records for all different areas and send out. */
        private void runDiffAnalysis(Cursor cursor,
                                     Protocol protocol)
            throws IOException {

            while (true) {
                Message msg = protocol.read(channel);
                if (msg.getOp().getMessageClass() ==
                    Protocol.REMOTE_DIFF_REQUEST_CLASS) {
                    sendDiffArea(cursor, (RemoteDiffRequest) msg, protocol);
                } else {
                    if (msg.getOp().getMessageClass() != Protocol.DONE_CLASS) {
                        protocol.write(protocol.new ProtocolError
                                ("Invalid message: " + msg), channel);
                    }
                    break;
                }
            }
        }

        /* Send the different records of an area to the requested machine. */
        private void sendDiffArea(Cursor cursor,
                                  RemoteDiffRequest request,
                                  Protocol protocol)
            throws IOException {

            /* Get the records in the different area. */
            HashSet<Record> records = null;
            try {
                records = DiffRecordAnalyzer.getDiffArea(cursor, request);
            } catch (Exception e) {
                protocol.write(protocol.new Error(e.getMessage()), channel);
                throw new LDiffRecordRequestException(e.getMessage());
            }

            /* Write them out to the requested machine. */
            protocol.write(protocol.new DiffAreaStart(), channel);
            for (Record record: records) {
                protocol.write(protocol.new RemoteRecord(record), channel);
            }
            protocol.write(protocol.new DiffAreaEnd(), channel);
        }

        public void runEnvDiff(EnvDiff request, Protocol protocol)
            throws IOException {

            protocol.write(protocol.new EnvInfo
                           (env.getDatabaseNames().size()), channel);
        }

        @Override
        public void run() {
            final Protocol protocol;

            ensureChannelBlocking(channel);
            try {
                env = repImpl.getInternalEnvHandle();
                protocol = new Protocol(new NameIdPair("Ldiff", -1), repImpl);
                try {
                    Message msg = protocol.read(channel);
                    if (msg.getOp().getMessageClass()==
                        Protocol.DB_BLOCKS_CLASS) {
                        runLDiff((DbBlocks)msg, protocol);
                    } else if (msg.getOp().getMessageClass() ==
                        Protocol.ENV_DIFF_CLASS) {
                        runEnvDiff((EnvDiff)msg, protocol);
                    }
                } catch (ProtocolException e) {
                    /* Unexpected message. */
                    protocol.write
                        (protocol.new ProtocolError(e.getMessage()),
                         channel);
                } finally {
                    if (channel.isOpen()) {
                        channel.close();
                    }
                }
            } catch (IOException e) {

                /*
                 * Channel has already been closed, or the close itself
                 * failed.
                 */
            } finally {
                if (!busy.compareAndSet(true, false)) {
                    throw EnvironmentFailureException.unexpectedState
                        ("Service is not busy");
                }
            }
        }
    }
}
