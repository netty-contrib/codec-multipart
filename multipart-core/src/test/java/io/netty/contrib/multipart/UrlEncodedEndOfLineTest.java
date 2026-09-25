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
package io.netty.contrib.multipart;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Line endings at the end of a URL-encoded body, with and without {@link DecoderQuirk#LENIENT_END_OF_LINE}.
 * Bodies are fed in a single chunk and byte-by-byte.
 */
@ParameterizedClass
@ValueSource(booleans = {false, true})
class UrlEncodedEndOfLineTest {
    private static final String ERROR = "<error>";

    private final boolean quirk;

    UrlEncodedEndOfLineTest(boolean quirk) {
        this.quirk = quirk;
    }

    /**
     * Decode the given body, returning the fields as {@code name=value} strings joined by {@code ;}.
     */
    private String decode(String body, int chunkSize) {
        PostBodyDecoder.Builder builder = PostBodyDecoder.builder();
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.LENIENT_END_OF_LINE);
        }
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        try (PostBodyDecoder decoder = builder.forUrlEncodedData()) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < bytes.length; i += chunkSize) {
                decoder.add(Unpooled.copiedBuffer(bytes, i, Math.min(chunkSize, bytes.length - i)));
                drain(decoder, fields, current);
            }
            decoder.endInput();
            drain(decoder, fields, current);
            // the decoder must be in a terminal state: further calls do not produce anything
            assertEquals(null, decoder.next());
        }
        return String.join(";", fields);
    }

    private static void drain(PostBodyDecoder decoder, List<String> fields, StringBuilder current) {
        while (true) {
            PostBodyDecoder.Event event = decoder.next();
            if (event == null) {
                return;
            }
            switch (event) {
                case HEADER:
                    current.setLength(0);
                    current.append(((ContentDisposition) decoder.parsedHeaderValue()).name()).append('=');
                    break;
                case CONTENT:
                    current.append(decoder.decodedContentString());
                    break;
                case FIELD_COMPLETE:
                    fields.add(current.toString());
                    break;
                default:
                    break;
            }
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // body                  | without quirk | with LENIENT_END_OF_LINE
            "'a=b'                   | a=b           | a=b",
            "'a=b\\r\\n'             | a=b           | a=b",
            "'a=b\\n'                | a=b           | a=b",
            "'a=b\\r'                | <error>       | a=b",
            "'a=b\\rc'               | <error>       | <error>",
            "'a=b\\r\\nc=d'          | <error>       | a=b",
            "'a=b\\nc=d'             | <error>       | a=b",
            "'a=b\\r\\n\\r\\n'       | <error>       | a=b",
            "'a=b\\n\\n'             | <error>       | a=b",
            "'a=b\\r\\n&c=d'         | <error>       | a=b",
            "'a=b&c\\r\\n'           | a=b;c=        | 'a=b;c\\r\\n='",
            "'a=b&\\r\\n'            | a=b           | 'a=b;\\r\\n='",
            "'a\\r\\nb'              | <error>       | 'a\\r\\nb='",
            "'\\r\\n'                | ''            | '\\r\\n='",
    })
    void endOfLine(String rawBody, String expectedStrict, String expectedLenient) {
        String body = unescape(rawBody);
        String expected = unescape(quirk ? expectedLenient : expectedStrict);
        for (int chunkSize : new int[] {Integer.MAX_VALUE, 1}) {
            if (expected.equals(ERROR)) {
                assertThrows(FormDecoderException.class, () -> decode(body, chunkSize));
            } else {
                assertEquals(expected, decode(body, chunkSize), "chunk size " + chunkSize);
            }
        }
    }

    private static String unescape(String s) {
        return s == null ? "" : s.replace("\\r", "\r").replace("\\n", "\n");
    }
}
