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

package com.sleepycat.bind.tuple;

import com.sleepycat.je.DatabaseEntry;

/**
 * A concrete <code>TupleBinding</code> for a sorted <code>Double</code>
 * primitive wrapper or a sorted <code>double</code> primitive.
 *
 * <p>There are two ways to use this class:</p>
 * <ol>
 * <li>When using the {@link com.sleepycat.je} package directly, the static
 * methods in this class can be used to convert between primitive values and
 * {@link DatabaseEntry} objects.</li>
 * <li>When using the {@link com.sleepycat.collections} package, an instance of
 * this class can be used with any stored collection.</li>
 * </ol>
 *
 * @see <a href="package-summary.html#floatFormats">Floating Point Formats</a>
 */
public class SortedDoubleBinding extends TupleBinding<Double> {

    /* javadoc is inherited */
    public Double entryToObject(TupleInput input) {

        return input.readSortedDouble();
    }

    /* javadoc is inherited */
    public void objectToEntry(Double object, TupleOutput output) {

        output.writeSortedDouble(object);
    }

    /* javadoc is inherited */
    protected TupleOutput getTupleOutput(Double object) {

        return DoubleBinding.sizedOutput();
    }

    /**
     * Converts an entry buffer into a simple <code>double</code> value.
     *
     * @param entry is the source entry buffer.
     *
     * @return the resulting value.
     */
    public static double entryToDouble(DatabaseEntry entry) {

        return entryToInput(entry).readSortedDouble();
    }

    /**
     * Converts a simple <code>double</code> value into an entry buffer.
     *
     * @param val is the source value.
     *
     * @param entry is the destination entry buffer.
     */
    public static void doubleToEntry(double val, DatabaseEntry entry) {

        outputToEntry(DoubleBinding.sizedOutput().writeSortedDouble(val),
                      entry);
    }
}
