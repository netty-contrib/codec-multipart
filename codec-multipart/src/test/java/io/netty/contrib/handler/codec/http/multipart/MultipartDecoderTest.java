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
package io.netty.contrib.handler.codec.http.multipart;

import io.netty5.buffer.DefaultBufferAllocators;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

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
            decoder.add(DefaultBufferAllocators.preferredAllocator().copyOf(input, StandardCharsets.UTF_8).send());

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
}