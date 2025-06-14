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

import java.io.IOException;
import java.io.InputStream;

import oracle.kv.impl.api.table.FieldDefImpl;

/**
 * A factory for creating and configuring {@link Decoder}s.
 * <p/>
 * Factories are thread-safe, and are generally cached by applications for
 * performance reasons. Multiple instances are only required if multiple
 * concurrent configurations are needed.
 * 
 * @see Decoder
 */

public class DecoderFactory {
    private static final DecoderFactory DEFAULT_FACTORY = new DefaultDecoderFactory();
    static final int DEFAULT_BUFFER_SIZE = 8192;

    int binaryDecoderBufferSize = DEFAULT_BUFFER_SIZE;

    /** Constructor for factory instances */
    public DecoderFactory() {
        super();
    }

    /**
     * Returns an immutable static DecoderFactory configured with default
     * settings All mutating methods throw IllegalArgumentExceptions. All
     * creator methods create objects with default settings.
     */
    public static DecoderFactory get() {
        return DEFAULT_FACTORY;
    }

    /**
     * Creates or reinitializes a {@link BinaryDecoder} with the input stream
     * provided as the source of data. If <i>reuse</i> is provided, it will be
     * reinitialized to the given input stream.
     * <p/>
     * {@link BinaryDecoder} instances returned by this method buffer their
     * input, reading up to binaryDecoderBufferSize bytes past the minimum
     * required to satisfy read requests in order to achieve better performance.
     * <p/>
     * {@link BinaryDecoder#inputStream()} provides a view on the data that is
     * buffer-aware, for users that need to interleave access to data with the
     * Decoder API.
     * 
     * @param in
     *            The InputStream to initialize to
     * @param reuse
     *            The BinaryDecoder to <i>attempt</i> to reuse given the factory
     *            configuration. A BinaryDecoder implementation may not be
     *            compatible with reuse, causing a new instance to be returned.
     *            If null, a new instance is returned.
     * @return A BinaryDecoder that uses <i>in</i> as its source of data. If
     *         <i>reuse</i> is null, this will be a new instance. If
     *         <i>reuse</i> is not null, then it may be reinitialized if
     *         compatible, otherwise a new instance will be returned.
     * @see BinaryDecoder
     * @see Decoder
     */
    public BinaryDecoder binaryDecoder(InputStream in, BinaryDecoder reuse) {
        if (null == reuse || !reuse.getClass().equals(BinaryDecoder.class)) {
            return new BinaryDecoder(in, binaryDecoderBufferSize);
        }
        return reuse.configure(in, binaryDecoderBufferSize);
    }

    /**
     * Creates or reinitializes a {@link BinaryDecoder} with the byte array
     * provided as the source of data. If <i>reuse</i> is provided, it will
     * attempt to reinitialize <i>reuse</i> to the new byte array. This instance
     * will use the provided byte array as its buffer.
     * <p/>
     * {@link BinaryDecoder#inputStream()} provides a view on the data that is
     * buffer-aware and can provide a view of the data not yet read by Decoder
     * API methods.
     * 
     * @param bytes
     *            The byte array to initialize to
     * @param offset
     *            The offset to start reading from
     * @param length
     *            The maximum number of bytes to read from the byte array
     * @param reuse
     *            The BinaryDecoder to attempt to reinitialize. if null a new
     *            BinaryDecoder is created.
     * @return A BinaryDecoder that uses <i>bytes</i> as its source of data. If
     *         <i>reuse</i> is null, this will be a new instance. <i>reuse</i>
     *         may be reinitialized if appropriate, otherwise a new instance is
     *         returned. Clients must not assume that <i>reuse</i> is
     *         reinitialized and returned.
     */
    public BinaryDecoder binaryDecoder(byte[] bytes, int offset, int length,
            BinaryDecoder reuse) {
        if (null == reuse || !reuse.getClass().equals(BinaryDecoder.class)) {
            return new BinaryDecoder(bytes, offset, length);
        }
        return reuse.configure(bytes, offset, length);
    }

    /**
     * This method is shorthand for
     * 
     * <pre>
     * createBinaryDecoder(bytes, 0, bytes.length, reuse);
     * </pre>
     * 
     * {@link #binaryDecoder(byte[], int, int, BinaryDecoder)}
     */
    public BinaryDecoder binaryDecoder(byte[] bytes, BinaryDecoder reuse) {
        return binaryDecoder(bytes, 0, bytes.length, reuse);
    }

    /**
     * Creates a {@link ResolvingDecoder} wrapping the Decoder provided. This
     * ResolvingDecoder will resolve input conforming to the <i>writer</i>
     * from the wrapped Decoder, and present it as the <i>reader</i>.
     * 
     * @param writer
     *          The RecordDefImpl that the source data is in. Cannot be null.
     * @param reader
     *          The RecordDefImpl that the reader wishes to read the data as.
     *          Cannot be null.
     * @param wrapped
     *          The Decoder to wrap.
     * @return A ResolvingDecoder configured to resolve <i>writer</i> to
     *         <i>reader</i> from <i>in</i>
     * @throws IOException
     */
    public ResolvingDecoder resolvingDecoder(FieldDefImpl writer,
            FieldDefImpl reader, Decoder wrapped) throws IOException {
      return new ResolvingDecoder(writer, reader, wrapped);
    }

    private static class DefaultDecoderFactory extends DecoderFactory {
    }
}
