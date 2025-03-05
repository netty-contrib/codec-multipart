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

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

/**
 * This fuzz test compares the behavior of {@link HttpPostMultipartRequestDecoder} with that of
 * {@link HttpPostMultipartRequestDecoderLegacy}, a copy of the old decoder implementation.
 */
public class MultipartComparisonTest extends AbstractComparisonTest {
    static final HttpRequest REQUEST = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    static final HttpDataFactory FACTORY = new DefaultHttpDataFactory(false);

    static {
        REQUEST.headers().add(HttpHeaderNames.CONTENT_TYPE, "multipart/form-data; boundary=" + BOUNDARY);
    }

    public static void main(String[] args) throws Throwable {
        minimize(MultipartComparisonTest.class, "src/test/resources/io/netty/contrib/handler/codec/http/multipart/MultiPartComparisonTestInputs/compare/crash-ad03f90654bb98077b5ca8093bfe6f7ba875341a");
    }

    @SuppressWarnings("unused")
    public static void fuzzerTestOneInput(byte[] bytes) {
        new MultipartComparisonTest().compare(bytes);
    }


    @Override
    protected InterfaceHttpPostRequestDecoder createNormal() {
        return new HttpPostMultipartRequestDecoder(FACTORY, REQUEST);
    }

    @Override
    protected InterfaceHttpPostRequestDecoder createLegacy() {
        return new HttpPostMultipartRequestDecoderLegacy(FACTORY, REQUEST);
    }
}
