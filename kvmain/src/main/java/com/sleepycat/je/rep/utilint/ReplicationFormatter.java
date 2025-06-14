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

package com.sleepycat.je.rep.utilint;

import com.sleepycat.je.rep.impl.node.NameIdPair;
import com.sleepycat.je.utilint.TracerFormatter;

/**
 * Formatter for replication log messages
 */
public class ReplicationFormatter extends TracerFormatter {
    private final NameIdPair nameIdPair;

    public ReplicationFormatter(NameIdPair nameIdPair) {
        super();
        this.nameIdPair = nameIdPair;
    }

    @Override
    protected void appendEnvironmentName(StringBuilder sb) {
        sb.append(" [" + nameIdPair.getName() + "]");
    }
}
