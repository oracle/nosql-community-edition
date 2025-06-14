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

import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;

/**
 * Convenience methods for handling JE properties.
 */
public class PropUtil {

    private static final long NS_IN_MS = 1000000;
    private static final long NS_IN_SEC = 1000000000;
    private static final long NS_IN_MINUTE = NS_IN_SEC * 60;
    private static final long NS_IN_HOUR = NS_IN_MINUTE * 60;

    /**
     * Converts the given duration (interval value plus unit) to milliseconds,
     * ensuring that any given value greater than zero converts to at least one
     * millisecond to avoid a zero millisecond result, since Object.wait(0)
     * waits forever.
     *
     * @throws IllegalArgumentException if the duration argument is illegal.
     * Thrown via API setter methods such as Transaction.setLockTimeout.
     */
    public static int durationToMillis(final long val, final TimeUnit unit) {
        if (val == 0) {
            /* Allow zero duration with null unit. */
            return 0;
        }
        if (unit == null) {
            throw new IllegalArgumentException
                ("Duration TimeUnit argument may not be null if interval " +
                 "is non-zero");
        }
        if (val < 0) {
            throw new IllegalArgumentException
                ("Duration argument may not be negative: " + val);
        }
        final long newVal = unit.toMillis(val);
        if (newVal == 0) {
            /* Input val is positive, so return at least one. */
            return 1;
        }
        if (newVal > Integer.MAX_VALUE) {
            throw new IllegalArgumentException
                ("Duration argument may not be greater than " +
                 "Integer.MAX_VALUE milliseconds: " + newVal);
        }
        return (int) newVal;
    }

    /**
     * Converts the given duration value in milliseconds to the given unit.
     *
     * @throws IllegalArgumentException if the unit is null. Thrown via API
     * getter methods such as Transaction.getLockTimeout.
     */
    public static long millisToDuration(final int val, final TimeUnit unit) {
        if (unit == null) {
            throw new IllegalArgumentException
                ("TimeUnit argument may not be null");
        }
        return unit.convert(val, TimeUnit.MILLISECONDS);
    }

    /**
     * Parses a String duration property (time + optional unit) and returns the
     * value in millis.
     *
     * @throws IllegalArgumentException if the duration string is illegal.
     * Thrown via the Environment ctor and setMutableConfig, and likewise for a
     * ReplicatedEnvironment.
     */
    public static int parseDuration(final String...property) {

        long millis = parseLongDuration(property);

        if (millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Duration argument may not be greater than " +
                "Integer.MAX_VALUE milliseconds: " + property);
        }

        return (int)millis;
    }

    public static long parseLongDuration(final String...property) {
        long ns = parseDurationNS(property);
        long millis = ns / NS_IN_MS;

        /* If input val is positive, return at least one. */
        if (ns > 0 && millis == 0) {
            return 1;
        }

        return millis;
    }

    /**
     * Parses a String duration property (time + optional unit) and returns the
     * value in nanos.
     *
     * @throws IllegalArgumentException if the duration string is illegal.
     * Thrown via the Environment ctor and setMutableConfig, and likewise for a
     * ReplicatedEnvironment.
     */
    public static long parseDurationNS(final String...property) {
        StringTokenizer tokens =
            new StringTokenizer(property[0].toUpperCase(java.util.Locale.ENGLISH),
                                " \t");
        if (!tokens.hasMoreTokens()) {
            throw new IllegalArgumentException("Duration argument is empty");
        }
        final long time;
        try {
            time = Long.parseLong(tokens.nextToken());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException
                ("Duration argument does not start with a long integer: " +
                 property[0]);
        }
        /* Convert time from specified unit to millis. */
        long ns;
        if (tokens.hasMoreTokens()) {
            final String unitName = tokens.nextToken();
            if (tokens.hasMoreTokens()) {
                throw new IllegalArgumentException
                    ("Duration argument has extra characters after unit: " +
                     property[0]);
            }
            try {
                final TimeUnit unit = TimeUnit.valueOf(unitName);
                ns = TimeUnit.NANOSECONDS.convert(time, unit);
            } catch (IllegalArgumentException e) {
                try {
                    final IEEETimeUnit unit = IEEETimeUnit.valueOf(unitName);
                    ns = unit.toNanos(time);
                } catch (IllegalArgumentException e2) {
                    throw new IllegalArgumentException
                        ("Duration argument has unknown unit name: " +
                         property[0]);
                }
            }
        } else {
            /*
             * When set the value of PRE_HEARTBEAT_TIMEOUT, if time unit is not
             * provided, the default unit should be millis, because its name:
             * je.rep.preHeartbeatTimeoutMs implies it.
             * See DurationConfigInMillisParam.validateValue().
             */
            if(property.length == 2 && property[1].equals("ms")) {
                ns = TimeUnit.NANOSECONDS.convert(time, TimeUnit.MILLISECONDS);
            } else {
                /* Default unit is micros. */
                ns = TimeUnit.NANOSECONDS.convert(time, TimeUnit.MICROSECONDS);
            }
        }
        /* If input val is positive, return at least one. */
        if (time > 0 && ns == 0) {
            return 1;
        }
        return ns;
    }

    /**
     * Formats a String duration property (time + optional unit).
     * value in millis.
     */
    public static String formatDuration(long time, TimeUnit unit) {
        return String.valueOf(time) + ' ' + unit.name();
    }

    /**
     * Support for conversion of IEEE time units.  Although names are defined
     * in uppercase, we uppercase the input string before calling
     * IEEETimeUnit.valueOf, in order to support input names in both upper and
     * lower case.
     */
    private enum IEEETimeUnit {

        /* Nanoseconds */
        NS() {
            long toNanos(long val) {
                return nanosUnit.convert(val, TimeUnit.NANOSECONDS);
            }
        },

        /* Microseconds */
        US() {
            long toNanos(long val) {
                return nanosUnit.convert(val, TimeUnit.MICROSECONDS);
            }
        },

        /* Milliseconds */
        MS() {
            long toNanos(long val) {
                return nanosUnit.convert(val, TimeUnit.MILLISECONDS);
            }
        },

        /* Seconds */
        S() {
            long toNanos(long val) {
                return nanosUnit.convert(val, TimeUnit.SECONDS);
            }
        },

        /* Minutes */
        MIN() {
            long toNanos(long val) {
                return val * NS_IN_MINUTE;
            }
        },

        /* Hours */
        H() {
            long toNanos(long val) {
                return val * NS_IN_HOUR;
            }
        };

        private static final TimeUnit nanosUnit = TimeUnit.NANOSECONDS;

        abstract long toNanos(long val);
    }
}
