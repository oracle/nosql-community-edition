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

package com.sleepycat.je.utilint;

import com.sleepycat.je.DatabaseEntry;

/**
 * Utils for use in the db package.
 */
public class DatabaseUtil {

    /**
     * Throw an exception if the parameter is null.
     *
     * @throws IllegalArgumentException via any API method
     */
    public static void checkForNullParam(final Object param,
                                         final String name) {
        if (param == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
    }

    /**
     * Throw an exception if the parameter is a null or 0-length array.
     *
     * @throws IllegalArgumentException via any API method
     */
    public static void checkForZeroLengthArrayParam(final Object[] param,
                                                    final String name) {
        checkForNullParam(param, name);

        if (param.length == 0) {
            throw new IllegalArgumentException(
                "'" + name + "' param cannot be zero length");
        }
    }

    /**
     * Throw an exception if the entry is null or the data field is not set.
     *
     * @throws IllegalArgumentException via any API method that takes a
     * required DatabaseEntry param
     */
    public static void checkForNullDbt(final DatabaseEntry entry,
                                       final String name,
                                       final boolean checkData) {
        if (entry == null) {
            throw new IllegalArgumentException(
                "'" + name + "' param cannot be null");
        }

        if (checkData) {
            if (entry.getData() == null) {
                throw new IllegalArgumentException(
                    "Data field for '" + name + "' param cannot be null");
            }
        }
    }

    /**
     * Throw an exception if the entry has the partial flag set.
     */
    public static void checkForPartial(final DatabaseEntry entry,
                                       final String name) {
        if (entry.getPartial()) {
            throw new IllegalArgumentException(
                "'" + name + "' param may not be partial");
        }
    }

    public static String printByteArray(byte[] bytes) {

        if (bytes == null) {
            return "null";
        }

        StringBuffer sb = new StringBuffer();

        sb.append("[ ");

        for (byte b : bytes) {
            sb.append(b).append(" ");
        }

        sb.append("]");

        return sb.toString();
    }
}
