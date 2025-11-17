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

import io.netty5.buffer.DefaultBufferAllocators;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ParameterizedClass
@ValueSource(booleans = {false, true})
class MultipartQuirksTest {
    private final boolean quirk;

    public MultipartQuirksTest(boolean quirk) {
        this.quirk = quirk;
    }

    private static void add(PostBodyDecoder decoder, String text) {
        decoder.add(DefaultBufferAllocators.preferredAllocator().copyOf(text, StandardCharsets.UTF_8).send());
    }

    @Test
    void stopAfterMultipartMixedHeader() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.STOP_AFTER_MULTIPART_MIXED_HEADER);
        }
        try (PostBodyDecoder decoder = builder.forMultipartBoundary("a")) {
            add(decoder, "--a\ncontent-type: multipart/mixed; boundary=b\nfoo: bar\n");

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            if (quirk) {
                assertEquals(PostBodyDecoder.Event.BEGIN_MIXED, decoder.next());
                assertNull(decoder.next());
            } else {
                assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
                assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
                assertNull(decoder.next());
            }
        }
    }

    @Test
    void inverseDelimiterAtBufferStart() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.INVERSE_DELIMITER_AT_BUFFER_START);
        }
        try (PostBodyDecoder decoder = builder.forMultipartBoundary("a")) {
            add(decoder, "--a\nfoo:bar\n\nx");

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());

            add(decoder, "--a\ny");
            if (quirk) {
                // in quirk mode we wrongly see this as a field end, even though the boundary was not preceded by LF
                assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            } else {
                assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            }
        }
    }

    @Test
    void conservativeLfBacktrack() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.CONSERVATIVE_LF_BACKTRACK);
        }
        try (PostBodyDecoder decoder = builder.forMultipartBoundary("a")) {
            add(decoder, "--a\nfoo:bar\n\na\nyyy");

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            String s = decoder.decodedContentString();
            assertEquals(quirk ? "a" : "a\nyyy", s);
            Assertions.assertNull(decoder.next());
        }
    }

    @Test
    void forwardChunkCr() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.FORWARD_CHUNK_CR);
        }
        try (PostBodyDecoder decoder = builder.forMultipartBoundary("a")) {
            add(decoder, "--a\nfoo:bar\n\na\r");

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            String s = decoder.decodedContentString();
            assertEquals(quirk ? "a\r" : "a", s);
            Assertions.assertNull(decoder.next());
        }
    }

    @Test
    void disableEarlyMixedEnd() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.DISABLE_EARLY_MIXED_END);
        }
        try (PostBodyDecoder decoder = builder.forMultipartBoundary("a")) {
            add(decoder, "--a\ncontent-type: multipart/mixed; boundary=b\n\n--b\nfizz: buzz\n\nx\n--a\nfoo: bar\n\n--b");

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.BEGIN_MIXED, decoder.next());
            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            if (!quirk) {
                assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
                assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
                assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
                assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
                assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            }
            assertNull(decoder.next());
        }
    }

    @Test
    void conservativeWhitespaceSkip() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.CONSERVATIVE_WHITESPACE_SKIP);
        }
        try (PostBodyDecoder decoder = builder.forMultipartBoundary("a")) {
            add(decoder, "--a\n\r\r\n\n");

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            if (quirk) {
                assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
                assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            }
            assertNull(decoder.next());
        }
    }
}
