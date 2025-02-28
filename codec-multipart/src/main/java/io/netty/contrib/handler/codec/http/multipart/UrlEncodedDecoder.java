package io.netty.contrib.handler.codec.http.multipart;

import io.netty5.buffer.Buffer;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.util.ByteProcessor;
import io.netty5.util.Send;
import io.netty5.util.internal.StringUtil;

import java.nio.charset.Charset;

final class UrlEncodedDecoder extends AbstractDecoder {
    private static final ByteProcessor FIND_KEY_END = value -> value != '=' && value != '&';
    private static final ByteProcessor FIND_VALUE_END = value -> value != '&' && value != '\r' && value != '\n';

    private State state = State.KEY;

    private String key;
    private Buffer undecodedContent;

    UrlEncodedDecoder(Charset charset, int undecodedLimit) {
        super(charset, undecodedLimit);
    }

    @Override
    public Event next() {
        while (true) {
            switch (state) {
                case KEY:
                    int keyEnd = buffer.openCursor().process(FIND_KEY_END);
                    if (keyEnd >= 0) {
                        boolean hasValue;
                        try (Buffer keyBuffer = buffer.readSplit(keyEnd)) {
                            hasValue = buffer.readByte() == '=';
                            if (!hasValue && keyBuffer.readableBytes() == 0) {
                                // Some weird request bodies start with an '&' character, eg: &name=J&age=17.
                                // Just ignore.
                                break;
                            }
                            key = HttpPostStandardRequestDecoder.decodeAttribute(keyBuffer.toString(charset), charset);
                        }
                        if (!hasValue) {
                            // go to just before the '&', it will read as an empty value
                            buffer.readerOffset(buffer.readerOffset() - 1);
                        }
                        state = State.EMIT_HEADER_1;
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
                        undecodedContent.close();
                        undecodedContent = null;
                    }
                    if (buffer != null && buffer.readableBytes() == 0) {
                        buffer.close();
                        buffer = null;
                    }
                    if (buffer == null && !eof) {
                        return null;
                    }
                    int valueEnd = buffer == null ? 0 : buffer.openCursor().process(FIND_VALUE_END);
                    if (valueEnd == 0) {
                        if (buffer == null) {
                            state = State.DISCARD_REMAINING;
                        } else {
                            byte b = buffer.readByte();
                            if (b == '&') {
                                state = State.KEY;
                            } else {
                                buffer.readerOffset(buffer.readerOffset() - 1);
                                state = State.EOL;
                            }
                        }
                        return Event.FIELD_COMPLETE;
                    } else {
                        if (valueEnd < 0 && !eof) {
                            for (int i = buffer.writerOffset() - 1; i >= buffer.readerOffset(); i--) {
                                if (buffer.getByte(i) == '%') {
                                    valueEnd = i - buffer.readerOffset();
                                    if (valueEnd == 0) {
                                        return null;
                                    }
                                    break;
                                }
                            }
                        }

                        if (valueEnd < 0) {
                            undecodedContent = buffer;
                            buffer = null;
                        } else {
                            undecodedContent = buffer.readSplit(valueEnd);
                        }
                        return Event.CONTENT;
                    }
                case EOL:
                    byte first = buffer.getByte(buffer.readerOffset());
                    assert first == '\r' || first == '\n';
                    if (first == '\r') {
                        if (buffer.readableBytes() == 1) {
                            // need to wait for \n to verify line ending
                            return null;
                        }
                        if (buffer.getByte(buffer.readerOffset() + 1) != '\n') {
                            throw new HttpPostRequestDecoder.ErrorDataDecoderException("Bad end of line");
                        }
                    }
                    state = State.DISCARD_REMAINING;
                    // fall-through
                case DISCARD_REMAINING:
                    if (buffer != null) {
                        buffer.close();
                        buffer = null;
                    }
                    return null;
            }
        }
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
    public Send<Buffer> decodedContent() {
        if (undecodedContent == null) {
            throw new IllegalStateException("Not in CONTENT event");
        }
        Buffer b = undecodedContent;
        undecodedContent = null;
        return decodeValuePiece(b);
    }

    private Send<Buffer> decodeValuePiece(Buffer buffer) {
        int wi = buffer.readerOffset();
        for (int ri = wi; ri < buffer.writerOffset(); wi++, ri++) {
            byte b = buffer.getByte(ri);
            if (b == '%' && ri < buffer.writerOffset() - 2) {
                int hi = StringUtil.decodeHexNibble((char) buffer.getByte(ri + 1));
                int lo = StringUtil.decodeHexNibble((char) buffer.getByte(ri + 2));
                if (hi != -1 && lo != -1) {
                    buffer.setByte(wi, (byte) ((hi << 4) + lo));
                    ri += 2;
                    continue;
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
        buffer.writerOffset(wi);
        return buffer.send();
    }

    @Override
    public void close() {
        super.close();
        if (undecodedContent != null) {
            undecodedContent.close();
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
