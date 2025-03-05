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
import io.netty5.handler.codec.http.HttpConstants;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpHeaderValues;
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
        mixedBoundary = null;
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
                    DelimiterType delimiter = findMultipartDelimiter(multipartDataBoundary);
                    if (delimiter == null) {
                        return null;
                    } else if (delimiter == DelimiterType.DISPOSITION) {
                        state = State.DISPOSITION;
                        checkNewField();
                        return Event.BEGIN_FIELD;
                    } else {
                        state = State.PREEPILOGUE;
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
                            // todo: do we need to reset the control chars?
                            buffer.readerOffset(quirkMode ? quirkHeaderStart : readerIndex);
                            return null;
                        }
                        if (quirkMode) {
                            parseHeaderQuirk(newline);
                        } else {
                            parseHeader(newline);
                        }
                        return Event.HEADER;
                    } else {
                        // no more headers
                        if (quirkMode) {
                            quirkHeaderStart = -1;
                        }
                        state = State.CONTENT;
                        return Event.HEADERS_COMPLETE;
                    }
                case CONTENT:
                    if (undecodedPartData != null) {
                        undecodedPartData.close();
                        undecodedPartData = null;
                    }
                    if (buffer == null) {
                        return null;
                    }
                    if (quirkMode ?
                            loadContentQuirk(buffer, mixedBoundary != null ? mixedBoundary : multipartDataBoundary) :
                            loadContent(buffer, mixedBoundary != null ? mixedBoundary : multipartDataBoundary)) {
                        state = State.CONTENT_DONE;
                        if (undecodedPartData == null) {
                            break;
                        } else {
                            return Event.CONTENT;
                        }
                    } else {
                        if (undecodedPartData != null) {
                            return Event.CONTENT;
                        } else {
                            return null;
                        }
                    }
                case CONTENT_DONE:
                    clearPartData();
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
            if (parser.mixed && mixedBoundary == null) {
                throw new HttpPostRequestDecoder.ErrorDataDecoderException("No boundary found for multipart/mixed");
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

    private boolean loadContent(Buffer undecodedChunk, String delimiter) {
        assert !quirkMode;
        assert undecodedPartData == null;
        byte[] bdelimiter = delimiter.getBytes(currentCharset());
        // this variable is either the position in bdelimiter or:
        // -2 if we expect a CR or LF next
        // -1 if we expect an LF next because we just saw a CR
        int j = receivedLength > 0 ? -2 : 0;
        int fieldEnd = undecodedChunk.readerOffset();
        boolean delimiterFound = false;
        // TODO: this loop has a data dependency (j) and is probably pretty slow. use SWAR search instead
        for (int i = undecodedChunk.readerOffset(); i < undecodedChunk.writerOffset(); i++) {
            byte b = undecodedChunk.getByte(i);
            if (j >= 0) {
                if (b == bdelimiter[j]) {
                    if (j == bdelimiter.length - 1) {
                        delimiterFound = true;
                        break;
                    }
                    j++;
                } else {
                    j = -2;
                }
            }
            if (j < 0) {
                if (b == HttpConstants.CR) {
                    fieldEnd = i;
                    j = -1;
                } else if (b == HttpConstants.LF) {
                    if (j == -2) {
                        fieldEnd = i;
                    }
                    j = 0;
                } else {
                    j = -2;
                }
            }
        }
        int n = fieldEnd - undecodedChunk.readerOffset();
        if (n > 0) {
            undecodedPartData = undecodedChunk.readSplit(n);
            addReceivedLength(undecodedPartData.readableBytes());
        }
        return delimiterFound;
    }

    private boolean loadContentQuirk(Buffer undecodedChunk, String delimiter) {
        assert quirkMode;
        assert undecodedPartData == null;
        if (undecodedChunk.readableBytes() == 0) {
            return false;
        }
        final int startReaderIndex = undecodedChunk.readerOffset();
        final byte[] bdelimiter = delimiter.getBytes(currentCharset());
        int posDelimiter = HttpPostBodyUtil.findDelimiter(undecodedChunk, startReaderIndex, bdelimiter, true);
        if (posDelimiter < 0) {
            // Not found but however perhaps because incomplete so search LF or CRLF from the end.
            // Possible last bytes contain partially delimiter
            // (delimiter is possibly partially there, at least 1 missing byte),
            // therefore searching last delimiter.length +1 (+1 for CRLF instead of LF)
            int readableBytes = undecodedChunk.readableBytes();
            int lastPosition = readableBytes - bdelimiter.length - 1;
            if (lastPosition < 0) {
                // Not enough bytes, but at most delimiter.length bytes available so can still try to find CRLF there
                lastPosition = 0;
            }
            posDelimiter = HttpPostBodyUtil.findLastLineBreak(undecodedChunk, startReaderIndex + lastPosition);
            // No LineBreak, however CR can be at the end of the buffer, LF not yet there (issue #11668)
            // Check if last CR (if any) shall not be in the content (definedLength vs actual length + buffer - 1)
            if (posDelimiter < 0 &&
                    (quirkDefinedLength == receivedLength + readableBytes - 1) &&
                    undecodedChunk.getByte(readableBytes + startReaderIndex - 1) == HttpConstants.CR) {
                // Last CR shall precede a future LF
                lastPosition = 0;
                posDelimiter = readableBytes - 1;
            }
            if (posDelimiter < 0) {
                // not found so this chunk can be fully added
                addReceivedLength(buffer.readableBytes());
                undecodedPartData = buffer;
                buffer = null;
                return false;
            }
            // posDelimiter is not from startReaderIndex but from startReaderIndex + lastPosition
            posDelimiter += lastPosition;
            if (posDelimiter == 0) {
                // Nothing to add
                return false;
            }
            // Not fully but still some bytes to provide: httpData is not yet finished since delimiter not found
            addReceivedLength(posDelimiter);
            undecodedPartData = undecodedChunk.readSplit(posDelimiter);
            return false;
        }
        // Delimiter found at posDelimiter, including LF or CRLF, so httpData has its last chunk
        addReceivedLength(posDelimiter);
        undecodedPartData = undecodedChunk.readSplit(posDelimiter);
        return true;
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
        CONTENT_DONE,
        PREEPILOGUE,
    }

    private enum DelimiterType {
        DISPOSITION,
        CLOSEDELIMITER
    }

}
