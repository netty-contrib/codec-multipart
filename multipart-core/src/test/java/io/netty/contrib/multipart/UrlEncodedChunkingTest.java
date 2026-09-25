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
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Percent-decoding of url encoded values must not depend on how the input is split into chunks.
 */
class UrlEncodedChunkingTest {
    private static final String ERROR = "<error>";

    static Stream<Set<DecoderQuirk>> quirkSets() {
        return Stream.of(
                EnumSet.noneOf(DecoderQuirk.class),
                EnumSet.of(DecoderQuirk.REFUSE_SHORT_PERCENT_DECODE),
                EnumSet.of(DecoderQuirk.REFUSE_NON_HEX_PERCENT_DECODE),
                EnumSet.of(DecoderQuirk.REFUSE_SHORT_PERCENT_DECODE, DecoderQuirk.REFUSE_NON_HEX_PERCENT_DECODE),
                EnumSet.of(DecoderQuirk.EARLY_CRLF_CHECK),
                EnumSet.of(DecoderQuirk.EARLY_CRLF_CHECK, DecoderQuirk.REFUSE_SHORT_PERCENT_DECODE),
                EnumSet.of(DecoderQuirk.EARLY_CRLF_CHECK, DecoderQuirk.REFUSE_NON_HEX_PERCENT_DECODE),
                EnumSet.allOf(DecoderQuirk.class)
        );
    }

    /**
     * Decode the given body split into the given chunks, and return the concatenated decoded values (separated by
     * {@code |}), or {@link #ERROR} if decoding fails.
     */
    private static String decode(Set<DecoderQuirk> quirks, List<String> chunks) {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (!quirks.isEmpty()) {
            builder.enableQuirks(quirks.toArray(new DecoderQuirk[0]));
        }
        StringBuilder result = new StringBuilder();
        try (PostBodyDecoder decoder = builder.forUrlEncodedData()) {
            for (int i = 0; i <= chunks.size(); i++) {
                if (i == chunks.size()) {
                    decoder.endInput();
                } else {
                    decoder.add(Unpooled.copiedBuffer(chunks.get(i), StandardCharsets.ISO_8859_1));
                }
                PostBodyDecoder.Event event;
                while ((event = decoder.next()) != null) {
                    if (event == PostBodyDecoder.Event.CONTENT) {
                        ByteBuf content = decoder.decodedContent();
                        try {
                            result.append(content.toString(StandardCharsets.ISO_8859_1));
                        } finally {
                            content.release();
                        }
                    } else if (event == PostBodyDecoder.Event.FIELD_COMPLETE) {
                        result.append('|');
                    }
                }
            }
        } catch (FormDecoderException e) {
            return ERROR;
        }
        return result.toString();
    }

    private static List<String> split(String body, int... splits) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        for (int split : splits) {
            chunks.add(body.substring(start, split));
            start = split;
        }
        chunks.add(body.substring(start));
        return chunks;
    }

    static Stream<Arguments> issueCases() {
        return Stream.of(
                Arguments.of(EnumSet.of(DecoderQuirk.REFUSE_SHORT_PERCENT_DECODE), "a=%%44", 4, "%D|"),
                Arguments.of(EnumSet.of(DecoderQuirk.REFUSE_NON_HEX_PERCENT_DECODE), "a=%%4", 4, ERROR),
                Arguments.of(EnumSet.of(DecoderQuirk.REFUSE_NON_HEX_PERCENT_DECODE), "a=%4%", 5, ERROR),
                Arguments.of(EnumSet.of(DecoderQuirk.REFUSE_SHORT_PERCENT_DECODE), "a=%4%", 5, ERROR),
                Arguments.of(EnumSet.of(DecoderQuirk.REFUSE_SHORT_PERCENT_DECODE), "a=%4%41", 5, "%4A|"),
                Arguments.of(EnumSet.of(DecoderQuirk.REFUSE_NON_HEX_PERCENT_DECODE), "a=%4%41", 5, ERROR),
                Arguments.of(EnumSet.of(DecoderQuirk.EARLY_CRLF_CHECK), "a=%41", 3, "A|"),
                Arguments.of(EnumSet.of(DecoderQuirk.EARLY_CRLF_CHECK), "a=%41", 4, "A|"),
                Arguments.of(EnumSet.allOf(DecoderQuirk.class), "a=%41", 3, "A|"),
                Arguments.of(EnumSet.allOf(DecoderQuirk.class), "a=%41", 4, "A|")
        );
    }

    @ParameterizedTest
    @MethodSource("issueCases")
    public void issueCases(Set<DecoderQuirk> quirks, String body, int split, String expected) {
        assertEquals(expected, decode(quirks, List.of(body)));
        assertEquals(expected, decode(quirks, split(body, split)));
    }

    @ParameterizedTest
    @MethodSource("quirkSets")
    public void exhaustiveSplits(Set<DecoderQuirk> quirks) {
        char[] alphabet = {'%', '4', '1', 'G'};
        for (int length = 1; length <= 5; length++) {
            int[] digits = new int[length];
            char[] value = new char[length];
            do {
                for (int i = 0; i < length; i++) {
                    value[i] = alphabet[digits[i]];
                }
                for (String suffix : new String[]{"", "&b=%41"}) {
                    String body = "a=" + new String(value) + suffix;
                    String expected = decode(quirks, List.of(body));

                    // one split point
                    for (int split = 1; split < body.length(); split++) {
                        assertEquals(expected, decode(quirks, split(body, split)),
                                () -> "body " + body + " quirks " + quirks);
                    }
                    // one byte at a time
                    int[] allSplits = new int[body.length() - 1];
                    for (int i = 0; i < allSplits.length; i++) {
                        allSplits[i] = i + 1;
                    }
                    assertEquals(expected, decode(quirks, split(body, allSplits)),
                            () -> "body " + body + " quirks " + quirks);
                }
            } while (increment(digits, alphabet.length));
        }
    }

    @Test
    public void skipContent() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            decoder.add(Unpooled.copiedBuffer("a=x%4", StandardCharsets.ISO_8859_1));
            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            assertEquals("x", decoder.decodedContentString());
            assertNull(decoder.next());

            // skipped content, the held back escape is dropped with it
            decoder.add(Unpooled.copiedBuffer("ZZZ", StandardCharsets.ISO_8859_1));
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            assertNull(decoder.next());

            decoder.add(Unpooled.copiedBuffer("1yy", StandardCharsets.ISO_8859_1));
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            assertEquals("1yy", decoder.decodedContentString());
            assertNull(decoder.next());

            decoder.endInput();
            assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            assertNull(decoder.next());
        }
    }

    @Test
    public void skipFlushContent() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            decoder.add(Unpooled.copiedBuffer("a=x%4", StandardCharsets.ISO_8859_1));
            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            assertEquals("x", decoder.decodedContentString());
            assertNull(decoder.next());

            decoder.add(Unpooled.copiedBuffer("&b=1", StandardCharsets.ISO_8859_1));
            decoder.endInput();
            // flush of the held back escape, skipped
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            assertEquals("1", decoder.decodedContentString());
            assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            assertNull(decoder.next());
        }
    }

    @Test
    public void mixedDecodedAndUndecoded() {
        PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData();
        try {
            VintageAccess.UrlEncodedDecoder access = (VintageAccess.UrlEncodedDecoder) decoder;
            decoder.add(Unpooled.copiedBuffer("a=x%4", StandardCharsets.ISO_8859_1));
            assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            assertEquals("x", decoder.decodedContentString());
            assertNull(decoder.next());

            // the held back escape comes first
            decoder.add(Unpooled.copiedBuffer("1yy", StandardCharsets.ISO_8859_1));
            assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            ByteBuf undecoded = access.undecodedContent();
            try {
                assertEquals("%41yy", undecoded.toString(StandardCharsets.ISO_8859_1));
            } finally {
                undecoded.release();
            }
            assertNull(decoder.next());

            decoder.endInput();
            assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            assertNull(decoder.next());
        } finally {
            decoder.close();
        }
    }

    private static boolean increment(int[] digits, int radix) {
        for (int i = 0; i < digits.length; i++) {
            if (++digits[i] < radix) {
                return true;
            }
            digits[i] = 0;
        }
        return false;
    }
}
