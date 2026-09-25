/*
 * Copyright 2022 The Netty Project
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
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.MemoryAttribute;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static io.netty.handler.codec.http.DefaultHttpHeadersFactory.headersFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpPostStandardRequestDecoderTest {

    @Test
    void testDecodeAttributes() {
        String requestBody = "key1=value1&key2=value2";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");

        HttpPostStandardRequestDecoder decoder = HttpPostRequestDecoder.builder()
                .dataFactory(httpDiskDataFactory())
                .buildStandard(request);
        ByteBuf buf = Unpooled.wrappedBuffer(requestBody.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultLastHttpContent(buf);
        decoder.offer(httpContent);

        assertEquals(2, decoder.getBodyHttpDatas().size());
        assertMemoryAttribute(decoder.getBodyHttpData("key1"), "value1");
        assertMemoryAttribute(decoder.getBodyHttpData("key2"), "value2");
        decoder.destroy();
    }

    @Test
    void testDecodeSingleAttributeWithNoValue() {
        String requestBody = "key1";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload",
                headersFactory().newHeaders().add("Content-Type", "application/x-www-form-urlencoded"));

        HttpPostStandardRequestDecoder decoder = new HttpPostStandardRequestDecoder(httpDiskDataFactory(), request);
        ByteBuf buf = Unpooled.wrappedBuffer(requestBody.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultLastHttpContent(buf);
        decoder.offer(httpContent);

        assertEquals(1, decoder.getBodyHttpDatas().size());
        assertMemoryAttribute(decoder.getBodyHttpData("key1"), "");
        decoder.destroy();
    }

    @Test
    void testDecodeSingleAttributeWithNoValueEmptyLast() {
        String requestBody = "key1";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload",
                headersFactory().newHeaders().add("Content-Type", "application/x-www-form-urlencoded"));

        HttpPostStandardRequestDecoder decoder = HttpPostRequestDecoder.builder()
                .dataFactory(httpDiskDataFactory())
                .buildStandard(request);
        ByteBuf buf = Unpooled.wrappedBuffer(requestBody.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultHttpContent(buf);
        decoder.offer(httpContent);

        decoder.offer(LastHttpContent.EMPTY_LAST_CONTENT);

        assertEquals(1, decoder.getBodyHttpDatas().size());
        assertMemoryAttribute(decoder.getBodyHttpData("key1"), "");
        decoder.destroy();
    }

    @Test
    void testDecodeEndAttributeWithNoValue() {
        String requestBody = "key1=value1&key2";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload",
                headersFactory().newHeaders().add("Content-Type", "application/x-www-form-urlencoded"));

        HttpPostStandardRequestDecoder decoder = new HttpPostStandardRequestDecoder(httpDiskDataFactory(), request);
        ByteBuf buf = Unpooled.wrappedBuffer(requestBody.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultLastHttpContent(buf);
        decoder.offer(httpContent);

        assertEquals(2, decoder.getBodyHttpDatas().size());
        assertMemoryAttribute(decoder.getBodyHttpData("key1"), "value1");
        assertMemoryAttribute(decoder.getBodyHttpData("key2"), "");
        decoder.destroy();
    }

    @Test
    @Disabled // https://github.com/netty/netty/pull/13998
    void testDecodeJsonAttributeAsEmpty() {
        String requestBody = "{\"iAm\": \" a JSON!\"}";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload",
                headersFactory().newHeaders().add("Content-Type", "application/json"));

        HttpPostStandardRequestDecoder decoder = new HttpPostStandardRequestDecoder(httpDiskDataFactory(), request);
        ByteBuf buf = Unpooled.wrappedBuffer(requestBody.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultLastHttpContent(buf);
        decoder.offer(httpContent);

        assertEquals(0, decoder.getBodyHttpDatas().size());
        decoder.destroy();
    }

    @Test
    @Disabled // https://github.com/netty/netty/pull/13998
    void testDecodeJsonAttributeAsEmptyAndNoHeaders() {
        String requestBody = "{\"iAm\": \" a JSON!\"}";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");

        HttpPostStandardRequestDecoder decoder = HttpPostRequestDecoder.builder()
                .dataFactory(httpDiskDataFactory())
                .buildStandard(request);
        ByteBuf buf = Unpooled.wrappedBuffer(requestBody.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultLastHttpContent(buf);
        decoder.offer(httpContent);

        assertEquals(0, decoder.getBodyHttpDatas().size());
        decoder.destroy();
    }

    @Test
    void testDecodeStartAttributeWithNoValue() {
        String requestBody = "key1&key2=value2";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");

        HttpPostStandardRequestDecoder decoder = new HttpPostStandardRequestDecoder(httpDiskDataFactory(), request);
        ByteBuf buf = Unpooled.wrappedBuffer(requestBody.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultLastHttpContent(buf);
        decoder.offer(httpContent);

        assertEquals(2, decoder.getBodyHttpDatas().size());
        assertMemoryAttribute(decoder.getBodyHttpData("key1"), "");
        assertMemoryAttribute(decoder.getBodyHttpData("key2"), "value2");
        decoder.destroy();
    }

    @Test
    void testDecodeMultipleAttributesWithNoValue() {
        String requestBody = "key1&key2&key3";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload",
                headersFactory().newHeaders().add("Content-Type", "application/x-www-form-urlencoded"));

        HttpPostStandardRequestDecoder decoder = new HttpPostStandardRequestDecoder(httpDiskDataFactory(), request);
        ByteBuf buf = Unpooled.wrappedBuffer(requestBody.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultLastHttpContent(buf);
        decoder.offer(httpContent);

        assertEquals(3, decoder.getBodyHttpDatas().size());
        assertMemoryAttribute(decoder.getBodyHttpData("key1"), "");
        assertMemoryAttribute(decoder.getBodyHttpData("key2"), "");
        assertMemoryAttribute(decoder.getBodyHttpData("key3"), "");
        decoder.destroy();
    }

    @Test
    void testDecodeNestedAttributeWithNoValue() {
        String requestBody = "key1=value1&key2&key3=value3";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");

        HttpPostStandardRequestDecoder decoder = new HttpPostStandardRequestDecoder(httpDiskDataFactory(), request);
        ByteBuf buf = Unpooled.wrappedBuffer(requestBody.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultLastHttpContent(buf);
        decoder.offer(httpContent);

        assertEquals(3, decoder.getBodyHttpDatas().size());
        assertMemoryAttribute(decoder.getBodyHttpData("key1"), "value1");
        assertMemoryAttribute(decoder.getBodyHttpData("key2"), "");
        assertMemoryAttribute(decoder.getBodyHttpData("key3"), "value3");
        decoder.destroy();
    }

    @Test
    void testDecodeAttributesWithAmpersandPrefixSkipsNullAttribute() {
        String requestBody = "&key1=value1";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");

        HttpPostStandardRequestDecoder decoder = HttpPostRequestDecoder.builder()
                .dataFactory(httpDiskDataFactory())
                .buildStandard(request);
        ByteBuf buf = Unpooled.wrappedBuffer(requestBody.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultLastHttpContent(buf);
        decoder.offer(httpContent);

        assertEquals(1, decoder.getBodyHttpDatas().size());
        assertMemoryAttribute(decoder.getBodyHttpData("key1"), "value1");
        decoder.destroy();
    }

    @Test
    void testDecodeZeroAttributesWithAmpersandPrefix() {
        String requestBody = "&";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");

        HttpPostStandardRequestDecoder decoder = new HttpPostStandardRequestDecoder(httpDiskDataFactory(), request);
        ByteBuf buf = Unpooled.wrappedBuffer(requestBody.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultLastHttpContent(buf);
        decoder.offer(httpContent);

        assertEquals(0, decoder.getBodyHttpDatas().size());
        decoder.destroy();
    }

    @Test
    void testOfferDoesNotModifyContent() {
        ByteBuf first = Unpooled.buffer(64).writeBytes("key1=value1&ke".getBytes(CharsetUtil.UTF_8));
        first.retain();
        try {
            assertContentNotModified(first);
            // spare capacity must not have been written to either
            assertEquals(0, first.getByte(first.writerIndex()));
        } finally {
            first.release();
        }
    }

    @Test
    void testOfferDoesNotModifyCompositeContent() {
        CompositeByteBuf first = Unpooled.compositeBuffer();
        first.addComponent(true, Unpooled.copiedBuffer("key1=val", CharsetUtil.UTF_8));
        first.addComponent(true, Unpooled.copiedBuffer("ue1&ke", CharsetUtil.UTF_8));
        first.retain();
        try {
            assertContentNotModified(first);
            assertEquals(2, first.numComponents());
        } finally {
            first.release();
        }
    }

    private static void assertContentNotModified(ByteBuf first) {
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");

        HttpPostStandardRequestDecoder decoder = new HttpPostStandardRequestDecoder(httpDiskDataFactory(), request);
        DefaultHttpContent firstContent = new DefaultHttpContent(first);
        DefaultHttpContent secondContent = new DefaultLastHttpContent(
                Unpooled.copiedBuffer("y2=value2", CharsetUtil.UTF_8));
        try {
            decoder.offer(firstContent);
            decoder.offer(secondContent);

            assertEquals(2, decoder.getBodyHttpDatas().size());
            assertMemoryAttribute(decoder.getBodyHttpData("key1"), "value1");
            assertMemoryAttribute(decoder.getBodyHttpData("key2"), "value2");

            assertEquals(0, first.readerIndex());
            assertEquals(14, first.writerIndex());
            assertEquals("key1=value1&ke", first.toString(CharsetUtil.UTF_8));
        } finally {
            decoder.destroy();
            firstContent.release();
            secondContent.release();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "a, a",
            "a&b=c, a",
            "a=, ''",
            "a=&b=c, ''",
            "a=x, ''",
    })
    void testKeyWithoutValueCompletedState(String requestBody, String keysWithoutValue) throws IOException {
        List<String> noValue = Arrays.asList(keysWithoutValue.split(","));
        for (boolean quirks : new boolean[] {true, false}) {
            for (String factoryType : new String[] {"memory", "disk", "mixed"}) {
                DefaultHttpDataFactory factory;
                switch (factoryType) {
                    case "memory":
                        factory = new DefaultHttpDataFactory(false);
                        break;
                    case "disk":
                        factory = new DefaultHttpDataFactory(true);
                        break;
                    default:
                        // limit 0 so that non-empty values actually move to disk
                        factory = new DefaultHttpDataFactory(0);
                        break;
                }
                HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
                HttpPostRequestDecoder.Builder builder = HttpPostRequestDecoder.builder().dataFactory(factory);
                if (quirks) {
                    builder.enableAllQuirks();
                }
                HttpPostStandardRequestDecoder decoder = builder.buildStandard(request);
                try {
                    decoder.offer(new DefaultLastHttpContent(
                            Unpooled.wrappedBuffer(requestBody.getBytes(CharsetUtil.UTF_8))));
                    for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
                        Attribute attribute = (Attribute) data;
                        String message = requestBody + " " + factoryType + " quirks=" + quirks + " " + data.getName();
                        // like netty 4, disk attributes for keys without '=' are not marked as completed
                        boolean expectCompleted = !"disk".equals(factoryType) || !noValue.contains(data.getName());
                        assertEquals(expectCompleted, attribute.isCompleted(), message);
                        if (noValue.contains(data.getName())) {
                            assertEquals("", attribute.getValue(), message);
                            assertEquals(0, attribute.length(), message);
                        }
                    }
                } finally {
                    decoder.destroy();
                }
            }
        }
    }

    @Test
    void testPercentDecodingDoesNotModifyOfferedBuffers() {
        String firstBody = "k%41=v%41&x";
        String secondBody = "=y%42+z";
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        HttpPostStandardRequestDecoder decoder = HttpPostRequestDecoder.builder()
                .dataFactory(httpDiskDataFactory())
                .buildStandard(request);
        ByteBuf first = Unpooled.copiedBuffer(firstBody, CharsetUtil.UTF_8);
        ByteBuf second = Unpooled.copiedBuffer(secondBody, CharsetUtil.UTF_8);
        try {
            decoder.offer(new DefaultHttpContent(first));
            decoder.offer(new DefaultLastHttpContent(second));

            assertEquals(2, decoder.getBodyHttpDatas().size());
            assertMemoryAttribute(decoder.getBodyHttpData("kA"), "vA");
            assertMemoryAttribute(decoder.getBodyHttpData("x"), "yB z");

            // neither the indices nor the content of the caller buffers may be changed by percent decoding
            assertEquals(0, first.readerIndex());
            assertEquals(firstBody.length(), first.writerIndex());
            assertEquals(firstBody, first.toString(CharsetUtil.UTF_8));
            assertEquals(0, second.readerIndex());
            assertEquals(secondBody.length(), second.writerIndex());
            assertEquals(secondBody, second.toString(CharsetUtil.UTF_8));
        } finally {
            decoder.destroy();
            first.release();
            second.release();
        }
    }

    @Test
    void testPercentDecodingSingleChunkDoesNotModifyOfferedBuffer() {
        String body = "k%41+x=v%41+";
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        HttpPostStandardRequestDecoder decoder = HttpPostRequestDecoder.builder()
                .dataFactory(httpDiskDataFactory())
                .buildStandard(request);
        ByteBuf buf = Unpooled.copiedBuffer(body, CharsetUtil.UTF_8);
        try {
            decoder.offer(new DefaultLastHttpContent(buf));

            assertEquals(1, decoder.getBodyHttpDatas().size());
            assertMemoryAttribute(decoder.getBodyHttpData("kA x"), "vA ");
            assertEquals(0, buf.readerIndex());
            assertEquals(body.length(), buf.writerIndex());
            assertEquals(body, buf.toString(CharsetUtil.UTF_8));
        } finally {
            decoder.destroy();
            buf.release();
        }
    }

    @Test
    void testPercentDecodingReadOnlyBuffer() {
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        HttpPostStandardRequestDecoder decoder = HttpPostRequestDecoder.builder()
                .dataFactory(httpDiskDataFactory())
                .buildStandard(request);
        ByteBuf buf = Unpooled.copiedBuffer("k%41=v%41&a+b=c+d", CharsetUtil.UTF_8).asReadOnly();
        try {
            decoder.offer(new DefaultLastHttpContent(buf));

            assertEquals(2, decoder.getBodyHttpDatas().size());
            assertMemoryAttribute(decoder.getBodyHttpData("kA"), "vA");
            assertMemoryAttribute(decoder.getBodyHttpData("a b"), "c d");
        } finally {
            decoder.destroy();
            buf.release();
        }
    }

    private static DefaultHttpDataFactory httpDiskDataFactory() {
        return new DefaultHttpDataFactory(false);
    }

    private static void assertMemoryAttribute(InterfaceHttpData data, String expectedValue) {
        assertEquals(InterfaceHttpData.HttpDataType.Attribute, data.getHttpDataType());
        assertEquals(((MemoryAttribute) data).getValue(), expectedValue);
    }

}
