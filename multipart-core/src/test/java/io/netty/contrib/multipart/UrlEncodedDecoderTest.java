/*
 * Copyright 2025 The Netty Project
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
package io.netty.contrib.multipart;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

class UrlEncodedDecoderTest {
    private void expectField(PostBodyDecoder decoder, String name, String value) {
        Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());

        Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
        Assertions.assertEquals(HttpHeaderNames.CONTENT_DISPOSITION, decoder.headerName());
        var cd = Assertions.assertInstanceOf(ContentDisposition.class, decoder.parsedHeaderValue());
        Assertions.assertEquals(name, cd.name());
        Assertions.assertNull(cd.fileName());

        Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());

        PostBodyDecoder.Event next = decoder.next();

        if (next == PostBodyDecoder.Event.CONTENT) {
            Assertions.assertEquals(PostBodyDecoder.Event.CONTENT, next);
            Assertions.assertEquals(value, decoder.decodedContentString());
            next = decoder.next();
        } else {
            //noinspection MisorderedAssertEqualsArguments
            Assertions.assertEquals(value, "");
        }

        Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, next);
    }

    @Test
    public void simple() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            decoder.add(Unpooled.copiedBuffer("foo=bar&fizz=buzz", StandardCharsets.UTF_8));
            decoder.endInput();

            expectField(decoder, "foo", "bar");
            expectField(decoder, "fizz", "buzz");
            Assertions.assertNull(decoder.next());
        }
    }

    @Test
    public void decodePlus() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            decoder.add(Unpooled.copiedBuffer("foo=xyz+abc", StandardCharsets.UTF_8));
            decoder.endInput();

            expectField(decoder, "foo", "xyz abc");
            Assertions.assertNull(decoder.next());
        }
    }

    @Test
    public void decodePercent() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            decoder.add(Unpooled.copiedBuffer("foo=xyz%20abc", StandardCharsets.UTF_8));
            decoder.endInput();

            expectField(decoder, "foo", "xyz abc");
            Assertions.assertNull(decoder.next());
        }
    }

    @Test
    public void special() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            decoder.add(Unpooled.copiedBuffer("foo", StandardCharsets.UTF_8));
            decoder.endInput();

            expectField(decoder, "foo", "");
            Assertions.assertNull(decoder.next());
        }
    }

    @Test
    public void spacesNoValue() {
        // the url spec does not require stripping spaces: https://url.spec.whatwg.org/#urlencoded-parsing
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            decoder.add(Unpooled.copiedBuffer("    ", StandardCharsets.UTF_8));
            decoder.endInput();

            expectField(decoder, "    ", "");
            Assertions.assertNull(decoder.next());
        }
    }

    @Test
    public void spacesValue() {
        // the url spec does not require stripping spaces: https://url.spec.whatwg.org/#urlencoded-parsing
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            decoder.add(Unpooled.copiedBuffer("    =   ", StandardCharsets.UTF_8));
            decoder.endInput();

            expectField(decoder, "    ", "   ");
            Assertions.assertNull(decoder.next());
        }
    }

    private void expectFooBar(PostBodyDecoder decoder, ByteBuf first) {
        decoder.add(first);
        decoder.add(Unpooled.copiedBuffer("ar&fizz=buzz", StandardCharsets.UTF_8));
        decoder.endInput();

        expectField(decoder, "foo", "bar");
        expectField(decoder, "fizz", "buzz");
        Assertions.assertNull(decoder.next());
    }

    @Test
    public void wrappedFirstBuffer() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            expectFooBar(decoder, Unpooled.wrappedBuffer("foo=b".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    public void sliceFirstBuffer() {
        ByteBuf full = Unpooled.copiedBuffer("foo=bxyz", StandardCharsets.UTF_8);
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            expectFooBar(decoder, full.retainedSlice(0, 5));
            Assertions.assertEquals("foo=bxyz", full.toString(StandardCharsets.UTF_8));
        } finally {
            full.release();
        }
    }

    @Test
    public void readOnlyFirstBuffer() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            expectFooBar(decoder, Unpooled.copiedBuffer("foo=b", StandardCharsets.UTF_8).asReadOnly());
        }
    }

    @Test
    public void callerBufferNotModified() {
        ByteBuf first = Unpooled.buffer(64).writeBytes("foo=b".getBytes(StandardCharsets.UTF_8));
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            expectFooBar(decoder, first.retain());
            Assertions.assertEquals(5, first.writerIndex());
            Assertions.assertEquals("foo=b", first.toString(0, 5, StandardCharsets.UTF_8));
            // spare capacity must not have been written to either
            Assertions.assertEquals(0, first.getByte(5));
        } finally {
            first.release();
        }
    }

    @Test
    public void callerCompositeBufferNotModified() {
        CompositeByteBuf first = Unpooled.compositeBuffer();
        first.addComponent(true, Unpooled.copiedBuffer("foo", StandardCharsets.UTF_8));
        first.addComponent(true, Unpooled.copiedBuffer("=b", StandardCharsets.UTF_8));
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            // pass the composite itself (not a duplicate) so that the decoder sees a CompositeByteBuf
            expectFooBar(decoder, first.retain());
            Assertions.assertEquals(2, first.numComponents());
            Assertions.assertEquals(5, first.writerIndex());
            Assertions.assertEquals("foo=b", first.toString(0, 5, StandardCharsets.UTF_8));
        } finally {
            first.release();
        }
    }

    @Test
    public void bufferCompaction() throws IOException {
        byte[] fullData = new byte[10 * 1024 * 1024];
        for (int i = 0; i < fullData.length; i++) {
            // avoid *valid* escape sequences, but we still need some invalid ones to trigger buffering behavior
            int c = ThreadLocalRandom.current().nextInt('f', 'z' + 1);
            if (c == 'f') {
                c = '%';
            }
            fullData[i] = (byte) c;
        }

        MultipartDecoderTest.bufferCompaction(PostBodyDecoder.builder().forUrlEncodedData(), "xyz=", fullData, "");
    }
}
