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

import io.netty5.buffer.Buffer;
import io.netty5.buffer.ByteCursor;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpHeaderValues;
import io.netty5.util.ByteProcessor;
import io.netty5.util.Send;
import io.netty5.util.internal.StringUtil;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

final class MultipartDecoder extends AbstractDecoder {
    /**
     * When enabled, try to reproduce exactly the weird behavior of the old {@link HttpPostMultipartRequestDecoder}
     * implementation.
     */
    boolean quirkMode = false;

    private final String multipartDataBoundary;

    private State state = State.HEADERDELIMITER;

    private Buffer undecodedPartData;
    private Charset partCharset;
    private String mixedBoundary;
    private boolean mixedHeader;
    private String headerKey;
    private String headerValue;
    private long receivedLength;

    /**
     * Old implementation would revisit all headers on the next iteration if there is an end-of-chunk during header
     * parsing.
     */
    private int quirkHeaderStart = -1;
    private String[] quirkHeader;
    /**
     * Old implementation would fail late on invalid charset.
     */
    private String quirkPartCharset;
    private long quirkDefinedLength;

    MultipartDecoder(Builder builder, String multipartDataBoundary) {
        super(builder);
        this.multipartDataBoundary = multipartDataBoundary;

        clearPartData();
    }

    @Override
    public void add(Send<Buffer> buffer) {
        super.add(buffer);
        if (quirkMode) {
            quirkHeaderStart = -1;
        }
    }

    private void clearPartData() {
        if (undecodedPartData != null) {
            undecodedPartData.close();
            undecodedPartData = null;
        }
        partCharset = null;
        receivedLength = 0;
        if (quirkMode) {
            quirkDefinedLength = 0;
            quirkPartCharset = null;
        }
    }

    @Override
    public Event next() {
        while (true) {
            switch (state) {
                case HEADERDELIMITER:
                    if (buffer == null) {
                        return null;
                    }
                    DelimiterType delimiter = findMultipartDelimiter(mixedBoundary == null ? multipartDataBoundary : mixedBoundary);
                    if (delimiter == null) {
                        return null;
                    } else if (delimiter == DelimiterType.DISPOSITION) {
                        state = State.DISPOSITION;
                        checkNewField();
                        return Event.BEGIN_FIELD;
                    } else {
                        if (mixedBoundary == null) {
                            state = State.PREEPILOGUE;
                        } else {
                            mixedBoundary = null;
                            return Event.FIELD_COMPLETE;
                        }
                        break;
                    }
                case DISPOSITION:
                    if (buffer == null) {
                        return null;
                    }
                    if (!HttpPostMultipartRequestDecoder.skipOneLine(buffer)) {
                        int readerIndex = buffer.readerOffset();
                        if (quirkMode && quirkHeaderStart == -1) {
                            quirkHeaderStart = readerIndex;
                        }
                        String newline;
                        try {
                            HttpPostMultipartRequestDecoder.skipControlCharacters(buffer, quirkMode);
                            newline = HttpPostMultipartRequestDecoder.readLineOptimized(buffer, charset);
                        } catch (HttpPostRequestDecoder.NotEnoughDataDecoderException ignored) {
                            if (quirkMode) {
                                buffer.readerOffset(quirkHeaderStart);
                                if (mixedHeader) {
                                    mixedBoundary = null;
                                    mixedHeader = false;
                                }
                            } else {
                                buffer.readerOffset(readerIndex);
                            }
                            return null;
                        }
                        if (quirkMode) {
                            parseHeaderQuirk(newline);
                            if (mixedHeader) {
                                // quirk mode does not parse more headers after the multipart/mixed header
                                mixedHeader = false;
                                state = State.HEADERDELIMITER;
                                return Event.BEGIN_MIXED;
                            }
                        } else {
                            parseHeader(newline);
                        }
                        return Event.HEADER;
                    } else {
                        // no more headers
                        if (quirkMode) {
                            quirkHeaderStart = -1;
                        }
                        if (mixedHeader) {
                            mixedHeader = false;
                            state = State.HEADERDELIMITER;
                            return Event.BEGIN_MIXED;
                        } else {
                            state = State.CONTENT;
                            return Event.HEADERS_COMPLETE;
                        }
                    }
                case CONTENT:
                    if (undecodedPartData != null) {
                        undecodedPartData.close();
                        undecodedPartData = null;
                    }
                    if (buffer == null) {
                        return null;
                    }
                    int normal = findDelimiter(buffer, multipartDataBoundary.getBytes(currentCharset()));
                    boolean earlyMixedEnd = false;
                    if (mixedBoundary != null) {
                        int fullEnd = normal;
                        normal = findDelimiter(buffer, mixedBoundary.getBytes(currentCharset()));
                        if (fullEnd < normal) {
                            // we found the multipart delimiter before the mixed delimiter
                            earlyMixedEnd = true;
                            normal = fullEnd;
                        }
                    }
                    if (normal < 0) {
                        clearPartData();
                        state = State.HEADERDELIMITER;
                        if (earlyMixedEnd) {
                            state = State.EARLY_MIXED_END;
                        }
                        return Event.FIELD_COMPLETE;
                    } else {
                        if (normal == 0) {
                            return null;
                        }
                        undecodedPartData = buffer.readSplit(normal);
                        addReceivedLength(normal);
                        return Event.CONTENT;
                    }
                case EARLY_MIXED_END:
                    if (buffer == null) {
                        return null;
                    }
                    state = State.HEADERDELIMITER;
                    return Event.FIELD_COMPLETE;
                default:
                    return null;
            }
        }
    }

    @Override
    public CharSequence headerName() {
        if (headerKey == null) {
            throw new IllegalStateException("Not in a header");
        }
        return headerKey;
    }

    @Override
    public String headerValue() {
        if (headerKey == null) {
            throw new IllegalStateException("Not in a header");
        }
        return headerValue;
    }

    @Override
    public ParsedHeaderValue parsedHeaderValue() {
        if (headerKey == null) {
            throw new IllegalStateException("Not in a header");
        }
        if (HttpHeaderNames.CONTENT_DISPOSITION.contentEqualsIgnoreCase(headerKey)) {
            return new MultipartContentDisposition(headerValue);
        }
        return null;
    }

    private void parseHeader(String headerLine) {
        // adapted from HttpPostMultipartRequestDecoder.splitMultipartHeader
        int nameStart = HttpPostBodyUtil.findNonWhitespace(headerLine, 0);
        int nameEnd;
        for (nameEnd = nameStart; nameEnd < headerLine.length(); nameEnd++) {
            char c = headerLine.charAt(nameEnd);
            if (c == ':' || Character.isWhitespace(c)) {
                break;
            }
        }
        int colonEnd;
        for (colonEnd = nameEnd; colonEnd < headerLine.length(); colonEnd++) {
            if (headerLine.charAt(colonEnd) == ':') {
                colonEnd++;
                break;
            }
        }
        int valueStart = HttpPostBodyUtil.findNonWhitespace(headerLine, colonEnd);
        int valueEnd = HttpPostBodyUtil.findEndOfString(headerLine);
        if (valueEnd < valueStart) {
            throw new HttpPostRequestDecoder.ErrorDataDecoderException("Invalid header");
        }
        headerKey = headerLine.substring(nameStart, nameEnd);
        headerValue = headerLine.substring(valueStart, valueEnd);

        if (HttpHeaderNames.CONTENT_TRANSFER_ENCODING.contentEqualsIgnoreCase(headerKey)) {
            if (HttpPostBodyUtil.TransferEncodingMechanism.BIT7.value().equals(headerValue)) {
                if (partCharset != null) {
                    partCharset = StandardCharsets.US_ASCII;
                }
            } else if (HttpPostBodyUtil.TransferEncodingMechanism.BIT8.value().equals(headerValue)) {
                if (partCharset != null) {
                    partCharset = StandardCharsets.ISO_8859_1;
                }
            } else if (HttpPostBodyUtil.TransferEncodingMechanism.BINARY.value().equals(headerValue)) {
                // no charset
            } else {
                throw new HttpPostRequestDecoder.ErrorDataDecoderException("TransferEncoding Unknown: " + headerValue);
            }
        } else if (HttpHeaderNames.CONTENT_TYPE.contentEqualsIgnoreCase(headerKey)) {
            var parser = new ParmParser() {
                boolean mixed;

                boolean charset;
                boolean boundary;

                @Override
                void visitType(String type) {
                    mixed = HttpHeaderValues.MULTIPART_MIXED.contentEqualsIgnoreCase(type);
                    if (mixed && mixedBoundary != null) {
                        throw new HttpPostRequestDecoder.ErrorDataDecoderException("Mixed Multipart found in a previous Mixed Multipart");
                    }
                }

                @Override
                boolean visitAttribute(String attribute) {
                    boundary = mixed && HttpHeaderValues.BOUNDARY.contentEqualsIgnoreCase(attribute);
                    charset = !mixed && HttpHeaderValues.CHARSET.contentEqualsIgnoreCase(attribute);
                    return boundary || charset;
                }

                @Override
                void visitAttributeValue(String attribute, String value) {
                    if (boundary) {
                        mixedBoundary = "--" + value;
                    } else if (charset) {
                        try {
                            partCharset = Charset.forName(value);
                        } catch (UnsupportedCharsetException | IllegalCharsetNameException e) {
                            throw new HttpPostRequestDecoder.ErrorDataDecoderException(e);
                        }
                    }
                }
            };
            parser.run(headerValue);
            if (parser.mixed) {
                if (mixedBoundary == null) {
                    throw new HttpPostRequestDecoder.ErrorDataDecoderException("No boundary found for multipart/mixed");
                }
                mixedHeader = true;
            }
        }
    }

    private void parseHeaderQuirk(String headerLine) {
        quirkHeader = HttpPostMultipartRequestDecoder.splitMultipartHeader(headerLine);
        if (HttpHeaderNames.CONTENT_TRANSFER_ENCODING.contentEqualsIgnoreCase(quirkHeader[0])) {
            String mechanismName = HttpPostMultipartRequestDecoder.cleanString(quirkHeader[1]);
            if (HttpPostBodyUtil.TransferEncodingMechanism.BIT7.value().equals(mechanismName)) {
                if (partCharset != null) {
                    partCharset = StandardCharsets.US_ASCII;
                }
            } else if (HttpPostBodyUtil.TransferEncodingMechanism.BIT8.value().equals(mechanismName)) {
                if (partCharset != null) {
                    partCharset = StandardCharsets.ISO_8859_1;
                }
            }
        } else if (HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(quirkHeader[0])) {
            try {
                quirkDefinedLength = Long.parseLong(HttpPostMultipartRequestDecoder.cleanString(quirkHeader[1]));
            } catch (NumberFormatException e) {
                quirkDefinedLength = 0;
            }
        } else if (HttpHeaderNames.CONTENT_TYPE.contentEqualsIgnoreCase(quirkHeader[0])) {
            if (quirkHeader.length == 1 && !quirkMode) {
                throw new HttpPostRequestDecoder.ErrorDataDecoderException("Invalid Content-Type header");
            }
            if (HttpHeaderValues.MULTIPART_MIXED.contentEqualsIgnoreCase(quirkHeader[1])) {
                if (mixedBoundary != null) {
                    throw new HttpPostRequestDecoder.ErrorDataDecoderException("Mixed Multipart found in a previous Mixed Multipart");
                }
                String values = StringUtil.substringAfter(quirkHeader[2], '=');
                mixedBoundary = "--" + values;
                mixedHeader = true;
            } else {
                for (int i = 1; i < quirkHeader.length; i++) {
                    final String charsetHeader = HttpHeaderValues.CHARSET.toString();
                    if (quirkHeader[i].regionMatches(true, 0, charsetHeader, 0, charsetHeader.length())) {
                        quirkPartCharset = StringUtil.substringAfter(quirkHeader[i], '=');
                    }
                }
            }
        }
    }

    String[] getQuirkHeader() {
        return quirkHeader;
    }

    Send<Buffer> sendUndecodedPartContent() {
        return undecodedPartData.send();
    }

    @Override
    public Send<Buffer> decodedContent() {
        if (undecodedPartData == null) {
            throw new IllegalStateException("Not a CONTENT event");
        }
        // we don't support content-transfer-encodings that need actual decoding
        return sendUndecodedPartContent();
    }

    boolean isMixed() {
        return mixedBoundary != null;
    }

    int getCurrentAllocatedCapacity() {
        int n = 0;
        if (buffer != null) {
            n += buffer.capacity();
        }
        if (undecodedPartData != null) {
            n += undecodedPartData.capacity();
        }
        return n;
    }

    private DelimiterType findMultipartDelimiter(String delimiter) {
        // --AaB03x or --AaB03x--
        int readerIndex = buffer.readerOffset();
        try {
            HttpPostMultipartRequestDecoder.skipControlCharacters(buffer, quirkMode);
        } catch (HttpPostRequestDecoder.NotEnoughDataDecoderException ignored) {
            // todo: do we need to reset here?
            buffer.readerOffset(readerIndex);
            return null;
        }
        HttpPostMultipartRequestDecoder.skipOneLine(buffer);
        String newline;
        try {
            newline = HttpPostMultipartRequestDecoder.readDelimiterOptimized(buffer, delimiter, charset);
        } catch (HttpPostRequestDecoder.NotEnoughDataDecoderException ignored) {
            buffer.readerOffset(readerIndex);
            return null;
        }
        if (newline.equals(delimiter)) {
            return DelimiterType.DISPOSITION;
        }
        if (newline.equals(delimiter + "--")) {
            return DelimiterType.CLOSEDELIMITER;
        }
        buffer.readerOffset(readerIndex);
        throw new HttpPostRequestDecoder.ErrorDataDecoderException("No Multipart delimiter found");
    }

    /**
     * Find the given delimiter, preceded by a newline (CRLF or LF), in the given buffer.
     * <p>
     * If the delimiter is found at the start of the input (readerOffset), this method returns the <i>binary inverse</i>
     * length of the delimiter including the preceding newline. This is the only case where this method returns a
     * negative number.
     * <p>
     * In all other cases, this method returns the number of bytes that can be safely read before reaching the
     * delimiter, or a part of the potential delimiter.
     *
     * @param buffer The buffer to search
     * @param delimiter The delimiter to search for
     * @return The inverse length of the delimiter if found at the start of the buffer, or the number of bytes that can
     * be safely read from the buffer before reaching the delimiter.
     */
    int findDelimiter(Buffer buffer, byte[] delimiter) {
        if ((receivedLength == 0 || quirkMode) && startsWith(buffer.openCursor(), delimiter)) {
            // special case at start of buffer
            return ~delimiter.length;
        }

        int i = buffer.readerOffset();
        int lfOffset = -1;
        while (true) {
            ByteCursor cursor = buffer.openCursor(i, buffer.writerOffset() - i);
            int lf = cursor.process(ByteProcessor.FIND_LF);
            if (lf == -1) {
                if (quirkMode) {
                    int lastLf = quirkLfMatch(buffer, delimiter.length);
                    if (lastLf != -1) {
                        return lastLf;
                    }
                }

                if (lfOffset == -1) {
                    lfOffset = buffer.writerOffset();
                }
                if (buffer.writerOffset() - lfOffset > delimiter.length) {
                    return buffer.readableBytes();
                }
                if (buffer.readerOffset() < lfOffset && buffer.getByte(lfOffset - 1) == '\r') {
                    if (!quirkMode || quirkDefinedLength == receivedLength + buffer.readableBytes() - 1) {
                        lfOffset--;
                    }
                }
                return lfOffset - buffer.readerOffset();
            }
            lfOffset = i + lf;
            boolean crlf = lfOffset > buffer.readerOffset() && buffer.getByte(lfOffset - 1) == '\r';
            if (cursor.bytesLeft() >= delimiter.length && startsWith(cursor, delimiter)) {
                int start = crlf ? lfOffset - 1 : lfOffset;
                if (start == buffer.readerOffset()) {
                    // found at start of buffer.
                    return ~(delimiter.length + (crlf ? 2 : 1));
                } else {
                    return start - buffer.readerOffset();
                }
            }
            i += lf + 1;
        }
    }

    private static boolean startsWith(ByteCursor haystack, byte[] needle) {
        for (byte b : needle) {
            haystack.readByte();
            if (haystack.getByte() != b) {
                return false;
            }
        }
        return true;
    }

    private int quirkLfMatch(Buffer buffer, int delimiterLength) {
        assert quirkMode;
        if (buffer.readableBytes() > 0) {
            int lastLf = buffer.openReverseCursor(buffer.writerOffset() - 1, Math.min(buffer.readableBytes(), delimiterLength + 1))
                    .process(ByteProcessor.FIND_LF);
            if (lastLf != -1) {
                lastLf = buffer.readableBytes() - lastLf - 1;
                if (lastLf > 0 &&
                        lastLf + delimiterLength >= buffer.readableBytes() &&
                        buffer.getByte(buffer.readerOffset() + lastLf - 1) == '\r') {
                    lastLf--;
                }
                return lastLf;
            }
        }
        return -1;
    }

    private void addReceivedLength(int extra) {
        receivedLength += extra;
        if (quirkMode) {
            if (quirkDefinedLength > 0 && quirkDefinedLength < receivedLength) {
                // old implementation extends the defined length to match the received length, if necessary
                quirkDefinedLength = receivedLength;
            }
        }
    }

    private Charset currentCharset() {
        if (quirkMode) {
            if (quirkPartCharset != null) {
                try {
                    return Charset.forName(quirkPartCharset);
                } catch (UnsupportedCharsetException e) {
                    throw new HttpPostRequestDecoder.ErrorDataDecoderException(e);
                }
            }
        }
        if (partCharset != null) {
            return partCharset;
        }
        return charset;
    }

    @Override
    public void close() {
        super.close();
        if (undecodedPartData != null) {
            undecodedPartData.close();
        }
    }

    private enum State {
        HEADERDELIMITER,
        DISPOSITION,
        CONTENT,
        PREEPILOGUE,
        EARLY_MIXED_END,
    }

    private enum DelimiterType {
        DISPOSITION,
        CLOSEDELIMITER
    }

}
