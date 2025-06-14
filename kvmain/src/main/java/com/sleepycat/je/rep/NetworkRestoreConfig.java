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

import java.util.List;
import java.util.logging.Handler;

/**
 * NetworkRestoreConfig defines the configuration parameters used to configure
 * a NetworkRestore operation.
 *
 * @see NetworkRestore
 */
public class NetworkRestoreConfig {
    /**
     * Determines whether obsolete log files must be renamed or deleted.
     */
    private boolean retainLogFiles = true;

    /**
     * The size of the network restore client socket's receive buffer.
     */
    private int receiveBufferSize = 0x200000; /* 2 MB */

    /*
     * The number of threads to be used for the restore process
     */
    private int feederCount = 2;

    /**
     * List (in priority order) of the data nodes, either ELECTABLE or
     * SECONDARY members, that should be contacted for the the log files.
     */
    private List<ReplicationNode> logProviders;

    /**
     * Used to configure the logging handler during network restore.
     */
    private Handler loggingHandler;

    /**
     * Returns a boolean indicating whether existing log files should be
     * retained or deleted.
     *
     * @return true if log files must be retained
     */
    public boolean getRetainLogFiles() {
        return retainLogFiles;
    }

    /**
     * If true retains obsolete log files, by renaming them instead of deleting
     * them. The default is "true".
     * <p>
     * A renamed file has its <code>.jdb</code> suffix replaced by
     * <code>.bup</code> and an additional numeric monotonically increasing
     * numeric suffix. All files that were renamed as part of the same
     * NetworkRestore attempt will have the same numeric suffix.
     * <p>
     * For example, if files 00000001.jdb and files 00000002.jdb were rendered
     * obsolete, and 4 was the highest suffix in use for this environment when
     * the operation was initiated, then the files would be renamed as
     * 00000001.bup.5 and 00000002.bup.5.
     *
     * @param retainLogFiles if true retains obsolete log files
     *
     * @return this
     */
    public NetworkRestoreConfig setRetainLogFiles(boolean retainLogFiles) {
        this.retainLogFiles = retainLogFiles;
        return this;
    }

    /**
     * Returns the size of the receive buffer associated with the socket used
     * to transfer files during the NetworkRestore operation.
     */
    public int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    /**
     * Sets the size of the receive buffer associated with the socket used to
     * transfer files during the NetworkRestore operation.
     * <p>
     * Note that if the size specified is larger than the operating system
     * constrained maximum, it will be limited to this maximum value. For
     * example, on Linux you may need to set the kernel parameter:
     * net.core.rmem_max property using the command: <i>sysctl -w
     * net.core.rmem_max=1048576</i> to increase the operating system imposed
     * limit.
     *
     * @param receiveBufferSize the size of the receive buffer. If it's zero,
     * the operating system default value is used.
     */
    public NetworkRestoreConfig setReceiveBufferSize(int receiveBufferSize) {
        if (receiveBufferSize < 0) {
            throw new IllegalArgumentException("receiveBufferSize:" +
                                                receiveBufferSize +
                                                " is negative.");
        }
        this.receiveBufferSize = receiveBufferSize;
        return this;
    }

    /**
     * Return the number of threads to be used for the restore process.
     */
    public int getFeederCount() {
        return feederCount;
    }

    /**
     * Set the number of threads to be used for the restore process.
     *
     * @param feederCount number of threads that user desires
     *
     * @return this
     */
    public NetworkRestoreConfig setFeederCount(int feederCount) {
        if (feederCount < 1) {
            throw new IllegalArgumentException("feederCount:" +
                                               feederCount +
                                               " is less than 1.");
        }

        this.feederCount = feederCount;
        return this;
    }

    /**
     * Returns the candidate list of data nodes, either ELECTABLE or SECONDARY
     * members, that may be used to obtain log files.
     *
     * @return the list of data nodes in priority order, or null
     */
    public List<ReplicationNode> getLogProviders() {
        return logProviders;
    }

    /**
     * Sets the prioritized list of data nodes, either ELECTABLE or SECONDARY
     * members, used to select a node from which to obtain log files for the
     * NetworkRestore operation. If a list is supplied, NetworkRestore will
     * only use nodes from this list, trying each one in order.
     *
     * <p> The default value is null. If a null value is configured for
     * NetworkRestore, it will choose the least busy data node with a current
     * set of logs, as the provider of log files.
     *
     * @param providers the list of data nodes in priority order, or null
     *
     * @return this
     */
    public NetworkRestoreConfig
        setLogProviders(List<ReplicationNode> providers) {

        logProviders = providers;
        return this;
    }

    /**
     * Sets the handler to be used for logging during the network restore.
     * <p>
     * If a logging handler is normally configured using {@link
     * com.sleepycat.je.EnvironmentConfig#setLoggingHandler}, configuring the
     * same handler here will ensure that all logging appears together in the
     * same place(s).
     */
    public NetworkRestoreConfig setLoggingHandler(Handler loggingHandler) {
        this.loggingHandler = loggingHandler;
        return this;
    }

    /**
     * Returns the handler to be used for logging during the network restore.
     */
    public Handler getLoggingHandler() {
        return loggingHandler;
    }
}
