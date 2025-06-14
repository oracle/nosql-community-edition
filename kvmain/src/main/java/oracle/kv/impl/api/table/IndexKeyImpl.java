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
 */

package oracle.kv.impl.api.table;

import java.math.BigDecimal;
import java.sql.Timestamp;

import oracle.kv.table.FieldDef;
import oracle.kv.table.FieldDef.Type;
import oracle.kv.table.FieldValue;
import oracle.kv.table.Index;
import oracle.kv.table.IndexKey;

import com.fasterxml.jackson.core.io.CharTypes;

/**
 * IndexKeyImpl is a flattened instance of RecordValueImpl where nested
 * fields declared in an index are translated into simple field names. This
 * simplifies manipulation of complex index keys by turning them into single-
 * level records. The type definitions of the fields in this record are
 * atomic, indexable types such as Integer, String, and Boolean.
 *
 * Instances of IndexKeyImpl are generated by calls to Index.createIndexKey()
 * which calls (static) IndexKeyImpl createIndexKey(IndexImpl index).
 *
 * Complex index paths are represented as these strings in IndexKeyImpl:
 * 1. array element index &rarr; path-to-array[], e.g. this_is_an_array[]
 * 2. map element index &rarr; path-to-map[], e.g. this_is_a_map[]
 * 3. map key index &rarr; "keys(path-to-member), e.g. keys(this_is_a_map)
 * 4. record member index &rarr; path-to-member, e.g. address.city
 *
 * There is still the special case of an index on a specific map key, which
 * is not a multi-key index. These are represented as
 *  path-to-map.indexedKeyString, e.g. this_is_a_map.indexedKeyString
 * NOTE: should this be path-to-map[indexedKeyString] instead?
 */
public class IndexKeyImpl extends RecordValueImpl implements IndexKey {

    private static final long serialVersionUID = 1L;

    final IndexImpl index;

    /**
     * The RecordDef associated with an IndexKeyImpl is that of its table.
     */
    IndexKeyImpl(final IndexImpl index,
                 final RecordDefImpl indexKeyDef) {
        super(indexKeyDef);
        this.index = index;
    }

    private IndexKeyImpl(IndexKeyImpl other) {
        super(other);
        this.index = other.index;
    }

    /**
     * Return the Index associated with this key
     */
    @Override
    public Index getIndex() {
        return index;
    }

    @Override
    public IndexKeyImpl clone() {
        return new IndexKeyImpl(this);
    }

    @Override
    public IndexKey asIndexKey() {
    /**
     * Override putField to add validation
     */
        return this;
    }

    @Override
    public boolean isIndexKey() {
        return true;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof IndexKeyImpl) {
            return super.equals(other);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    /**
     * Validate the index key.  Rules:
     * 1. Fields must be in the index. This is guaranteed by the schema
     * associated with this object.
     * 2. Fields must be specified in order.  If a field "to the right"
     * in the index definition is set, all fields to its "left" must also
     * be present.
     */
    @Override
    public void validate() {
        validateIndexFields();
    }

    public int getKeySize() {
        return index.serializeIndexKey(this).length;
    }

    TableImpl getTable() {
        return index.getTable();
    }

    public IndexImpl getIndexImpl() {
        return index;
    }

    /**
     * Return true if all fields in the index are specified. This method
     * should be called only after the key has been validated.
     */
    public boolean isComplete() {
        return (size() == getNumFields());
    }

    /**
     * This function behaves like adding "one" to the entire index key.  That
     * is, it increments the least significant field but if that field "wraps"
     * in that it's already at its maximum in terms of data type, such as
     * Integer.MAX_VALUE then increment the next more significant field and
     * set that field to its minimum value.
     *
     * If the value would wrap and there are no more significant fields then
     * return false, indicating that the "next" value is actually the end
     * of the index, period.
     *
     * This code is used to implement inclusive/exclusive semantics.
     *
     * Indexes that include a map key as a field are slightly more complicated.
     * In this case the key needs to be incremented and put back into the map.
     * In order to avoid multiple keys in the map the original key must be
     * removed from the map.
     */
    public boolean incrementIndexKey() {

        RecordDefImpl def = getDefinition();
        FieldValue[] values = new FieldValue[def.getNumFields()];
        int fieldIndex;

        for (fieldIndex = 0; fieldIndex < def.getNumFields(); ++fieldIndex) {
            values[fieldIndex] = get(fieldIndex);
            if (values[fieldIndex] == null) {
                break;
            }
        }

        /*
         * At least one field must exist.  Assert that and move back to the
         * target field.
         */
        assert fieldIndex > 0;
        --fieldIndex;

        /*
         * Increment and reset.  If the current field returns null, indicating
         * that it will wrap its value, set it to its minimum value and move to
         * the next more significant field.  If there are none, return false
         * indicating that there are no larger keys in the index that match the
         * key.
         */
        boolean isJsonField = index.getIndexFields().get(fieldIndex).isJson();
        FieldDefImpl ftype = def.getFieldDef(fieldIndex);
        FieldValueImpl fvi;

        fvi = getNextValue(ftype,
                           (FieldValueImpl)values[fieldIndex],
                           index.fieldMayHaveSpecialValue(fieldIndex),
                           isJsonField);

        while (fvi == null) {

            fvi = getMinimumValue((FieldValueImpl)values[fieldIndex], ftype);

            put(fieldIndex, fvi);
            --fieldIndex;
            /*
             * Move to next more significant field if it exists
             */
            if (fieldIndex >= 0) {
                ftype = def.getFieldDef(fieldIndex);
                fvi = getNextValue(ftype,
                                   (FieldValueImpl)values[fieldIndex],
                                   index.fieldMayHaveSpecialValue(fieldIndex),
                                   isJsonField);
            } else {
                /*
                 * Failed to increment
                 */
                return false;
            }
        }
        assert fvi != null && fieldIndex >= 0;

        put(fieldIndex, fvi);
        return true;
    }

    @Override
    protected String getClassNameForError() {
        return "IndexKey";
    }

    /**
     * Inserts the field at the given position, or updates its value if the
     * field exists already. The field must be of type JSON.
     */
    @Override
    public IndexKeyImpl putJsonNull(int pos) {
        if (!index.getIndexFields().get(pos).isJson()) {
            String fname = getFieldName(pos);
            throw new IllegalArgumentException(
                "Field \"" + fname + "\" is not JSON");
        }
        putInternal(pos, NullJsonValueImpl.getInstance());
        return this;
    }

    /**
     * Inserts the field at the given position, or updates its value if the
     * field exists already.
     */
    @Override
    public IndexKeyImpl putEMPTY(int pos) {
        putInternal(pos, EmptyValueImpl.getInstance());
        return this;
    }

    /**
     * Puts an EMPTY value in the named field, silently overwriting existing
     * value.
     */
    @Override
    public IndexKeyImpl putEMPTY(String name) {
        int pos = getFieldPos(name);
        return putEMPTY(pos);
    }

    /**
     * Returns the next value of the given value:
     *  If NULLs are allowed in index key, follow the rule of NULL last, the
     *  next value of MAX VALUE for each type is NULL.
     *  Null values (SQL or JSON null) cannot be incremented.
     */
    private FieldValueImpl getNextValue(
        FieldDefImpl ftype,
        FieldValueImpl value,
        boolean allowNull,
        boolean isJsonField) {

        if (value.isNull()) {
            return null;
        }

        FieldValueImpl next;
        if (value.isEMPTY()) {
            next = isJsonField ? NullJsonValueImpl.getInstance() :
                                 NullValueImpl.getInstance();
        } else if (value.isJsonNull()) {
            next = NullValueImpl.getInstance();
        } else {
            if (ftype.isUUIDString()) {
                String str = ((StringValueImpl)value).get();
                next = StringValueImpl.incrementUUIDString(str);
            } else {
                next = value.getNextValue();
            }

            if (next == null && allowNull) {
                next = EmptyValueImpl.getInstance();
            }
        }
        return next;
    }

    /**
     * Returns the minimal field value..
     */
    private FieldValueImpl getMinimumValue(
        FieldValueImpl value,
        FieldDefImpl def) {

        /*
         * The minimum value for a JSON type that is null is itself.
         */
        if ((value.isNull() || value.isJsonNull()) && def.isJson()) {
            return value;
        }

        if (value.isNull()) {
            return getMinValueOfType(def);
        }

        if (def.isUUIDString()) {
            return StringValueImpl.MINUUID;
        }

        return value.getMinimumValue();
    }

    /**
     * Returns the minimal field value of the specified type.
     */
    private FieldValueImpl getMinValueOfType(FieldDef def) {
        Type type = def.getType();
        FieldValue fv = null;
        switch(type) {
            case INTEGER:
                fv = def.createInteger(0);
                break;
            case LONG:
                fv = def.createLong(0L);
                break;
            case FLOAT:
                fv = def.createFloat(0.0f);
                break;
            case DOUBLE:
                fv = def.createDouble(0.0d);
                break;
            case NUMBER:
                fv = def.createNumber(BigDecimal.ZERO);
                break;
            case STRING:
                fv = def.createString("");
                break;
            case ENUM:
                return ((EnumDefImpl)def).createEnum(0);
            case TIMESTAMP:
                fv = ((TimestampDefImpl)def).createTimestamp(new Timestamp(0));
                break;
            case BOOLEAN:
                fv = def.createBoolean(false);
                break;
            default:
                throw new IllegalArgumentException(
                    "Unexpected type for index key: " + type);
        }
        return ((FieldValueImpl)fv).getMinimumValue();
    }

    /*
     * Must overwrite the RecordValueImpl implementation of this method,
     * because the field names of index keys are not just simple identifiers,
     * and as a result, CharTypes.appendQuoted() must be used to print them.
     */
    @Override
    public void toStringBuilder(StringBuilder sb, DisplayFormatter formatter) {
        if (formatter == null) {
            throw new IllegalArgumentException(
                "DisplayFormatter must be non-null");
        }

        boolean wroteFirstField = false;
        formatter.startObject();
        sb.append('{');
        for (int i = 0; i < getNumFields(); ++i) {
            String fieldName = getFieldName(i);
            FieldValueImpl val = get(i);
            if (val != null) {
                formatter.newPair(sb, wroteFirstField);
                sb.append('\"');
                CharTypes.appendQuoted(sb, fieldName);
                sb.append('\"');
                formatter.separator(sb);
                val.toStringBuilder(sb, formatter);
                wroteFirstField = true;
            }
        }
        formatter.endObject(sb, getNumFields());
        sb.append('}');
    }
}
