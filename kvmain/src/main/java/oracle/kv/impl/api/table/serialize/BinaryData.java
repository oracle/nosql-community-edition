/*-
 * Copyright (C) 2011, 2025 Oracle and/or its affiliates. All rights reserved.
 *
 * This file was distributed by Oracle as part of a version of Oracle NoSQL
 * Database made available at:
 *
 * http://www.oracle.com/technetwork/database/database-technologies/nosqldb/downloads/index.html
 *
 * Please see the LICENSE file included in the top-level directory of the
 * appropriate version of Oracle NoSQL Database for a copy of the license and
 * additional information.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package oracle.kv.impl.api.table.serialize;

/** Utilities for binary-encoded data. */
public class BinaryData {

    private BinaryData() {
    }

    /**
     * Lexicographically compare bytes. If equal, return zero. If greater-than,
     * return a positive value, if less than return a negative value.
     */
    public static int compareBytes(byte[] b1, int s1, int l1, byte[] b2, int s2,
            int l2) {
        int end1 = s1 + l1;
        int end2 = s2 + l2;
        for (int i = s1, j = s2; i < end1 && j < end2; i++, j++) {
            int a = (b1[i] & 0xff);
            int b = (b2[j] & 0xff);
            if (a != b) {
                return a - b;
            }
        }
        return l1 - l2;
    }

    /** Skip a binary-encoded long, returning the position after it. */
    public static int skipLong(byte[] bytes, int start) {
        int i = start;
        for (int b = bytes[i++]; ((b & 0x80) != 0); b = bytes[i++]) {
        }
        return i;
    }

    /**
     * Encode a boolean to the byte array at the given position. Will throw
     * IndexOutOfBounds if the position is not valid.
     * 
     * @return The number of bytes written to the buffer, 1.
     */
    public static int encodeBoolean(boolean b, byte[] buf, int pos) {
        buf[pos] = b ? (byte) 1 : (byte) 0;
        return 1;
    }

    /**
     * Encode an integer to the byte array at the given position. Will throw
     * IndexOutOfBounds if it overflows. Users should ensure that there are at
     * least 5 bytes left in the buffer before calling this method.
     * 
     * @return The number of bytes written to the buffer, between 1 and 5.
     */
    public static int encodeInt(int n, byte[] buf, int pos) {
        // move sign to low-order bit, and flip others if negative
        n = (n << 1) ^ (n >> 31);
        int start = pos;
        if ((n & ~0x7F) != 0) {
            buf[pos++] = (byte) ((n | 0x80) & 0xFF);
            n >>>= 7;
            if (n > 0x7F) {
                buf[pos++] = (byte) ((n | 0x80) & 0xFF);
                n >>>= 7;
                if (n > 0x7F) {
                    buf[pos++] = (byte) ((n | 0x80) & 0xFF);
                    n >>>= 7;
                    if (n > 0x7F) {
                        buf[pos++] = (byte) ((n | 0x80) & 0xFF);
                        n >>>= 7;
                    }
                }
            }
        }
        buf[pos++] = (byte) n;
        return pos - start;
    }

    /**
     * Encode a long to the byte array at the given position. Will throw
     * IndexOutOfBounds if it overflows. Users should ensure that there are at
     * least 10 bytes left in the buffer before calling this method.
     * 
     * @return The number of bytes written to the buffer, between 1 and 10.
     */
    public static int encodeLong(long n, byte[] buf, int pos) {
        // move sign to low-order bit, and flip others if negative
        n = (n << 1) ^ (n >> 63);
        int start = pos;
        if ((n & ~0x7FL) != 0) {
            buf[pos++] = (byte) ((n | 0x80) & 0xFF);
            n >>>= 7;
            if (n > 0x7F) {
                buf[pos++] = (byte) ((n | 0x80) & 0xFF);
                n >>>= 7;
                if (n > 0x7F) {
                    buf[pos++] = (byte) ((n | 0x80) & 0xFF);
                    n >>>= 7;
                    if (n > 0x7F) {
                        buf[pos++] = (byte) ((n | 0x80) & 0xFF);
                        n >>>= 7;
                        if (n > 0x7F) {
                            buf[pos++] = (byte) ((n | 0x80) & 0xFF);
                            n >>>= 7;
                            if (n > 0x7F) {
                                buf[pos++] = (byte) ((n | 0x80) & 0xFF);
                                n >>>= 7;
                                if (n > 0x7F) {
                                    buf[pos++] = (byte) ((n | 0x80) & 0xFF);
                                    n >>>= 7;
                                    if (n > 0x7F) {
                                        buf[pos++] = (byte) ((n | 0x80) & 0xFF);
                                        n >>>= 7;
                                        if (n > 0x7F) {
                                            buf[pos++] = (byte) ((n | 0x80)
                                                    & 0xFF);
                                            n >>>= 7;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        buf[pos++] = (byte) n;
        return pos - start;
    }

    /**
     * Encode a float to the byte array at the given position. Will throw
     * IndexOutOfBounds if it overflows. Users should ensure that there are at
     * least 4 bytes left in the buffer before calling this method.
     * 
     * @return Returns the number of bytes written to the buffer, 4.
     */
    public static int encodeFloat(float f, byte[] buf, int pos) {
        int len = 1;
        int bits = Float.floatToRawIntBits(f);
        // hotspot compiler works well with this variant
        buf[pos] = (byte) ((bits) & 0xFF);
        buf[pos + len++] = (byte) ((bits >>> 8) & 0xFF);
        buf[pos + len++] = (byte) ((bits >>> 16) & 0xFF);
        buf[pos + len++] = (byte) ((bits >>> 24) & 0xFF);
        return 4;
    }

    /**
     * Encode a double to the byte array at the given position. Will throw
     * IndexOutOfBounds if it overflows. Users should ensure that there are at
     * least 8 bytes left in the buffer before calling this method.
     * 
     * @return Returns the number of bytes written to the buffer, 8.
     */
    public static int encodeDouble(double d, byte[] buf, int pos) {
        long bits = Double.doubleToRawLongBits(d);
        int first = (int) (bits & 0xFFFFFFFF);
        int second = (int) ((bits >>> 32) & 0xFFFFFFFF);
        // the compiler seems to execute this order the best, likely due to
        // register allocation -- the lifetime of constants is minimized.
        buf[pos] = (byte) ((first) & 0xFF);
        buf[pos + 4] = (byte) ((second) & 0xFF);
        buf[pos + 5] = (byte) ((second >>> 8) & 0xFF);
        buf[pos + 1] = (byte) ((first >>> 8) & 0xFF);
        buf[pos + 2] = (byte) ((first >>> 16) & 0xFF);
        buf[pos + 6] = (byte) ((second >>> 16) & 0xFF);
        buf[pos + 7] = (byte) ((second >>> 24) & 0xFF);
        buf[pos + 3] = (byte) ((first >>> 24) & 0xFF);
        return 8;
    }

}
