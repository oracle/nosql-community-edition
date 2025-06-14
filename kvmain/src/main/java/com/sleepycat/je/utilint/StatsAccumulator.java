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

import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_BINS_BYLEVEL;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_BINS;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_BIN_ENTRIES_HISTOGRAM;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_DELETED_LNS;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_INS_BYLEVEL;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_INS;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_LNS;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_MAINTREE_MAXDEPTH;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_GROUP_DESC;
import static com.sleepycat.je.dbi.BTreeStatDefinition.BT_COUNT_GROUP_NAME;

import java.io.PrintStream;
import java.text.DecimalFormat;
import java.util.HashSet;
import java.util.Set;

import com.sleepycat.je.tree.BIN;
import com.sleepycat.je.tree.IN;
import com.sleepycat.je.tree.Node;
import com.sleepycat.je.tree.TreeWalkerStatsAccumulator;
import com.sleepycat.utilint.FormatUtil;

public class StatsAccumulator implements TreeWalkerStatsAccumulator {
    private final Set<Long> inNodeIdsSeen = new HashSet<Long>();
    private final Set<Long> binNodeIdsSeen = new HashSet<Long>();
    private long[] insSeenByLevel = null;
    private long[] binsSeenByLevel = null;
    private long[] binEntriesHistogram = null;
    private long lnCount = 0;
    private long deletedLNCount = 0;
    private int mainTreeMaxDepth = 0;

    public PrintStream progressStream;
    int progressInterval;

    /* The max levels we ever expect to see in a tree. */
    private static final int MAX_LEVELS = 100;

    public StatsAccumulator(
        PrintStream progressStream,
        int progressInterval) {

        this.progressStream = progressStream;
        this.progressInterval = progressInterval;

        insSeenByLevel = new long[MAX_LEVELS];
        binsSeenByLevel = new long[MAX_LEVELS];
        binEntriesHistogram = new long[10];
    }

    public void verifyNode(@SuppressWarnings("unused") Node node) {
    }

    @Override
    public void processIN(IN node, Long nid, int level) {
        if (inNodeIdsSeen.add(nid)) {
            tallyLevel(level, insSeenByLevel);
            verifyNode(node);
        }
    }

    @Override
    public void processBIN(BIN node, Long nid, int level) {
        if (binNodeIdsSeen.add(nid)) {
            tallyLevel(level, binsSeenByLevel);
            verifyNode(node);
            tallyEntries(node, binEntriesHistogram);
        }
    }

    private void tallyLevel(int levelArg, long[] nodesSeenByLevel) {
        int level = levelArg;
        if (level >= IN.MAIN_LEVEL) {
            /* Count DBMAP_LEVEL as main level. [#22209] */
            level &= IN.LEVEL_MASK;
            if (level > mainTreeMaxDepth) {
                mainTreeMaxDepth = level;
            }
        }

        nodesSeenByLevel[level]++;
    }

    @Override
    public void incrementLNCount() {
        lnCount++;
        if (progressInterval != 0  && progressStream != null) {
            if ((lnCount % progressInterval) == 0) {
                progressStream.println(getStats());
            }
        }
    }

    @Override
    public void incrementDeletedLNCount() {
        deletedLNCount++;
    }

    private void tallyEntries(BIN bin, long[] binEntriesHistogram) {
        int nEntries = bin.getNEntries();
        int nonDeletedEntries = 0;
        for (int i = 0; i < nEntries; i++) {
            /* KD and PD determine deletedness. */
            if (!bin.isEntryPendingDeleted(i) &&
                !bin.isEntryKnownDeleted(i)) {
                nonDeletedEntries++;
            }
        }

        int bucket = (nonDeletedEntries * 100) / (bin.getMaxEntries() + 1);
        bucket /= 10;
        binEntriesHistogram[bucket]++;
    }

    Set<Long> getINNodeIdsSeen() {
        return inNodeIdsSeen;
    }

    Set<Long> getBINNodeIdsSeen() {
        return binNodeIdsSeen;
    }

    long[] getINsByLevel() {
        return insSeenByLevel;
    }

    long[] getBINsByLevel() {
        return binsSeenByLevel;
    }

    long[] getBINEntriesHistogram() {
        return binEntriesHistogram;
    }

    long getLNCount() {
        return lnCount;
    }

    long getDeletedLNCount() {
        return deletedLNCount;
    }

    int getMainTreeMaxDepth() {
        return mainTreeMaxDepth;
    }

    public StatGroup getStats() {
        StatGroup group = new StatGroup(
            BT_COUNT_GROUP_NAME, BT_COUNT_GROUP_DESC);
        new LongStat(group, BT_COUNT_INS, getINNodeIdsSeen().size());
        new LongStat(group, BT_COUNT_BINS, getBINNodeIdsSeen().size());
        new LongStat(group, BT_COUNT_LNS, getLNCount());
        new LongStat(group, BT_COUNT_DELETED_LNS, getDeletedLNCount());
        new IntStat(group, BT_COUNT_MAINTREE_MAXDEPTH, getMainTreeMaxDepth());
        new LongArrayStat(group, BT_COUNT_INS_BYLEVEL, getINsByLevel());
        new LongArrayStat(group, BT_COUNT_BINS_BYLEVEL, getBINsByLevel());
        new HistogramStat(group, BT_COUNT_BIN_ENTRIES_HISTOGRAM,
            getBINEntriesHistogram());
        return group;
    }

    private static class HistogramStat extends LongArrayStat {
        private static final long serialVersionUID = 1L;

        HistogramStat(StatGroup group,
                      StatDefinition definition,
                      long[] array) {
            super(group, definition, array);
        }

        @Override
        protected String getFormattedValue() {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            if (array != null && array.length > 0) {
                DecimalFormat fmt = FormatUtil.decimalScale0();
                boolean first = true;
                for (int i = 0; i < array.length; i++) {
                    if (array[i] > 0) {
                        if (!first) {
                            sb.append("; ");
                        }

                        first = false;
                        int startPct = i * 10;
                        int endPct = (i + 1) * 10 - 1;
                        sb.append(startPct).append("-");
                        sb.append(endPct).append("%: ");
                        sb.append(fmt.format(array[i]));
                    }
                }
            }

            sb.append("]");

            return sb.toString();
        }
    }
}
