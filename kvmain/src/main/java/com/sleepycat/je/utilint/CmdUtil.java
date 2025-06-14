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

import java.io.File;
import java.lang.reflect.InvocationTargetException;

import com.sleepycat.je.DbInternal;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentLockedException;
import com.sleepycat.je.EnvironmentNotFoundException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.ExtinctionFilter;
import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.dbi.DbConfigManager;
import com.sleepycat.je.dbi.EnvironmentImpl;
import com.sleepycat.util.ClassResolver;

/**
 * Convenience methods for command line utilities.
 */
public class CmdUtil {

    /**
     * @throws IllegalArgumentException via main
     */
    public static String getArg(String[] argv, int whichArg)
        throws IllegalArgumentException {

        if (whichArg < argv.length) {
            return argv[whichArg];
        } else {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Parse a string into a long. If the string starts with 0x, this is a hex
     * number, else it's decimal.
     */
    public static long readLongNumber(String longVal) {
        if (longVal.startsWith("0x")) {
            return Long.parseLong(longVal.substring(2), 16);
        } else {
            return Long.parseLong(longVal);
        }
    }

    /**
     * Convert a string that is either 0xabc or 0xabc/0x123 into an lsn.
     */
    public static long readLsn(String lsnVal) {
        int slashOff = lsnVal.indexOf("/");
        if (slashOff < 0) {
            long fileNum = readLongNumber(lsnVal);
            return DbLsn.makeLsn(fileNum, 0);
        } else {
            long fileNum = readLongNumber(lsnVal.substring(0, slashOff));
            long offset = CmdUtil.readLongNumber
                (lsnVal.substring(slashOff + 1));
            return DbLsn.makeLsn(fileNum, offset);
        }
    }

    private static final String printableChars =
        "!\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
        "[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~";

    public static void formatEntry(StringBuilder sb,
                                   byte[] entryData,
                                   boolean formatUsingPrintable) {
        for (byte element : entryData) {
            int b = element & 0xff;
            if (formatUsingPrintable) {
                if (isPrint(b)) {
                    if (b == 0134) {  /* backslash */
                        sb.append('\\');
                    }
                    sb.append(printableChars.charAt(b - 33));
                } else {
                    sb.append('\\');
                    String hex = Integer.toHexString(b);
                    if (b < 16) {
                        sb.append('0');
                    }
                    sb.append(hex);
                }
            } else {
                String hex = Integer.toHexString(b);
                if (b < 16) {
                    sb.append('0');
                }
                sb.append(hex);
            }
        }
    }

    private static boolean isPrint(int b) {
        return (b < 0177) && (040 < b);
    }

    /**
     * Create an environment suitable for utilities. Utilities should in
     * general send trace output to the console and not to the db log.
     */
    public static EnvironmentImpl makeUtilityEnvironment(File envHome,
                                                         boolean readOnly,
                                                         boolean runRecovery)
        throws EnvironmentNotFoundException, EnvironmentLockedException {

        EnvironmentConfig config = new EnvironmentConfig();
        config.setReadOnly(readOnly);

        /* Don't debug log to the database log. */
        config.setConfigParam(EnvironmentParams.JE_LOGGING_DBLOG.getName(),
                              "false");
        Environment env = null;
        if (runRecovery) {
            /*
             * Load filter
             */
            ExtinctionFilter filter;
            final Class<?>[] args = new Class[] { Environment.class };
            String KV_EXTINCTION_FILTER =
                "oracle.kv.impl.util.DbVerifyExtinctionFilter";
            try {
                env = new Environment(envHome, config);
                Class<?> filterClass =
                        ClassResolver.resolveClass(KV_EXTINCTION_FILTER, null);
                filter = (ExtinctionFilter)
                        filterClass.getConstructor(args).newInstance(env);

                config.setExtinctionFilter(filter);

            } catch (InvocationTargetException e) {
                throw new IllegalArgumentException("An error was encountered" +
                        " while loading DbVerifyExtictionFilter.  Cause: " +
                        e.getCause());
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Unable to load the DbVerifyExtinctionFilter class, check " +
                                "that the kvstore.jar is in the classpath.  Exception: " + e);
            } finally {
                if (env != null) {
                    env.close();
                }
            }

        }
        config.setConfigParam(EnvironmentParams.ENV_RECOVERY.getName(),
                              String.valueOf(runRecovery));

        /* Apply the configuration in the je.properties file. */
        DbConfigManager.applyFileConfig
            (envHome, DbInternal.getProps(config), false);

        EnvironmentImpl envImpl =
            new EnvironmentImpl(envHome,
                                config,
                                null);
        envImpl.finishInit(config);

        return envImpl;
    }

    /**
     * Returns a description of the java command for running a utility, without
     * arguments.  For utilities the last name of the class name can be
     * specified when "-jar je.jar" is used.
     */
    public static String getJavaCommand(Class<?> cls) {

        String clsName = cls.getName();
        String lastName = clsName.substring(clsName.lastIndexOf('.') + 1);

        return "java { " + cls.getName() + " | -jar je-<version>.jar " + lastName + " }";
    }
}
