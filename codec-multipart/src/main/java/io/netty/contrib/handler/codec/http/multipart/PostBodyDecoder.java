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
     * {@code (BEGIN_FIELD HEADER* HEADERS_COMPLETE CONTENT* FIELD_COMPLETE)*}
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
     *
     * @return {@code true} iff {@link #headerValue()} is supported
     */
    default boolean hasUnparsedHeaderValue() {
        return true;
    }

    /**
     * If the last event was a {@link Event#HEADER}, get the header value.
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
     * If the last event was a {@link Event#CONTENT}, get the content buffer. Should only be called once.
     *
     * @return The content
     * @throws IllegalStateException If the last event was not {@link Event#CONTENT}, or if this method has already
     *                               been called
     */
    Send<Buffer> decodedContent();

    /**
     * If the last event was a {@link Event#CONTENT}, get the string value of the content buffer with the configured
     * charset.
     *
     * @return The content
     * @throws IllegalStateException If the last event was not {@link Event#CONTENT}, or if this method has already
     *                               been called
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
        BEGIN_FIELD,
        HEADER,
        HEADERS_COMPLETE,
        CONTENT,
        FIELD_COMPLETE
    }

    final class Builder {
        private static final int DEFAULT_UNDECODED_LIMIT = 4096;

        private int undecodedLimit = DEFAULT_UNDECODED_LIMIT;
        private Charset charset = HttpConstants.DEFAULT_CHARSET;

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
            this.undecodedLimit = undecodedLimit;
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
         * Create a new multipart decoder for the given multipart boundary (excluding the two preceding dashes).
         *
         * @param boundary The boundary
         * @return A multipart decoder
         */
        public PostBodyDecoder forMultipartBoundary(String boundary) {
            return forBoundary0("--" + boundary);
        }

        MultipartDecoder forBoundary0(String boundary) {
            return new MultipartDecoder(boundary, charset, undecodedLimit);
        }

        public PostBodyDecoder forUrlEncodedData() {
            return new UrlEncodedDecoder(charset, undecodedLimit);
        }
    }
}
