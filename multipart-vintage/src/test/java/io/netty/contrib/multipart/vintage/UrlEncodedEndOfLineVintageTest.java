/*
 * Copyright 2026 The Netty Project
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

import io.netty.buffer.Unpooled;
import io.netty.contrib.multipart.DecoderQuirk;
import io.netty.contrib.multipart.FormDecoderException;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Line endings at the end of a URL-encoded body through the vintage wrapper. With all quirks enabled, the result must
 * match the legacy decoder. Without quirks, data after a line ending and a lone trailing CR must be rejected instead
 * of being silently discarded.
 */
class UrlEncodedEndOfLineVintageTest {
    private static final HttpRequest REQUEST = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");

    private HttpDataFactory dataFactory;

    @BeforeEach
    void setUp() {
        dataFactory = new DefaultHttpDataFactory(false);
    }

    @AfterEach
    void tearDown() {
        dataFactory.cleanAllHttpData();
    }

    private static List<String> decode(Supplier<InterfaceHttpPostRequestDecoder> factory, String body,
                                       int chunkSize) throws IOException {
        InterfaceHttpPostRequestDecoder decoder = factory.get();
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            int i = 0;
            do {
                int n = Math.min(chunkSize, bytes.length - i);
                boolean last = i + n >= bytes.length;
                HttpContent content = last ?
                        new DefaultLastHttpContent(Unpooled.copiedBuffer(bytes, i, n)) :
                        new DefaultHttpContent(Unpooled.copiedBuffer(bytes, i, n));
                try {
                    decoder.offer(content);
                } finally {
                    content.release();
                }
                i += n;
            } while (i < bytes.length);
            List<String> result = new ArrayList<>();
            for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
                result.add(data.getName() + "=" + ((Attribute) data).getValue());
            }
            return result;
        } finally {
            decoder.destroy();
        }
    }

    private InterfaceHttpPostRequestDecoder legacy() {
        return new HttpPostStandardRequestDecoderLegacy(dataFactory, REQUEST, StandardCharsets.UTF_8, -1, -1);
    }

    private InterfaceHttpPostRequestDecoder withQuirks(DecoderQuirk... quirks) {
        return HttpPostRequestDecoder.builder()
                .dataFactory(dataFactory)
                .enableQuirks(quirks)
                .buildStandard(REQUEST);
    }

    private InterfaceHttpPostRequestDecoder allQuirks() {
        return HttpPostRequestDecoder.builder()
                .dataFactory(dataFactory)
                .enableAllQuirks()
                .buildStandard(REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a=b\r", "a=b\n", "a=b\r\n",
            "a=b\r\nc=d", "a=b\nc=d", "a=b\r\n\r\n", "a=b\n&c=d", "a=b&c=d\r\ne=f",
    })
    void allQuirksMatchLegacy(String body) throws IOException {
        for (int chunkSize : new int[] {Integer.MAX_VALUE, 1}) {
            List<String> expected = decode(this::legacy, body, chunkSize);
            assertEquals(expected, decode(this::allQuirks, body, chunkSize), "chunk size " + chunkSize);
            assertEquals(expected, decode(() -> withQuirks(DecoderQuirk.LENIENT_END_OF_LINE), body, chunkSize),
                    "chunk size " + chunkSize);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a=b\n", "a=b\r\n", "a=b&c=d\r\n"})
    void noQuirksAcceptsTrailingLineEnding(String body) throws IOException {
        String expected = body.trim();
        for (int chunkSize : new int[] {Integer.MAX_VALUE, 1}) {
            assertEquals(List.of(expected.split("&")), decode(this::withQuirks, body, chunkSize),
                    "chunk size " + chunkSize);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a=b\r", "a=b\r\nc=d", "a=b\nc=d", "a=b\r\n\r\n", "a=b\n&c=d", "a=b&c=d\r\ne=f"})
    void noQuirksRejectsTrailingData(String body) {
        for (int chunkSize : new int[] {Integer.MAX_VALUE, 1}) {
            assertThrows(FormDecoderException.class, () -> decode(this::withQuirks, body, chunkSize),
                    "chunk size " + chunkSize);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a=b\r\nc=d", "a=b\r"})
    @SuppressWarnings("deprecation")
    void deprecatedConstructorKeepsLegacyBehavior(String body) throws IOException {
        for (int chunkSize : new int[] {Integer.MAX_VALUE, 1}) {
            assertEquals(List.of("a=b"), decode(() -> new HttpPostStandardRequestDecoder(REQUEST), body, chunkSize),
                    "chunk size " + chunkSize);
            assertEquals(List.of("a=b"), decode(() -> new HttpPostStandardRequestDecoder(dataFactory, REQUEST),
                    body, chunkSize), "chunk size " + chunkSize);
        }
    }
}
