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

import io.netty.contrib.multipart.FormDecoderException;
import io.netty.contrib.multipart.PostBodyDecoder;
import io.netty5.handler.codec.DecoderException;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpHeaderValues;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.util.internal.StringUtil;

import java.nio.charset.Charset;
import java.util.List;

/**
 * This decoder will decode Body and can handle POST BODY.
 *
 * You <strong>MUST</strong> call {@link #destroy()} after completion to release all resources.
 *
 */
public class HttpPostRequestDecoder implements InterfaceHttpPostRequestDecoder {

    static final int DEFAULT_DISCARD_THRESHOLD = 10 * 1024 * 1024;

    static final int DEFAULT_MAX_FIELDS = 128;

    static final int DEFAULT_MAX_BUFFERED_BYTES = 1024;

    private final InterfaceHttpPostRequestDecoder decoder;

    /**
     *
     * @param request
     *            the request to decode
     * @throws NullPointerException
     *             for request
     * @throws ErrorDataDecoderException
     *             if the default charset was wrong when decoding or other
     *             errors
     * @deprecated Use {@link #builder()}
     */
    @Deprecated
    public HttpPostRequestDecoder(HttpRequest request) {
        this(builder(), request);
    }

    /**
     *
     * @param request
     *            the request to decode
     * @param maxFields
     *            the maximum number of fields the form can have, {@code -1} to disable
     * @param maxBufferedBytes
     *            the maximum number of bytes the decoder can buffer when decoding a field, {@code -1} to disable
     * @throws NullPointerException
     *             for request
     * @throws ErrorDataDecoderException
     *             if the default charset was wrong when decoding or other
     *             errors
     * @deprecated Use {@link #builder()}
     */
    @Deprecated
    public HttpPostRequestDecoder(HttpRequest request, int maxFields, int maxBufferedBytes) {
        this(builder().maxFields(maxFields).undecodedLimit(maxBufferedBytes), request);
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
     * @deprecated Use {@link #builder()}
     */
    @Deprecated
    public HttpPostRequestDecoder(HttpDataFactory factory, HttpRequest request) {
        this(builder().dataFactory(factory), request);
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
     * @deprecated Use {@link #builder()}
     */
    @Deprecated
    public HttpPostRequestDecoder(HttpDataFactory factory, HttpRequest request, Charset charset) {
        this(builder().dataFactory(factory).charset(charset), request);
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
     * @deprecated Use {@link #builder()}
     */
    @Deprecated
    public HttpPostRequestDecoder(HttpDataFactory factory, HttpRequest request, Charset charset,
                                  int maxFields, int maxBufferedBytes) {
        this(builder().dataFactory(factory).charset(charset).maxFields(maxFields).undecodedLimit(maxBufferedBytes), request);
    }

    private HttpPostRequestDecoder(Builder builder, HttpRequest request) {
        if (isMultipart(request)) {
            decoder = builder.buildMultipart(request);
        } else {
            decoder = builder.buildStandard(request);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * <pre>{@code
     * states follow NOTSTARTED PREAMBLE ( (HEADERDELIMITER DISPOSITION (FIELD |
     * FILEUPLOAD))* (HEADERDELIMITER DISPOSITION MIXEDPREAMBLE (MIXEDDELIMITER
     * MIXEDDISPOSITION MIXEDFILEUPLOAD)+ MIXEDCLOSEDELIMITER)* CLOSEDELIMITER)+
     * EPILOGUE
     *
     * First getStatus is: NOSTARTED
     *
     * Content-type: multipart/form-data, boundary=AaB03x => PREAMBLE in Header
     *
     * --AaB03x => HEADERDELIMITER content-disposition: form-data; name="field1"
     * => DISPOSITION
     *
     * Joe Blow => FIELD --AaB03x => HEADERDELIMITER content-disposition:
     * form-data; name="pics" => DISPOSITION Content-type: multipart/mixed,
     * boundary=BbC04y
     *
     * --BbC04y => MIXEDDELIMITER Content-disposition: attachment;
     * filename="file1.txt" => MIXEDDISPOSITION Content-Type: text/plain
     *
     * ... contents of file1.txt ... => MIXEDFILEUPLOAD --BbC04y =>
     * MIXEDDELIMITER Content-disposition: file; filename="file2.gif" =>
     * MIXEDDISPOSITION Content-type: image/gif Content-Transfer-Encoding:
     * binary
     *
     * ...contents of file2.gif... => MIXEDFILEUPLOAD --BbC04y-- =>
     * MIXEDCLOSEDELIMITER --AaB03x-- => CLOSEDELIMITER
     *
     * Once CLOSEDELIMITER is found, last getStatus is EPILOGUE
     *  }</pre>
     */
    protected enum MultiPartStatus {
        NOTSTARTED, PREAMBLE, HEADERDELIMITER, DISPOSITION, FIELD, FILEUPLOAD, MIXEDPREAMBLE, MIXEDDELIMITER,
        MIXEDDISPOSITION, MIXEDFILEUPLOAD, MIXEDCLOSEDELIMITER, CLOSEDELIMITER, PREEPILOGUE, EPILOGUE
    }

    /**
     * Check if the given request is a multipart request
     * @return True if the request is a Multipart request
     */
    public static boolean isMultipart(HttpRequest request) {
        CharSequence mimeType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (mimeType != null && HttpHeaderValues.MULTIPART_FORM_DATA.
                regionMatches(0, mimeType, 0, HttpHeaderValues.MULTIPART_FORM_DATA.length())) {
                return getMultipartDataBoundary(mimeType.toString()) != null;
        }
        return false;
    }

    /**
     * Check from the request ContentType if this request is a Multipart request.
     * @return an array of String if multipartDataBoundary exists with the multipartDataBoundary
     * as first element, charset if any as second (missing if not set), else null
     */
    protected static String[] getMultipartDataBoundary(String contentType) {
        // Check if Post using "multipart/form-data; boundary=--89421926422648 [; charset=xxx]"
        String[] headerContentType = splitHeaderContentType(contentType);
        final String multiPartHeader = HttpHeaderValues.MULTIPART_FORM_DATA.toString();
        if (headerContentType[0].regionMatches(true, 0, multiPartHeader, 0 , multiPartHeader.length())) {
            int mrank;
            int crank;
            final String boundaryHeader = HttpHeaderValues.BOUNDARY.toString();
            if (headerContentType[1].regionMatches(true, 0, boundaryHeader, 0, boundaryHeader.length())) {
                mrank = 1;
                crank = 2;
            } else if (headerContentType[2].regionMatches(true, 0, boundaryHeader, 0, boundaryHeader.length())) {
                mrank = 2;
                crank = 1;
            } else {
                return null;
            }
            String boundary = StringUtil.substringAfter(headerContentType[mrank], '=');
            if (boundary == null) {
                throw new ErrorDataDecoderException("Needs a boundary value");
            }
            if (boundary.charAt(0) == '"') {
                String bound = boundary.trim();
                int index = bound.length() - 1;
                if (bound.charAt(index) == '"') {
                    boundary = bound.substring(1, index);
                }
            }
            final String charsetHeader = HttpHeaderValues.CHARSET.toString();
            if (headerContentType[crank].regionMatches(true, 0, charsetHeader, 0, charsetHeader.length())) {
                String charset = StringUtil.substringAfter(headerContentType[crank], '=');
                if (charset != null) {
                    return new String[] {"--" + boundary, charset};
                }
            }
            return new String[] {"--" + boundary};
        }
        return null;
    }

    @Override
    public boolean isMultipart() {
        return decoder.isMultipart();
    }

    @Override
    public void setDiscardThreshold(int discardThreshold) {
        decoder.setDiscardThreshold(discardThreshold);
    }

    @Override
    public int getDiscardThreshold() {
        return decoder.getDiscardThreshold();
    }

    @Override
    public List<InterfaceHttpData> getBodyHttpDatas() {
        return decoder.getBodyHttpDatas();
    }

    @Override
    public List<InterfaceHttpData> getBodyHttpDatas(String name) {
        return decoder.getBodyHttpDatas(name);
    }

    @Override
    public InterfaceHttpData getBodyHttpData(String name) {
        return decoder.getBodyHttpData(name);
    }

    @Override
    public InterfaceHttpPostRequestDecoder offer(HttpContent<?> content) {
        return decoder.offer(content);
    }

    @Override
    public boolean hasNext() {
        return decoder.hasNext();
    }

    @Override
    public InterfaceHttpData next() {
        return decoder.next();
    }

    @Override
    public InterfaceHttpData currentPartialHttpData() {
        return decoder.currentPartialHttpData();
    }

    @Override
    public void destroy() {
        decoder.destroy();
    }

    @Override
    public void cleanFiles() {
        decoder.cleanFiles();
    }

    @Override
    public void removeHttpDataFromClean(InterfaceHttpData data) {
        decoder.removeHttpDataFromClean(data);
    }

    /**
     * Split the very first line (Content-Type value) in 3 Strings
     *
     * @return the array of 3 Strings
     */
    private static String[] splitHeaderContentType(String sb) {
        int aStart;
        int aEnd;
        int bStart;
        int bEnd;
        int cStart;
        int cEnd;
        aStart = HttpPostBodyUtil.findNonWhitespace(sb, 0);
        aEnd =  sb.indexOf(';');
        if (aEnd == -1) {
            return new String[] { sb, "", "" };
        }
        bStart = HttpPostBodyUtil.findNonWhitespace(sb, aEnd + 1);
        if (sb.charAt(aEnd - 1) == ' ') {
            aEnd--;
        }
        bEnd =  sb.indexOf(';', bStart);
        if (bEnd == -1) {
            bEnd = HttpPostBodyUtil.findEndOfString(sb);
            return new String[] { sb.substring(aStart, aEnd), sb.substring(bStart, bEnd), "" };
        }
        cStart = HttpPostBodyUtil.findNonWhitespace(sb, bEnd + 1);
        if (sb.charAt(bEnd - 1) == ' ') {
            bEnd--;
        }
        cEnd = HttpPostBodyUtil.findEndOfString(sb);
        return new String[] { sb.substring(aStart, aEnd), sb.substring(bStart, bEnd), sb.substring(cStart, cEnd) };
    }

    /**
     * Exception when try reading data from request in chunked format, and not
     * enough data are available (need more chunks)
     */
    public static class NotEnoughDataDecoderException extends DecoderException {
        private static final long serialVersionUID = -7846841864603865638L;

        public NotEnoughDataDecoderException() {
        }

        public NotEnoughDataDecoderException(String msg) {
            super(msg);
        }

        public NotEnoughDataDecoderException(Throwable cause) {
            super(cause);
        }

        public NotEnoughDataDecoderException(String msg, Throwable cause) {
            super(msg, cause);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /**
     * Exception when the body is fully decoded, even if there is still data
     */
    public static class EndOfDataDecoderException extends DecoderException {
        private static final long serialVersionUID = 1336267941020800769L;
    }

    /**
     * Exception when an error occurs while decoding
     */
    public static class ErrorDataDecoderException extends FormDecoderException {
        private static final long serialVersionUID = 5020247425493164465L;

        public ErrorDataDecoderException() {
        }

        public ErrorDataDecoderException(String msg) {
            super(msg);
        }

        public ErrorDataDecoderException(Throwable cause) {
            super(cause);
        }

        public ErrorDataDecoderException(String msg, Throwable cause) {
            super(msg, cause);
        }
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    public static final class Builder {
        private final PostBodyDecoder.Builder decoderBuilder = PostBodyDecoder.builder();
        private HttpDataFactory dataFactory;

        Builder() {
        }

        /**
         * Set the factory to use for creating form field data structures.
         *
         * @param dataFactory The factory
         * @return This builder
         */
        public Builder dataFactory(HttpDataFactory dataFactory) {
            this.dataFactory = dataFactory;
            return this;
        }

        /**
         * Corresponds to {@code maxFields} in old constructors. For details see {@link PostBodyDecoder.Builder}.
         *
         * @see PostBodyDecoder.Builder#maxFields(int)
         * @return This builder
         */
        public Builder maxFields(int maxFields) {
            decoderBuilder.maxFields(maxFields);
            return this;
        }

        /**
         * Corresponds to {@code charset} in old constructors. For details see {@link PostBodyDecoder.Builder}.
         *
         * @see PostBodyDecoder.Builder#charset(Charset)
         * @return This builder
         */
        public Builder charset(Charset charset) {
            decoderBuilder.charset(charset);
            return this;
        }

        /**
         * For details see {@link PostBodyDecoder.Builder}.
         *
         * @see PostBodyDecoder.Builder#compactionThreshold(int)
         * @return This builder
         */
        public Builder compactionThreshold(int compactionThreshold) {
            decoderBuilder.compactionThreshold(compactionThreshold);
            return this;
        }

        /**
         * Corresponds to {@code maxBufferedBytes} in old constructors. For details see {@link PostBodyDecoder.Builder}.
         *
         * @see PostBodyDecoder.Builder#undecodedLimit(int)
         * @return This builder
         */
        public Builder undecodedLimit(int undecodedLimit) {
            decoderBuilder.undecodedLimit(undecodedLimit);
            return this;
        }

        /**
         * Create a new {@link HttpPostRequestDecoder} with the given request. Multipart vs standard form encoding is
         * detected automatically.
         *
         * @param request The request
         * @return The decoder
         */
        public HttpPostRequestDecoder build(HttpRequest request) {
            return new HttpPostRequestDecoder(this, request);
        }

        private HttpDataFactory getOrCreateDataFactory() {
            if (dataFactory == null) {
                return new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);
            } else {
                return dataFactory;
            }
        }

        /**
         * Create a new {@link HttpPostMultipartRequestDecoder} with the given request.
         *
         * @param request The request
         * @return The decoder
         */
        public HttpPostMultipartRequestDecoder buildMultipart(HttpRequest request) {
            return new HttpPostMultipartRequestDecoder(getOrCreateDataFactory(), request, decoderBuilder);
        }

        /**
         * Create a new {@link HttpPostStandardRequestDecoder} with the given request.
         *
         * @param request The request
         * @return The decoder
         */
        public HttpPostStandardRequestDecoder buildStandard(HttpRequest request) {
            return new HttpPostStandardRequestDecoder(getOrCreateDataFactory(), request, decoderBuilder);
        }
    }
}
