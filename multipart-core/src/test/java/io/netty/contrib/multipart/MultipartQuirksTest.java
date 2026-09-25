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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ParameterizedClass
@ValueSource(booleans = {false, true})
class MultipartQuirksTest {
    private final boolean quirk;

    MultipartQuirksTest(boolean quirk) {
        this.quirk = quirk;
    }

    private static void add(PostBodyDecoder decoder, String text) {
        decoder.add(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
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
    void ignoreDelimiterSuffix() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.IGNORE_DELIMITER_SUFFIX);
        }
        try (PostBodyDecoder decoder = builder.forMultipartBoundary("a")) {
            add(decoder, "--a\nfoo:bar\n\nx\n--aX\n--a--\n");

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            if (quirk) {
                // in quirk mode, --aX ends the field, and then the decoder gets stuck on the invalid delimiter line
                assertEquals("x", decoder.decodedContentString());
                assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            } else {
                assertEquals("x\n--aX", decoder.decodedContentString());
                assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            }
            assertNull(decoder.next());
        }
    }

    @Test
    void forwardPartStartDelimiterPrefix() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.FORWARD_PART_START_DELIMITER_PREFIX);
        }
        try (PostBodyDecoder decoder = builder.forMultipartBoundary("a")) {
            add(decoder, "--a\nfoo:bar\n\n-");

            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            if (quirk) {
                // in quirk mode, the partial delimiter is emitted as content
                assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
                assertEquals("-", decoder.decodedContentString());
            }
            assertNull(decoder.next());

            add(decoder, "-a\nfoo:bar\n\n");
            if (quirk) {
                // ... and the rest of the delimiter and the following headers are absorbed into the content
                assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
                assertEquals("-a\nfoo:bar\n", decoder.decodedContentString());
            } else {
                assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
                assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
                assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
                assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            }
        }
    }

    @Test
    void disableEarlyMixedEnd() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.DISABLE_EARLY_MIXED_END);
        }
        try (PostBodyDecoder decoder = builder.forMultipartBoundary("a")) {
            add(decoder, "--a\ncontent-type: multipart/mixed; boundary=b\n\n" +
                    "--b\nfizz: buzz\n\nx\n--a\nfoo: bar\n\n--b\n");

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
            } else {
                // the nested "--b" delimiter starts a new nested field
                assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            }
            assertNull(decoder.next());
        }
    }

    @Test
    void rescanHeadersOnChunkBoundaryReportsEachHeaderOnce() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.RESCAN_HEADERS_ON_CHUNK_BOUNDARY);
        }
        int headerCount = 50;
        StringBuilder body = new StringBuilder("--a\n");
        List<String> expectedNames = new ArrayList<>();
        for (int i = 0; i < headerCount; i++) {
            expectedNames.add("x-h" + i);
            body.append("x-h").append(i).append(": v").append(i).append('\n');
        }
        body.append("\nfoo\n--a--\n");

        List<String> headerNames = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        try (PostBodyDecoder decoder = builder.forMultipartBoundary("a")) {
            // feed the input byte by byte, so that every header line spans multiple chunks
            for (int i = 0; i < body.length(); i++) {
                add(decoder, body.substring(i, i + 1));
                PostBodyDecoder.Event event;
                while ((event = decoder.next()) != null) {
                    if (event == PostBodyDecoder.Event.HEADER) {
                        headerNames.add(decoder.headerName().toString());
                        assertEquals("v" + (headerNames.size() - 1), decoder.headerValue());
                    } else if (event == PostBodyDecoder.Event.CONTENT) {
                        content.append(decoder.decodedContentString());
                    }
                }
            }
        }
        // previously, with the quirk, every chunk would re-parse and re-report all completed headers of the block,
        // leading to a quadratic number of HEADER events
        assertEquals(expectedNames, headerNames);
        assertEquals("foo", content.toString());
    }

    @Test
    void rescanHeadersOnChunkBoundaryRetainsHeaderBlock() {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder().undecodedLimit(30);
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.RESCAN_HEADERS_ON_CHUNK_BOUNDARY);
        }
        try (PostBodyDecoder decoder = builder.forMultipartBoundary("a")) {
            add(decoder, "--a\n");
            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertNull(decoder.next());
            Runnable feedHeaders = () -> {
                for (int i = 0; i < 10; i++) {
                    add(decoder, "x-h" + i + ": ");
                    assertNull(decoder.next());
                    add(decoder, "v" + i + "\n");
                    assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
                    assertEquals("x-h" + i, decoder.headerName());
                    assertEquals("v" + i, decoder.headerValue());
                    assertNull(decoder.next());
                }
            };
            if (quirk) {
                // like the legacy decoder, the completed headers of an unfinished header block are still retained,
                // so they count towards the undecoded limit
                assertThrows(UndecodedDataLimitExceededException.class, feedHeaders::run);
            } else {
                feedHeaders.run();
            }
        }
    }
}
