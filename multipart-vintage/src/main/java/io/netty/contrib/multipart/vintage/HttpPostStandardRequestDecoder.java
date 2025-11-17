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
package io.netty.contrib.multipart.vintage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.contrib.multipart.ContentDisposition;
import io.netty.contrib.multipart.FormDecoderException;
import io.netty.contrib.multipart.PostBodyDecoder;
import io.netty.contrib.multipart.TooManyFormFieldsException;
import io.netty.contrib.multipart.VintageAccess;
import io.netty.contrib.multipart.vintage.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.contrib.multipart.vintage.HttpPostRequestDecoder.ErrorDataDecoderException;
import io.netty.contrib.multipart.vintage.HttpPostRequestDecoder.NotEnoughDataDecoderException;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder;
import io.netty.util.internal.PlatformDependent;

import java.io.IOException;
import java.nio.charset.Charset;
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
public class HttpPostStandardRequestDecoder implements InterfaceHttpPostRequestDecoder {

    /**
     * Factory used to create InterfaceHttpData
     */
    private final HttpDataFactory factory;

    /**
     * Request to decode
     */
    private final HttpRequest request;

    private final VintageAccess.UrlEncodedDecoder decoder;

    /**
     * The maximum number of fields allows by the form
     */
    private final int maxFields;

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
     * The current Attribute that is currently in decode process
     */
    private Attribute currentAttribute;

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
     * @deprecated Use {@link HttpPostRequestDecoder#builder()}
     */
    @Deprecated
    public HttpPostStandardRequestDecoder(HttpRequest request) {
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
     * @deprecated Use {@link HttpPostRequestDecoder#builder()}
     */
    @Deprecated
    public HttpPostStandardRequestDecoder(HttpDataFactory factory, HttpRequest request) {
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
     * @deprecated Use {@link HttpPostRequestDecoder#builder()}
     */
    @Deprecated
    public HttpPostStandardRequestDecoder(HttpDataFactory factory, HttpRequest request, Charset charset) {
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
     * @deprecated Use {@link HttpPostRequestDecoder#builder()}
     */
    @Deprecated
    public HttpPostStandardRequestDecoder(HttpDataFactory factory, HttpRequest request, Charset charset,
                                          int maxFields, int maxBufferedBytes) {
        this(factory, request, PostBodyDecoder.builder().charset(charset).maxFields(maxFields).undecodedLimit(maxBufferedBytes == 0 ? Integer.MAX_VALUE : maxBufferedBytes));
    }

    HttpPostStandardRequestDecoder(HttpDataFactory factory, HttpRequest request, PostBodyDecoder.Builder builder) {
        this.request = checkNotNullWithIAE(request, "request");
        this.factory = checkNotNullWithIAE(factory, "factory");
        // we do our own maxFields checks to pass the legacy comparison fuzzer
        this.maxFields = VintageAccess.maxFields(builder);
        if (VintageAccess.maxFields(builder) < Integer.MAX_VALUE) {
            builder.maxFields(VintageAccess.maxFields(builder) + 1);
        }

        this.decoder = (VintageAccess.UrlEncodedDecoder) builder.forUrlEncodedData();
        decoder.setQuirkMode(true);
        try {
            if (request instanceof HttpContent) {
                // Offer automatically if the given request is as type of HttpContent
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
            throw new IllegalStateException(HttpPostStandardRequestDecoder.class.getSimpleName()
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
        return false;
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

        if (!decoder.isEof()) {
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

        if (!decoder.isEof()) {
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

        if (!decoder.isEof()) {
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
    public HttpPostStandardRequestDecoder offer(HttpContent content) {
        checkDestroyed();

        ByteBuf buf = content.content();
        decoder.add(buf.retain());
        if (content instanceof LastHttpContent) {
            decoder.endInput();
        }
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

        if (decoder.isEof()) {
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
        return currentAttribute;
    }

    /**
     * This getMethod will parse as much as possible data and fill the list and map
     *
     * @throws ErrorDataDecoderException
     *             if there is a problem with the charset decoding or other
     *             errors
     */
    private void parseBody() {
        parseBodyAttributes();
    }

    /**
     * Utility function to add a new decoded data
     */
    protected void addHttpData(InterfaceHttpData data) {
        if (data == null) {
            return;
        }
        if (maxFields > 0 && bodyListHttpData.size() >= maxFields) {
            throw new TooManyFormFieldsException();
        }
        List<InterfaceHttpData> datas = bodyMapHttpData.get(data.getName());
        if (datas == null) {
            datas = new ArrayList<InterfaceHttpData>(1);
            bodyMapHttpData.put(data.getName(), datas);
        }
        datas.add(data);
        bodyListHttpData.add(data);
    }

    /**
     * This getMethod fill the map and list with as much Attribute as possible from
     * Body in not Multipart mode.
     *
     * @throws ErrorDataDecoderException
     *             if there is a problem with the charset decoding or other
     *             errors
     */
    private void parseBodyAttributesStandard() {
        try {
            while (true) {
                PostBodyDecoder.Event event = decoder.next();
                if (event == null) {
                    break;
                }
                if (event == PostBodyDecoder.Event.HEADER) {
                    currentAttribute = factory.createAttribute(request, ((ContentDisposition) decoder.parsedHeaderValue()).name());
                } else if (event == PostBodyDecoder.Event.CONTENT) {
                    currentAttribute.addContent(decoder.undecodedContent(), false);
                } else if (event == PostBodyDecoder.Event.FIELD_COMPLETE) {
                    currentAttribute.addContent(Unpooled.EMPTY_BUFFER, true);
                    // in netty 4, decoding happens late
                    ByteBuf bb = currentAttribute.getByteBuf().retain();
                    try {
                        decoder.decodeComponent(bb, false);
                    } catch (Exception e) {
                        bb.release();
                        throw e;
                    }
                    currentAttribute.setContent(bb);

                    addHttpData(currentAttribute);
                    currentAttribute = null;
                }
            }
        } catch (FormDecoderException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new ErrorDataDecoderException(e);
        }
    }

    /**
     * This getMethod fill the map and list with as much Attribute as possible from
     * Body in not Multipart mode.
     *
     * @throws ErrorDataDecoderException
     *             if there is a problem with the charset decoding or other
     *             errors
     */
    private void parseBodyAttributes() {
        parseBodyAttributesStandard();
    }

    /**
     * Destroy the {@link HttpPostStandardRequestDecoder} and release all it resources. After this method
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
    }

    /**
     * Clean all {@link HttpData}s for the current request.
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
}
