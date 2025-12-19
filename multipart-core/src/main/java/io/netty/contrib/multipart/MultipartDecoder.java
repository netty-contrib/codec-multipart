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
package io.netty.contrib.multipart;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.ByteProcessor;
import io.netty.util.internal.StringUtil;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

final class MultipartDecoder extends AbstractDecoder implements VintageAccess.MultipartDecoder {
    private final static ByteProcessor CTRLSPACE_PROCESSOR = value -> {
        char c = (char) (value & 0xff);
        return Character.isISOControl(c) || Character.isWhitespace(c);
    };

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
    private String[] quirkHeader;
    long quirkDefinedLength;
    Charset quirkPartCharset;

    final EnumSet<DecoderQuirk> quirks;

    MultipartDecoder(Builder builder, String multipartDataBoundary) {
        super(builder);
        this.multipartDataBoundary = multipartDataBoundary;
        this.quirks = EnumSet.copyOf(builder.multipartQuirks);

        clearPartData();
    }

    @Override
    public void add(ByteBuf buffer) {
        super.add(buffer);
        if (hasQuirk(DecoderQuirk.RESCAN_HEADERS_ON_CHUNK_BOUNDARY)) {
            quirkHeaderStart = -1;
        }
    }

    private void clearPartData() {
        if (undecodedPartData != null) {
            undecodedPartData.release();
            undecodedPartData = null;
        }
        partCharset = null;
        receivedLength = 0;
        if (!quirks.isEmpty()) {
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
                            if (!quirks.isEmpty()) {
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
                    if (!skipOneLine(buffer)) {
                        int readerIndex = buffer.readerIndex();
                        if (hasQuirk(DecoderQuirk.RESCAN_HEADERS_ON_CHUNK_BOUNDARY) && quirkHeaderStart == -1) {
                            quirkHeaderStart = readerIndex;
                        }
                        String newline;
                        try {
                            skipControlCharacters(buffer);
                            newline = readLineOptimized(buffer, charset);
                        } catch (NotEnoughDataDecoderException ignored) {
                            if (hasQuirk(DecoderQuirk.RESCAN_HEADERS_ON_CHUNK_BOUNDARY)) {
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
                        if (hasQuirk(DecoderQuirk.LEGACY_HEADER_SPLITTING)) {
                            parseHeaderQuirk(newline);
                        } else {
                            parseHeader(newline);
                        }
                        if (mixedHeader && hasQuirk(DecoderQuirk.STOP_AFTER_MULTIPART_MIXED_HEADER)) {
                            // quirk mode does not parse more headers after the multipart/mixed header
                            quirkHeaderStart = -1;
                            mixedHeader = false;
                            state = State.HEADERDELIMITER;
                            return Event.BEGIN_MIXED;
                        }
                        return Event.HEADER;
                    } else {
                        // no more headers
                        if (hasQuirk(DecoderQuirk.RESCAN_HEADERS_ON_CHUNK_BOUNDARY)) {
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
                    Charset c;
                    if (hasQuirk(DecoderQuirk.USE_FIELD_CHARSET_FOR_DELIMITER_SEARCH) && quirkPartCharset != null) {
                        c = quirkPartCharset;
                    } else {
                        c = charset;
                    }
                    int normal = findDelimiter(buffer, multipartDataBoundary.getBytes(c));
                    boolean earlyMixedEnd = false;
                    if (mixedBoundary != null) {
                        int fullEnd = normal;
                        normal = findDelimiter(buffer, mixedBoundary.getBytes(c));
                        if (!hasQuirk(DecoderQuirk.DISABLE_EARLY_MIXED_END) && fullEnd < normal) {
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
                        undecodedPartData = buffer.readBytes(normal);
                        addReceivedLength(normal);
                        return Event.CONTENT;
                    }
                case EARLY_MIXED_END:
                    if (buffer == null) {
                        return null;
                    }
                    mixedBoundary = null;
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
        headerKey = headerLine.substring(nameStart, nameEnd);
        if (valueEnd < valueStart) {
            headerValue = "";
        } else {
            headerValue = headerLine.substring(valueStart, valueEnd);
        }

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
                throw new FormDecoderException("TransferEncoding Unknown: " + headerValue);
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
                        throw new FormDecoderException("Mixed Multipart found in a previous Mixed Multipart");
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
                            partCharset = null;
                        }
                    }
                }
            };
            parser.run(headerValue);
            if (parser.mixed) {
                if (mixedBoundary == null) {
                    throw new FormDecoderException("No boundary found for multipart/mixed");
                }
                mixedHeader = true;
            }
        }
    }

    private void parseHeaderQuirk(String headerLine) {
        quirkHeader = splitMultipartHeader(headerLine);
        if (HttpHeaderNames.CONTENT_TRANSFER_ENCODING.contentEqualsIgnoreCase(quirkHeader[0])) {
            String mechanismName = cleanString(quirkHeader[1]);
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
            if (quirkHeader.length == 1 && !hasQuirk(DecoderQuirk.LEGACY_HEADER_SPLITTING)) {
                throw new FormDecoderException("Invalid Content-Type header");
            }
            if (HttpHeaderValues.MULTIPART_MIXED.contentEqualsIgnoreCase(quirkHeader[1])) {
                if (mixedBoundary != null) {
                    throw new FormDecoderException("Mixed Multipart found in a previous Mixed Multipart");
                }
                String values = StringUtil.substringAfter(quirkHeader[2], '=');
                mixedBoundary = "--" + values;
                mixedHeader = true;
            }
        }
    }

    @Override
    public String[] getQuirkHeader() {
        return quirkHeader;
    }

    @Override
    public ByteBuf decodedContent() {
        if (undecodedPartData == null) {
            throw new IllegalStateException("Not a CONTENT event");
        }
        // we don't support content-transfer-encodings that need actual decoding
        ByteBuf d = undecodedPartData;
        this.undecodedPartData = null;
        return d;
    }

    @Override
    public boolean isMixed() {
        return mixedBoundary != null;
    }

    @Override
    public int getCurrentAllocatedCapacity() {
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
            skipControlCharacters(buffer);
        } catch (NotEnoughDataDecoderException ignored) {
            // todo: do we need to reset here?
            buffer.readerIndex(readerIndex);
            return null;
        }
        skipOneLine(buffer);
        String newline;
        try {
            newline = readDelimiterOptimized(buffer, delimiter, charset);
        } catch (NotEnoughDataDecoderException ignored) {
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
        throw new FormDecoderException("No Multipart delimiter found");
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
        if ((receivedLength == 0 || hasQuirk(DecoderQuirk.INVERSE_DELIMITER_AT_BUFFER_START)) &&
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
                if (hasQuirk(DecoderQuirk.CONSERVATIVE_LF_BACKTRACK)) {
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
                            buffer.getByte(buffer.writerIndex() - 1) == '\r' && (!hasQuirk(DecoderQuirk.FORWARD_CHUNK_CR) || quirkDefinedLength == receivedLength + n - 1)) {
                        n--;
                    }
                    return n;
                }
                if (buffer.readerIndex() < lfOffset && buffer.getByte(lfOffset - 1) == '\r') {
                    if (!hasQuirk(DecoderQuirk.FORWARD_CHUNK_CR) || quirkDefinedLength == receivedLength + buffer.readableBytes() - 1) {
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
        assert hasQuirk(DecoderQuirk.CONSERVATIVE_LF_BACKTRACK);
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
        if (quirkDefinedLength > 0 && quirkDefinedLength < receivedLength) {
            // old implementation extends the defined length to match the received length, if necessary
            quirkDefinedLength = receivedLength;
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

    /**
     * Skip control Characters
     *
     * @throws NotEnoughDataDecoderException
     */
    static void skipControlCharacters(ByteBuf undecodedChunk) throws NotEnoughDataDecoderException {
        try {
            skipControlCharactersStandard(undecodedChunk);
        } catch (IndexOutOfBoundsException e1) {
            throw new NotEnoughDataDecoderException(e1);
        }
    }

    private static void skipControlCharactersStandard(ByteBuf undecodedChunk) {
        int processed = undecodedChunk.forEachByte(CTRLSPACE_PROCESSOR);
        if (processed > undecodedChunk.readerIndex()) {
            undecodedChunk.readerIndex(processed);
        } else if (processed == -1) {
            undecodedChunk.readerIndex(undecodedChunk.writerIndex());
        }
    }
    /**
     * Read one line up to the CRLF or LF
     *
     * @return the String from one line
     * @throws NotEnoughDataDecoderException
     *             Need more chunks and reset the {@code readerIndex} to the previous
     *             value
     */
    static String readLineOptimized(ByteBuf undecodedChunk, Charset charset) {
        int readerIndex = undecodedChunk.readerIndex();
        try {
            if (undecodedChunk.readableBytes() > 0) {
                int posLfOrCrLf = HttpPostBodyUtil.findLineBreak(undecodedChunk, undecodedChunk.readerIndex());
                if (posLfOrCrLf <= 0) {
                    throw new NotEnoughDataDecoderException();
                }

                CharSequence lineCharSeq = undecodedChunk.readCharSequence(posLfOrCrLf, charset);
                byte nextByte = undecodedChunk.readByte();
                if (nextByte == HttpConstants.CR) {
                    // force read next byte since LF is the following one
                    undecodedChunk.readByte();
                }
                return lineCharSeq.toString();
            }
        } catch (IndexOutOfBoundsException e) {
            undecodedChunk.readerIndex(readerIndex);
            throw new NotEnoughDataDecoderException(e);
        }
        undecodedChunk.readerIndex(readerIndex);
        throw new NotEnoughDataDecoderException();
    }

    /**
     * Read one line up to --delimiter or --delimiter-- and if existing the CRLF
     * or LF Read one line up to --delimiter or --delimiter-- and if existing
     * the CRLF or LF. Note that CRLF or LF are mandatory for opening delimiter
     * (--delimiter) but not for closing delimiter (--delimiter--) since some
     * clients does not include CRLF in this case.
     *
     * @param delimiter
     *            of the form --string, such that '--' is already included
     * @return the String from one line as the delimiter searched (opening or
     *         closing)
     * @throws NotEnoughDataDecoderException
     *             Need more chunks and reset the {@code readerIndex} to the previous
     *             value
     */
    static String readDelimiterOptimized(ByteBuf undecodedChunk, String delimiter, Charset charset) {
        final int readerIndex = undecodedChunk.readerIndex();
        final byte[] bdelimiter = delimiter.getBytes(charset);
        final int delimiterLength = bdelimiter.length;
        try {
            int delimiterPos = HttpPostBodyUtil.findDelimiter(undecodedChunk, readerIndex, bdelimiter, false);
            if (delimiterPos < 0) {
                // delimiter not found so break here !
                undecodedChunk.readerIndex(readerIndex);
                throw new NotEnoughDataDecoderException();
            }
            StringBuilder sb = new StringBuilder(delimiter);
            undecodedChunk.readerIndex(readerIndex + delimiterPos + delimiterLength);
            // Now check if either opening delimiter or closing delimiter
            if (undecodedChunk.readableBytes() > 0) {
                byte nextByte = undecodedChunk.readByte();
                // first check for opening delimiter
                if (nextByte == HttpConstants.CR) {
                    nextByte = undecodedChunk.readByte();
                    if (nextByte == HttpConstants.LF) {
                        return sb.toString();
                    } else {
                        // error since CR must be followed by LF
                        // delimiter not found so break here !
                        undecodedChunk.readerIndex(readerIndex);
                        throw new NotEnoughDataDecoderException();
                    }
                } else if (nextByte == HttpConstants.LF) {
                    return sb.toString();
                } else if (nextByte == '-') {
                    sb.append('-');
                    // second check for closing delimiter
                    nextByte = undecodedChunk.readByte();
                    if (nextByte == '-') {
                        sb.append('-');
                        // now try to find if CRLF or LF there
                        if (undecodedChunk.readableBytes() > 0) {
                            nextByte = undecodedChunk.readByte();
                            if (nextByte == HttpConstants.CR) {
                                nextByte = undecodedChunk.readByte();
                                if (nextByte == HttpConstants.LF) {
                                    return sb.toString();
                                } else {
                                    // error CR without LF
                                    // delimiter not found so break here !
                                    undecodedChunk.readerIndex(readerIndex);
                                    throw new NotEnoughDataDecoderException();
                                }
                            } else if (nextByte == HttpConstants.LF) {
                                return sb.toString();
                            } else {
                                // No CRLF but ok however (Adobe Flash uploader)
                                // minus 1 since we read one char ahead but
                                // should not
                                undecodedChunk.readerIndex(undecodedChunk.readerIndex() - 1);
                                return sb.toString();
                            }
                        }
                        // FIXME what do we do here?
                        // either considering it is fine, either waiting for
                        // more data to come?
                        // lets try considering it is fine...
                        return sb.toString();
                    }
                    // only one '-' => not enough
                    // whatever now => error since incomplete
                }
            }
        } catch (IndexOutOfBoundsException e) {
            undecodedChunk.readerIndex(readerIndex);
            throw new NotEnoughDataDecoderException(e);
        }
        undecodedChunk.readerIndex(readerIndex);
        throw new NotEnoughDataDecoderException();
    }

    /**
     * Clean the String from any unallowed character
     *
     * @return the cleaned String
     */
    static String cleanString(String field) {
        int size = field.length();
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            char nextChar = field.charAt(i);
            switch (nextChar) {
                case HttpConstants.COLON:
                case HttpConstants.COMMA:
                case HttpConstants.EQUALS:
                case HttpConstants.SEMICOLON:
                case HttpConstants.HT:
                    sb.append(HttpConstants.SP_CHAR);
                    break;
                case HttpConstants.DOUBLE_QUOTE:
                    // nothing added, just removes it
                    break;
                default:
                    sb.append(nextChar);
                    break;
            }
        }
        return sb.toString().trim();
    }

    /**
     * Skip one empty line
     *
     * @return True if one empty line was skipped
     * @param undecodedChunk
     */
    static boolean skipOneLine(ByteBuf undecodedChunk) {
        if (undecodedChunk.readableBytes() == 0) {
            return false;
        }
        byte nextByte = undecodedChunk.readByte();
        if (nextByte == HttpConstants.CR) {
            if (undecodedChunk.readableBytes() == 0) {
                undecodedChunk.readerIndex(undecodedChunk.readerIndex() - 1);
                return false;
            }
            nextByte = undecodedChunk.readByte();
            if (nextByte == HttpConstants.LF) {
                return true;
            }
            undecodedChunk.readerIndex(undecodedChunk.readerIndex() - 2);
            return false;
        }
        if (nextByte == HttpConstants.LF) {
            return true;
        }
        undecodedChunk.readerIndex(undecodedChunk.readerIndex() - 1);
        return false;
    }

    /**
     * Split one header in Multipart
     *
     * @return an array of String where rank 0 is the name of the header,
     *         follows by several values that were separated by ';' or ','
     */
    static String[] splitMultipartHeader(String sb) {
        ArrayList<String> headers = new ArrayList<String>(1);
        int nameStart;
        int nameEnd;
        int colonEnd;
        int valueStart;
        int valueEnd;
        nameStart = HttpPostBodyUtil.findNonWhitespace(sb, 0);
        for (nameEnd = nameStart; nameEnd < sb.length(); nameEnd++) {
            char ch = sb.charAt(nameEnd);
            if (ch == ':' || Character.isWhitespace(ch)) {
                break;
            }
        }
        for (colonEnd = nameEnd; colonEnd < sb.length(); colonEnd++) {
            if (sb.charAt(colonEnd) == ':') {
                colonEnd++;
                break;
            }
        }
        valueStart = HttpPostBodyUtil.findNonWhitespace(sb, colonEnd);
        valueEnd = HttpPostBodyUtil.findEndOfString(sb);
        headers.add(sb.substring(nameStart, nameEnd));
        String svalue = (valueStart >= valueEnd) ? StringUtil.EMPTY_STRING : sb.substring(valueStart, valueEnd);
        String[] values;
        if (svalue.indexOf(';') >= 0) {
            values = splitMultipartHeaderValues(svalue);
        } else {
            values = svalue.split(",");
        }
        for (String value : values) {
            headers.add(value.trim());
        }
        String[] array = new String[headers.size()];
        for (int i = 0; i < headers.size(); i++) {
            array[i] = headers.get(i);
        }
        return array;
    }

    /**
     * Split one header value in Multipart
     * @return an array of String where values that were separated by ';'
     */
    private static String[] splitMultipartHeaderValues(String svalue) {
        List<String> values = new ArrayList<>(1);
        boolean inQuote = false;
        boolean escapeNext = false;
        int start = 0;
        for (int i = 0; i < svalue.length(); i++) {
            char c = svalue.charAt(i);
            if (inQuote) {
                if (escapeNext) {
                    escapeNext = false;
                } else {
                    if (c == '\\') {
                        escapeNext = true;
                    } else if (c == '"') {
                        inQuote = false;
                    }
                }
            } else {
                if (c == '"') {
                    inQuote = true;
                } else if (c == ';') {
                    values.add(svalue.substring(start, i));
                    start = i + 1;
                }
            }
        }
        values.add(svalue.substring(start));
        return values.toArray(new String[0]);
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
    public void setQuirkPartCharset(Charset quirkPartCharset) {
        this.quirkPartCharset = quirkPartCharset;
    }

    @Override
    public void setQuirkDefinedLength(long quirkDefinedLength) {
        this.quirkDefinedLength = quirkDefinedLength;
    }

    @Override
    public Charset getCharset() {
        return charset;
    }

    @Override
    public boolean hasQuirk(DecoderQuirk quirk) {
        return quirks.contains(quirk);
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
