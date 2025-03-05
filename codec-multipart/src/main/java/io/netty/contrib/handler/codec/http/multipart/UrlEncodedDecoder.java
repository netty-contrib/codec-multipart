package io.netty.contrib.handler.codec.http.multipart;


import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.ByteProcessor;
import io.netty.util.internal.StringUtil;

final class UrlEncodedDecoder extends AbstractDecoder {
    private static final ByteProcessor FIND_KEY_END = value -> value != '=' && value != '&';
    private static final ByteProcessor FIND_VALUE_END = value -> value != '&' && value != '\r' && value != '\n';

    private State state = State.KEY;

    private String key;
    private ByteBuf undecodedContent;

    boolean quirkMode = false;

    UrlEncodedDecoder(Builder builder) {
        super(builder);
    }

    @Override
    public Event next() {
        while (true) {
            switch (state) {
                case KEY:
                    if (buffer == null) {
                        return null;
                    }
                    int keyEnd = buffer.forEachByte(FIND_KEY_END);
                    boolean noValueAtEof = keyEnd == -1 && eof && buffer.readableBytes() > 0;
                    if (noValueAtEof) {
                        keyEnd = buffer.readableBytes();
                    }
                    if (keyEnd >= 0) {
                        boolean hasValue;
                        ByteBuf keyByteBuf = buffer.readRetainedSlice(keyEnd);
                        try {
                            hasValue = !noValueAtEof && buffer.readByte() == '=';
                            if (!hasValue && keyByteBuf.readableBytes() == 0) {
                                // Some weird request bodies start with an '&' character, eg: &name=J&age=17.
                                // Just ignore.
                                break;
                            }
                            if (quirkMode) {
                                // old impl does charset decoding first. this is subtly different wrt invalid sequences
                                key = HttpPostStandardRequestDecoder.decodeAttribute(keyByteBuf.toString(charset), charset);
                            } else {
                                // whatwg spec first does percent decoding, then utf-8 decoding
                                decodeComponent(keyByteBuf);
                                key = keyByteBuf.toString(charset);
                            }
                        } finally {
                            keyByteBuf.release();
                        }
                        if (!hasValue && !noValueAtEof) {
                            // go to just before the '&', it will read as an empty value
                            buffer.readerIndex(buffer.readerIndex() - 1);
                        }
                        state = State.EMIT_HEADER_1;
                        checkNewField();
                        return Event.BEGIN_FIELD;
                    } else {
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
                    boolean endAttribute = valueEnd == 0;
                    if (endAttribute && quirkMode && buffer != null && buffer.readableBytes() == 1 && buffer.getByte(buffer.readerIndex()) == '\r' && !eof) {
                        endAttribute = false;
                    }
                    if (endAttribute) {
                        if (buffer == null) {
                            state = State.DISCARD_REMAINING;
                        } else {
                            byte b = buffer.readByte();
                            if (b == '&') {
                                state = State.KEY;
                            } else if (quirkMode && b == '\r' && buffer.readableBytes() == 0 && !eof) {
                                buffer.readerIndex(buffer.readerIndex() - 1);
                                return null;
                            } else {
                                buffer.readerIndex(buffer.readerIndex() - 1);
                                state = State.EOL;
                                if (quirkMode) {
                                    earlyEolCheck(buffer.readerIndex());
                                }
                            }
                        }
                        return Event.FIELD_COMPLETE;
                    } else {
                        if (quirkMode && !eof && valueEnd >= 0 && buffer.getByte(buffer.readerIndex() + valueEnd) == '\r') {
                            // in quirk mode, delay processing (potentially invalid) trailing escape until we can check
                            // for sure whether a terminating CRLF is valid.
                            int trailing = findTrailingEscape(buffer, buffer.readerIndex() + valueEnd);
                            if (trailing != -1) {
                                if (!earlyEolCheck(buffer.readerIndex() + valueEnd)) {
                                    valueEnd = trailing - buffer.readerIndex();
                                    if (valueEnd == 0) {
                                        return null;
                                    }
                                }
                            }
                        }
                        if (!eof && valueEnd < 0) {
                            int trailing = findTrailingEscape(buffer, buffer.writerIndex());
                            if (trailing != -1) {
                                valueEnd = trailing - buffer.readerIndex();
                                if (valueEnd == 0) {
                                    return null;
                                }
                            }
                        }

                        if (valueEnd < 0) {
                            undecodedContent = buffer;
                            buffer = null;
                        } else if (valueEnd == 0) {
                            return null;
                        } else {
                            undecodedContent = buffer.readRetainedSlice(valueEnd);
                        }
                        if (quirkMode && buffer != null) {
                            earlyEolCheck(buffer.readerIndex());
                        }
                        return Event.CONTENT;
                    }
                case EOL:
                    byte first = buffer.getByte(buffer.readerIndex());
                    assert first == '\r' || first == '\n';
                    if (first == '\r') {
                        if (buffer.readableBytes() == 1) {
                            // need to wait for \n to verify line ending
                            return null;
                        }
                        if (buffer.getByte(buffer.readerIndex() + 1) != '\n') {
                            throw new HttpPostRequestDecoder.ErrorDataDecoderException("Bad end of line");
                        }
                    }
                    state = State.DISCARD_REMAINING;
                    // fall-through
                case DISCARD_REMAINING:
                    if (buffer != null) {
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
     * @throws io.netty.contrib.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException on invalid CRLF
     */
    private boolean earlyEolCheck(int start) {
        assert quirkMode;

        if (buffer.writerIndex() > start + 1 &&
                buffer.getByte(start) == '\r') {
            if (buffer.getByte(start + 1) != '\n') {
                throw new HttpPostRequestDecoder.ErrorDataDecoderException("Bad end of line");
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
        try {
            undecodedContent = null;
            decodeComponent(b);
            return b;
        } catch (Exception e) {
            b.release();
            throw e;
        }
    }

    private void decodeComponent(ByteBuf buffer) {
        int wi = buffer.readerIndex();
        for (int ri = wi; ri < buffer.writerIndex(); wi++, ri++) {
            byte b = buffer.getByte(ri);
            if (b == '%') {
                if (ri < buffer.writerIndex() - 2) {
                    int hi = StringUtil.decodeHexNibble((char) buffer.getByte(ri + 1));
                    int lo = StringUtil.decodeHexNibble((char) buffer.getByte(ri + 2));
                    if (hi != -1 && lo != -1) {
                        buffer.setByte(wi, (byte) ((hi << 4) + lo));
                        ri += 2;
                        continue;
                    } else if (quirkMode) {
                        // whatwg URL spec allows this
                        failPercentDecode();
                    }
                } else if (quirkMode) {
                    // whatwg URL spec allows this
                    failPercentDecode();
                }
            }
            if (b == '+') {
                buffer.setByte(wi, (byte) ' ');
                continue;
            }
            if (ri != wi) {
                buffer.setByte(wi, b);
            }
        }
        buffer.writerIndex(wi);
    }

    private void failPercentDecode() {
        assert quirkMode;
        if (state == State.KEY) {
            throw new HttpPostRequestDecoder.ErrorDataDecoderException("Bad string");
        } else {
            throw new HttpPostRequestDecoder.ErrorDataDecoderException("Invalid hex byte");
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

    private enum State {
        KEY,

        EMIT_HEADER_1,
        EMIT_HEADER_2,

        VALUE,

        EOL,
        DISCARD_REMAINING,
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
