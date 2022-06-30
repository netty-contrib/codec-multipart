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
import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.ByteCursor;
import io.netty5.buffer.api.DefaultBufferAllocators;
import io.netty5.handler.codec.http.HttpConstants;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.handler.codec.http.QueryStringDecoder;
import io.netty5.util.ByteProcessor;
import io.netty5.util.internal.PlatformDependent;
import io.netty5.util.internal.StringUtil;

import java.io.IOException;
import java.nio.charset.Charset;
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
public class HttpPostStandardRequestDecoder implements InterfaceHttpPostRequestDecoder {

    /**
     * Factory used to create InterfaceHttpData
     */
    private final HttpDataFactory factory;

    /**
     * Request to decode
     */
    private final HttpRequest request;

    /**
     * Default charset to use
     */
    private final Charset charset;

    /**
     * Does the last chunk already received
     */
    private boolean isLastChunk;

    /**
     * HttpDatas from Body
     */
    private final List<InterfaceHttpData> bodyListHttpData = new ArrayList<InterfaceHttpData>();

    /**
     * HttpDatas as Map from Body
     */
    private final Map<String, List<InterfaceHttpData>> bodyMapHttpData = new TreeMap<String, List<InterfaceHttpData>>(
            CaseIgnoringComparator.INSTANCE);

    /**
     * The current channelBuffer
     */
    private Buffer undecodedChunk;

    /**
     * Body HttpDatas current position
     */
    private int bodyListHttpDataRank;

    /**
     * Current getStatus
     */
    private MultiPartStatus currentStatus = MultiPartStatus.NOTSTARTED;

    /**
     * The current Attribute that is currently in decode process
     */
    private Attribute currentAttribute;

    private boolean destroyed;

    private int discardThreshold = HttpPostRequestDecoder.DEFAULT_DISCARD_THRESHOLD;

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
    public HttpPostStandardRequestDecoder(HttpRequest request) {
        this(new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE), request, HttpConstants.DEFAULT_CHARSET);
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
    public HttpPostStandardRequestDecoder(HttpDataFactory factory, HttpRequest request) {
        this(factory, request, HttpConstants.DEFAULT_CHARSET);
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
    public HttpPostStandardRequestDecoder(HttpDataFactory factory, HttpRequest request, Charset charset) {
        this.request = checkNotNullWithIAE(request, "request");
        this.charset = checkNotNullWithIAE(charset, "charset");
        this.factory = checkNotNullWithIAE(factory, "factory");
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
        this.discardThreshold = checkPositiveOrZero(discardThreshold, "discardThreshold");
    }

    /**
     * Return the threshold in bytes after which read data in the buffer should be discarded.
     */
    @Override
    public int getDiscardThreshold() {
        return discardThreshold;
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
    public HttpPostStandardRequestDecoder offer(HttpContent content) {
        checkDestroyed();

        if (content instanceof LastHttpContent) {
            isLastChunk = true;
        }

        Buffer buf = content.payload();
        if (undecodedChunk == null) {
            undecodedChunk =
                    // Since the Handler will release the incoming later on, we need to copy it
                    //
                    // We are explicit allocate a buffer and NOT calling copy() as otherwise it may set a maxCapacity
                    // which is not really usable for us as we may exceed it once we add more bytes.
                    buf.isDirect() ?
                        DefaultBufferAllocators.offHeapAllocator().allocate(buf.readableBytes()).writeBytes(buf) :
                            DefaultBufferAllocators.onHeapAllocator().allocate(buf.readableBytes()).writeBytes(buf);
        } else {
            undecodedChunk.ensureWritable(buf.readableBytes());
            undecodedChunk.writeBytes(buf);
        }
        parseBody();
        if (undecodedChunk != null && undecodedChunk.writerOffset() > discardThreshold) {
            undecodedChunk.compact();
        }
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
        if (currentStatus == MultiPartStatus.PREEPILOGUE || currentStatus == MultiPartStatus.EPILOGUE) {
            if (isLastChunk) {
                currentStatus = MultiPartStatus.EPILOGUE;
            }
            return;
        }
        parseBodyAttributes();
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

    /**
     * This getMethod fill the map and list with as much Attribute as possible from
     * Body in not Multipart mode.
     *
     * @throws ErrorDataDecoderException
     *             if there is a problem with the charset decoding or other
     *             errors
     */
    private void parseBodyAttributesStandard() {
        int firstpos = undecodedChunk.readerOffset();
        int currentpos = firstpos;
        int equalpos;
        int ampersandpos;
        if (currentStatus == MultiPartStatus.NOTSTARTED) {
            currentStatus = MultiPartStatus.DISPOSITION;
        }
        boolean contRead = true;
        try {
            while (undecodedChunk.readableBytes() > 0 && contRead) {
                char read = (char) undecodedChunk.readUnsignedByte();
                currentpos++;
                switch (currentStatus) {
                case DISPOSITION:// search '='
                    if (read == '=') {
                        currentStatus = MultiPartStatus.FIELD;
                        equalpos = currentpos - 1;
                        String key = decodeAttribute(Helpers.toString(undecodedChunk, firstpos, equalpos - firstpos, charset), charset);
                        currentAttribute = factory.createAttribute(request, key);
                        firstpos = currentpos;
                    } else if (read == '&') { // special empty FIELD
                        currentStatus = MultiPartStatus.DISPOSITION;
                        ampersandpos = currentpos - 1;
                        String key = decodeAttribute(
                                Helpers.toString(undecodedChunk, firstpos, ampersandpos - firstpos, charset), charset);
                        currentAttribute = factory.createAttribute(request, key);
                        currentAttribute.setValue(""); // empty
                        addHttpData(currentAttribute);
                        currentAttribute = null;
                        firstpos = currentpos;
                        contRead = true;
                    }
                    break;
                case FIELD:// search '&' or end of line
                    if (read == '&') {
                        currentStatus = MultiPartStatus.DISPOSITION;
                        ampersandpos = currentpos - 1;
                        undecodedChunk.readerOffset(firstpos);
                        setFinalBuffer(undecodedChunk.readSplit(ampersandpos - firstpos));
                        undecodedChunk.skipReadableBytes(1); // skip ampersand
                        currentpos = 1;
                        firstpos = currentpos;
                        contRead = true;
                    } else if (read == HttpConstants.CR) {
                        if (undecodedChunk.readableBytes() > 0) {
                            read = (char) undecodedChunk.readUnsignedByte();
                            currentpos++;
                            if (read == HttpConstants.LF) {
                                currentStatus = MultiPartStatus.PREEPILOGUE;
                                ampersandpos = currentpos - 2;
                                undecodedChunk.readerOffset(firstpos);
                                setFinalBuffer(undecodedChunk.readSplit(ampersandpos - firstpos));
                                undecodedChunk.skipReadableBytes(2); // skip CRLF
                                currentpos = 2;
                                firstpos = currentpos;
                                contRead = false;
                            } else {
                                // Error
                                throw new ErrorDataDecoderException("Bad end of line");
                            }
                        } else {
                            currentpos--;
                        }
                    } else if (read == HttpConstants.LF) {
                        currentStatus = MultiPartStatus.PREEPILOGUE;
                        ampersandpos = currentpos - 1;
                        undecodedChunk.readerOffset(firstpos);
                        setFinalBuffer(undecodedChunk.readSplit(ampersandpos - firstpos));
                        undecodedChunk.skipReadableBytes(1); // skip LF
                        currentpos = 1;
                        firstpos = currentpos;
                        contRead = false;
                    }
                    break;
                default:
                    // just stop
                    contRead = false;
                }
            }
            if (isLastChunk && currentAttribute != null) {
                // special case
                ampersandpos = currentpos;
                if (ampersandpos > firstpos) {
                    undecodedChunk.readerOffset(firstpos);
                    setFinalBuffer(undecodedChunk.readSplit(ampersandpos - firstpos));
                    currentpos = 0;
                } else if (!currentAttribute.isCompleted()) {
                    setFinalBuffer(DefaultBufferAllocators.preferredAllocator().allocate(0));
                }
                firstpos = currentpos;
                currentStatus = MultiPartStatus.EPILOGUE;
            } else if (contRead && currentAttribute != null && currentStatus == MultiPartStatus.FIELD) {
                // reset index except if to continue in case of FIELD getStatus
                undecodedChunk.readerOffset(firstpos);
                currentAttribute.addContent(undecodedChunk.readSplit(currentpos - firstpos),
                                            false);
                currentpos = 0;
                firstpos = currentpos;
            }
            undecodedChunk.readerOffset(firstpos);
        } catch (ErrorDataDecoderException e) {
            // error while decoding
            undecodedChunk.readerOffset(firstpos);
            throw e;
        } catch (IOException e) {
            // error while decoding
            undecodedChunk.readerOffset(firstpos);
            throw new ErrorDataDecoderException(e);
        } catch (IllegalArgumentException e) {
            // error while decoding
            undecodedChunk.readerOffset(firstpos);
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
        if (undecodedChunk == null) {
            return;
        }
        parseBodyAttributesStandard();
    }

    private void setFinalBuffer(Buffer buffer) throws IOException {
        currentAttribute.addContent(buffer, true);
        Buffer decodedBuf = decodeAttribute(currentAttribute.getBuffer(), charset);
        if (decodedBuf != null) { // override content only when ByteBuf needed decoding
            currentAttribute.setContent(decodedBuf);
        }
        addHttpData(currentAttribute);
        currentAttribute = null;
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
            throw new ErrorDataDecoderException("Bad string: '" + s + '\'', e);
        }
    }

    private static Buffer decodeAttribute(Buffer b, Charset charset) {
        ByteCursor cursor = b.openCursor();
        int firstEscaped = cursor.process(new UrlEncodedDetector());
        if (firstEscaped == -1) {
            return null; // nothing to decode
        }

        cursor = b.openCursor();
        Buffer buf = b.isDirect() ? DefaultBufferAllocators.offHeapAllocator().allocate(b.readableBytes()) :
                DefaultBufferAllocators.onHeapAllocator().allocate(b.readableBytes());
        UrlDecoder urlDecode = new UrlDecoder(buf);
        int idx = cursor.process(urlDecode);
        if (urlDecode.nextEscapedIdx != 0) { // incomplete hex byte
            if (idx == -1) {
                idx = b.readableBytes() - 1;
            }
            idx -= urlDecode.nextEscapedIdx - 1;
            buf.close();
            throw new ErrorDataDecoderException(
                String.format("Invalid hex byte at index '%d' in string: '%s'", idx, b.toString(charset)));
        }

        return buf;
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
            if (httpData.isAccessible()) {
                httpData.close();
            }
        }

        destroyed = true;

        if (undecodedChunk != null) {
            if (undecodedChunk.isAccessible()) {
                undecodedChunk.close();
            }
            undecodedChunk = null;
        }
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

    private static final class UrlEncodedDetector implements ByteProcessor {
        @Override
        public boolean process(byte value) {
            return value != '%' && value != '+';
        }
    }

    private static final class UrlDecoder implements ByteProcessor {

        private final Buffer output;
        private int nextEscapedIdx;
        private byte hiByte;

        UrlDecoder(Buffer output) {
            this.output = output;
        }

        @Override
        public boolean process(byte value) {
            if (nextEscapedIdx != 0) {
                if (nextEscapedIdx == 1) {
                    hiByte = value;
                    ++nextEscapedIdx;
                } else {
                    int hi = StringUtil.decodeHexNibble((char) hiByte);
                    int lo = StringUtil.decodeHexNibble((char) value);
                    if (hi == -1 || lo == -1) {
                        ++nextEscapedIdx;
                        return false;
                    }
                    output.writeByte((byte) ((hi << 4) + lo));
                    nextEscapedIdx = 0;
                }
            } else if (value == '%') {
                nextEscapedIdx = 1;
            } else if (value == '+') {
                output.writeByte((byte) ' ');
            } else {
                output.writeByte(value);
            }
            return true;
        }
    }
}
