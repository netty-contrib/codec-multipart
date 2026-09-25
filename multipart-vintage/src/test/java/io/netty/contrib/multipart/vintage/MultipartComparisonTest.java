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
package io.netty.contrib.multipart.vintage;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * This fuzz test compares the behavior of {@link HttpPostMultipartRequestDecoder} with that of
 * {@link HttpPostMultipartRequestDecoderLegacy}, a copy of the old decoder implementation.
 */
public class MultipartComparisonTest extends AbstractComparisonTest {
    static final HttpRequest REQUEST = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

    static {
        REQUEST.headers().add(HttpHeaderNames.CONTENT_TYPE, "multipart/form-data; boundary=" + BOUNDARY);
    }

    public static void main(String[] args) throws Throwable {
        minimize(MultipartComparisonTest.class,
                "src/test/resources/io/netty/contrib/handler/codec/http/multipart/" +
                        "MultiPartComparisonTestInputs/compare/crash-ad03f90654bb98077b5ca8093bfe6f7ba875341a");
    }

    @SuppressWarnings("unused")
    public static void fuzzerTestOneInput(byte[] bytes) {
        new MultipartComparisonTest().compare(bytes);
    }

    @Override
    protected InterfaceHttpPostRequestDecoder createNormal(HttpDataFactory factory) {
        return HttpPostRequestDecoder.builder()
                .enableAllQuirks()
                .dataFactory(factory).maxFields(-1).undecodedLimit(-1).buildMultipart(REQUEST);
    }

    @Override
    protected InterfaceHttpPostRequestDecoder createLegacy(HttpDataFactory factory) {
        return new HttpPostMultipartRequestDecoderLegacy(factory, REQUEST, StandardCharsets.UTF_8, -1, -1);
    }

    @Test
    void headersSplitByteByByte() {
        // Every byte arrives in its own chunk, so header parsing hits a chunk boundary in every line. This exercises
        // the RESCAN_HEADERS_ON_CHUNK_BOUNDARY quirk, which must behave like the legacy decoder.
        String body = "--a\r\n" +
                "content-disposition: form-data; name=\"field\"\r\n" +
                "content-transfer-encoding: 8bit\r\n" +
                "content-type: text/plain; charset=utf-16\r\n" +
                "content-length: 3\r\n" +
                "x-unknown: foo\r\n" +
                "\r\n" +
                "bar\r\n" +
                "--a\r\n" +
                "content-type: application/octet-stream\r\n" +
                "content-disposition: form-data; name=\"file\"; filename=\"f.txt\"\r\n" +
                "content-transfer-encoding: binary\r\n" +
                "\r\n" +
                "baz\r\n" +
                "--a\r\n" +
                "content-disposition: form-data; name=\"mixed\"\r\n" +
                "content-type: multipart/mixed; boundary=b\r\n" +
                "\r\n" +
                "--b\r\n" +
                "content-disposition: attachment; filename=\"g.txt\"\r\n" +
                "content-type: text/plain\r\n" +
                "\r\n" +
                "qux\r\n" +
                "--b--\r\n" +
                "--a--\r\n";
        StringBuilder split = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            if (i != 0) {
                split.append(FUZZ_SEPARATOR_STR);
            }
            split.append(body.charAt(i));
        }
        compare(split.toString().getBytes(StandardCharsets.US_ASCII));
    }
}
