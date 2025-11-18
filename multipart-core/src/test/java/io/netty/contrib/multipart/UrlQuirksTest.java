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


import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ParameterizedClass
@ValueSource(booleans = {false, true})
class UrlQuirksTest {
    private final boolean quirk;

    public UrlQuirksTest(boolean quirk) {
        this.quirk = quirk;
    }

    private static void add(PostBodyDecoder decoder, byte[] b) {
        decoder.add(Unpooled.wrappedBuffer(b));
    }

    private static void add(PostBodyDecoder decoder, String s) {
        decoder.add(Unpooled.copiedBuffer(s, StandardCharsets.UTF_8));
    }

    @Test
    public void earlyDecode() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.EARLY_DECODE);
        }
        try (PostBodyDecoder decoder = builder.forUrlEncodedData()) {
            // code point is partially percent-encoded, see quirk javadoc
            add(decoder, new byte[] {(byte) 0xc8, '%', 'b', 'a' });
            decoder.endInput();

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());

            assertEquals(quirk ? "\uFFFD\uFFFD" : "Ⱥ", ((ContentDisposition) decoder.parsedHeaderValue()).name());

            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());

            assertNull(decoder.next());
        }
    }

    @Test
    public void waitOnCr() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.WAIT_ON_CR);
        }
        try (PostBodyDecoder decoder = builder.forUrlEncodedData()) {
            add(decoder, "foo=\r");

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            if (!quirk) {
                assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            }
            assertNull(decoder.next());
        }
    }

    @Test
    public void earlyCrlfCheck() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.EARLY_CRLF_CHECK);
        }
        try (PostBodyDecoder decoder = builder.forUrlEncodedData()) {
            add(decoder, "foo=\ra");

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            if (!quirk) {
                assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            }
            assertThrows(FormDecoderException.class, decoder::next);
        }
    }

    @Test
    public void refuseNonHexPercentDecode() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.REFUSE_NON_HEX_PERCENT_DECODE);
        }
        try (PostBodyDecoder decoder = builder.forUrlEncodedData()) {
            add(decoder, "foo=a%xx");

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());

            if (quirk) {
                assertThrows(FormDecoderException.class, decoder::decodedContentString);
            } else {
                assertEquals("a%xx", decoder.decodedContentString());
            }
        }
    }

    @Test
    public void refuseShortPercentDecode() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.REFUSE_SHORT_PERCENT_DECODE);
        }
        try (PostBodyDecoder decoder = builder.forUrlEncodedData()) {
            add(decoder, "foo=a%a");
            decoder.endInput();

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());

            if (quirk) {
                assertThrows(FormDecoderException.class, decoder::decodedContentString);
            } else {
                assertEquals("a%a", decoder.decodedContentString());
            }
        }
    }
}
