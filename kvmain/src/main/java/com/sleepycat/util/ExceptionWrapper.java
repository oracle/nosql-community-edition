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

package com.sleepycat.util;

/**
 * Interface implemented by exceptions that can contain nested exceptions.
 *
 * @author Mark Hayes
 */
public interface ExceptionWrapper {

    /**
     * Returns the nested exception or null if none is present.
     *
     * <p>This method is intentionally defined to be the same signature as the
     * <code>java.lang.Throwable.getCause</code> method in Java 1.4 and
     * greater.  By defining this method to return a nested exception, the Java
     * 1.4 runtime will print the nested stack trace.</p>
     *
     * @return the nested exception or null if none is present.
     */
    Throwable getCause();
}
