/*
 * Copyright 2012 The Netty Project
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
import io.netty.buffer.Unpooled;
import io.netty.contrib.handler.codec.http.multipart.HttpPostBodyUtil.TransferEncodingMechanism;
import io.netty.contrib.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.contrib.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.contrib.handler.codec.http.multipart.HttpPostRequestDecoder.MultiPartStatus;
import io.netty.contrib.handler.codec.http.multipart.HttpPostRequestDecoder.NotEnoughDataDecoderException;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ByteProcessor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.netty.util.internal.ObjectUtil.checkNotNullWithIAE;
import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

/**
 * This decoder will decode Body and can handle POST BODY.
 *
 * You <strong>MUST</strong> call {@link #destroy()} after completion to release all resources.
 *
 */
public class HttpPostMultipartRequestDecoder implements InterfaceHttpPostRequestDecoder {

    /**
     * Factory used to create InterfaceHttpData
     */
    private final HttpDataFactory factory;

    /**
     * Request to decode
     */
    private final HttpRequest request;

    private final MultipartDecoder decoder;

    /**
     * Does the last chunk already received
     */
    private boolean isLastChunk;

    /**
     * HttpDatas from Body
     */
    final List<InterfaceHttpData> bodyListHttpData = new ArrayList<InterfaceHttpData>();

    /**
     * HttpDatas as Map from Body
     */
    private final Map<String, List<InterfaceHttpData>> bodyMapHttpData = new TreeMap<String, List<InterfaceHttpData>>(
            CaseIgnoringComparator.INSTANCE);

    /**
     * Body HttpDatas current position
     */
    private int bodyListHttpDataRank;

    /**
     * Current getStatus
     */
    private MultiPartStatus currentStatus = MultiPartStatus.NOTSTARTED;

    /**
     * Used in Multipart
     */
    private Map<CharSequence, Attribute> currentFieldAttributes;

    /**
     * The current FileUpload that is currently in decode process
     */
    private FileUpload currentFileUpload;

    /**
     * The current Attribute that is currently in decode process
     */
    private Attribute currentAttribute;

    private boolean destroyed;

    private final static ByteProcessor CTRLSPACE_PROCESSOR = value -> {
        char c = (char) (value & 0xff);
        return Character.isISOControl(c) || Character.isWhitespace(c);
    };

    /**
     *
     * @param request
     *            the request to decode
     * @throws NullPointerException
     *             for request
     * @throws ErrorDataDecoderException
     *             if the default charset was wrong when decoding or other
     *             errors
     */
    public HttpPostMultipartRequestDecoder(HttpRequest request) {
        this(new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE), request, PostBodyDecoder.builder());
    }

    /**
     *
     * @param factory
     *            the factory used to create InterfaceHttpData
     * @param request
     *            the request to decode
     * @throws NullPointerException
     *             for request or factory
     * @throws ErrorDataDecoderException
     *             if the default charset was wrong when decoding or other
     *             errors
     */
    public HttpPostMultipartRequestDecoder(HttpDataFactory factory, HttpRequest request) {
        this(factory, request, PostBodyDecoder.builder());
    }

    /**
     *
     * @param factory
     *            the factory used to create InterfaceHttpData
     * @param request
     *            the request to decode
     * @param charset
     *            the charset to use as default
     * @throws NullPointerException
     *             for request or charset or factory
     * @throws ErrorDataDecoderException
     *             if the default charset was wrong when decoding or other
     *             errors
     */
    public HttpPostMultipartRequestDecoder(HttpDataFactory factory, HttpRequest request, Charset charset) {
        this(factory, request, PostBodyDecoder.builder().charset(charset));
    }

    /**
     *
     * @param factory
     *            the factory used to create InterfaceHttpData
     * @param request
     *            the request to decode
     * @param charset
     *            the charset to use as default
     * @param maxFields
     *            the maximum number of fields the form can have, {@code -1} to disable
     * @param maxBufferedBytes
     *            the maximum number of bytes the decoder can buffer when decoding a field, {@code -1} to disable
     * @throws NullPointerException
     *             for request or charset or factory
     * @throws ErrorDataDecoderException
     *             if the default charset was wrong when decoding or other
     *             errors
     */
    public HttpPostMultipartRequestDecoder(HttpDataFactory factory, HttpRequest request, Charset charset,
                                           int maxFields, int maxBufferedBytes) {
        this(factory, request, PostBodyDecoder.builder().charset(charset).maxFields(maxFields).undecodedLimit(maxBufferedBytes));
    }

    private HttpPostMultipartRequestDecoder(HttpDataFactory factory, HttpRequest request, PostBodyDecoder.Builder builder) {
        this.request = checkNotNullWithIAE(request, "request");
        this.factory = checkNotNullWithIAE(factory, "factory");
        // Fill default values

        CharSequence contentTypeValue = this.request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (contentTypeValue == null) {
            throw new ErrorDataDecoderException("No '" + HttpHeaderNames.CONTENT_TYPE + "' header present.");
        }

        String[] dataBoundary = HttpPostRequestDecoder.getMultipartDataBoundary(contentTypeValue.toString());
        String multipartDataBoundary;
        if (dataBoundary != null) {
            multipartDataBoundary = dataBoundary[0];
            if (dataBoundary.length > 1 && dataBoundary[1] != null) {
                try {
                    builder.charset = Charset.forName(dataBoundary[1]);
                } catch (IllegalCharsetNameException e) {
                    throw new ErrorDataDecoderException(e);
                }
            }
        } else {
            multipartDataBoundary = null;
        }
        decoder = builder.forBoundary0(multipartDataBoundary);
        decoder.quirkMode = true;

        try {
            if (request instanceof HttpContent) {
                // Offer automatically if the given request is als type of HttpContent
                // See #1089
                offer((HttpContent) request);
            } else {
                parseBody();
            }
        } catch (Throwable e) {
            destroy();
            PlatformDependent.throwException(e);
        }
    }

    private void checkDestroyed() {
        if (destroyed) {
            throw new IllegalStateException(HttpPostMultipartRequestDecoder.class.getSimpleName()
                    + " was destroyed already");
        }
    }

    /**
     * True if this request is a Multipart request
     *
     * @return True if this request is a Multipart request
     */
    @Override
    public boolean isMultipart() {
        checkDestroyed();
        return true;
    }

    /**
     * Set the amount of bytes after which read bytes in the buffer should be discarded.
     * Setting this lower gives lower memory usage but with the overhead of more memory copies.
     * Use {@code 0} to disable it.
     */
    @Override
    public void setDiscardThreshold(int discardThreshold) {
        decoder.compactionThreshold = checkPositiveOrZero(discardThreshold, "discardThreshold");
    }

    /**
     * Return the threshold in bytes after which read data in the buffer should be discarded.
     */
    @Override
    public int getDiscardThreshold() {
        return decoder.compactionThreshold;
    }

    /**
     * This getMethod returns a List of all HttpDatas from body.<br>
     *
     * If chunked, all chunks must have been offered using offer() getMethod. If
     * not, NotEnoughDataDecoderException will be raised.
     *
     * @return the list of HttpDatas from Body part for POST getMethod
     * @throws NotEnoughDataDecoderException
     *             Need more chunks
     */
    @Override
    public List<InterfaceHttpData> getBodyHttpDatas() {
        checkDestroyed();

        if (!isLastChunk) {
            throw new NotEnoughDataDecoderException();
        }
        return bodyListHttpData;
    }

    /**
     * This getMethod returns a List of all HttpDatas with the given name from
     * body.<br>
     *
     * If chunked, all chunks must have been offered using offer() getMethod. If
     * not, NotEnoughDataDecoderException will be raised.
     *
     * @return All Body HttpDatas with the given name (ignore case)
     * @throws NotEnoughDataDecoderException
     *             need more chunks
     */
    @Override
    public List<InterfaceHttpData> getBodyHttpDatas(String name) {
        checkDestroyed();

        if (!isLastChunk) {
            throw new NotEnoughDataDecoderException();
        }
        return bodyMapHttpData.get(name);
    }

    /**
     * This getMethod returns the first InterfaceHttpData with the given name from
     * body.<br>
     *
     * If chunked, all chunks must have been offered using offer() getMethod. If
     * not, NotEnoughDataDecoderException will be raised.
     *
     * @return The first Body InterfaceHttpData with the given name (ignore
     *         case)
     * @throws NotEnoughDataDecoderException
     *             need more chunks
     */
    @Override
    public InterfaceHttpData getBodyHttpData(String name) {
        checkDestroyed();

        if (!isLastChunk) {
            throw new NotEnoughDataDecoderException();
        }
        List<InterfaceHttpData> list = bodyMapHttpData.get(name);
        if (list != null) {
            return list.get(0);
        }
        return null;
    }

    /**
     * Initialized the internals from a new chunk
     *
     * @param content
     *            the new received chunk
     * @throws ErrorDataDecoderException
     *             if there is a problem with the charset decoding or other
     *             errors
     */
    @Override
    public HttpPostMultipartRequestDecoder offer(HttpContent content) {
        checkDestroyed();

        if (content instanceof LastHttpContent) {
            isLastChunk = true;
        }

        ByteBuf buf = content.content();
        decoder.add(buf.retain());
        parseBody();
        return this;
    }

    /**
     * True if at current getStatus, there is an available decoded
     * InterfaceHttpData from the Body.
     *
     * This getMethod works for chunked and not chunked request.
     *
     * @return True if at current getStatus, there is a decoded InterfaceHttpData
     * @throws EndOfDataDecoderException
     *             No more data will be available
     */
    @Override
    public boolean hasNext() {
        checkDestroyed();

        if (currentStatus == MultiPartStatus.EPILOGUE) {
            // OK except if end of list
            if (bodyListHttpDataRank >= bodyListHttpData.size()) {
                throw new EndOfDataDecoderException();
            }
        }
        return !bodyListHttpData.isEmpty() && bodyListHttpDataRank < bodyListHttpData.size();
    }

    /**
     * Returns the next available InterfaceHttpData or null if, at the time it
     * is called, there is no more available InterfaceHttpData. A subsequent
     * call to offer(httpChunk) could enable more data.
     *
     * Be sure to call {@link InterfaceHttpData#release()} after you are done
     * with processing to make sure to not leak any resources
     *
     * @return the next available InterfaceHttpData or null if none
     * @throws EndOfDataDecoderException
     *             No more data will be available
     */
    @Override
    public InterfaceHttpData next() {
        checkDestroyed();

        if (hasNext()) {
            return bodyListHttpData.get(bodyListHttpDataRank++);
        }
        return null;
    }

    @Override
    public InterfaceHttpData currentPartialHttpData() {
        if (currentFileUpload != null) {
            return currentFileUpload;
        } else {
            return currentAttribute;
        }
    }

    /**
     * This getMethod will parse as much as possible data and fill the list and map
     *
     * @throws ErrorDataDecoderException
     *             if there is a problem with the charset decoding or other
     *             errors
     */
    private void parseBody() {
        while (true) {
            MultipartDecoder.Event event = decoder.next();
            if (event == null) {
                break;
            }
            switch (event) {
                case BEGIN_FIELD:
                    clearCurrentFieldAttributes();
                    currentFieldAttributes = new TreeMap<CharSequence, Attribute>(CaseIgnoringComparator.INSTANCE);
                    break;
                case HEADER:
                    handleHeader(decoder.getQuirkHeader());
                    break;
                case HEADERS_COMPLETE:
                    // Is it a FileUpload
                    Attribute filenameAttribute = currentFieldAttributes.get(HttpHeaderValues.FILENAME);
                    if (filenameAttribute != null) {
                        // FileUpload
                        currentStatus = MultiPartStatus.FILEUPLOAD;
                        getFileUpload();
                    } else {
                        // Field
                        currentStatus = MultiPartStatus.FIELD;
                        // do not change the buffer position
                        getAttribute();
                    }
                    break;
                case CONTENT:
                    try {
                        ((HttpData) currentPartialHttpData()).addContent(decoder.sendUndecodedPartContent(), false);
                    } catch (IOException e) {
                        throw new ErrorDataDecoderException(e);
                    }
                    break;
                case FIELD_COMPLETE:
                    try {
                        ((HttpData) currentPartialHttpData()).addContent(Unpooled.EMPTY_BUFFER, true);
                    } catch (IOException e) {
                        throw new ErrorDataDecoderException(e);
                    }
                    addHttpData(currentPartialHttpData());
                    currentFileUpload = null;
                    currentAttribute = null;
                    break;
            }
        }
    }

    /**
     * Utility function to add a new decoded data
     */
    protected void addHttpData(InterfaceHttpData data) {
        if (data == null) {
            return;
        }
        List<InterfaceHttpData> datas = bodyMapHttpData.get(data.getName());
        if (datas == null) {
            datas = new ArrayList<InterfaceHttpData>(1);
            bodyMapHttpData.put(data.getName(), datas);
        }
        datas.add(data);
        bodyListHttpData.add(data);
    }

    private Attribute getAttribute() {
        // Now get value according to Content-Type and Charset
        Charset localCharset = null;
        Attribute charsetAttribute = currentFieldAttributes.get(HttpHeaderValues.CHARSET);
        if (charsetAttribute != null) {
            try {
                localCharset = Charset.forName(charsetAttribute.getValue());
            } catch (IOException e) {
                throw new ErrorDataDecoderException(e);
            } catch (UnsupportedCharsetException e) {
                throw new ErrorDataDecoderException(e);
            }
        }
        Attribute nameAttribute = currentFieldAttributes.get(HttpHeaderValues.NAME);
        Attribute lengthAttribute = currentFieldAttributes
                .get(HttpHeaderNames.CONTENT_LENGTH);
        long size;
        try {
            size = lengthAttribute != null ? Long.parseLong(lengthAttribute
                    .getValue()) : 0L;
        } catch (IOException e) {
            throw new ErrorDataDecoderException(e);
        } catch (NumberFormatException ignored) {
            size = 0;
        }
        try {
            if (size > 0) {
                currentAttribute = factory.createAttribute(request,
                        cleanString(nameAttribute.getValue()), size);
            } else {
                currentAttribute = factory.createAttribute(request,
                        cleanString(nameAttribute.getValue()));
            }
        } catch (NullPointerException e) {
            throw new ErrorDataDecoderException(e);
        } catch (IllegalArgumentException e) {
            throw new ErrorDataDecoderException(e);
        } catch (IOException e) {
            throw new ErrorDataDecoderException(e);
        }
        if (localCharset != null) {
            currentAttribute.setCharset(localCharset);
        }
        return currentAttribute;
    }

    /**
     * Skip control Characters
     *
     * @throws NotEnoughDataDecoderException
     */
    static void skipControlCharacters(ByteBuf undecodedChunk, boolean quirk) throws NotEnoughDataDecoderException {
        try {
            skipControlCharactersStandard(undecodedChunk, quirk);
        } catch (IndexOutOfBoundsException e1) {
            throw new NotEnoughDataDecoderException(e1);
        }
    }

    private static void skipControlCharactersStandard(ByteBuf undecodedChunk, boolean quirk) {
        int processed = undecodedChunk.forEachByte(CTRLSPACE_PROCESSOR);
        if (processed > undecodedChunk.readerIndex()) {
            undecodedChunk.readerIndex(processed);
        } else if (processed == -1) {
            undecodedChunk.readerIndex(undecodedChunk.writerIndex());
        }
    }

    private boolean handleHeader(String[] contents) {
        if (HttpHeaderNames.CONTENT_DISPOSITION.contentEqualsIgnoreCase(contents[0])) {
            boolean checkSecondArg;
            if (!decoder.isMixed()) {
                checkSecondArg = HttpHeaderValues.FORM_DATA.contentEqualsIgnoreCase(contents[1]);
            } else {
                checkSecondArg = HttpHeaderValues.ATTACHMENT.contentEqualsIgnoreCase(contents[1])
                        || HttpHeaderValues.FILE.contentEqualsIgnoreCase(contents[1]);
            }
            if (checkSecondArg) {
                // read next values and store them in the map as Attribute
                for (int i = 2; i < contents.length; i++) {
                    String[] values = contents[i].split("=", 2);
                    Attribute attribute;
                    try {
                        attribute = getContentDispositionAttribute(values);
                    } catch (NullPointerException e) {
                        throw new ErrorDataDecoderException(e);
                    } catch (IllegalArgumentException e) {
                        throw new ErrorDataDecoderException(e);
                    }
                    putCurrentFieldAttribute(attribute.getName(), attribute);
                }
            }
        } else if (HttpHeaderNames.CONTENT_TRANSFER_ENCODING.contentEqualsIgnoreCase(contents[0])) {
            Attribute attribute;
            try {
                attribute = factory.createAttribute(request, HttpHeaderNames.CONTENT_TRANSFER_ENCODING.toString(),
                        cleanString(contents[1]));
            } catch (NullPointerException e) {
                throw new ErrorDataDecoderException(e);
            } catch (IllegalArgumentException e) {
                throw new ErrorDataDecoderException(e);
            }

            putCurrentFieldAttribute(HttpHeaderNames.CONTENT_TRANSFER_ENCODING, attribute);
        } else if (HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(contents[0])) {
            Attribute attribute;
            try {
                attribute = factory.createAttribute(request, HttpHeaderNames.CONTENT_LENGTH.toString(),
                        cleanString(contents[1]));
            } catch (NullPointerException e) {
                throw new ErrorDataDecoderException(e);
            } catch (IllegalArgumentException e) {
                throw new ErrorDataDecoderException(e);
            }

            putCurrentFieldAttribute(HttpHeaderNames.CONTENT_LENGTH, attribute);
        } else if (HttpHeaderNames.CONTENT_TYPE.contentEqualsIgnoreCase(contents[0])) {
            // Take care of possible "multipart/mixed"
            if (HttpHeaderValues.MULTIPART_MIXED.contentEqualsIgnoreCase(contents[1])) {
                if (decoder.isMixed()) {
                    String values = StringUtil.substringAfter(contents[2], '=');
                    currentStatus = MultiPartStatus.MIXEDDELIMITER;
                    return true;
                } else {
                    throw new ErrorDataDecoderException("Mixed Multipart found in a previous Mixed Multipart");
                }
            } else {
                for (int i = 1; i < contents.length; i++) {
                    final String charsetHeader = HttpHeaderValues.CHARSET.toString();
                    if (contents[i].regionMatches(true, 0, charsetHeader, 0, charsetHeader.length())) {
                        String values = StringUtil.substringAfter(contents[i], '=');
                        Attribute attribute;
                        try {
                            attribute = factory.createAttribute(request, charsetHeader, cleanString(values));
                        } catch (NullPointerException e) {
                            throw new ErrorDataDecoderException(e);
                        } catch (IllegalArgumentException e) {
                            throw new ErrorDataDecoderException(e);
                        }
                        putCurrentFieldAttribute(HttpHeaderValues.CHARSET, attribute);
                    } else if (contents[i].contains("=")) {
                        String name = StringUtil.substringBefore(contents[i], '=');
                        String values = StringUtil.substringAfter(contents[i], '=');
                        Attribute attribute;
                        try {
                            attribute = factory.createAttribute(request, cleanString(name), values);
                        } catch (NullPointerException e) {
                            throw new ErrorDataDecoderException(e);
                        } catch (IllegalArgumentException e) {
                            throw new ErrorDataDecoderException(e);
                        }
                        putCurrentFieldAttribute(name, attribute);
                    } else {
                        Attribute attribute;
                        try {
                            attribute = factory.createAttribute(request,
                                    cleanString(contents[0]), contents[i]);
                        } catch (NullPointerException e) {
                            throw new ErrorDataDecoderException(e);
                        } catch (IllegalArgumentException e) {
                            throw new ErrorDataDecoderException(e);
                        }
                        putCurrentFieldAttribute(attribute.getName(), attribute);
                    }
                }
            }
        }
        return false;
    }

    private void putCurrentFieldAttribute(CharSequence name, Attribute attribute) {
        currentFieldAttributes.compute(attribute.getName(), (key, old) -> {
            if (old != null) {
                old.release();
            }
            return attribute;
        });
    }

    private static final String FILENAME_ENCODED = HttpHeaderValues.FILENAME.toString() + '*';

    private Attribute getContentDispositionAttribute(String... values) {
        String name = cleanString(values[0]);
        String value = values[1];

        // Filename can be token, quoted or encoded. See https://tools.ietf.org/html/rfc5987
        if (HttpHeaderValues.FILENAME.contentEquals(name)) {
            // Value is quoted or token. Strip if quoted:
            int last = value.length() - 1;
            if (last > 0 &&
              value.charAt(0) == HttpConstants.DOUBLE_QUOTE &&
              value.charAt(last) == HttpConstants.DOUBLE_QUOTE) {
                value = value.substring(1, last);
            }
        } else if (FILENAME_ENCODED.equals(name)) {
            try {
                name = HttpHeaderValues.FILENAME.toString();
                String[] split = cleanString(value).split("'", 3);
                value = QueryStringDecoder.decodeComponent(split[2], Charset.forName(split[0]));
            } catch (ArrayIndexOutOfBoundsException e) {
                 throw new ErrorDataDecoderException(e);
            } catch (UnsupportedCharsetException e) {
                throw new ErrorDataDecoderException(e);
            }
        } else {
            // otherwise we need to clean the value
            value = cleanString(value);
        }
        return factory.createAttribute(request, name, value);
    }

    /**
     * Get the FileUpload (new one or current one)
     *
     * @return the InterfaceHttpData if any
     * @throws ErrorDataDecoderException
     */
    private InterfaceHttpData getFileUpload() {
        // eventually restart from existing FileUpload
        // Now get value according to Content-Type and Charset
        Attribute encoding = currentFieldAttributes.get(HttpHeaderNames.CONTENT_TRANSFER_ENCODING);
        Charset localCharset = this.decoder.charset;
        // Default
        TransferEncodingMechanism mechanism = TransferEncodingMechanism.BIT7;
        if (encoding != null) {
            String code;
            try {
                code = encoding.getValue().toLowerCase();
            } catch (IOException e) {
                throw new ErrorDataDecoderException(e);
            }
            if (code.equals(HttpPostBodyUtil.TransferEncodingMechanism.BIT7.value())) {
                localCharset = StandardCharsets.US_ASCII;
            } else if (code.equals(HttpPostBodyUtil.TransferEncodingMechanism.BIT8.value())) {
                localCharset = StandardCharsets.ISO_8859_1;
                mechanism = TransferEncodingMechanism.BIT8;
            } else if (code.equals(HttpPostBodyUtil.TransferEncodingMechanism.BINARY.value())) {
                // no real charset, so let the default
                mechanism = TransferEncodingMechanism.BINARY;
            } else {
                throw new ErrorDataDecoderException("TransferEncoding Unknown: " + code);
            }
        }
        Attribute charsetAttribute = currentFieldAttributes.get(HttpHeaderValues.CHARSET);
        if (charsetAttribute != null) {
            try {
                localCharset = Charset.forName(charsetAttribute.getValue());
            } catch (IOException e) {
                throw new ErrorDataDecoderException(e);
            } catch (UnsupportedCharsetException e) {
                throw new ErrorDataDecoderException(e);
            }
        }
        Attribute filenameAttribute = currentFieldAttributes.get(HttpHeaderValues.FILENAME);
        Attribute nameAttribute = currentFieldAttributes.get(HttpHeaderValues.NAME);
        Attribute contentTypeAttribute = currentFieldAttributes.get(HttpHeaderNames.CONTENT_TYPE);
        Attribute lengthAttribute = currentFieldAttributes.get(HttpHeaderNames.CONTENT_LENGTH);
        long size;
        try {
            size = lengthAttribute != null ? Long.parseLong(lengthAttribute.getValue()) : 0L;
        } catch (IOException e) {
            throw new ErrorDataDecoderException(e);
        } catch (NumberFormatException ignored) {
            size = 0;
        }
        try {
            String contentType;
            if (contentTypeAttribute != null) {
                contentType = contentTypeAttribute.getValue();
            } else {
                contentType = HttpPostBodyUtil.DEFAULT_BINARY_CONTENT_TYPE;
            }
            currentFileUpload = factory.createFileUpload(request,
                    cleanString(nameAttribute.getValue()), cleanString(filenameAttribute.getValue()),
                    contentType, mechanism.value(), localCharset,
                    size);
        } catch (NullPointerException e) {
            throw new ErrorDataDecoderException(e);
        } catch (IllegalArgumentException e) {
            throw new ErrorDataDecoderException(e);
        } catch (IOException e) {
            throw new ErrorDataDecoderException(e);
        }
        return currentFileUpload;
    }

    /**
     * Destroy the {@link HttpPostMultipartRequestDecoder} and release all it resources. After this method
     * was called it is not possible to operate on it anymore.
     */
    @Override
    public void destroy() {
        // Release all data items, including those not yet pulled, only file based items
        cleanFiles();
        // Clean Memory based data
        for (InterfaceHttpData httpData : bodyListHttpData) {
            // Might have been already closed by the user
            if (httpData.refCnt() > 0) {
                httpData.release();
            }
        }

        destroyed = true;

        decoder.close();

        clearCurrentFieldAttributes();
    }

    /**
     * Clean all HttpDatas (on Disk) for the current request.
     */
    @Override
    public void cleanFiles() {
        checkDestroyed();

        factory.cleanRequestHttpData(request);
    }

    /**
     * Remove the given FileUpload from the list of FileUploads to clean
     */
    @Override
    public void removeHttpDataFromClean(InterfaceHttpData data) {
        checkDestroyed();

        factory.removeHttpDataFromClean(request, data);
    }

    /**
     * Clear all attributes from the currentFieldAttributes, and reset the map to null.
     * Make sure any attributes are properly closed.
     */
    private void clearCurrentFieldAttributes() {
        if (currentFieldAttributes != null) {
            currentFieldAttributes = null;
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

    /**
     * This method is package private intentionally in order to allow during tests
     * to access to the amount of memory allocated (capacity) within the private
     * ByteBuf undecodedChunk
     *
     * @return the number of bytes the internal buffer can contain
     */
    int getCurrentAllocatedCapacity() {
        return decoder.getCurrentAllocatedCapacity();
    }
}
