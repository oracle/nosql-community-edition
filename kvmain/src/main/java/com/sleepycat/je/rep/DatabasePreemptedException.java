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

import com.sleepycat.je.Database;
import com.sleepycat.je.OperationFailureException;

/**
 * Thrown when attempting to use a Database handle that was forcibly closed by
 * replication.  This exception only occurs in a replicated environment and
 * normally only occurs on a Replica node.  In the case of a DPL schema upgrade
 * where an entity class or secondary key is renamed, it may also occur on a
 * Master node, as described below.
 *
 * <p>This exception occurs when accessing a database or store and one of the
 * following methods was recently executed on the master node and then replayed
 * on a replica node:
 * {@link com.sleepycat.je.Environment#truncateDatabase truncateDatabase},
 * {@link com.sleepycat.je.Environment#removeDatabase removeDatabase} and
 * {@link com.sleepycat.je.Environment#renameDatabase renameDatabase}.</p>
 *
 * <p>When this exception occurs, the application must close any open cursors
 * and abort any open transactions that are using the database or store, and
 * then close the database or store handle.  If the application wishes, it may
 * then reopen the database (if it still exists) or store.</p>
 *
 * <p>Some applications may wish to coordinate the Master and Replica sites to
 * prevent a Replica from accessing a database that is being truncated, removed
 * or renamed, and thereby prevent this exception.  Such coordination is not
 * directly supported by JE.  The DatabasePreemptedException is provided to
 * allow an application to handle database truncation, removal and renaming
 * without such coordination between nodes.</p>
 *
 * <p>The {@link com.sleepycat.je.Transaction} handle is <em>not</em>
 * invalidated as a result of this exception.</p>
 *
 * @since 4.0
 */
public class DatabasePreemptedException extends OperationFailureException {

    private static final long serialVersionUID = 1;

    private final String dbName;
    private final transient Database dbHandle;

    /** 
     * For internal use only.
     * @hidden 
     */
    public DatabasePreemptedException(final String message,
                                      final String dbName,
                                      final Database dbHandle) {
        super(null /*locker*/, false /*abortOnly*/, message, null /*cause*/);
        this.dbName = dbName;
        this.dbHandle = dbHandle;
    }

    /** 
     * Only for use by wrapSelf methods.
     */
    private DatabasePreemptedException(String message,
                                       DatabasePreemptedException cause) {
        super(message, cause);
        dbName = cause.dbName;
        dbHandle = cause.dbHandle;
    }

    /**
     * Returns the database handle that was forcibly closed, or null if this
     * exception has been de-serialized.
     */
    public Database getDatabase() {
        return dbHandle;
    }

    /**
     * Returns the name of the database that was forcibly closed.
     */
    public String getDatabaseName() {
        return dbName;
    }

    /** 
     * For internal use only.
     * @hidden 
     */
    @Override
    public OperationFailureException wrapSelf(
        String msg,
        OperationFailureException clonedCause) {

        return new DatabasePreemptedException(
            msg, (DatabasePreemptedException) clonedCause);
    }
}
