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

package com.sleepycat.je;

import java.io.Serializable;

import com.sleepycat.je.tree.Key;
import com.sleepycat.util.keyrange.KeyRange;

/**
 * Encodes database key and data items as a byte array.
 *
 * <p>Storage and retrieval for the {@link com.sleepycat.je.Database Database}
 * and {@link com.sleepycat.je.Cursor Cursor} methods are based on key/data
 * pairs. Both key and data items are represented by DatabaseEntry objects.
 * Key and data byte arrays may refer to arrays of zero length up to arrays of
 * essentially unlimited length.</p>
 *
 * <p>The DatabaseEntry class provides simple access to an underlying object
 * whose elements can be examined or changed.  DatabaseEntry objects can be
 * subclassed, providing a way to associate with it additional data or
 * references to other structures.</p>
 *
 * <p>Access to DatabaseEntry objects is not re-entrant. In particular, if
 * multiple threads simultaneously access the same DatabaseEntry object using
 * {@link com.sleepycat.je.Database Database} or {@link com.sleepycat.je.Cursor
 * Cursor} methods, the results are undefined.</p>
 *
 * <p>DatabaseEntry objects may be used in conjunction with the object mapping
 * support provided in the {@link com.sleepycat.bind} package.</p>
 *
 * <h2><a id="params">Input and Output Parameters</a></h2>
 *
 * <p>DatabaseEntry objects are used for both input values (for example, when
 * writing to a database or specifying a search parameter) and output values
 * (for example, when reading from a database). For every CRUD method
 * ({@code get}, {@code put}, etc), each of the method's DatabaseEntry
 * parameters ({@code key}, {@code data}, etc) may be input or output
 * parameters, and this is specified by the method's documentation.</p>
 *
 * <h3><a id="inParam">Input Parameters</a></h3>
 *
 * <p>An input parameter is required by the JE method. The parameter may not be
 * null, and the caller is also responsible for initializing the data of the
 * DatabaseEntry to a non-null byte array.</p>
 *
 * <p>Input parameters normally may not be {@link #setPartial(int,int,boolean)
 * partial}. However, this is allowed under certain circumstances, namely
 * the {@link Cursor#putCurrent} method allows specifying a partial data
 * parameter in order to update only part of the record's data value. Input
 * parameters are NOT allowed to be partial unless this is explicitly stated in
 * the method documentation.</p>
 *
 * <p>Although an input parameter is always used for input, in some cases it
 * may be also used for output. For example, the {@link
 * Cursor#getSearchKeyRange} method is passed a key parameter that is used as
 * input, but since a record with a different key (greater or equal to the key
 * given) may be found, the key parameter is also used to return the key
 * that was found. Such parameters are documented as "input/output"
 * parameters.</p>
 *
 * <p>Another example is when a custom key comparator is used and a key
 * parameter is passed to a search method. The input parameter may match a
 * record's key even if the bytes are not equal, and the key of the record
 * found will be returned via the parameter. The same thing is true of data (or
 * primary key) parameters when a custom duplicate comparator is used. Because
 * of this, all input parameters of "get" methods can potentially be used for
 * output, however, they are not explicitly documented to be input/output
 * parameters.</p>
 *
 * <h3><a id="outParam">Output Parameters</a></h3>
 *
 * <p>An output parameter is not required by the JE method. It is used to
 * optionally return a value to the caller. Null may be passed for the
 * parameter if no returned value is needed. Passing null is a common way to
 * optimize read operations when only the record's key, and not the record's
 * data, is required. By passing null for the data parameter, a read from
 * disk can be avoided when the data is not already cached. In addition, all
 * output parameters may be {@link #setPartial(int,int,boolean) partial} to
 * allow only returning a part of the data byte array. See <a
 * href="Cursor.html#partialEntry">Using Null and Partial DatabaseEntry
 * Parameters</a> for more information.</p>
 *
 * <p>For output parameters, the byte array specified by the caller will not be
 * used and may be null. The JE method will will always allocate a new byte
 * array. Therefore, after calling a method that returns output parameters,
 * the application can safely keep a reference to the byte array returned by
 * {@link #getData} without danger that the array will be overwritten in a
 * subsequent call.</p>
 *
 * <p>Historical note: Prior to JE 7.0, null could not be passed for output
 * parameters. Instead, {@code DatabaseEntry.setPartial(0, 0, true)} was called
 * for a data parameter to avoid reading the record's data. Now, null can be
 * passed instead.</p>
 *
 * <h2>Offset and Size Properties</h2>
 *
 * <p>By default the Offset property is zero and the Size property is the
 * length of the byte array.  However, to allow for optimizations involving the
 * partial use of a byte array, the Offset and Size may be set to non-default
 * values.</p>
 *
 * <p>For output parameters, the Size will always be set to the length of the
 * byte array and the Offset will always be set to zero.</p>
 *
 * <p>However, for input parameters the Offset and Size are set to non-default
 * values by the built-in tuple and serial bindings. For example, with a tuple
 * or serial binding the byte array is grown dynamically as data is output, and
 * the Size is set to the number of bytes actually used. For a serial binding,
 * the Offset is set to a non-zero value in order to implement an optimization
 * having to do with the serialization stream header.</p>
 *
 * <p>WARNING: In callbacks that are passed DatabaseEntry parameters, the
 * application should always honor the Size and Offset properties, rather than
 * assuming they have default values.</p>
 */
public class DatabaseEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    /* Currently, JE stores all data records as byte array */
    private byte[] data;
    private int dlen = 0;
    private int doff = 0;
    private int offset = 0;
    private int size = 0;
    private boolean partial = false;

    /* FindBugs - ignore not "final" since a user can set this. */
    /** @hidden
     * The maximum number of bytes to show when toString() is called.
     */
    public static int MAX_DUMP_BYTES = 100;

    /**
     * Returns all the attributes of the database entry in text form, including
     * the underlying data.  The maximum number of bytes that will be formatted
     * is taken from the static variable DatabaseEntry.MAX_DUMP_BYTES, which
     * defaults to 100.  MAX_DUMP_BYTES may be changed by an application if it
     * wishes to cause more bytes to be formatted.
     */
    @Override
    public String toString() {
    	StringBuilder sb = new StringBuilder("<DatabaseEntry");
        if (partial) {
            sb.append(" partial=\"true");
            sb.append("\" doff=\"").append(doff);
            sb.append("\" dlen=\"").append(dlen);
            sb.append("\"");
        }
        sb.append(" offset=\"").append(offset);
        sb.append("\" size=\"").append(size);
        sb.append("\" data=\"").append(dumpData());
        if ((size - 1) > MAX_DUMP_BYTES) {
            sb.append(" ... ").append((size - MAX_DUMP_BYTES) +
                          " bytes not shown ");
        }
        sb.append("\"/>");
        return sb.toString();
    }

    /*
     * Constructors
     */

    /**
     * Constructs a DatabaseEntry with null data. The offset and size are set
     * to zero.
     */
    public DatabaseEntry() {
    }

    /**
     * Constructs a DatabaseEntry with a given byte array.  The offset is set
     * to zero; the size is set to the length of the array, or to zero if null
     * is passed.
     *
     * @param data Byte array wrapped by the DatabaseEntry.
     */
    public DatabaseEntry(byte[] data) {
        this.data = data;
        if (data != null) {
            this.size = data.length;
        }
    }

    /**
     * Constructs a DatabaseEntry with a given byte array, offset and size.
     *
     * @param data Byte array wrapped by the DatabaseEntry.
     *
     * @param offset Offset in the first byte in the byte array to be included.
     *
     * @param size Number of bytes in the byte array to be included.
     */
    public DatabaseEntry(byte[] data, int offset, int size) {
        this.data = data;
        this.offset = offset;
        this.size = size;
    }

    /*
     * Accessors
     */

    /**
     * Returns the byte array.
     *
     * <p>For a DatabaseEntry that is used as an output parameter, the byte
     * array will always be a newly allocated array.  The byte array specified
     * by the caller will not be used and may be null.</p>
     *
     * @return The byte array.
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Sets the byte array.  The offset is set to zero; the size is set to the
     * length of the array, or to zero if null is passed.
     *
     * @param data Byte array wrapped by the DatabaseEntry.
     */
    public void setData(byte[] data) {
        this.data = data;
        offset = 0;
        size = (data == null) ? 0 : data.length;
    }

    /**
     * Sets the byte array, offset and size.
     *
     * @param data Byte array wrapped by the DatabaseEntry.
     *
     * @param offset Offset in the first byte in the byte array to be included.
     *
     * @param size Number of bytes in the byte array to be included.
     */
    public void setData(byte[] data, int offset, int size) {
        this.data = data;
        this.offset = offset;
        this.size = size;
    }

    /**
     * Configures this DatabaseEntry to read or write partial records.
     *
     * <p>By default the specified data (byte array, offset and size)
     * corresponds to the full stored key or data item. Optionally, the
     * Partial property can be set to true, and the PartialOffset and
     * PartialLength properties are used to specify the portion of the key or
     * data item to be read or written.</p>
     *
     * <p>Note that the Partial properties are set only by the caller. They
     * will never be set by a Database or Cursor method, nor will they every be
     * set by bindings. Therefore, the application can assume that the Partial
     * properties are not set, unless the application itself sets them
     * explicitly.</p>
     *
     * <p>All <a href="#outParam">output parameters</a> may be partial. If the
     * calling application is doing a retrieval, length bytes specified by
     * <code>dlen</code>, starting at the offset set by <code>doff</code> bytes from
     * the beginning of the retrieved data record are returned as if they
     * comprised the entire record.  If any or all of the specified bytes do
     * not exist in the record, the get is successful, and any existing bytes
     * are returned.</p>
     *
     * <p>For example, if the data portion of a retrieved record was 100 bytes,
     * and a partial retrieval was done using a DatabaseEntry having a partial
     * length of 20 and a partial offset of 85, the retrieval would succeed and
     * the retrieved data would be the last 15 bytes of the record.</p>
     *
     * <p>Input parameters normally may not be {@link
     * #setPartial(int,int,boolean) partial}. However, this is allowed under
     * certain circumstances, namely the {@link Cursor#putCurrent} method
     * allows specifying a partial data parameter in order to update only part
     * of the record's data value. Input parameters are NOT allowed to be
     * partial unless this is explicitly stated in the method
     * documentation.</p>
     *
     * <p>For storing an item using a partial parameter, length bytes specified
     * by <code>dlen</code>, starting at the offset set by <code>doff</code> bytes from
     * the beginning of the specified key's data item are replaced by the data
     * specified by the DatabaseEntry.  If the partial length is smaller than
     * the data, the record will grow; if the partial length is larger than the
     * data, the record will shrink.  If the partial offset is greater than the
     * length of the data, the record will be extended using zero bytes as
     * necessary, and the store will succeed.</p>
     *
     * @param doff The offset of the partial record being read or written by
     * the application, in bytes.
     *
     * @param dlen The byte length of the partial record being read or written
     * by the application, in bytes.
     *
     * @param partial Whether this DatabaseEntry is configured to read or write
     * partial records.
     */
    public void setPartial(int doff, int dlen, boolean partial) {
        setPartialOffset(doff);
        setPartialLength(dlen);
        setPartial(partial);
    }

    /**
     * Returns the byte length of the partial record being read or written by
     * the application, in bytes.
     *
     * <p>Note that the Partial properties are set only by the caller.  They
     * will never be set by a Database or Cursor method.</p>
     *
     * @return The byte length of the partial record being read or written by
     * the application, in bytes.
     *
     * @see #setPartial(int,int,boolean)
     */
    public int getPartialLength() {
        return dlen;
    }

    /**
     * Sets the byte length of the partial record being read or written by the
     * application, in bytes.
     *
     * <p>Note that the Partial properties are set only by the caller.  They
     * will never be set by a Database or Cursor method.</p>
     *
     * @param dlen The byte length of the partial record being read or written
     * by the
     *
     * @see #setPartial(int,int,boolean)
     */
    public void setPartialLength(int dlen) {
        this.dlen = dlen;
    }

    /**
     * Returns the offset of the partial record being read or written by the
     * application, in bytes.
     *
     * <p>Note that the Partial properties are set only by the caller.  They
     * will never be set by a Database or Cursor method.</p>
     *
     * @return The offset of the partial record being read or written by the
     * application, in bytes.
     *
     * @see #setPartial(int,int,boolean)
     */
    public int getPartialOffset() {
        return doff;
    }

    /**
     * Sets the offset of the partial record being read or written by the
     * application, in bytes.
     *
     * <p>Note that the Partial properties are set only by the caller.  They
     * will never be set by a Database or Cursor method.</p>
     *
     * @param doff The offset of the partial record being read or written by
     * the application, in bytes.
     *
     * @see #setPartial(int,int,boolean)
     */
    public void setPartialOffset(int doff) {
        this.doff = doff;
    }

    /**
     * Returns whether this DatabaseEntry is configured to read or write
     * partial records.
     *
     * <p>Note that the Partial properties are set only by the caller.  They
     * will never be set by a Database or Cursor method.</p>
     *
     * @return Whether this DatabaseEntry is configured to read or write
     * partial records.
     *
     * @see #setPartial(int,int,boolean)
     */
    public boolean getPartial() {
        return partial;
    }

    /**
     * Configures this DatabaseEntry to read or write partial records.
     *
     * <p>Note that the Partial properties are set only by the caller.  They
     * will never be set by a Database or Cursor method.</p>
     *
     * @param partial Whether this DatabaseEntry is configured to read or write
     * partial records.
     *
     * @see #setPartial(int,int,boolean)
     */
    public void setPartial(boolean partial) {
        this.partial = partial;
    }

    /**
     * Returns the byte offset into the data array.
     *
     * <p>For a DatabaseEntry that is used as an output parameter, the offset
     * will always be zero.</p>
     *
     * @return Offset in the first byte in the byte array to be included.
     */
    public int getOffset() {
        return offset;
    }

    /**
     * Sets the byte offset into the data array.
     *
     * ArrayIndexOutOfBoundsException if the data, offset, and size parameters
     * refer to elements of the data array which do not exist.  Note that this
     * exception will not be thrown by setSize() or setOffset(), but will be
     * thrown by varous JE methods if "this" is inconsistent and is used as an
     * input parameter to those methods.  It is the caller's responsibility to
     * ensure that size, offset, and data.length are consistent.
     *
     * @param offset Offset in the first byte in the byte array to be included.
     */
    public void setOffset(int offset) {
        this.offset = offset;
    }

    /**
     * Returns the byte size of the data array.
     *
     * <p>For a DatabaseEntry that is used as an output parameter, the size
     * will always be the length of the data array.</p>
     *
     * @return Number of bytes in the byte array to be included.
     */
    public int getSize() {
        return size;
    }

    /**
     * Sets the byte size of the data array.
     *
     * ArrayIndexOutOfBoundsException if the data, offset, and size parameters
     * refer to elements of the data array which do not exist.  Note that this
     * exception will not be thrown by setSize() or setOffset(), but will be
     * thrown by varous JE methods if "this" is inconsistent and is used as an
     * input parameter to those methods.  It is the caller's responsibility to
     * ensure that size, offset, and data.length are consistent.
     *
     * @param size Number of bytes in the byte array to be included.
     */
    public void setSize(int size) {
        this.size = size;
    }

    /**
     * Dumps the data as a byte array, for tracing purposes
     */
    String dumpData() {
        return Key.DUMP_TYPE.dumpByteArray(
            KeyRange.getByteArray(this, MAX_DUMP_BYTES));
    }

    /**
     * Compares the data of two entries for byte-by-byte equality.
     *
     * <p>In either entry, if the offset is non-zero or the size is not equal
     * to the data array length, then only the data bounded by these values is
     * compared.  The data array length and offset need not be the same in both
     * entries for them to be considered equal.</p>
     *
     * <p>If the data array is null in one entry, then to be considered equal
     * both entries must have a null data array.</p>
     *
     * <p>If the partial property is set in either entry, then to be considered
     * equal both entries must have the same partial properties: partial,
     * partialOffset and partialLength.
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DatabaseEntry)) {
            return false;
        }
        DatabaseEntry e = (DatabaseEntry) o;
        if (partial || e.partial) {
            if (partial != e.partial ||
                dlen != e.dlen ||
                doff != e.doff) {
                return false;
            }
        }
        if (data == null && e.data == null) {
            return true;
        }
        if (data == null || e.data == null) {
            return false;
        }
        if (size != e.size) {
            return false;
        }
        for (int i = 0; i < size; i += 1) {
            if (data[offset + i] != e.data[e.offset + i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a hash code based on the data value.
     */
    @Override
    public int hashCode() {
        int hash = 0;
        if (data != null) {
            for (int i = 0; i < size; i += 1) {
                hash += data[offset + i];
            }
        }
        return hash;
    }
}
