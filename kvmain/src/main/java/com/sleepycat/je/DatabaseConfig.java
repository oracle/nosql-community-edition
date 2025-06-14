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

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import com.sleepycat.je.dbi.DatabaseImpl;
import com.sleepycat.je.trigger.ReplicatedDatabaseTrigger;
import com.sleepycat.je.trigger.PersistentTrigger;
import com.sleepycat.je.trigger.Trigger;

/**
 * <p>Specifies the attributes of a database.</p>
 *
 * <p>There are two groups of database attributes: per-database handle
 * attributes, and database-wide attributes. An attribute may be
 * persistent/transient or mutable/immutable:</p>
 *
 * <table border="1">
 * <caption style="display:none">Database attributes</caption>
 * <tr>
 *     <td>Scope</td>
 *     <td>Mutable</td>
 *     <td>Persistent</td>
 *     <td>Attribute</td>
 * </tr>
 * <tr>
 *     <td rowspan="4">Database-wide attribute</td>
 *     <td>True</td>
 *     <td>True</td>
 *     <td>{@link DatabaseConfig#getBtreeComparator() btree comparator}<br>
 *         {@link DatabaseConfig#getDuplicateComparator() duplicate comparator}<br>
 *         {@link DatabaseConfig#getKeyPrefixing() key prefixing}<br>
 *         {@link DatabaseConfig#getNodeMaxEntries() nodeMaxEntries}<br>
 *         <!--
 *         {@link DatabaseConfig#getTriggers() triggers}<br></td>
 *         -->
 * </tr>
 * <tr>
 *     <td>True</td>
 *     <td>False</td>
 *     <td>{@link DatabaseConfig#getTransactional() transactional}<br></td>
 * </tr>
 * <tr>
 *     <td>False</td>
 *     <td>True</td>
 *     <td>
 *         {@link DatabaseConfig#getSortedDuplicates() sorted duplicates}<br>
 *     </td>
 * </tr>
 * <tr>
 *     <td>Per-database handle attributes</td>
 *     <td>False</td>
 *     <td>False</td>
 *     <td>{@link DatabaseConfig#getAllowCreate() allow create}<br>
 *         {@link DatabaseConfig#getExclusiveCreate() exclusive create}<br>
 *         {@link DatabaseConfig#getReadOnly() read only}<br>
 *         {@link DatabaseConfig#getCacheMode()}  read only}<br>
 *         {@link DatabaseConfig#getUseExistingConfig() use existing config}<br>
 *     </td>
 * </tr>
 * </table>
 *
 * <p>Persistent attributes will be saved in the log and remain in effect
 * every time the environment is reopened. Transient attributes only remain
 * in effect until:</p>
 *
 * <ul>
 * <li>the database configuration is updated</li>
 * <li>the database handle(per-database handle attributes) is closed, or all
 * handles for this database (database-wide attributes) are closed.</li>
 * </ul>
 */
public class DatabaseConfig implements Cloneable {

    /**
     * An instance created using the default constructor is initialized with
     * the system's default settings.
     */
    public static final DatabaseConfig DEFAULT = new DatabaseConfig();

    private boolean allowCreate = false;
    private boolean exclusiveCreate = false;
    private boolean transactional = false;
    private boolean readOnly = false;
    private boolean sortedDuplicates = false;
    private boolean keyPrefixing = false;
    private boolean replicated = true;

    private int nodeMaxEntries;
    private Object btreeComparator = null;
    private Object duplicateComparator = null;
    private boolean btreeComparatorByClassName = false;
    private boolean duplicateComparatorByClassName = false;
    private boolean btreeByteComparator = false;
    private boolean duplicateByteComparator = false;
    private boolean overrideBtreeComparator = false;
    private boolean overrideDuplicateComparator = false;
    private boolean useExistingConfig = false;
    private CacheMode cacheMode = null;
    private SecondaryAssociation secAssociation = null;

    /* User defined triggers associated with this database. */
    private List<Trigger> triggers = new LinkedList<Trigger>();
    private boolean overrideTriggers;

    /**
     * An instance created using the default constructor is initialized with
     * the system's default settings.
     */
    public DatabaseConfig() {
    }

    /**
     * Configures the {@link com.sleepycat.je.Environment#openDatabase
     * Environment.openDatabase} method to create the database if it does not
     * already exist.
     *
     * @param allowCreate If true, configure the {@link
     * com.sleepycat.je.Environment#openDatabase Environment.openDatabase}
     * method to create the database if it does not already exist.
     *
     * @return this
     */
    public DatabaseConfig setAllowCreate(boolean allowCreate) {
        this.allowCreate = allowCreate;
        return this;
    }

    /**
     * Returns true if the {@link com.sleepycat.je.Environment#openDatabase
     * Environment.openDatabase} method is configured to create the database
     * if it does not already exist.
     *
     * <p>This method may be called at any time during the life of the
     * application.</p>
     *
     * @return true if the {@link com.sleepycat.je.Environment#openDatabase
     * Environment.openDatabase} method is configured to create the database
     * if it does not already exist.
     */
    public boolean getAllowCreate() {
        return allowCreate;
    }

    /**
     * Configure the {@link com.sleepycat.je.Environment#openDatabase
     * Environment.openDatabase} method to fail if the database already exists.
     *
     * <p>The exclusiveCreate mode is only meaningful if specified with the
     * allowCreate mode.</p>
     *
     * @param exclusiveCreate If true, configure the {@link
     * com.sleepycat.je.Environment#openDatabase Environment.openDatabase}
     * method to fail if the database already exists.
     *
     * @return this
     */
    public DatabaseConfig setExclusiveCreate(boolean exclusiveCreate) {
        this.exclusiveCreate = exclusiveCreate;
        return this;
    }

    /**
     * Returns true if the {@link com.sleepycat.je.Environment#openDatabase
     * Environment.openDatabase} method is configured to fail if the database
     * already exists.
     *
     * <p>This method may be called at any time during the life of the
     * application.</p>
     *
     * @return true if the {@link com.sleepycat.je.Environment#openDatabase
     * Environment.openDatabase} method is configured to fail if the database
     * already exists.
     */
    public boolean getExclusiveCreate() {
        return exclusiveCreate;
    }

    /**
     * Configures the database to support records with duplicate keys.
     *
     * <p>When duplicate keys are configured for a database, key prefixing is
     * also implicitly configured.  Without key prefixing, databases with
     * duplicates would store keys inefficiently. Key prefixing is therefore
     * mandatory for databases with duplicates.</p>
     *
     * <p>Although two records may have the same key, they may not also have
     * the same data item.  Two identical records, that have the same key and
     * data, may not be stored in a database.</p>
     *
     * <p>The ordering of duplicates in the database is determined by the
     * duplicate comparison function.  See {@link #setDuplicateComparator}.  If
     * the application does not specify a duplicate comparison function, a
     * default lexical comparison will be used.</p>
     *
     * <p>If a primary database is to be associated with one or more secondary
     * databases, it may not be configured for duplicates.</p>
     *
     * <p>Calling this method affects the database, including all threads of
     * control accessing the database.</p>
     *
     * <p>If the database already exists when the database is opened, any
     * database configuration specified by this method must be the same as the
     * existing database or an error will be returned.</p>
     *
     * @param sortedDuplicates If true, configure the database to support
     * duplicate data items. A value of false is illegal to this method, that
     * is, once set, the configuration cannot be cleared.
     *
     * @return this
     */
    public DatabaseConfig setSortedDuplicates(boolean sortedDuplicates) {
        this.sortedDuplicates = sortedDuplicates;
        if (sortedDuplicates) {
            setKeyPrefixing(true);
        }
        return this;
    }

    /**
     * Returns true if the database is configured to support records with
     * duplicate keys.
     *
     * <p>This method may be called at any time during the life of the
     * application.</p>
     *
     * @return true if the database is configured to support records with
     * duplicate keys.
     */
    public boolean getSortedDuplicates() {
        return sortedDuplicates;
    }

    /**
     * Returns the key prefixing configuration.  Note that key prefixing is
     * always enabled for a database with duplicates configured.
     *
     * @return true if key prefixing has been enabled in this database.
     */
    public boolean getKeyPrefixing() {
        return keyPrefixing;
    }

    /**
     * Configure the database to support key prefixing.
     *
     * <p>Key prefixing causes the representation of keys in the b-tree
     * internal nodes to be split in each BIN (bottom internal node) between
     * the common prefix of all keys and the suffixes.  Using this often
     * results in a more space-efficient representation in both the
     * in-memory and on-disk formats. In general the cost of maintaining
     * the prefix separately is low compared to the benefit, and therefore
     * enabling key prefixing is strongly recommended.</p>
     *
     * <p>When duplicate keys are configured for a database, key prefixing is
     * also implicitly configured.  Without key prefixing, databases with
     * duplicates would store keys inefficiently. Key prefixing is therefore
     * mandatory for databases with duplicates.</p>
     *
     * @param keyPrefixing If true, enables keyPrefixing for the database.
     *
     * @return this
     *
     * @throws IllegalStateException if the keyPrefixing argument is false and
     * {@link #setSortedDuplicates} has been called to configure duplicates.
     * Key prefixing is therefore mandatory for databases with duplicates.
     *
     * @see <a href="EnvironmentStats.html#cacheSizeOptimizations">Cache
     * Statistics: Size Optimizations</a>
     */
    public DatabaseConfig setKeyPrefixing(boolean keyPrefixing) {
        if (!keyPrefixing && sortedDuplicates) {
            throw new IllegalStateException
                ("Key prefixing is mandatory for databases with duplicates");
        }
        this.keyPrefixing = keyPrefixing;
        return this;
    }

    /**
     * Encloses the database open within a transaction.
     *
     * <p>If the call succeeds, the open operation will be recoverable.  If the
     * call fails, no database will have been created.</p>
     *
     * <p>All future operations on this database, which are not explicitly
     * enclosed in a transaction by the application, will be enclosed in in a
     * transaction within the library.</p>
     *
     * @param transactional If true, enclose the database open within a
     * transaction.
     *
     * @return this
     */
    public DatabaseConfig setTransactional(boolean transactional) {
        this.transactional = transactional;
        return this;
    }

    /**
     * Returns true if the database open is enclosed within a transaction.
     *
     * <p>This method may be called at any time during the life of the
     * application.</p>
     *
     * @return true if the database open is enclosed within a transaction.
     */
    public boolean getTransactional() {
        return transactional;
    }

    /**
     * Configures the database in read-only mode.
     *
     * <p>Any attempt to modify items in the database will fail, regardless of
     * the actual permissions of any underlying files.</p>
     *
     * @param readOnly If true, configure the database in read-only mode.
     *
     * @return this
     */
    public DatabaseConfig setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    /**
     * Returns true if the database is configured in read-only mode.
     *
     * <p>This method may be called at any time during the life of the
     * application.</p>
     *
     * @return true if the database is configured in read-only mode.
     */
    public boolean getReadOnly() {
        return readOnly;
    }

    /**
     * Configures the {@link com.sleepycat.je.Environment#openDatabase
     * Environment.openDatabase} method to have a B+Tree fanout of
     * nodeMaxEntries.
     *
     * <p>The nodeMaxEntries parameter is only meaningful if specified with the
     * allowCreate mode. See {@link EnvironmentConfig#NODE_MAX_ENTRIES} for the
     * valid value range, and the default value. </p>
     *
     * @param nodeMaxEntries The maximum children per B+Tree node.
     *
     * @return this
     */
    public DatabaseConfig setNodeMaxEntries(int nodeMaxEntries) {
        this.nodeMaxEntries = nodeMaxEntries;
        return this;
    }

    /**
     * Returns the maximum number of children a B+Tree node can have.
     *
     * <p>This method may be called at any time during the life of the
     * application.</p>
     *
     * @return The maximum number of children a B+Tree node can have.
     */
    public int getNodeMaxEntries() {
        return nodeMaxEntries;
    }

    /**
     * By default, a byte by byte lexicographic comparison is used for btree
     * keys. To customize the comparison, supply a different Comparator.
     *
     * <p>Note that there are two ways to set the comparator: by specifying the
     * class or by specifying a serializable object.  This method is used to
     * specify a serializable object.  The comparator class must implement
     * java.util.Comparator and must be serializable.  JE will serialize the
     * Comparator and deserialize it when subsequently opening the
     * database.</p>
     *
     * <p>If a comparator needs to be initialized before it is used or needs
     * access to the environment's ClassLoader property, it may implement the
     * {@link DatabaseComparator} interface.</p>
     *
     * <p>The Comparator.compare() method is passed the byte arrays that are
     * stored in the database. If you know how your data is organized in the
     * byte array, then you can write a comparison routine that directly
     * examines the contents of the arrays. Otherwise, you have to reconstruct
     * your original objects, and then perform the comparison.  See the <a
     * href="{@docRoot}/../GettingStartedGuide/comparator.html"
     * target="_top">Getting Started Guide</a> for examples.</p>
     *
     * <p><em>WARNING:</em> There are several special considerations that must
     * be taken into account when implementing a comparator.</p>
     * <ul>
     *   <li>Comparator instances are shared by multiple threads and comparator
     *   methods are called without any special synchronization. Therefore,
     *   comparators must be thread safe.  In general no shared state should be
     *   used and any caching of computed values must be done with proper
     *   synchronization.</li>
     *
     *   <li>Because records are stored in the order determined by the
     *   Comparator, the Comparator's behavior must not change over time and
     *   therefore should not be dependent on any state that may change over
     *   time.  In addition, although it is possible to change the comparator
     *   for an existing database, care must be taken that the new comparator
     *   provides compatible results with the previous comparator, or database
     *   corruption will occur.</li>
     *
     *   <li>JE uses comparators internally in a wide variety of circumstances,
     *   so custom comparators must be sure to return valid values for any two
     *   arbitrary keys.  The user must not make any assumptions about the
     *   range of key values that might be compared. For example, it's possible
     *   for the comparator may be used against previously deleted values.</li>
     * </ul>
     *
     * <p>A special type of comparator is a <em>partial comparator</em>, which
     * allows for the keys of a database to be updated, but only if the updates
     * do not change the relative order of the keys. For example, if a database
     * uses strings as keys and a case-insensitive comparator, it is possible
     * to change the case of characters in the keys, as this will not change
     * the ordering of the keys. Another example is when the keys contain
     * multiple fields but uniquely identify each record with a single field.
     * The partial comparator could then compare only the single identifying
     * field, allowing the rest of the fields to be updated. A query
     * ({@link Cursor#getSearchKey Cursor.getSearchKey}, for example) could
     * then be performed by passing a partial key that contains only the
     * identifying field.
     *
     * <p><strong>WARNING:</strong> To allow for key updates in situations
     * like those described above, all partial comparators must implement the
     * {@link PartialComparator} tag interface. Otherwise, BDB JE will raise
     * an exception if an attempt is made to update a key in a database whose
     * comparators do not implement PartialComparator. See "Upgrading from JE
     * 5.0 or earlier" in the change log and the {@link PartialComparator}
     * javadoc for more information.</p>
     * <p>
     * Another special type of comparator is a <em>binary equality</em>
     * comparator, which considers two keys to be equal if and only if they
     * have the same length and they are equal byte-per-byte. All binary
     * equality comparators must implement the {@link BinaryEqualityComparator}
     * interface. The significance of binary equality comparators is that they
     * make possible certain internal optimizations, like the "blind puts"
     * optimization, described in
     * {@link BinaryEqualityComparator}
     * <p>
     * The comparator for an existing database will not be overridden unless
     * setOverrideBtreeComparator() is set to true.
     *
     * @return this
     */
    public DatabaseConfig setBtreeComparator(
        Comparator<byte[]> btreeComparator) {

        /* Note: comparator may be null */
        this.btreeComparator =
            validateComparator(btreeComparator, "BtreeComparator");
        this.btreeComparatorByClassName = false;
        this.btreeByteComparator = false;
        return this;
    }

    /**
     * By default, a byte by byte lexicographic comparison is used for btree
     * keys. To customize the comparison, supply a different Comparator.
     *
     * <p>Note that there are two ways to set the comparator: by specifying the
     * class or by specifying a serializable object.  This method is used to
     * specify a Comparator class.  The comparator class must implement
     * java.util.Comparator and must have a public zero-parameter constructor.
     * JE will store the class name and instantiate the Comparator by class
     * name (using <code>Class.forName</code> and <code>newInstance</code>)
     * when subsequently opening the database.  Because the Comparator is
     * instantiated using its default constructor, it should not be dependent
     * on other constructor parameters.</p>
     *
     * <p>The Comparator.compare() method is passed the byte arrays that are
     * stored in the database. If you know how your data is organized in the
     * byte array, then you can write a comparison routine that directly
     * examines the contents of the arrays. Otherwise, you have to reconstruct
     * your original objects, and then perform the comparison.  See the <a
     * href="{@docRoot}/../GettingStartedGuide/comparator.html"
     * target="_top">Getting Started Guide</a> for examples.</p>
     *
     * <p>If a comparator needs to be initialized before it is used or needs
     * access to the environment's ClassLoader property, it may implement the
     * {@link DatabaseComparator} interface.</p>
     *
     * <p><em>WARNING:</em> There are several special considerations that must
     * be taken into account when implementing a comparator.</p>
     * <ul>
     *   <li>Comparator instances are shared by multiple threads and comparator
     *   methods are called without any special synchronization. Therefore,
     *   comparators must be thread safe.  In general no shared state should be
     *   used and any caching of computed values must be done with proper
     *   synchronization.</li>
     *
     *   <li>Because records are stored in the order determined by the
     *   Comparator, the Comparator's behavior must not change over time and
     *   therefore should not be dependent on any state that may change over
     *   time.  In addition, although it is possible to change the comparator
     *   for an existing database, care must be taken that the new comparator
     *   provides compatible results with the previous comparator, or database
     *   corruption will occur.</li>
     *
     *   <li>JE uses comparators internally in a wide variety of circumstances,
     *   so custom comparators must be sure to return valid values for any two
     *   arbitrary keys.  The user must not make any assumptions about the
     *   range of key values that might be compared. For example, it's possible
     *   for the comparator may be used against previously deleted values.</li>
     * </ul>
     *
     * <p>A special type of comparator is a <em>partial comparator</em>, which
     * allows for the keys of a database to be updated, but only if the updates
     * do not change the relative order of the keys. For example, if a database
     * uses strings as keys and a case-insensitive comparator, it is possible
     * to change the case of characters in the keys, as this will not change the
     * ordering of the keys. Another example is when the keys contain multiple
     * fields but uniquely identify each record with a single field. The
     * partial comparator could then compare only the single identifying field,
     * allowing the rest of the fields to be updated. A query
     * ({@link Cursor#getSearchKey Cursor.getSearchKey}, for example) could
     * then be performed by passing a partial key that contains only the
     * identifying field.
     *
     * <p><strong>WARNING:</strong> To allow for key updates in situations
     * like those described above, all partial comparators must implement the
     * {@link PartialComparator} tag interface. See "Upgrading from JE 5.0
     * or earlier" in the change log and the {@link PartialComparator} javadoc
     * for more information.</p>
     *
     * Another special type of comparator is a <em>binary equality</em>
     * comparator, which considers two keys to be equal if and only if they
     * have the same length and they are equal byte-per-byte. All binary
     * equality comparators must implement the {@link BinaryEqualityComparator}
     * interface. The significance of binary equality comparators is that they
     * make possible certain internal optimizations, like the "blind puts"
     * optimization, described in
     * {@link BinaryEqualityComparator}
     * <p>
     * The comparator for an existing database will not be overridden unless
     * setOverrideBtreeComparator() is set to true.
     *
     * @return this
     */
    public DatabaseConfig setBtreeComparator(
        Class<? extends Comparator<byte[]>> btreeComparatorClass) {

        /* Note: comparator may be null */
        this.btreeComparator = validateComparator(
            btreeComparatorClass, "Btree");
        this.btreeByteComparator = false;
        this.btreeComparatorByClassName = true;
        return this;
    }

    /**
     * Returns the Comparator used for key comparison on this database.
     */
    @SuppressWarnings("unchecked")
    public Comparator<byte[]> getBtreeComparator() {

        return btreeByteComparator ?
            null : (Comparator<byte[]>) btreeComparator;
    }

    public DatabaseConfig setBtreeByteComparator(
        ByteComparator btreeComparator) {

        /* Note: comparator may be null */
        this.btreeComparator =
            validateComparator(btreeComparator, "BtreeByte");
        this.btreeByteComparator = true;
        this.btreeComparatorByClassName = false;
        return this;
    }

    public DatabaseConfig setBtreeByteComparator(
        Class<? extends ByteComparator> btreeComparatorClass) {

        /* Note: comparator may be null */
        this.btreeComparator = validateComparator(
            btreeComparatorClass, "Btree");
        this.btreeByteComparator = true;
        this.btreeComparatorByClassName = true;
        return this;
    }

    public ByteComparator getBtreeByteComparator() {

        return btreeByteComparator ?
            (ByteComparator) btreeComparator : null;
    }

    /**
     * Returns true if the btree comparator is set by class name, not by
     * serializable Comparator object
     * @return true if the comparator is set by class name, not by serializable
     * Comparator object.
     */
    public boolean getBtreeComparatorByClassName() {
        return btreeComparatorByClassName;
    }

    /**
     * Sets to true if the database exists and the btree comparator specified
     * in this configuration object should override the current comparator.
     *
     * @param override Set to true to override the existing comparator.
     *
     * @return this
     */
    public DatabaseConfig setOverrideBtreeComparator(boolean override) {
        overrideBtreeComparator = override;
        return this;
    }

    /**
     * Returns the override setting for the btree comparator.
     */
    public boolean getOverrideBtreeComparator() {
        return overrideBtreeComparator;
    }

    /**
     * By default, a byte by byte lexicographic comparison is used for
     * duplicate data items in a duplicate set.  To customize the comparison,
     * supply a different Comparator.
     *
     * <p>Note that there are two ways to set the comparator: by specifying the
     * class or by specifying a serializable object.  This method is used to
     * specify a serializable object.  The comparator class must implement
     * java.util.Comparator and must be serializable.  JE will serialize the
     * Comparator and deserialize it when subsequently opening the
     * database.</p>
     *
     * <p>The Comparator.compare() method is passed the byte arrays that are
     * stored in the database. If you know how your data is organized in the
     * byte array, then you can write a comparison routine that directly
     * examines the contents of the arrays. Otherwise, you have to reconstruct
     * your original objects, and then perform the comparison.  See the <a
     * href="{@docRoot}/../GettingStartedGuide/comparator.html"
     * target="_top">Getting Started Guide</a> for examples.</p>
     *
     * <p>If a comparator needs to be initialized before it is used or needs
     * access to the environment's ClassLoader property, it may implement the
     * {@link DatabaseComparator} interface.</p>
     *
     * <p><em>WARNING:</em> There are several special considerations that must
     * be taken into account when implementing a comparator.</p>
     * <ul>
     *   <li>Comparator instances are shared by multiple threads and comparator
     *   methods are called without any special synchronization. Therefore,
     *   comparators must be thread safe.  In general no shared state should be
     *   used and any caching of computed values must be done with proper
     *   synchronization.</li>
     *
     *   <li>Because records are stored in the order determined by the
     *   Comparator, the Comparator's behavior must not change over time and
     *   therefore should not be dependent on any state that may change over
     *   time.  In addition, although it is possible to change the comparator
     *   for an existing database, care must be taken that the new comparator
     *   provides compatible results with the previous comparator, or database
     *   corruption will occur.</li>
     *
     *   <li>JE uses comparators internally in a wide variety of circumstances,
     *   so custom comparators must be sure to return valid values for any two
     *   arbitrary keys.  The user must not make any assumptions about the
     *   range of key values that might be compared. For example, it's possible
     *   for the comparator may be used against previously deleted values.</li>
     * </ul>
     *
     * <p>A special type of comparator is a <em>partial comparator</em>, which
     * allows for the keys of a database to be updated, but only if the updates
     * do not change the relative order of the keys. For example, if a database
     * uses strings as keys and a case-insensitive comparator, it is possible to
     * change the case of characters in the keys, as this will not change the
     * ordering of the keys. Another example is when the keys contain multiple
     * fields but uniquely identify each record with a single field. The
     * partial comparator could then compare only the single identifying field,
     * allowing the rest of the fields to be updated. A query
     * ({@link Cursor#getSearchKey Cursor.getSearchKey}, for example) could
     * then be performed by passing a partial key that contains only the
     * identifying field.
     *
     * <p>When using a partial duplicates comparator, it is possible to update
     * the data for a duplicate record, as long as only the non-identifying
     * fields in the data are changed.  See
     * {@link Cursor#putCurrent Cursor.putCurrent} for more information.</p>
     *
     * <p><strong>WARNING:</strong> To allow for key updates in situations
     * like those described above, all partial comparators must implement the
     * {@link PartialComparator} tag interface. See "Upgrading from JE 5.0
     * or earlier" in the change log and the {@link PartialComparator} javadoc
     * for more information.</p>
     *
     * <p>
     * Another special type of comparator is a <em>binary equality</em>
     * comparator, which considers two keys to be equal if and only if they
     * have the same length and they are equal byte-per-byte. All binary
     * equality comparators must implement the {@link BinaryEqualityComparator}
     * interface. The significance of binary equality comparators is that they
     * make possible certain internal optimizations, like the "blind puts"
     * optimization, described in
     * {@link BinaryEqualityComparator}
     * <p>
     * The comparator for an existing database will not be overridden unless
     * setOverrideDuplicateComparator() is set to true.
     */
    public DatabaseConfig setDuplicateComparator(
        Comparator<byte[]> duplicateComparator) {

        /* Note: comparator may be null */
        this.duplicateComparator =
            validateComparator(duplicateComparator, "DuplicateComparator");
        this.duplicateByteComparator = false;
        this.duplicateComparatorByClassName = false;
        return this;
    }

    /**
     * By default, a byte by byte lexicographic comparison is used for
     * duplicate data items in a duplicate set.  To customize the comparison,
     * supply a different Comparator.
     *
     * <p>Note that there are two ways to set the comparator: by specifying the
     * class or by specifying a serializable object.  This method is used to
     * specify a Comparator class.  The comparator class must implement
     * java.util.Comparator and must have a public zero-parameter constructor.
     * JE will store the class name and instantiate the Comparator by class
     * name (using <code>Class.forName</code> and <code>newInstance</code>)
     * when subsequently opening the database.  Because the Comparator is
     * instantiated using its default constructor, it should not be dependent
     * on other constructor parameters.</p>
     *
     * <p>The Comparator.compare() method is passed the byte arrays that are
     * stored in the database. If you know how your data is organized in the
     * byte array, then you can write a comparison routine that directly
     * examines the contents of the arrays. Otherwise, you have to reconstruct
     * your original objects, and then perform the comparison.  See the <a
     * href="{@docRoot}/../GettingStartedGuide/comparator.html"
     * target="_top">Getting Started Guide</a> for examples.</p>
     *
     * <p>If a comparator needs to be initialized before it is used or needs
     * access to the environment's ClassLoader property, it may implement the
     * {@link DatabaseComparator} interface.</p>
     *
     * <p><em>WARNING:</em> There are several special considerations that must
     * be taken into account when implementing a comparator.</p>
     * <ul>
     *   <li>Comparator instances are shared by multiple threads and comparator
     *   methods are called without any special synchronization. Therefore,
     *   comparators must be thread safe.  In general no shared state should be
     *   used and any caching of computed values must be done with proper
     *   synchronization.</li>
     *
     *   <li>Because records are stored in the order determined by the
     *   Comparator, the Comparator's behavior must not change over time and
     *   therefore should not be dependent on any state that may change over
     *   time.  In addition, although it is possible to change the comparator
     *   for an existing database, care must be taken that the new comparator
     *   provides compatible results with the previous comparator, or database
     *   corruption will occur.</li>
     *
     *   <li>JE uses comparators internally in a wide variety of circumstances,
     *   so custom comparators must be sure to return valid values for any two
     *   arbitrary keys.  The user must not make any assumptions about the
     *   range of key values that might be compared. For example, it's possible
     *   for the comparator may be used against previously deleted values.</li>
     * </ul>
     *
     * <p>A special type of comparator is a <em>partial comparator</em>, which
     * allows for the keys of a database to be updated, but only if the updates
     * do not change the relative order of the keys. For example, if a database
     * uses strings as keys and a case-insensitive comparator, it is possible to
     * change the case of characters in the keys, as this will not change the
     * ordering of the keys. Another example is when the keys contain multiple
     * fields but uniquely identify each record with a single field. The
     * partial comparator could then compare only the single identifying field,
     * allowing the rest of the fields to be updated. A query
     * ({@link Cursor#getSearchKey Cursor.getSearchKey}, for example) could
     * then be performed by passing a partial key that contains only the
     * identifying field.
     *
     * <p>When using a partial duplicates comparator, it is possible to update
     * the data for a duplicate record, as long as only the non-identifying
     * fields in the data are changed.  See
     * {@link Cursor#putCurrent Cursor.putCurrent} for more information.</p>
     *
     * <p><strong>WARNING:</strong> To allow for key updates in situations
     * like those described above, all partial comparators must implement the
     * {@link PartialComparator} tag interface. See "Upgrading from JE 5.0
     * or earlier" in the change log and the {@link PartialComparator} javadoc
     * for more information.</p>
     * <p>
     * Another special type of comparator is a <em>binary equality</em>
     * comparator, which considers two keys to be equal if and only if they
     * have the same length and they are equal byte-per-byte. All binary
     * equality comparators must implement the {@link BinaryEqualityComparator}
     * interface. The significance of binary equality comparators is that they
     * make possible certain internal optimizations, like the "blind puts"
     * optimization, described in
     * {@link BinaryEqualityComparator}
     * <p>
     * The comparator for an existing database will not be overridden unless
     * setOverrideDuplicateComparator() is set to true.
     *
     * @return this
     */
    public DatabaseConfig setDuplicateComparator(
        Class<? extends Comparator<byte[]>> duplicateComparatorClass) {

        /* Note: comparator may be null */
        this.duplicateComparator =
            validateComparator(duplicateComparatorClass, "Duplicate");
        this.duplicateByteComparator = false;
        this.duplicateComparatorByClassName = true;
        return this;
    }

    /**
     * Returns the Comparator used for duplicate record comparison on this
     * database.
     */
    @SuppressWarnings("unchecked")
    public Comparator<byte[]> getDuplicateComparator() {

        return duplicateByteComparator ?
            null : (Comparator<byte[]>) duplicateComparator;
    }

    public DatabaseConfig setDuplicateByteComparator(
        ByteComparator duplicateComparator) {

        /* Note: comparator may be null */
        this.duplicateComparator =
            validateComparator(duplicateComparator, "Duplicate");
        this.duplicateByteComparator = true;
        this.duplicateComparatorByClassName = false;
        return this;
    }

    public DatabaseConfig setDuplicateByteComparator(
        Class<? extends ByteComparator> duplicateComparatorClass) {

        /* Note: comparator may be null */
        this.duplicateComparator =
            validateComparator(duplicateComparatorClass, "Duplicate");
        this.duplicateByteComparator = true;
        this.duplicateComparatorByClassName = true;
        return this;
    }

    public ByteComparator getDuplicateByteComparator() {

        return duplicateByteComparator ?
            (ByteComparator) duplicateComparator : null;
    }

    /**
     * Returns true if the duplicate comparator is set by class name, not by
     * serializable Comparator object.
     *
     * @return true if the duplicate comparator is set by class name, not by
     * serializable Comparator object.
     */
    public boolean getDuplicateComparatorByClassName() {
        return duplicateComparatorByClassName;
    }

    /**
     * Sets to true if the database exists and the duplicate comparator
     * specified in this configuration object should override the current
     * comparator.
     *
     * @param override Set to true to override the existing comparator.
     *
     * @return this
     */
    public DatabaseConfig setOverrideDuplicateComparator(boolean override) {
        overrideDuplicateComparator = override;
        return this;
    }

    /**
     * Returns the override setting for the duplicate comparator.
     */
    public boolean getOverrideDuplicateComparator() {
        return overrideDuplicateComparator;
    }

    /**
     * @hidden
     * For internal use only.
     *
     * Specifies the list of triggers associated with the database; triggers
     * are executed in the order specified by this list.
     * <p>
     * This configuration parameter is only meaningful when configuring a
     * <code>Primary</code> database.
     * <p>
     * The type of the trigger specified in the list must match the type of
     * database being configured. For example, the trigger object must
     * implement the <code>ReplicatedDatabaseTrigger</code> interface if it's
     * used to configure a replicated database.
     * <p>
     * Some of the incorrect uses of this parameter are detected during calls
     * to {@link Environment#openDatabase Environment.openDatabase} or
     * {@link Environment#openSecondaryDatabase
     * Environment.openSecondaryDatabase} and will result in an
     * <code>IllegalArgumentException</code>.
     *
     * @param triggers the list of database triggers to be associated with the
     * environment.
     *
     * @throws IllegalArgumentException If the triggers in the list do not have
     * unique names, have conflicting types (e.g. only a subset implement
     * {@link ReplicatedDatabaseTrigger ReplicatedDatabaseTrigger}), or do not
     * implement {@link ReplicatedDatabaseTrigger ReplicatedDatabaseTrigger}
     * for a replicated database.
     *
     * @return this
     */
    public DatabaseConfig setTriggers(List<Trigger> triggers) {

        this.triggers = triggers;

        if ((triggers == null) || (triggers.size() == 0)) {
            return this;
        }

        checkTriggers(triggers);
        return this;
    }

    /**
     * @hidden
     * For internal use only.
     *
     * Returns the list of configured database triggers.
     */
    public List<Trigger> getTriggers() {
        return triggers;
    }

    /**
     * @hidden
     * For internal use only.
     *
     * Set to true if the database exists and the {@link PersistentTrigger}s in
     * the trigger list specified in this configuration object should override
     * those in the current list of triggers.  Note that any transient triggers
     * that are specified are always configured, because they do not override
     * existing triggers.
     *
     * @param override Set to true to override the existing comparator.
     *
     * @return this
     */
    public DatabaseConfig setOverrideTriggers(boolean override) {
        overrideTriggers = override;
        return this;
    }

    /**
     * @hidden
     * For internal use only.
     *
     * Returns the override setting for triggers.
     */
    public boolean getOverrideTriggers() {
        return overrideTriggers;
    }

    /**
     * Used to set the comparator when filling in a configuration from an
     * existing database.
     */
    private void setBtreeComparatorInternal(Comparator<byte[]> comparator,
                                            ByteComparator byteComparator,
                                            boolean byClassName) {
        if (byteComparator != null) {
            btreeComparator = byteComparator;
            btreeByteComparator = true;
        } else {
            btreeComparator = comparator;
            btreeByteComparator = false;
        }
        btreeComparatorByClassName = byClassName;
    }

    /**
     * Used to set the comparator when filling in a configuration from an
     * existing database.
     */
    private void setDuplicateComparatorInternal(Comparator<byte[]> comparator,
                                                ByteComparator byteComparator,
                                                boolean byClassName) {
        if (byteComparator != null) {
            duplicateComparator = byteComparator;
            btreeByteComparator = true;
        } else {
            duplicateComparator = comparator;
            btreeByteComparator = false;
        }
        duplicateComparatorByClassName = byClassName;
    }

    /**
     * Setting useExistingConfig to true allows a program to open a database
     * without knowing a prior what its configuration is.  For example, if you
     * want to open a database without knowing whether it contains sorted
     * duplicates or not, you can set this property to true.  In general, this
     * is used by the JE utilities, to avoid having to know the configuration
     * of a database.  The databases should be opened readOnly when this
     * property is set to true.
     *
     * @param useExistingConfig true if this Database should be opened using
     * the existing configuration.
     *
     * @return this
     */
    public DatabaseConfig setUseExistingConfig(boolean useExistingConfig) {
        this.useExistingConfig = useExistingConfig;
        return this;
    }

    /**
     * Return the value of the useExistingConfig property.
     *
     * @return the value of the useExistingConfig property.
     */
    public boolean getUseExistingConfig() {
        return useExistingConfig;
    }

    /**
     * Sets the default {@code CacheMode} used for operations performed on this
     * database.  If this property is non-null, it overrides the default
     * specified using {@link EnvironmentConfig#setCacheMode} for operations on
     * this database.  The default cache mode may be overridden on a per-record
     * or per-operation basis using {@link Cursor#setCacheMode}, {@link
     * ReadOptions#setCacheMode(CacheMode)} or {@link
     * WriteOptions#setCacheMode(CacheMode)}.
     *
     * @param cacheMode is the default {@code CacheMode} used for operations
     * performed on this database.  If {@code null} is specified, the
     * environment default will be used.
     *
     * @see CacheMode for further details.
     *
     * @since 4.0.97
     */
    public DatabaseConfig setCacheMode(final CacheMode cacheMode) {
        this.cacheMode = cacheMode;
        return this;
    }

    /**
     * Returns the default {@code CacheMode} used for operations performed on
     * this database, or null if the environment default is used.
     *
     * @return the default {@code CacheMode} used for operations performed on
     * this database, or null if the environment default is used.
     *
     * @see #setCacheMode
     *
     * @since 4.0.97
     */
    public CacheMode getCacheMode() {
        return cacheMode;
    }

    /**
     * Configures a database to be replicated or non-replicated, in a
     * replicated Environment.  By default this property is true, meaning that
     * by default a database is replicated in a replicated Environment.
     * <p>
     * In a non-replicated Environment, this property is ignored.  All
     * databases are non-replicated in a non-replicated Environment.
     *
     * @see
     * <a href="rep/ReplicatedEnvironment.html#nonRepDbs">Non-replicated
     * Databases in a Replicated Environment</a>
     */
    public DatabaseConfig setReplicated(boolean replicated) {
        this.replicated = replicated;
        return this;
    }

    /**
     * Returns the replicated property for the database.
     * <p>
     * This method returns true by default.  However, in a non-replicated
     * Environment, this property is ignored.  All databases are non-replicated
     * in a non-replicated Environment.
     *
     * @see #setReplicated
     */
    public boolean getReplicated() {
        return replicated;
    }

    /**
     * @hidden
     * For internal use only.
     *
     * Configures a SecondaryAssociation that is used to define
     * primary-secondary associations for a group of primary and secondary
     * databases.  The same SecondaryAssociation instance must be configured on
     * the primary and secondary databases.
     *
     * @see SecondaryAssociation
     */
    public DatabaseConfig
        setSecondaryAssociation(SecondaryAssociation association) {
        secAssociation = association;
        return this;
    }

    /**
     * @hidden
     * For internal use only.
     *
     * Returns the configured SecondaryAssociation.
     *
     * @see #setSecondaryAssociation
     * @see SecondaryAssociation
     */
    public SecondaryAssociation getSecondaryAssociation() {
        return secAssociation;
    }

    /**
     * Returns a copy of this configuration object.
     */
    @Override
    public DatabaseConfig clone() {
        try {
            return (DatabaseConfig) super.clone();
        } catch (CloneNotSupportedException willNeverOccur) {
            return null;
        }
    }

    void validate(DatabaseConfig config)
        throws DatabaseException {

        if (config == null) {
            config = DatabaseConfig.DEFAULT;
        }

        boolean txnMatch = (config.transactional == transactional);
        boolean roMatch = (config.readOnly == readOnly);
        boolean sdMatch = (config.sortedDuplicates == sortedDuplicates);
        boolean btCmpMatch = true;
        if (config.overrideBtreeComparator) {
            if (btreeComparator == null) {
                btCmpMatch = (config.btreeComparator == null);
            } else if (config.btreeComparatorByClassName !=
                       btreeComparatorByClassName) {
                btCmpMatch = false;
            } else if (btreeComparatorByClassName) {
                btCmpMatch = btreeComparator.getClass() ==
                             config.btreeComparator.getClass();
            } else {
                btCmpMatch = Arrays.equals
                    (DatabaseImpl.objectToBytes
                        (btreeComparator, "BtreeComparator"),
                     DatabaseImpl.objectToBytes
                        (config.btreeComparator, "BtreeComparator"));
            }
        }
        boolean dtCmpMatch = true;
        if (config.overrideDuplicateComparator) {
            if (duplicateComparator == null) {
                dtCmpMatch = (config.duplicateComparator == null);
            } else if (config.duplicateComparatorByClassName !=
                       duplicateComparatorByClassName) {
                dtCmpMatch = false;
            } else if (duplicateComparatorByClassName) {
                dtCmpMatch = duplicateComparator.getClass() ==
                             config.duplicateComparator.getClass();
            } else {
                dtCmpMatch = Arrays.equals
                    (DatabaseImpl.objectToBytes
                        (duplicateComparator, "DuplicateComparator"),
                     DatabaseImpl.objectToBytes
                        (config.duplicateComparator, "DuplicateComparator"));
            }
        }

        if (txnMatch &&
            roMatch &&
            sdMatch &&
            btCmpMatch &&
            dtCmpMatch) {
            return;
        }
        String message = genDatabaseConfigMismatchMessage(
            txnMatch, roMatch, sdMatch, btCmpMatch, dtCmpMatch);
        throw new IllegalArgumentException(message);
    }

    private String genDatabaseConfigMismatchMessage(boolean txnMatch,
                                                    boolean roMatch,
                                                    boolean sdMatch,
                                                    boolean btCmpMatch,
                                                    boolean dtCmpMatch) {
        StringBuilder ret = new StringBuilder
            ("The following DatabaseConfig parameters for the\n" +
             "cached Database do not match the parameters for the\n" +
             "requested Database:\n");
        if (!txnMatch) {
            ret.append(" Transactional\n");
        }

        if (!roMatch) {
            ret.append(" Read-Only\n");
        }

        if (!sdMatch) {
            ret.append(" Sorted Duplicates\n");
        }

        if (!btCmpMatch) {
            ret.append(" Btree Comparator\n");
        }

        if (!dtCmpMatch) {
            ret.append(" Duplicate Comparator\n");
        }

        return ret.toString();
    }

    /**
     * Checks that this comparator can be serialized by JE.
     *
     * @throws IllegalArgumentException via setBtreeComparator and
     * setDuplicateComparator
     */
    private Object validateComparator(
        Object comparator,
        String type)
        throws IllegalArgumentException {

        if (comparator == null) {
            return null;
        }

        try {
            DatabaseImpl.comparatorToBytes(comparator, false /*byClassName*/,
                                           type);
            return comparator;
        } catch (DatabaseException e) {
            throw new IllegalArgumentException
                (type + " comparator is not valid.", e);
        }
    }

    /**
     * Checks that this comparator class can be instantiated by JE.
     *
     * @throws IllegalArgumentException via setBtreeComparator and
     * setDuplicateComparator
     */
    private Object validateComparator(
        Class<?> comparatorClass,
        String type)
        throws IllegalArgumentException {

        if (comparatorClass == null) {
            return null;
        }

        if (!Comparator.class.isAssignableFrom(comparatorClass) &&
            !ByteComparator.class.isAssignableFrom(comparatorClass)) {
            throw new IllegalArgumentException
                (comparatorClass.getName() +
                 " is is not valid as a " + type +
                 " comparator because it does not implement" +
                 " java.util.Comparator or com.sleepycat.ByteComparator.");
        }

        try {
            return DatabaseImpl.instantiateComparator(comparatorClass, type);
        } catch (DatabaseException e) {
            throw new IllegalArgumentException
                (type + " comparator is not valid. " +
                 "Perhaps you have not implemented a zero-parameter " +
                 "constructor for the comparator or the comparator class " +
                 "cannot be found.",
                 e);
        }
    }

    /**
     * Checks that this database configuration is valid for a new, non-existant
     * database.
     *
     * @throws IllegalArgumentException via Environment.openDatabase and
     * openSecondaryDatabase
     */
    void validateForNewDb()
        throws DatabaseException {

        if (readOnly) {
            throw new IllegalArgumentException
                ("DatabaseConfig.setReadOnly() must be set to false " +
                 "when creating a Database");
        }
    }

    /**
     * For unit tests, checks that the database configuration attributes that
     * are saved persistently are equal.
     */
    boolean persistentEquals(DatabaseConfig other) {
        if (sortedDuplicates != other.sortedDuplicates) {
            return false;
        }

        if (replicated != other.replicated) {
            return false;
        }

        if (nodeMaxEntries != other.nodeMaxEntries) {
            return false;
        }

        if (((btreeComparator == null) && (other.btreeComparator != null)) ||
            ((btreeComparator != null) && (other.btreeComparator == null))) {
            return false;
        }

        if (btreeComparator != null) {
            if (btreeComparator.getClass() !=
                other.btreeComparator.getClass()) {
            return false;
        }
        }

        if (((duplicateComparator == null) &&
             (other.duplicateComparator != null)) ||
            ((duplicateComparator != null) &&
             (other.duplicateComparator == null))) {
            return false;
        }

        if ((duplicateComparator != null)) {
            if (duplicateComparator.getClass() !=
                other.duplicateComparator.getClass()) {
                return false;
        }
        }

        return true;
    }

    /**
     * Perform validations at database open time on the completed DbConfig
     * object. Inter-attribute checks are done here.
     */
    void validateOnDbOpen(String databaseName,
                          boolean dbIsReplicated) {

        if ((triggers != null) && (triggers.size() > 0)) {

            final boolean replicatedTriggers = checkTriggers(triggers);
            if (dbIsReplicated && !replicatedTriggers) {
                throw new IllegalArgumentException
                    ("For a replicated Database, triggers must implement " +
                     ReplicatedDatabaseTrigger.class.getName());
            }
        }
    }

    /**
     * Checks that the triggers in the list have consistent definitions.
     *
     * @param triggerList the list of triggers to be checked
     *
     * @return true if the list consists of just replicated triggers, false if
     * it consists entirely of non-replicated triggers.
     *
     * @throws IllegalArgumentException if the list had triggers with duplicate
     * names or the types were not consistent.
     */
    boolean checkTriggers(List<Trigger> triggerList) {

        final boolean replicatedTrigger =
            triggerList.get(0) instanceof ReplicatedDatabaseTrigger;

        final Set<String> triggerNames = new HashSet<String>();

        for (Trigger trigger : triggerList) {

            /*
             * Note that we do not disallow the unsupported PersistentTrigger
             * or ReplicatedDatabaseTrigger intefaces here, to enable the
             * continued testing of these partially implemented features.
             *
            if (trigger instanceof PersistentTrigger) {
                throw new IllegalArgumentException
                    ("PeristentTrigger not supported: " + trigger.getName());
            }
            */

            if (!triggerNames.add(trigger.getName())) {
                throw new IllegalArgumentException
                    ("Duplicate trigger name:" + trigger.getName());
            }
            if (replicatedTrigger !=
                (trigger instanceof ReplicatedDatabaseTrigger)) {
                throw new IllegalArgumentException
                    ("Conflicting trigger types in list:" + triggerList);
            }
        }
        return replicatedTrigger;
    }

    /**
     * Combine the per-Database handle and Database-wide properties for a
     * database handle.
     *
     * @param dbImpl the underlying DatabaseImpl for a database handle, which
     * provides the Database-wide properties
     *
     * @param dbHandleConfig DatabaseConfig field for the same database handle,
     * which provides the per-Database properties.
     *
     * @return a DatabaseConfig which includes the correct Database-wide and
     * per-Database handle properties.
     */
    static DatabaseConfig combineConfig(DatabaseImpl dbImpl,
                                        DatabaseConfig dbHandleConfig) {

        DatabaseConfig showConfig = dbHandleConfig.clone();

        /*
         * Set the Database-wide properties from the DatabaseImpl, since they
         * might have changed from other database handles.
         *
         * Note: sorted duplicates, temporary and replicated attributes are not
         * mutable and were checked at handle creation to make sure these
         * properties in dbHandleConfig are consistent with
         * DatabaseImpl. They're still set here in case the useExistingConfig
         * property is set, and those field were not initialized.
         */
        if (dbImpl != null) {
            /* mutable, persistent, database wide attributes. */
            showConfig.setBtreeComparatorInternal(
                dbImpl.getBtreeComparator(),
                dbImpl.getBtreeByteComparator(),
                dbImpl.getBtreeComparatorByClass());
            showConfig.setDuplicateComparatorInternal(
                dbImpl.getDuplicateComparator(),
                dbImpl.getDuplicateByteComparator(),
                dbImpl.getDuplicateComparatorByClass());
            showConfig.setKeyPrefixing(dbImpl.getKeyPrefixing());
            showConfig.setNodeMaxEntries(dbImpl.getNodeMaxTreeEntries());
            showConfig.setTriggers(dbImpl.getTriggers());

            /* mutable, but non-persistent attributes. */
            showConfig.setTransactional(dbImpl.isTransactional());

            /* not mutable, but initialized in the showConfig. */
            showConfig.setReplicated(dbImpl.isReplicated());
            showConfig.setSortedDuplicates(dbImpl.getSortedDuplicates());
        }

        return showConfig;
    }

    /**
     * Returns the values for each configuration attribute.
     *
     * @return the values for each configuration attribute.
     */
    @Override
    public String toString() {
        return "allowCreate=" + allowCreate +
            "\nexclusiveCreate=" + exclusiveCreate +
            "\ntransactional=" + transactional +
            "\nreadOnly=" + readOnly +
            "\nsortedDuplicates=" + sortedDuplicates +
            "\nkeyPrefixing=" + keyPrefixing +
            "\n";
    }
}
