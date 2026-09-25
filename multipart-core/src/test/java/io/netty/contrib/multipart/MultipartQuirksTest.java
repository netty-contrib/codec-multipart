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
import org.junit.jupiter.params.ParameterizedTest;
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

    /**
     * Decode the given multipart input chunks (boundary {@code a}), followed by {@link PostBodyDecoder#endInput()},
     * and return the list of completed field contents.
     */
    private List<String> decodeUntilEnd(String... chunks) {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.ALLOW_MISSING_CLOSE_DELIMITER);
        }
        try (PostBodyDecoder decoder = builder.forMultipartBoundary("a")) {
            List<String> fields = new ArrayList<>();
            StringBuilder content = new StringBuilder();
            for (String chunk : chunks) {
                if (!chunk.isEmpty()) {
                    add(decoder, chunk);
                }
                // drain after each chunk and before endInput, as a streaming user would
                while (true) {
                    PostBodyDecoder.Event event = decoder.next();
                    if (event == null) {
                        break;
                    }
                    content = handle(decoder, event, fields, content);
                }
            }
            decoder.endInput();
            while (true) {
                PostBodyDecoder.Event event = decoder.next();
                if (event == null) {
                    break;
                }
                content = handle(decoder, event, fields, content);
            }
            // must remain null after completion
            assertNull(decoder.next());
            return fields;
        }
    }

    private static StringBuilder handle(PostBodyDecoder decoder, PostBodyDecoder.Event event, List<String> fields,
                                        StringBuilder content) {
        switch (event) {
            case CONTENT:
                content.append(decoder.decodedContentString());
                return content;
            case FIELD_COMPLETE:
                fields.add(content.toString());
                return new StringBuilder();
            default:
                return content;
        }
    }

    @Test
    void validCloseDelimiter() {
        assertEquals(List.of("x", "y"), decodeUntilEnd("--a\nfoo:bar\n\nx\n--a\nfoo:bar\n\ny\n--a--\n"));
        assertEquals(List.of("x"), decodeUntilEnd("--a\r\nfoo:bar\r\n\r\nx\r\n--a--\r\n"));
        // no line break after the close delimiter
        assertEquals(List.of("x"), decodeUntilEnd("--a\nfoo:bar\n\nx\n--a--"));
        // epilogue
        assertEquals(List.of("x"), decodeUntilEnd("--a\nfoo:bar\n\nx\n--a--\nepilogue"));
        // form without any fields
        assertEquals(List.of(), decodeUntilEnd("--a--\n"));
    }

    @Test
    void validCloseDelimiterWithTrailingCr() {
        // the close delimiter is complete, only the (optional) LF of the trailing CRLF is missing
        assertEquals(List.of("x"), decodeUntilEnd("--a\r\nfoo:bar\r\n\r\nx\r\n--a--\r"));
        assertEquals(List.of(), decodeUntilEnd("--a--\r"));
    }

    @Test
    void missingCloseDelimiter() {
        String input = "--a\nfoo:bar\n\nx\n--a\nfoo:bar\n\ny\n";
        if (quirk) {
            // the last field is incomplete and silently dropped
            assertEquals(List.of("x"), decodeUntilEnd(input));
        } else {
            FormDecoderException e = assertThrows(FormDecoderException.class, () -> decodeUntilEnd(input));
            assertEquals("Multipart input ended without a close delimiter", e.getMessage());
        }
    }

    @Test
    void missingCloseDelimiterAfterCompletePart() {
        // the part delimiter is there but not the close delimiter ("--a--")
        // "--a" at the end of input is not followed by a line break or "--", so it is not a delimiter (#30). The
        // content of the part is therefore incomplete, and dropped with the quirk.
        String input = "--a\nfoo:bar\n\nx\n--a";
        if (quirk) {
            assertEquals(List.of(), decodeUntilEnd(input));
        } else {
            assertThrows(FormDecoderException.class, () -> decodeUntilEnd(input));
        }
    }

    /**
     * Input truncated in the delimiter, the headers, or the content of the part following a complete part, or in the
     * close delimiter.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "--a\nfoo:bar\n\nx\n--a\n",
            "--a\nfoo:bar\n\nx\n--a\nfoo:b",
            "--a\nfoo:bar\n\nx\n--a\nfoo:bar\n",
            "--a\nfoo:bar\n\nx\n--a\nfoo:bar\n\ny",
    })
    void truncatedNextPart(String input) {
        if (quirk) {
            assertEquals(List.of("x"), decodeUntilEnd(input));
        } else {
            assertThrows(FormDecoderException.class, () -> decodeUntilEnd(input));
        }
    }

    /**
     * Input ending in a potential delimiter whose suffix is still undecided (held back as potential content by the
     * delimiter search). At the end of input, it is not a valid delimiter, so the current part is truncated.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "--a\nfoo:bar\n\nx\n--a",
            "--a\r\nfoo:bar\r\n\r\nx\r\n--a",
            "--a\r\nfoo:bar\r\n\r\nx\r\n--a\r",
            "--a\nfoo:bar\n\nx\n--a-",
            "--a\r\nfoo:bar\r\n\r\nx\r\n--a-",
    })
    void truncatedInHeldBackDelimiter(String input) {
        if (quirk) {
            assertEquals(List.of(), decodeUntilEnd(input));
        } else {
            FormDecoderException e = assertThrows(FormDecoderException.class, () -> decodeUntilEnd(input));
            assertEquals("Multipart input ended without a close delimiter", e.getMessage());
        }
    }

    /**
     * Input truncated right after a part delimiter at the start of the body.
     */
    @ParameterizedTest
    @ValueSource(strings = {"--a", "--a\r", "--a-", "--a\n", "--a\r\n"})
    void truncatedAfterFirstDelimiter(String input) {
        if (quirk) {
            assertEquals(List.of(), decodeUntilEnd(input));
        } else {
            FormDecoderException e = assertThrows(FormDecoderException.class, () -> decodeUntilEnd(input));
            assertEquals("Multipart input ended without a close delimiter", e.getMessage());
        }
    }

    /**
     * The close delimiter is complete at the end of input, without a trailing line break. The delimiter search may
     * hold back the delimiter while its suffix is undecided, so also feed the input in small chunks.
     */
    @Test
    void closeDelimiterAtEndOfInputChunked() {
        assertEquals(List.of("x"), decodeUntilEnd("--a\nfoo:bar\n\nx\n--a", "--"));
        assertEquals(List.of("x"), decodeUntilEnd("--a\nfoo:bar\n\nx\n--a-", "-"));
        assertEquals(List.of("x"), decodeUntilEnd("--a\r\nfoo:bar\r\n\r\nx\r\n--a", "--"));
        assertEquals(List.of("x"), decodeUntilEnd("--a\r\nfoo:bar\r\n\r\nx\r\n--a", "--", "\r"));
        assertEquals(List.of("x"), decodeUntilEnd("--a\r\nfoo:bar\r\n\r\nx\r\n--a--\r", "\n"));
        assertEquals(List.of(), decodeUntilEnd("--a", "--"));
        assertEquals(List.of(), decodeUntilEnd("--a-", "-", "\r"));
        String input = "--a\r\nfoo:bar\r\n\r\nx\r\n--a\r\nfoo:bar\r\n\r\ny\r\n--a--";
        assertEquals(List.of("x", "y"), decodeUntilEnd(input.split("")));
        assertEquals(List.of("x", "y"), decodeUntilEnd((input + "\r").split("")));
    }

    /**
     * Like {@link #truncatedInHeldBackDelimiter}, but the input is fed byte by byte.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "--a\r\nfoo:bar\r\n\r\nx\r\n--a",
            "--a\r\nfoo:bar\r\n\r\nx\r\n--a\r",
            "--a\r\nfoo:bar\r\n\r\nx\r\n--a-",
    })
    void truncatedInHeldBackDelimiterChunked(String input) {
        if (quirk) {
            assertEquals(List.of(), decodeUntilEnd(input.split("")));
        } else {
            assertThrows(FormDecoderException.class, () -> decodeUntilEnd(input.split("")));
        }
    }

    @Test
    void emptyInput() {
        if (quirk) {
            assertEquals(List.of(), decodeUntilEnd(""));
        } else {
            assertThrows(FormDecoderException.class, () -> decodeUntilEnd(""));
        }
    }
}
