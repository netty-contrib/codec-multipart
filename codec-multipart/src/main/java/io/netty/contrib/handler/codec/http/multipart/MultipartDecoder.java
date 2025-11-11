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

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.ByteProcessor;
import io.netty.util.internal.StringUtil;

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

    private ByteBuf undecodedPartData;
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
    private boolean quirkMixed = false;
    private String[] quirkHeader;
    long quirkDefinedLength;
    Charset quirkPartCharset;

    MultipartDecoder(Builder builder, String multipartDataBoundary) {
        super(builder);
        this.multipartDataBoundary = multipartDataBoundary;

        clearPartData();
    }

    @Override
    public void add(ByteBuf buffer) {
        super.add(buffer);
        if (quirkMode) {
            quirkHeaderStart = -1;
            quirkMixed = false;
        }
    }

    private void clearPartData() {
        if (undecodedPartData != null) {
            undecodedPartData.release();
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
                            if (quirkMode) {
                                quirkDefinedLength = 0;
                                quirkPartCharset = null;
                            }
                            return Event.FIELD_COMPLETE;
                        }
                        break;
                    }
                case DISPOSITION:
                    if (buffer == null) {
                        return null;
                    }
                    if (!HttpPostMultipartRequestDecoder.skipOneLine(buffer)) {
                        int readerIndex = buffer.readerIndex();
                        if (quirkMode && quirkHeaderStart == -1) {
                            quirkHeaderStart = readerIndex;
                        }
                        String newline;
                        try {
                            HttpPostMultipartRequestDecoder.skipControlCharacters(buffer, quirkMode);
                            newline = HttpPostMultipartRequestDecoder.readLineOptimized(buffer, charset);
                        } catch (HttpPostRequestDecoder.NotEnoughDataDecoderException ignored) {
                            if (quirkMode) {
                                buffer.readerIndex(quirkHeaderStart);
                                if (mixedHeader) {
                                    mixedBoundary = null;
                                    mixedHeader = false;
                                }
                            } else {
                                buffer.readerIndex(readerIndex);
                            }
                            return null;
                        }
                        if (quirkMode) {
                            parseHeaderQuirk(newline);
                            if (mixedHeader) {
                                // quirk mode does not parse more headers after the multipart/mixed header
                                quirkHeaderStart = -1;
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
                        undecodedPartData.release();
                        undecodedPartData = null;
                    }
                    if (buffer == null) {
                        return null;
                    }
                    Charset c = quirkMode && quirkPartCharset != null ? quirkPartCharset : partCharset != null ? partCharset : charset;
                    int normal = findDelimiter(buffer, multipartDataBoundary.getBytes(c));
                    boolean earlyMixedEnd = false;
                    if (mixedBoundary != null) {
                        int fullEnd = normal;
                        normal = findDelimiter(buffer, mixedBoundary.getBytes(c));
                        if (!quirkMode && fullEnd < normal) {
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
                        undecodedPartData = buffer.readRetainedSlice(normal);
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
            }
        }
    }

    String[] getQuirkHeader() {
        return quirkHeader;
    }

    ByteBuf sendUndecodedPartContent() {
        ByteBuf d = undecodedPartData;
        this.undecodedPartData = null;
        return d;
    }

    @Override
    public ByteBuf decodedContent() {
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
        int readerIndex = buffer.readerIndex();
        try {
            HttpPostMultipartRequestDecoder.skipControlCharacters(buffer, quirkMode);
        } catch (HttpPostRequestDecoder.NotEnoughDataDecoderException ignored) {
            // todo: do we need to reset here?
            buffer.readerIndex(readerIndex);
            return null;
        }
        HttpPostMultipartRequestDecoder.skipOneLine(buffer);
        String newline;
        try {
            newline = HttpPostMultipartRequestDecoder.readDelimiterOptimized(buffer, delimiter, charset);
        } catch (HttpPostRequestDecoder.NotEnoughDataDecoderException ignored) {
            buffer.readerIndex(readerIndex);
            return null;
        }
        if (newline.equals(delimiter)) {
            return DelimiterType.DISPOSITION;
        }
        if (newline.equals(delimiter + "--")) {
            return DelimiterType.CLOSEDELIMITER;
        }
        buffer.readerIndex(readerIndex);
        throw new HttpPostRequestDecoder.ErrorDataDecoderException("No Multipart delimiter found");
    }

    /**
     * Find the given delimiter, preceded by a newline (CRLF or LF), in the given buffer.
     * <p>
     * If the delimiter is found at the start of the input (readerIndex), this method returns the <i>binary inverse</i>
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
    int findDelimiter(ByteBuf buffer, byte[] delimiter) {
        if ((receivedLength == 0 || quirkMode) &&
                buffer.readableBytes() >= delimiter.length &&
                hasCommonPrefix(buffer, buffer.readerIndex(), delimiter)) {
            // special case at start of buffer
            return ~delimiter.length;
        }

        int i = buffer.readerIndex();
        int lfOffset = -1;
        while (true) {
            int lf = buffer.forEachByte(i, buffer.writerIndex() - i, ByteProcessor.FIND_LF);
            if (lf == -1) {
                if (quirkMode) {
                    int lastLf = quirkLfMatch(buffer, delimiter.length);
                    if (lastLf != -1) {
                        return lastLf;
                    }
                }

                if (lfOffset == -1) {
                    lfOffset = buffer.writerIndex();
                }
                if (buffer.writerIndex() - lfOffset > delimiter.length) {
                    int n = buffer.readableBytes();
                    if (n > 0 &&
                            buffer.getByte(buffer.writerIndex() - 1) == '\r' &&
                            (!quirkMode || quirkDefinedLength == receivedLength + n - 1)) {
                        n--;
                    }
                    return n;
                }
                if (buffer.readerIndex() < lfOffset && buffer.getByte(lfOffset - 1) == '\r') {
                    if (!quirkMode || quirkDefinedLength == receivedLength + buffer.readableBytes() - 1) {
                        lfOffset--;
                    }
                }
                return lfOffset - buffer.readerIndex();
            }
            lfOffset = lf;
            boolean crlf = lfOffset > buffer.readerIndex() && buffer.getByte(lfOffset - 1) == '\r';
            if (hasCommonPrefix(buffer, lf + 1, delimiter)) {
                int start = crlf ? lfOffset - 1 : lfOffset;
                if (start == buffer.readerIndex()) {
                    // found at start of buffer.
                    return ~(delimiter.length + (crlf ? 2 : 1));
                } else {
                    return start - buffer.readerIndex();
                }
            }
            i = lf + 1;
        }
    }

    private static boolean hasCommonPrefix(ByteBuf haystack, int haystackIndex, byte[] needle) {
        if (haystack.writerIndex() - haystackIndex < needle.length) {
            return false;
        }
        for (int i = 0; i < needle.length; i++) {
            if (haystack.getByte(haystackIndex + i) != needle[i]) {
                return false;
            }
        }
        return true;
    }

    private int quirkLfMatch(ByteBuf buffer, int delimiterLength) {
        assert quirkMode;
        if (buffer.readableBytes() > 0) {
            int len = Math.min(buffer.readableBytes(), delimiterLength + 1);
            int lastLf = buffer.forEachByteDesc(buffer.writerIndex() - len, len, ByteProcessor.FIND_LF);
            if (lastLf != -1) {
                if (lastLf > buffer.readerIndex() &&
                        lastLf + delimiterLength >= buffer.writerIndex() &&
                        buffer.getByte(lastLf - 1) == '\r') {
                    lastLf--;
                }
                return lastLf - buffer.readerIndex();
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

    @Override
    public void close() {
        super.close();
        if (undecodedPartData != null) {
            undecodedPartData.release();
            undecodedPartData = null;
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
