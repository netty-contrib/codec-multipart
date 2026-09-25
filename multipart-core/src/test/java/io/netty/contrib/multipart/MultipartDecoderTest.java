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
package io.netty.contrib.multipart;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class MultipartDecoderTest {

    @Test
    public void testSimple() {
        // from https://stackoverflow.com/questions/4238809/example-of-multipart-form-data
        String input = "-----------------------------9051914041544843365972754266\r\n" +
                "Content-Disposition: form-data; name=\"text\"\r\n" +
                "\r\n" +
                "text default\r\n" +
                "-----------------------------9051914041544843365972754266\r\n" +
                "Content-Disposition: form-data; name=\"file1\"; filename=\"a.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "Content of a.txt.\n" +
                "\r\n" +
                "-----------------------------9051914041544843365972754266\r\n" +
                "Content-Disposition: form-data; name=\"file2\"; filename=\"a.html\"\r\n" +
                "Content-Type: text/html\r\n" +
                "\r\n" +
                "<!DOCTYPE html><title>Content of a.html.</title>\n" +
                "\r\n" +
                "-----------------------------9051914041544843365972754266--";
        try (PostBodyDecoder decoder = PostBodyDecoder.builder()
                .forMultipartBoundary("---------------------------9051914041544843365972754266")) {
            decoder.add(Unpooled.copiedBuffer(input, StandardCharsets.UTF_8));

            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals("Content-Disposition", decoder.headerName());
            Assertions.assertEquals("form-data; name=\"text\"", decoder.headerValue());
            Assertions.assertEquals("text", ((ContentDisposition) decoder.parsedHeaderValue()).name());
            Assertions.assertNull(((ContentDisposition) decoder.parsedHeaderValue()).fileName());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            Assertions.assertEquals("text default", decoder.decodedContentString());
            Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());

            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals("Content-Disposition", decoder.headerName());
            Assertions.assertEquals("form-data; name=\"file1\"; filename=\"a.txt\"", decoder.headerValue());
            Assertions.assertEquals("file1", ((ContentDisposition) decoder.parsedHeaderValue()).name());
            Assertions.assertEquals("a.txt", ((ContentDisposition) decoder.parsedHeaderValue()).fileName());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals("Content-Type", decoder.headerName());
            Assertions.assertEquals("text/plain", decoder.headerValue());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            Assertions.assertEquals("Content of a.txt.\n", decoder.decodedContentString());
            Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());

            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals("Content-Disposition", decoder.headerName());
            Assertions.assertEquals("form-data; name=\"file2\"; filename=\"a.html\"", decoder.headerValue());
            Assertions.assertEquals("file2", ((ContentDisposition) decoder.parsedHeaderValue()).name());
            Assertions.assertEquals("a.html", ((ContentDisposition) decoder.parsedHeaderValue()).fileName());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals("Content-Type", decoder.headerName());
            Assertions.assertEquals("text/html", decoder.headerValue());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            Assertions.assertEquals("<!DOCTYPE html><title>Content of a.html.</title>\n",
                    decoder.decodedContentString());
            Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
        }
    }

    @Test
    public void testMixed() {
        String input = "--a\r\n" +
                "content-disposition: form-data; name=\"normal\"\r\n" +
                "\r\n" +
                "xyz\r\n" +
                "--a\r\n" +
                "content-disposition: form-data; name=\"mix\"\r\n" +
                "content-type: multipart/mixed; boundary=b\r\n" +
                "\r\n" +
                "--b\r\n" +
                "content-disposition: file; filename=\"1.txt\"\r\n" +
                "\r\n" +
                "file1\r\n" +
                "--b\r\n" +
                "content-disposition: file; filename=\"2.txt\"\r\n" +
                "\r\n" +
                "file2\r\n" +
                "--b--\r\n" +
                "--a--\r\n";
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forMultipartBoundary("a")) {
            decoder.add(Unpooled.copiedBuffer(input, StandardCharsets.UTF_8));

            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals("content-disposition", decoder.headerName());
            Assertions.assertEquals("form-data; name=\"normal\"", decoder.headerValue());
            Assertions.assertEquals("normal", ((ContentDisposition) decoder.parsedHeaderValue()).name());
            Assertions.assertNull(((ContentDisposition) decoder.parsedHeaderValue()).fileName());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            Assertions.assertEquals("xyz", decoder.decodedContentString());
            Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());

            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals("content-disposition", decoder.headerName());
            Assertions.assertEquals("form-data; name=\"mix\"", decoder.headerValue());
            Assertions.assertEquals("mix", ((ContentDisposition) decoder.parsedHeaderValue()).name());
            Assertions.assertNull(((ContentDisposition) decoder.parsedHeaderValue()).fileName());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals("content-type", decoder.headerName());
            Assertions.assertEquals("multipart/mixed; boundary=b", decoder.headerValue());
            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_MIXED, decoder.next());

            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals("content-disposition", decoder.headerName());
            Assertions.assertEquals("file; filename=\"1.txt\"", decoder.headerValue());
            Assertions.assertEquals("1.txt", ((ContentDisposition) decoder.parsedHeaderValue()).fileName());
            Assertions.assertNull(((ContentDisposition) decoder.parsedHeaderValue()).name());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            Assertions.assertEquals("file1", decoder.decodedContentString());
            Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());

            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals("content-disposition", decoder.headerName());
            Assertions.assertEquals("file; filename=\"2.txt\"", decoder.headerValue());
            Assertions.assertEquals("2.txt", ((ContentDisposition) decoder.parsedHeaderValue()).fileName());
            Assertions.assertNull(((ContentDisposition) decoder.parsedHeaderValue()).name());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            Assertions.assertEquals("file2", decoder.decodedContentString());
            Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());

            Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next()); // end of mixed
            Assertions.assertNull(decoder.next());
        }
    }

    @Test
    public void findDelimiter() {
        for (int ro = 0; ro < 4; ro++) {
            Assertions.assertEquals(0, findDelimiter(ro, "\r\n", "abc"));
            Assertions.assertEquals(0, findDelimiter(ro, "\n", "abc"));
            Assertions.assertEquals(0, findDelimiter(ro, "\r\na", "abc"));
            Assertions.assertEquals(0, findDelimiter(ro, "\na", "abc"));
            Assertions.assertEquals(0, findDelimiter(ro, "\r\nab", "abc"));
            Assertions.assertEquals(0, findDelimiter(ro, "\nab", "abc"));
            Assertions.assertEquals(2, findDelimiter(ro, "ab\r\nab", "abc"));
            Assertions.assertEquals(2, findDelimiter(ro, "ab\nab", "abc"));
            Assertions.assertEquals(~5, findDelimiter(ro, "\r\nabc\r\n", "abc"));
            Assertions.assertEquals(~5, findDelimiter(ro, "\r\nabc\n", "abc"));
            Assertions.assertEquals(~5, findDelimiter(ro, "\r\nabc--", "abc"));
            Assertions.assertEquals(~4, findDelimiter(ro, "\nabc\r\n", "abc"));
            Assertions.assertEquals(~4, findDelimiter(ro, "\nabc--", "abc"));
            Assertions.assertEquals(~3, findDelimiter(ro, "abc\r\n", "abc"));
            Assertions.assertEquals(~3, findDelimiter(ro, "abc--", "abc"));
            Assertions.assertEquals(2, findDelimiter(ro, "\n\n\n", "abc"));

            // delimiter suffix not yet known
            Assertions.assertEquals(0, findDelimiter(ro, "\r\nabc", "abc"));
            Assertions.assertEquals(0, findDelimiter(ro, "\nabc", "abc"));
            Assertions.assertEquals(0, findDelimiter(ro, "\r\nabc\r", "abc"));
            Assertions.assertEquals(0, findDelimiter(ro, "\r\nabc-", "abc"));
            Assertions.assertEquals(0, findDelimiter(ro, "abc", "abc"));
            Assertions.assertEquals(0, findDelimiter(ro, "abc-", "abc"));
            Assertions.assertEquals(2, findDelimiter(ro, "xy\r\nabc", "abc"));
            Assertions.assertEquals(2, findDelimiter(ro, "xy\r\nabc\r", "abc"));

            // invalid delimiter suffix: not a delimiter
            Assertions.assertEquals(6, findDelimiter(ro, "\r\nabcx", "abc"));
            Assertions.assertEquals(5, findDelimiter(ro, "\nabcx", "abc"));
            Assertions.assertEquals(4, findDelimiter(ro, "abcx", "abc"));
            Assertions.assertEquals(5, findDelimiter(ro, "abc-x", "abc"));
            Assertions.assertEquals(7, findDelimiter(ro, "\r\nabc\rx", "abc"));
            Assertions.assertEquals(7, findDelimiter(ro, "\r\nabc-x", "abc"));
            Assertions.assertEquals(6, findDelimiter(ro, "\r\nabcx\r\nabc\r\n", "abc"));
        }
    }

    private static int findDelimiter(int readerOffset, String input, String delimiter) {
        ByteBuf b = ByteBufAllocator.DEFAULT.buffer(input.length() + readerOffset);
        try (MultipartDecoder decoder = PostBodyDecoder.builder().forBoundary0("a")) {
            b.writeZero(readerOffset);
            b.skipBytes(readerOffset);

            b.writeBytes(input.getBytes(StandardCharsets.UTF_8));
            return decoder.findDelimiter(b, delimiter.getBytes(StandardCharsets.UTF_8));
        } finally {
            b.release();
        }
    }

    @Test
    public void delimiterWithInvalidSuffixIsContent() {
        String input = "--a\r\n" +
                "Content-Disposition: form-data; name=\"foo\"\r\n" +
                "\r\n" +
                "x\r\n--aX\r\n--a-\r\n--a\ry\r\n" +
                "--a\r\n" +
                "Content-Disposition: form-data; name=\"bar\"\r\n" +
                "\r\n" +
                "\r\n--ab\r\n" +
                "--a--\r\n";
        for (int chunkSize : new int[]{input.length(), 1, 2, 3, 5}) {
            try (PostBodyDecoder decoder = PostBodyDecoder.builder().forMultipartBoundary("a")) {
                for (int i = 0; i < input.length(); i += chunkSize) {
                    decoder.add(Unpooled.copiedBuffer(input.substring(i, Math.min(input.length(), i + chunkSize)),
                            StandardCharsets.UTF_8));
                }
                decoder.endInput();

                Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
                Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
                Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
                Assertions.assertEquals("x\r\n--aX\r\n--a-\r\n--a\ry", readContent(decoder));

                Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
                Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
                Assertions.assertEquals("bar", ((ContentDisposition) decoder.parsedHeaderValue()).name());
                Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
                Assertions.assertEquals("\r\n--ab", readContent(decoder));
                Assertions.assertNull(decoder.next());
            }
        }
    }

    @Test
    public void delimiterWithInvalidSuffixSplitAcrossChunks() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forMultipartBoundary("a")) {
            decoder.add(Unpooled.copiedBuffer("--a\r\nContent-Disposition: form-data; name=\"foo\"\r\n\r\nx\r\n--a",
                    StandardCharsets.UTF_8));

            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            Assertions.assertEquals("x", decoder.decodedContentString());
            // can't decide yet whether this is a delimiter
            Assertions.assertNull(decoder.next());

            decoder.add(Unpooled.copiedBuffer("X", StandardCharsets.UTF_8));
            Assertions.assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            Assertions.assertEquals("\r\n--aX", decoder.decodedContentString());
            Assertions.assertNull(decoder.next());

            decoder.add(Unpooled.copiedBuffer("\r\n--a--", StandardCharsets.UTF_8));
            decoder.endInput();
            Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            Assertions.assertNull(decoder.next());
        }
    }

    private static String readContent(PostBodyDecoder decoder) {
        StringBuilder content = new StringBuilder();
        PostBodyDecoder.Event event;
        while ((event = decoder.next()) == PostBodyDecoder.Event.CONTENT) {
            content.append(decoder.decodedContentString());
        }
        Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, event);
        return content.toString();
    }

    @Test
    public void wrappedFirstBuffer() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forMultipartBoundary("a")) {
            decoder.add(Unpooled.wrappedBuffer("--a\r\nContent-Disposition: form-data; name=\"foo\"\r\n\r\nb"
                    .getBytes(StandardCharsets.UTF_8)));
            decoder.add(Unpooled.copiedBuffer("ar\r\n--a--", StandardCharsets.UTF_8));
            decoder.endInput();

            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            StringBuilder content = new StringBuilder();
            PostBodyDecoder.Event event;
            while ((event = decoder.next()) == PostBodyDecoder.Event.CONTENT) {
                content.append(decoder.decodedContentString());
            }
            Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, event);
            Assertions.assertEquals("bar", content.toString());
            Assertions.assertNull(decoder.next());
        }
    }

    @Test
    public void headerOnlyPartFollowedBySplitDelimiter() {
        // https://github.com/netty-contrib/codec-multipart/issues/31
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forMultipartBoundary("a")) {
            decoder.add(Unpooled.copiedBuffer("--a\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\n-",
                    StandardCharsets.UTF_8));

            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            // the "-" might be the start of a delimiter, so it must not be emitted as content yet
            Assertions.assertNull(decoder.next());

            decoder.add(Unpooled.copiedBuffer("-a\r\nContent-Disposition: form-data; name=\"y\"\r\n\r\nval\r\n--a--",
                    StandardCharsets.UTF_8));
            decoder.endInput();

            Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
            Assertions.assertEquals("form-data; name=\"y\"", decoder.headerValue());
            Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());
            Assertions.assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
            Assertions.assertEquals("val", decoder.decodedContentString());
            Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
            Assertions.assertNull(decoder.next());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // header-only part, delimiter directly after the headers
            "--a\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\n" +
                    "--a\r\nContent-Disposition: form-data; name=\"y\"\r\n\r\nval\r\n--a--\r\n",
            // header-only last part
            "--a\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\nval\r\n" +
                    "--a\r\nContent-Disposition: form-data; name=\"y\"\r\n\r\n--a--\r\n",
            // empty content
            "--a\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\n\r\n" +
                    "--a\r\nContent-Disposition: form-data; name=\"y\"\r\n\r\n\r\n--a--\r\n",
            // content resembling a partial delimiter
            "--a\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\n-x\r\n" +
                    "--a\r\nContent-Disposition: form-data; name=\"y\"\r\n\r\n--\r\n-\r\n--b\r\n" +
                    "--a\r\nContent-Disposition: form-data; name=\"z\"\r\n\r\n--\r\n--a--\r\n",
            // content resembling a full delimiter with an invalid suffix, at part start and after a line break
            "--a\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\n--aX\r\n--a-x\n--ab\r\n" +
                    "--a\r\nContent-Disposition: form-data; name=\"y\"\r\n\r\n--a-\r\n--a\r\r\n--a--\r\n",
            // nested multipart/mixed with a header-only sub-part
            "--a\r\nContent-Disposition: form-data; name=\"mix\"\r\n" +
                    "Content-Type: multipart/mixed; boundary=b\r\n\r\n" +
                    "--b\r\nContent-Disposition: file; filename=\"1.txt\"\r\n\r\n" +
                    "--b\r\nContent-Disposition: file; filename=\"2.txt\"\r\n\r\nfile2\r\n" +
                    "--b\r\nContent-Disposition: file; filename=\"3.txt\"\r\n\r\n" +
                    "--b--\r\n--a--\r\n",
    })
    public void splitInvariance(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        List<String> expected = decodeSplit(bytes);
        Assertions.assertFalse(expected.stream().anyMatch(s -> s.startsWith("ERROR")), expected::toString);
        for (int i = 0; i <= bytes.length; i++) {
            Assertions.assertEquals(expected, decodeSplit(bytes, i), "split at " + i);
            for (int j = i; j <= bytes.length; j++) {
                Assertions.assertEquals(expected, decodeSplit(bytes, i, j), "split at " + i + ", " + j);
            }
        }
    }

    /**
     * Decode the given input, split into chunks at the given indices, and return a normalized list of events, with
     * consecutive content merged.
     */
    private static List<String> decodeSplit(byte[] input, int... splits) {
        List<String> events = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forMultipartBoundary("a")) {
            int start = 0;
            for (int k = 0; k <= splits.length; k++) {
                int end = k == splits.length ? input.length : splits[k];
                decoder.add(Unpooled.copiedBuffer(input, start, end - start));
                start = end;
                if (k == splits.length) {
                    decoder.endInput();
                }
                PostBodyDecoder.Event event;
                while ((event = decoder.next()) != null) {
                    if (event == PostBodyDecoder.Event.CONTENT) {
                        content.append(decoder.decodedContentString());
                        continue;
                    }
                    if (content.length() > 0) {
                        events.add("CONTENT " + content);
                        content.setLength(0);
                    }
                    if (event == PostBodyDecoder.Event.HEADER) {
                        events.add("HEADER " + decoder.headerName() + ": " + decoder.headerValue());
                    } else {
                        events.add(event.name());
                    }
                }
            }
        } catch (FormDecoderException e) {
            events.add("ERROR " + e.getClass().getSimpleName());
        }
        if (content.length() > 0) {
            events.add("CONTENT " + content);
        }
        return events;
    }

    @Test
    public void bufferCompaction() throws IOException {
        byte[] fullData = new byte[10 * 1024 * 1024];
        ThreadLocalRandom.current().nextBytes(fullData);
        for (int i = 0; i < fullData.length; i++) {
            // the data must not contain the delimiter ("\n--a"). Keep the newlines to exercise partial delimiter
            // matches, but remove the dashes
            if (fullData[i] == '-') {
                fullData[i] = '+';
            }
        }

        bufferCompaction(PostBodyDecoder.builder().forMultipartBoundary("a"), "--a\r\n\r\n", fullData, "\r\n--a--");
    }

    static void bufferCompaction(PostBodyDecoder decoder, String before, byte[] fullData, String after)
            throws IOException {
        // this test verifies that buffers returned by decodedContent remain unchanged over time. This tests for a bug
        // caused by incorrect discardSomeReadBytes calls

        List<ByteBuf> readData = new ArrayList<>();

        try (decoder) {
            decoder.add(Unpooled.copiedBuffer(before, StandardCharsets.UTF_8));

            for (int i = 0; i < fullData.length / 1024; i++) {
                decoder.add(Unpooled.copiedBuffer(fullData, i * 1024, 1024));

                while (true) {
                    PostBodyDecoder.Event event = decoder.next();
                    if (event == null) {
                        break;
                    } else if (event == PostBodyDecoder.Event.CONTENT) {
                        readData.add(decoder.decodedContent());
                    }
                }
            }

            decoder.add(Unpooled.copiedBuffer(after, StandardCharsets.UTF_8));
            decoder.endInput();

            while (true) {
                PostBodyDecoder.Event event = decoder.next();
                if (event == null) {
                    break;
                } else if (event == PostBodyDecoder.Event.CONTENT) {
                    readData.add(decoder.decodedContent());
                }
            }
        }

        ByteArrayOutputStream combined = new ByteArrayOutputStream(fullData.length);
        for (ByteBuf buf : readData) {
            buf.readBytes(combined, buf.readableBytes());
            buf.release();
        }

        assertArrayEquals(fullData, combined.toByteArray());
    }
}
