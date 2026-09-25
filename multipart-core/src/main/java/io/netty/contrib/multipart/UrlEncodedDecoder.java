/*
 * Copyright 2025 The Netty Project
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
package io.netty.contrib.multipart;


import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ByteProcessor;
import io.netty.util.internal.StringUtil;

import java.nio.charset.Charset;
import java.util.EnumSet;
import java.util.Set;

final class UrlEncodedDecoder extends AbstractDecoder implements VintageAccess.UrlEncodedDecoder {
    private static final ByteProcessor FIND_KEY_END = value -> value != '=' && value != '&';
    private static final ByteProcessor FIND_KEY_END_OR_EOL =
            value -> value != '=' && value != '&' && value != '\r' && value != '\n';
    private static final ByteProcessor FIND_VALUE_END = value -> value != '&' && value != '\r' && value != '\n';
    private static final ByteProcessor FIND_ESCAPE = value -> value != '%' && value != '+';

    private State state = State.KEY;
    /**
     * Number of bytes after the reader index that have already been scanned for the end of the current key without
     * finding it. This is relative to the reader index so that it stays valid when the buffer is compacted.
     */
    private int keyScanOffset;

    private String key;
    private boolean keyWithoutValue;
    private ByteBuf undecodedContent;

    private final Set<DecoderQuirk> quirks;
    private final ByteProcessor findKeyEnd;

    UrlEncodedDecoder(Builder builder) {
        super(builder);
        this.quirks = EnumSet.copyOf(builder.multipartQuirks);
        this.findKeyEnd = quirks.contains(DecoderQuirk.LENIENT_END_OF_LINE) ? FIND_KEY_END : FIND_KEY_END_OR_EOL;
    }

    @Override
    public Event next() {
        while (true) {
            switch (state) {
                case KEY:
                    if (buffer == null) {
                        return null;
                    }
                    // resume the delimiter search where the previous call left off. Key bytes are only decoded
                    // once the key is complete, so no bytes need to be rescanned for percent escapes. Without
                    // LENIENT_END_OF_LINE, the search also stops at a line ending.
                    int keyEnd = buffer.forEachByte(buffer.readerIndex() + keyScanOffset,
                            buffer.readableBytes() - keyScanOffset, findKeyEnd);
                    boolean noValueAtEof = keyEnd == -1 && eof && buffer.readableBytes() > 0;
                    if (noValueAtEof) {
                        keyEnd = buffer.writerIndex();
                    }
                    if (keyEnd >= 0) {
                        keyScanOffset = 0;
                        boolean hasValue;
                        ByteBuf keyByteBuf = buffer.readRetainedSlice(keyEnd - buffer.readerIndex());
                        try {
                            byte terminator = noValueAtEof ? 0 : buffer.readByte();
                            hasValue = terminator == '=';
                            if (!hasValue && keyByteBuf.readableBytes() == 0) {
                                if (terminator == '\r' || terminator == '\n') {
                                    // line ending without a preceding field (e.g. "a=b&\r\n"). Only possible
                                    // without LENIENT_END_OF_LINE.
                                    buffer.readerIndex(buffer.readerIndex() - 1);
                                    state = State.EOL;
                                    break;
                                }
                                // Some weird request bodies start with an '&' character, eg: &name=J&age=17.
                                // Just ignore.
                                break;
                            }
                            if (quirks.contains(DecoderQuirk.EARLY_DECODE)) {
                                // old impl does charset decoding first. this is subtly different wrt invalid sequences
                                key = decodeAttribute(keyByteBuf.toString(charset), charset);
                            } else {
                                // whatwg spec first does percent decoding, then utf-8 decoding
                                ByteBuf undecodedKey = keyByteBuf;
                                // decodeComponent takes ownership
                                keyByteBuf = null;
                                ByteBuf decodedKey = decodeComponent(undecodedKey, true);
                                try {
                                    key = decodedKey.toString(charset);
                                } finally {
                                    decodedKey.release();
                                }
                            }
                        } finally {
                            if (keyByteBuf != null) {
                                keyByteBuf.release();
                            }
                        }
                        keyWithoutValue = !hasValue;
                        if (!hasValue && !noValueAtEof) {
                            // go to just before the '&' (or line ending), it will read as an empty value
                            buffer.readerIndex(buffer.readerIndex() - 1);
                        }
                        state = State.EMIT_HEADER_1;
                        checkNewField();
                        return Event.BEGIN_FIELD;
                    } else {
                        keyScanOffset = buffer.readableBytes();
                        return null;
                    }

                    // these states emit the single fake disposition header
                case EMIT_HEADER_1:
                    state = State.EMIT_HEADER_2;
                    return Event.HEADER;
                case EMIT_HEADER_2:
                    state = State.VALUE;
                    return Event.HEADERS_COMPLETE;
                case VALUE:
                    if (undecodedContent != null) {
                        undecodedContent.release();
                        undecodedContent = null;
                    }
                    if (buffer != null && buffer.readableBytes() == 0) {
                        buffer.release();
                        buffer = null;
                    }
                    if (buffer == null && !eof) {
                        return null;
                    }
                    int valueEnd = buffer == null ? 0 : buffer.forEachByte(FIND_VALUE_END);
                    boolean endAttribute = buffer == null || valueEnd == buffer.readerIndex();
                    if (endAttribute) {
                        if (buffer == null) {
                            state = State.DISCARD_REMAINING;
                        } else {
                            byte b = buffer.readByte();
                            if (b == '&') {
                                state = State.KEY;
                            } else if (quirks.contains(DecoderQuirk.WAIT_ON_CR) && b == '\r' &&
                                    buffer.readableBytes() == 0 && !eof) {
                                buffer.readerIndex(buffer.readerIndex() - 1);
                                return null;
                            } else {
                                // \r or \n
                                buffer.readerIndex(buffer.readerIndex() - 1);
                                state = State.EOL;
                                if (quirks.contains(DecoderQuirk.EARLY_CRLF_CHECK)) {
                                    earlyEolCheck(buffer.readerIndex());
                                }
                            }
                        }
                        return Event.FIELD_COMPLETE;
                    } else {
                        if (!quirks.contains(DecoderQuirk.EARLY_CRLF_CHECK) && !eof && valueEnd < 0) {
                            int trailing = findTrailingEscape(buffer, buffer.writerIndex());
                            if (trailing != -1) {
                                valueEnd = trailing;
                                if (valueEnd == buffer.readerIndex()) {
                                    return null;
                                }
                            }
                        }

                        if (valueEnd < 0) {
                            undecodedContent = buffer;
                            buffer = null;
                        } else if (valueEnd == buffer.readerIndex()) {
                            return null;
                        } else {
                            undecodedContent = buffer.readBytes(valueEnd - buffer.readerIndex());
                        }
                        if (quirks.contains(DecoderQuirk.EARLY_CRLF_CHECK) && buffer != null) {
                            earlyEolCheck(buffer.readerIndex());
                        }
                        return Event.CONTENT;
                    }
                case EOL:
                    // A line ending (CRLF or bare LF) terminates the form. Without LENIENT_END_OF_LINE, it must
                    // be followed by the end of input, and a lone CR at the end of input is rejected.
                    boolean lenientEol = quirks.contains(DecoderQuirk.LENIENT_END_OF_LINE);
                    byte first = buffer.getByte(buffer.readerIndex());
                    assert first == '\r' || first == '\n';
                    if (first == '\r') {
                        if (buffer.readableBytes() == 1) {
                            if (!eof) {
                                // need to wait for \n to verify line ending
                                return null;
                            }
                            if (!lenientEol) {
                                throw new FormDecoderException("Bad end of line");
                            }
                        } else if (buffer.getByte(buffer.readerIndex() + 1) != '\n') {
                            throw new FormDecoderException("Bad end of line");
                        } else {
                            buffer.skipBytes(2);
                        }
                    } else {
                        buffer.skipBytes(1);
                    }
                    if (!lenientEol) {
                        state = State.AFTER_EOL;
                        break;
                    }
                    state = State.DISCARD_REMAINING;
                    // fall-through
                case DISCARD_REMAINING:
                    if (buffer != null) {
                        buffer.release();
                        buffer = null;
                    }
                    return null;
                case AFTER_EOL:
                    if (buffer != null) {
                        if (buffer.isReadable()) {
                            throw new FormDecoderException("Unexpected data after end of line");
                        }
                        buffer.release();
                        buffer = null;
                    }
                    return null;
            }
        }
    }

    /**
     * Find a trailing unfinished escape sequence in the given buffer, e.g. {@code %0}.
     *
     * @param buffer The buffer
     * @param end The end index in the buffer to start scanning at (exclusive)
     * @return The index of the unfinished escape, or {@code -1} if there is no unfinished escape
     */
    private static int findTrailingEscape(ByteBuf buffer, int end) {
        if (buffer.readerIndex() <= end - 1 && buffer.getByte(end - 1) == '%') {
            return end - 1;
        }
        if (buffer.readerIndex() <= end - 2 && buffer.getByte(end - 2) == '%') {
            return end - 2;
        }
        return -1;
    }

    /**
     * For quirk mode: Early check for valid CRLF.
     *
     * @param start The position of the potential CRLF
     * @return {@code true} if this is certainly a valid CRLF, {@code false} if not a CRLF or just a CR for now
     * @throws FormDecoderException on invalid CRLF
     */
    private boolean earlyEolCheck(int start) {
        assert quirks.contains(DecoderQuirk.EARLY_CRLF_CHECK);

        if (buffer.writerIndex() > start + 1 &&
                buffer.getByte(start) == '\r') {
            if (buffer.getByte(start + 1) != '\n') {
                throw new FormDecoderException("Bad end of line");
            } else {
                return true;
            }
        }
        return false;
    }

    @Override
    public CharSequence headerName() {
        if (state != State.EMIT_HEADER_2) {
            throw new IllegalStateException("Not in HEADER event");
        }
        return HttpHeaderNames.CONTENT_DISPOSITION;
    }

    @Override
    public boolean hasUnparsedHeaderValue() {
        return false;
    }

    @Override
    public String headerValue() {
        throw new UnsupportedOperationException("Not supported for UrlEncodedDecoder, please use parsedHeaderValue");
    }

    @Override
    public ParsedHeaderValue parsedHeaderValue() {
        if (state != State.EMIT_HEADER_2) {
            throw new IllegalStateException("Not in HEADER event");
        }
        return new MockContentDisposition(key);
    }

    @Override
    public ByteBuf decodedContent() {
        if (undecodedContent == null) {
            throw new IllegalStateException("Not in CONTENT event");
        }
        ByteBuf b = undecodedContent;
        undecodedContent = null;
        return decodeComponent(b, false);
    }

    @Override
    public ByteBuf undecodedContent() {
        ByteBuf b = undecodedContent;
        this.undecodedContent = null;
        return b;
    }

    /**
     * Percent-decode the given buffer. The input buffer is never modified, because it may be shared with the caller
     * (e.g. a slice of a buffer passed to {@link #add(ByteBuf)}) or be read-only. If the input contains no escape
     * sequences, it is returned as-is. Otherwise, the decoded data is written to a newly allocated buffer.
     *
     * @param buffer The buffer to decode. Ownership is transferred to this method, also on failure
     * @param key Whether this is a key, for the error message
     * @return The decoded buffer, owned by the caller
     */
    @Override
    public ByteBuf decodeComponent(ByteBuf buffer, boolean key) {
        int firstEscape = buffer.forEachByte(FIND_ESCAPE);
        if (firstEscape == -1) {
            return buffer;
        }
        ByteBuf decoded = null;
        try {
            decoded = buffer.alloc().buffer(buffer.readableBytes());
            decoded.writeBytes(buffer, buffer.readerIndex(), firstEscape - buffer.readerIndex());
            for (int ri = firstEscape; ri < buffer.writerIndex(); ri++) {
                byte b = buffer.getByte(ri);
                if (b == '%') {
                    if (ri < buffer.writerIndex() - 2) {
                        int hi = StringUtil.decodeHexNibble((char) buffer.getByte(ri + 1));
                        int lo = StringUtil.decodeHexNibble((char) buffer.getByte(ri + 2));
                        if (hi != -1 && lo != -1) {
                            decoded.writeByte((hi << 4) + lo);
                            ri += 2;
                            continue;
                        } else if (quirks.contains(DecoderQuirk.REFUSE_NON_HEX_PERCENT_DECODE)) {
                            // whatwg URL spec allows this
                            failPercentDecode(key);
                        }
                    } else if (quirks.contains(DecoderQuirk.REFUSE_SHORT_PERCENT_DECODE)) {
                        // whatwg URL spec allows this
                        failPercentDecode(key);
                    }
                }
                decoded.writeByte(b == '+' ? ' ' : b);
            }
            ByteBuf result = decoded;
            decoded = null;
            return result;
        } finally {
            buffer.release();
            if (decoded != null) {
                decoded.release();
            }
        }
    }

    private void failPercentDecode(boolean key) {
        if (key) {
            throw new FormDecoderException("Bad string");
        } else {
            throw new FormDecoderException("Invalid hex byte");
        }
    }

    @Override
    public void close() {
        super.close();
        if (undecodedContent != null) {
            undecodedContent.release();
            undecodedContent = null;
        }
    }

    /**
     * Decode component
     *
     * @return the decoded component
     */
    private static String decodeAttribute(String s, Charset charset) {
        try {
            return QueryStringDecoder.decodeComponent(s, charset);
        } catch (IllegalArgumentException e) {
            throw new FormDecoderException("Bad string: '" + s + '\'', e);
        }
    }

    @Override
    public int getCompactionThreshold() {
        return compactionThreshold;
    }

    @Override
    public void setCompactionThreshold(int compactionThreshold) {
        this.compactionThreshold = compactionThreshold;
    }

    @Override
    public boolean isEof() {
        return eof;
    }

    @Override
    public boolean isKeyWithoutValue() {
        return keyWithoutValue;
    }

    private enum State {
        KEY,

        EMIT_HEADER_1,
        EMIT_HEADER_2,

        VALUE,

        EOL,
        /**
         * Legacy ({@link DecoderQuirk#LENIENT_END_OF_LINE}): silently discard anything after the line ending.
         */
        DISCARD_REMAINING,
        /**
         * After the line ending, only the end of input is permitted.
         */
        AFTER_EOL,
    }

    private static final class MockContentDisposition implements ContentDisposition {
        private final String name;

        MockContentDisposition(String name) {
            this.name = name;
        }

        @Override
        public String fileName() {
            return null;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MockContentDisposition && ((MockContentDisposition) o).name.equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }
}
