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

import io.netty.contrib.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.contrib.handler.codec.http.multipart.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.contrib.handler.codec.http.multipart.HttpPostRequestDecoder.MultiPartStatus;
import io.netty.contrib.handler.codec.http.multipart.HttpPostRequestDecoder.NotEnoughDataDecoderException;
import io.netty.contrib.multipart.HttpPostBodyUtil;
import io.netty.contrib.multipart.HttpPostBodyUtil.TransferEncodingMechanism;
import io.netty.contrib.multipart.PostBodyDecoder;
import io.netty.contrib.multipart.VintageAccess;
import io.netty5.buffer.Buffer;
import io.netty5.buffer.DefaultBufferAllocators;
import io.netty5.handler.codec.http.HttpConstants;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpHeaderValues;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.handler.codec.http.QueryStringDecoder;
import io.netty5.util.internal.PlatformDependent;
import io.netty5.util.internal.StringUtil;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.netty5.util.internal.ObjectUtil.checkNotNullWithIAE;
import static io.netty5.util.internal.ObjectUtil.checkPositiveOrZero;

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

    private final VintageAccess.MultipartDecoder decoder;

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

    private boolean mixed;

    private boolean destroyed;

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
                    builder.charset(Charset.forName(dataBoundary[1]));
                } catch (IllegalCharsetNameException e) {
                    throw new ErrorDataDecoderException(e);
                }
            }
        } else {
            multipartDataBoundary = null;
        }
        decoder = VintageAccess.forBoundaryWithPrefix(builder, multipartDataBoundary);
        decoder.setQuirkMode(true);

        try {
            if (request instanceof HttpContent) {
                // Offer automatically if the given request is als type of HttpContent
                // See #1089
                offer((HttpContent<?>) request);
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
        decoder.setCompactionThreshold(checkPositiveOrZero(discardThreshold, "discardThreshold"));
    }

    /**
     * Return the threshold in bytes after which read data in the buffer should be discarded.
     */
    @Override
    public int getDiscardThreshold() {
        return decoder.getCompactionThreshold();
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
    public HttpPostMultipartRequestDecoder offer(HttpContent<?> content) {
        checkDestroyed();

        if (content instanceof LastHttpContent) {
            isLastChunk = true;
        }

        Buffer buf = content.payload();
        decoder.add(buf.send());
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
     * Be sure to call {@link InterfaceHttpData#close()} after you are done
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
            PostBodyDecoder.Event event = decoder.next();
            if (event == null) {
                break;
            }
            switch (event) {
                case BEGIN_FIELD:
                    if (!mixed) {
                        clearCurrentFieldAttributes();
                        currentFieldAttributes = new TreeMap<CharSequence, Attribute>(CaseIgnoringComparator.INSTANCE);
                    }
                    break;
                case HEADER:
                    handleHeader(decoder.getQuirkHeader());
                    break;
                case BEGIN_MIXED:
                    mixed = true;
                    break;
                case HEADERS_COMPLETE:
                    // Is it a FileUpload
                    Attribute filenameAttribute = currentFieldAttributes.get(HttpHeaderValues.FILENAME);
                    Charset c;
                    if (filenameAttribute != null) {
                        // FileUpload
                        currentStatus = MultiPartStatus.FILEUPLOAD;
                        FileUpload fileUpload = getFileUpload();
                        c = fileUpload.getCharset();
                    } else {
                        if (mixed) {
                            throw new ErrorDataDecoderException("Filename not found");
                        }

                        // Field
                        currentStatus = MultiPartStatus.FIELD;
                        // do not change the buffer position
                        Attribute attribute = getAttribute();
                        c = attribute.getCharset();
                    }
                    decoder.setQuirkPartCharset(c);
                    break;
                case CONTENT:
                    try {
                        ((HttpData) currentPartialHttpData()).addContent(decoder.sendUndecodedPartContent().receive(), false);
                    } catch (IOException e) {
                        throw new ErrorDataDecoderException(e);
                    }
                    break;
                case FIELD_COMPLETE:
                    HttpData partial = (HttpData) currentPartialHttpData();
                    if (partial != null) {
                        try {
                            partial.addContent(DefaultBufferAllocators.onHeapAllocator().allocate(0), true);
                        } catch (IOException e) {
                            throw new ErrorDataDecoderException(e);
                        }
                        addHttpData(currentPartialHttpData());
                        cleanMixedAttributes();
                        currentFileUpload = null;
                        currentAttribute = null;
                    } else {
                        assert mixed;
                        mixed = false;
                    }
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
                        VintageAccess.cleanString(nameAttribute.getValue()), size);
            } else {
                currentAttribute = factory.createAttribute(request,
                        VintageAccess.cleanString(nameAttribute.getValue()));
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
                        VintageAccess.cleanString(contents[1]));
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
                        VintageAccess.cleanString(contents[1]));
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
                            attribute = factory.createAttribute(request, charsetHeader, VintageAccess.cleanString(values));
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
                            attribute = factory.createAttribute(request, VintageAccess.cleanString(name), values);
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
                                    VintageAccess.cleanString(contents[0]), contents[i]);
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
        if (HttpHeaderNames.CONTENT_LENGTH.contentEqualsIgnoreCase(name)) {
            try {
                decoder.setQuirkDefinedLength(Long.parseLong(VintageAccess.cleanString(attribute.getValue())));
            } catch (NumberFormatException | IOException e) {
                decoder.setQuirkDefinedLength(0);
            }
        }
        currentFieldAttributes.put(decoder.isQuirkMode() ? name.toString() : attribute.getName(), attribute);
    }

    private static final String FILENAME_ENCODED = HttpHeaderValues.FILENAME.toString() + '*';

    private Attribute getContentDispositionAttribute(String... values) {
        String name = VintageAccess.cleanString(values[0]);
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
                String[] split = VintageAccess.cleanString(value).split("'", 3);
                value = QueryStringDecoder.decodeComponent(split[2], Charset.forName(split[0]));
            } catch (ArrayIndexOutOfBoundsException e) {
                 throw new ErrorDataDecoderException(e);
            } catch (UnsupportedCharsetException e) {
                throw new ErrorDataDecoderException(e);
            }
        } else {
            // otherwise we need to clean the value
            value = VintageAccess.cleanString(value);
        }
        return factory.createAttribute(request, name, value);
    }

    /**
     * Get the FileUpload (new one or current one)
     *
     * @return the InterfaceHttpData if any
     * @throws ErrorDataDecoderException
     */
    private FileUpload getFileUpload() {
        // eventually restart from existing FileUpload
        // Now get value according to Content-Type and Charset
        Attribute encoding = currentFieldAttributes.get(HttpHeaderNames.CONTENT_TRANSFER_ENCODING);
        Charset localCharset = this.decoder.getCharset();
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
                    VintageAccess.cleanString(nameAttribute.getValue()), VintageAccess.cleanString(filenameAttribute.getValue()),
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
            if (httpData.isAccessible()) {
                httpData.close();
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
            currentFieldAttributes.forEach((charSequence, attribute) -> {
                if (attribute.isAccessible()) {
                    attribute.close();
                }
            });
            currentFieldAttributes = null;
        }
    }

    /**
     * Remove all Attributes that should be cleaned between two FileUpload in
     * Mixed mode
     */
    @SuppressWarnings("EmptyTryBlock")
    private void cleanMixedAttributes() {
        if (currentFieldAttributes != null) {
            try (Attribute charset = currentFieldAttributes.remove(HttpHeaderValues.CHARSET);
                 Attribute clen = currentFieldAttributes.remove(HttpHeaderNames.CONTENT_LENGTH);
                 Attribute transferEncoding = currentFieldAttributes.remove(HttpHeaderNames.CONTENT_TRANSFER_ENCODING);
                 Attribute ctype = currentFieldAttributes.remove(HttpHeaderNames.CONTENT_TYPE);
                 Attribute fname = currentFieldAttributes.remove(HttpHeaderValues.FILENAME)) {
            }
        }
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
