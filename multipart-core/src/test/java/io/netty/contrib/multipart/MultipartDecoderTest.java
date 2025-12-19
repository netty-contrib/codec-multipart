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
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forMultipartBoundary("---------------------------9051914041544843365972754266")) {
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
            Assertions.assertEquals("<!DOCTYPE html><title>Content of a.html.</title>\n", decoder.decodedContentString());
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
            Assertions.assertEquals(~5, findDelimiter(ro, "\r\nabc", "abc"));
            Assertions.assertEquals(~4, findDelimiter(ro, "\nabc", "abc"));
            Assertions.assertEquals(~5, findDelimiter(ro, "\r\nabcx", "abc"));
            Assertions.assertEquals(~4, findDelimiter(ro, "\nabcx", "abc"));
            Assertions.assertEquals(~3, findDelimiter(ro, "abcx", "abc"));
            Assertions.assertEquals(2, findDelimiter(ro, "\n\n\n", "abc"));
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
    public void bufferCompaction() throws IOException {
        byte[] fullData = new byte[10 * 1024 * 1024];
        ThreadLocalRandom.current().nextBytes(fullData);

        bufferCompaction(PostBodyDecoder.builder().forMultipartBoundary("a"), "--a\r\n\r\n", fullData, "\r\n--a--");
    }

    static void bufferCompaction(PostBodyDecoder decoder, String before, byte[] fullData, String after) throws IOException {
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