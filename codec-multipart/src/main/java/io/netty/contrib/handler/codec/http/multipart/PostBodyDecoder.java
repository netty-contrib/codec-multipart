/*
 * Copyright 2024 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.contrib.handler.codec.http.multipart;

import io.netty.contrib.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty5.buffer.Buffer;
import io.netty5.handler.codec.http.HttpConstants;
import io.netty5.util.Send;

import java.io.Closeable;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * Base interface for a decoder for a post body. This API supersedes {@link HttpPostRequestDecoder}.
 * <p>
 * This API is oriented mostly to the structure of a multipart input. There is also an
 * {@code application/x-www-form-urlencoded} implementation, but that implementation mostly emulates a multipart body.
 * This is an intentional design choice so that users can support both types of input while mostly orienting themselves
 * to the more complicated multipart input.
 * <p>
 * To use this API, first create an instance using the {@link #builder() builder}. When new input comes in, add it
 * using {@link #add(Send)}. Then, repeatedly call {@link #next()} and handle the returned events. When {@link #next()}
 * returns {@code null}, wait for new input. At the end of the input, call {@link #endInput()} and repeatedly
 * {@link #next()} again.
 * <p>
 * The events returned by {@link #next()} are in a fixed sequence:<br>
 * {@code (BEGIN_FIELD HEADER* HEADERS_COMPLETE CONTENT* FIELD_COMPLETE)*}<br>
 * The {@link Event#HEADER} and {@link Event#CONTENT} events carry a payload that can be accessed by other methods of
 * this interface.
 * <p>
 * Please note that the {@code application/x-www-form-urlencoded} decoder emits a single header event per field that
 * contains the field name. Please see the {@link Event#HEADER header event} javadoc.
 *
 * @author Jonas Konrad
 */
public interface PostBodyDecoder extends Closeable {
    static Builder builder() {
        return new Builder();
    }

    /**
     * Add a new buffer to this decoder, to be parsed by {@link #next()}.
     *
     * @param buffer The buffer
     * @throws ErrorDataDecoderException If the {@link Builder#undecodedLimit(int)} has been exceeded. That means that
     *                                   either you didn't call {@link #next()} until it returned {@code null} before
     *                                   adding more data, or the input data has tokens that exceed the configured
     *                                   limit (possible attack vector).
     */
    void add(Send<Buffer> buffer);

    /**
     * Signal that no more input is forthcoming after the last {@link #add(Send)} call.
     */
    void endInput();

    /**
     * Attempt to parse some input. The events returned by this method have the following structure:
     * <p>
     * <pre>{@code
     * regular-part := BEGIN_FIELD HEADER* HEADERS_COMPLETE CONTENT* FIELD_COMPLETE
     * mixed-part   := BEGIN_FIELD HEADER* BEGIN_MIXED regular-part* FIELD_COMPLETE
     * part         := (regular-part | mixed-part)*
     * }</pre>
     *
     * @return The next parsed event, or {@code null} if more input is needed.
     * @throws ErrorDataDecoderException On invalid input
     * @see Event
     */
    Event next();

    /**
     * If the last event was a {@link Event#HEADER}, get the header name.
     *
     * @return The header name
     * @throws IllegalStateException If the last event was not a header
     */
    default CharSequence headerName() {
        throw new IllegalStateException("Not a header");
    }

    /**
     * Check whether this decoder supports {@link #headerValue()}. This is the case for the multipart decoder, but not
     * the {@code application/x-www-form-urlencoded} decoder. For {@code application/x-www-form-urlencoded}, only
     * {@link #parsedHeaderValue()} is supported (and always returns {@link ContentDisposition}).
     * <p>
     * This value never changes for a single decoder.
     *
     * @return {@code true} iff {@link #headerValue()} is supported
     */
    default boolean hasUnparsedHeaderValue() {
        return true;
    }

    /**
     * If the last event was a {@link Event#HEADER}, get the header value as a String. Only supported for multipart, so
     * check with {@link #hasUnparsedHeaderValue()} beforehand.
     *
     * @return The header value
     * @throws IllegalStateException If the last event was not a header
     */
    default String headerValue() {
        throw new IllegalStateException("Not a header");
    }

    /**
     * If the last event was a {@link Event#HEADER}, and the header contains a special complex value, return a parsed
     * representation of that value. See {@link ParsedHeaderValue} for information on special headers.
     *
     * @return The parsed header value, or {@code null} if this is not a special header
     * @throws IllegalStateException If the last event was not a header
     * @see ParsedHeaderValue
     */
    default ParsedHeaderValue parsedHeaderValue() {
        return null;
    }

    /**
     * If the last event was a {@link Event#CONTENT}, get the content buffer. Must only be called once.
     * <p>
     * This method will decode the content, if necessary. For example, for {@code application/x-www-form-urlencoded},
     * it will perform percent decoding. In the future, it may also decode encoded multipart fields like base64, but
     * this is currently unsupported.
     *
     * @return The content
     * @throws IllegalStateException If the last event was not {@link Event#CONTENT}, or if this method has already
     *                               been called
     * @throws ErrorDataDecoderException On invalid input
     */
    Send<Buffer> decodedContent();

    /**
     * If the last event was a {@link Event#CONTENT}, get the string value of the content buffer with the configured
     * charset. Shortcut for {@code decodedContent().toString(charset)}
     *
     * @return The content
     * @throws IllegalStateException If the last event was not {@link Event#CONTENT}, or if this method has already
     *                               been called
     * @throws ErrorDataDecoderException On invalid input
     * @see #decodedContent()
     */
    String decodedContentString();

    /**
     * Close this decoder, releasing any remaining buffers.
     */
    @Override
    void close();

    /**
     * Event types.
     */
    enum Event {
        /**
         * Begin a new field. Fired exactly once per field.
         */
        BEGIN_FIELD,
        /**
         * A field header. May be fired multiple times, or never, per field.<p>
         * The {@code application/x-www-form-urlencoded} parser emits exactly one "mock" header per field. This header
         * does not permit raw access using {@link #headerValue()}, only structured access using
         * {@link #parsedHeaderValue()}. The {@link #parsedHeaderValue()} is always a {@link ContentDisposition} that
         * has the field name as its {@link ContentDisposition#name()}.
         */
        HEADER,
        /**
         * End of headers, start of content. Fired exactly once per field.
         */
        HEADERS_COMPLETE,
        /**
         * End of headers for a mixed part. Now come the pieces of the mixed part.
         */
        BEGIN_MIXED,
        /**
         * A piece of field content. May be fired multiple times, or never, per field. Boundaries between different
         * content buffers have no meaning, the caller should treat all content events as a combined content.
         */
        CONTENT,
        /**
         * End of this field. Fired exactly once per field.
         */
        FIELD_COMPLETE
    }

    final class Builder {
        private static final int DEFAULT_UNDECODED_LIMIT = 4096;

        int undecodedLimit = DEFAULT_UNDECODED_LIMIT;
        int compactionThreshold = HttpPostRequestDecoder.DEFAULT_DISCARD_THRESHOLD;
        Charset charset = HttpConstants.DEFAULT_CHARSET;
        int maxFields = 128;

        Builder() {
        }

        /**
         * Set the maximum number of undecoded bytes when a new buffer is added to this decoder. You may
         * {@link #add(Send) add} buffers of arbitrary size to the decoder, but at most this number must remain in the
         * buffer before the next add call.
         *
         * @param undecodedLimit The limit
         * @return This builder
         */
        public Builder undecodedLimit(int undecodedLimit) {
            if (undecodedLimit < 0) {
                // compatibility
                undecodedLimit = Integer.MAX_VALUE;
            }
            this.undecodedLimit = undecodedLimit;
            return this;
        }

        /**
         * Set the threshold when the input buffer should be compacted. This will ensure that the memory used for the
         * input buffer does not exceed roughly the sum of this {@code compactionThreshold} and the maximum of the
         * {@link #add added} buffer size and the {@link #undecodedLimit}.
         *
         * @param compactionThreshold The threshold for the input buffer capacity to start compaction, or a negative
         *                            value to disable compaction
         * @return This builder
         */
        public Builder compactionThreshold(int compactionThreshold) {
            this.compactionThreshold = compactionThreshold;
            return this;
        }

        /**
         * Set the default charset for this decoder.
         *
         * @param charset The charset
         * @return This builder
         */
        public Builder charset(Charset charset) {
            this.charset = Objects.requireNonNull(charset, "charset");
            return this;
        }

        /**
         * Maximum number of fields to allow.
         *
         * @param maxFields The maximum number of fields
         * @return This builder
         */
        public Builder maxFields(int maxFields) {
            if (maxFields < 0) {
                // compatibility
                maxFields = Integer.MAX_VALUE;
            }
            this.maxFields = maxFields;
            return this;
        }

        /**
         * Create a new multipart decoder for the given multipart boundary (excluding the two preceding dashes).
         *
         * @param boundary The boundary
         * @return A multipart decoder
         */
        public PostBodyDecoder forMultipartBoundary(String boundary) {
            return forBoundary0("--" + boundary);
        }

        MultipartDecoder forBoundary0(String boundary) {
            return new MultipartDecoder(this, boundary);
        }

        /**
         * Create a new {@code application/x-www-form-urlencoded} decoder.
         *
         * @return The decoder
         */
        public PostBodyDecoder forUrlEncodedData() {
            return new UrlEncodedDecoder(this);
        }
    }
}
