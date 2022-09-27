/*
 * Copyright 2021 The Netty Project
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

import java.nio.charset.StandardCharsets;
import io.netty5.buffer.Buffer;
import io.netty5.buffer.DefaultBufferAllocators;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.Charset;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class HttpDataTest {
    private static final byte[] BYTES = new byte[64];

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @ParameterizedTest(name = "{displayName}({0})")
    @MethodSource("data")
    @interface ParameterizedHttpDataTest {
    }

    static HttpData[] data() {
        return new HttpData[]{
                new MemoryAttribute("test", 10),
                new MemoryFileUpload("test", "", "text/plain", null, StandardCharsets.UTF_8, 10),
                new MixedAttribute("test", 10, -1),
                new MixedFileUpload("test", "", "text/plain", null, StandardCharsets.UTF_8, 10, -1),
                new DiskAttribute("test", 10),
                new DiskFileUpload("test", "", "text/plain", null, StandardCharsets.UTF_8, 10)
        };
    }

    @BeforeAll
    static void setUp() {
        Random rndm = new Random();
        rndm.nextBytes(BYTES);
    }

    @ParameterizedHttpDataTest
    void testAddContentEmptyBuffer(HttpData httpData) throws IOException {
        Buffer content = DefaultBufferAllocators.preferredAllocator().allocate(0);
        httpData.addContent(content, false);
        assertThat(content.isAccessible()).isEqualTo(false);
    }

    @ParameterizedHttpDataTest
    void testCompletedFlagPreservedAfterRetainDuplicate(HttpData httpData) throws IOException {
        httpData.addContent(Helpers.copiedBuffer("foo".getBytes(StandardCharsets.UTF_8)), false);
        assertThat(httpData.isCompleted()).isFalse();
        HttpData duplicate = httpData.replace(httpData.content().split());
        assertThat(duplicate.isCompleted()).isFalse();
        duplicate.close();
        httpData.addContent(Helpers.copiedBuffer("bar".getBytes(StandardCharsets.UTF_8)), true);
        assertThat(httpData.isCompleted()).isTrue();
        duplicate = httpData.replace(httpData.content().split());
        assertThat(duplicate.isCompleted()).isTrue();
        duplicate.close();
    }

    @Test
    void testAddContentExceedsDefinedSizeDiskFileUpload() {
        doTestAddContentExceedsSize(
                new DiskFileUpload("test", "", "application/json", null, StandardCharsets.UTF_8, 10),
                "Out of size: 64 > 10");
    }

    @Test
    void testAddContentExceedsDefinedSizeMemoryFileUpload() {
        doTestAddContentExceedsSize(
                new MemoryFileUpload("test", "", "application/json", null, StandardCharsets.UTF_8, 10),
                "Out of size: 64 > 10");
    }

    @ParameterizedHttpDataTest
    void testAddContentExceedsMaxSize(final HttpData httpData) {
        httpData.setMaxSize(10);
        doTestAddContentExceedsSize(httpData, "Size exceed allowed maximum capacity");
    }

    @ParameterizedHttpDataTest
    void testSetContentExceedsDefinedSize(final HttpData httpData) {
        doTestSetContentExceedsSize(httpData, "Out of size: 64 > 10");
    }

    @ParameterizedHttpDataTest
    void testSetContentExceedsMaxSize(final HttpData httpData) {
        httpData.setMaxSize(10);
        doTestSetContentExceedsSize(httpData, "Size exceed allowed maximum capacity");
    }

    @Test
    void testMemoryAttributeSend() throws IOException {
        try (MemoryAttribute mem = new MemoryAttribute("test", 10, Charset.defaultCharset())) {
            mem.addContent(Helpers.copiedBuffer("content", Charset.defaultCharset()), false);
            mem.setMaxSize(100);
            assertThat(mem.isCompleted()).isFalse();
            final MemoryAttribute memSend = (MemoryAttribute) mem.send().receive();
            try (memSend) {
                assertThat(mem.isAccessible()).isFalse();
                assertThat(memSend.isAccessible()).isTrue();
                assertThat(memSend.isCompleted()).isFalse();
                assertThat(memSend.getValue()).isEqualTo("content");
                assertThat(memSend.getHttpDataType()).isEqualTo(InterfaceHttpData.HttpDataType.Attribute);
                assertThat(memSend.isInMemory()).isTrue();
                assertThat(memSend.getCharset()).isEqualTo(Charset.defaultCharset());
                assertThat(memSend.definedLength()).isEqualTo(10);
                assertThat(memSend.getMaxSize()).isEqualTo(100);
            }
            assertThat(memSend.isAccessible()).isFalse();
        }
    }

    @Test
    void testMemoryFileUploadSend() throws IOException {
        try (MemoryFileUpload mem = new MemoryFileUpload("test", "filename", "text/plain", "BINARY", StandardCharsets.UTF_8, 10)) {
            mem.addContent(Helpers.copiedBuffer("content", Charset.defaultCharset()), false);
            mem.setMaxSize(100);
            assertThat(mem.isCompleted()).isFalse();
            final MemoryFileUpload memSend = (MemoryFileUpload) mem.send().receive();
            try (memSend) {
                assertThat(mem.isAccessible()).isFalse();
                assertThat(memSend.isAccessible()).isTrue();
                assertThat(memSend.isCompleted()).isFalse();
                assertThat(memSend.getString()).isEqualTo("content");
                assertThat(memSend.getHttpDataType()).isEqualTo(InterfaceHttpData.HttpDataType.FileUpload);
                assertThat(memSend.isInMemory()).isTrue();
                assertThat(memSend.getCharset()).isEqualTo(StandardCharsets.UTF_8);
                assertThat(memSend.definedLength()).isEqualTo(10);
                assertThat(memSend.getMaxSize()).isEqualTo(100);
                assertThat(memSend.getName()).isEqualTo("test");
                assertThat(memSend.getFilename()).isEqualTo("filename");
                assertThat(memSend.getContentType()).isEqualTo("text/plain");
                assertThat(memSend.getContentTransferEncoding()).isEqualTo("BINARY");

            }
            assertThat(memSend.isAccessible()).isFalse();
        }
    }

    @Test
    void testMixedAttributeSend() throws IOException {
        try (MixedAttribute data = new MixedAttribute("test", 10, 100, StandardCharsets.UTF_8, "/tmp", true)) {
            data.addContent(Helpers.copiedBuffer("content", Charset.defaultCharset()), false);
            data.setMaxSize(1000);
            assertThat(data.isCompleted()).isFalse();
            final MixedAttribute send = (MixedAttribute) data.send().receive();
            try (send) {
                assertThat(data.isAccessible()).isFalse();
                assertThat(send.isAccessible()).isTrue();
                assertThat(send.isCompleted()).isFalse();
                assertThat(send.getValue()).isEqualTo("content");
                assertThat(send.getHttpDataType()).isEqualTo(InterfaceHttpData.HttpDataType.Attribute);
                assertThat(send.isInMemory()).isTrue();
                assertThat(send.getCharset()).isEqualTo(StandardCharsets.UTF_8);
                assertThat(send.definedLength()).isEqualTo(10);
                assertThat(send.getMaxSize()).isEqualTo(1000);
                assertThat(send.limitSize).isEqualTo(100);
                assertThat(send.deleteOnExit).isTrue();
                assertThat(send.baseDir).isEqualTo("/tmp");
            }
            assertThat(send.isAccessible()).isFalse();
        }
    }

    @Test
    void testMixedFileUploadSend() throws IOException {
        try (MixedFileUpload data = new MixedFileUpload("test", "filename", "text/plain", "BINARY",
                StandardCharsets.UTF_8, 10, 100, "/tmp", true)) {
            data.addContent(Helpers.copiedBuffer("content", Charset.defaultCharset()), false);
            data.setMaxSize(1000);
            assertThat(data.isCompleted()).isFalse();
            final MixedFileUpload send = (MixedFileUpload) data.send().receive();
            try (send) {
                assertThat(data.isAccessible()).isFalse();
                assertThat(send.isAccessible()).isTrue();
                assertThat(send.isCompleted()).isFalse();
                assertThat(send.getString()).isEqualTo("content");
                assertThat(send.getHttpDataType()).isEqualTo(InterfaceHttpData.HttpDataType.FileUpload);
                assertThat(send.isInMemory()).isTrue();
                assertThat(send.getCharset()).isEqualTo(Charset.defaultCharset());
                assertThat(send.definedLength()).isEqualTo(10);
                assertThat(send.getMaxSize()).isEqualTo(1000);
                assertThat(send.limitSize).isEqualTo(100);
                assertThat(send.deleteOnExit).isTrue();
                assertThat(send.baseDir).isEqualTo("/tmp");
            }
            assertThat(send.isAccessible()).isFalse();
        }

    }

    @Test
    void testDiskAttributeSend() throws IOException {
        try (DiskAttribute data = new DiskAttribute("test", 10, "/tmp", true)) {
            data.addContent(Helpers.copiedBuffer("content", Charset.defaultCharset()), false);
            data.setMaxSize(1000);
            assertThat(data.isCompleted()).isFalse();
            final DiskAttribute send = (DiskAttribute) data.send().receive();
            try (send) {
                assertThat(data.isAccessible()).isFalse();
                assertThat(send.isAccessible()).isTrue();
                assertThat(send.isCompleted()).isFalse();
                assertThat(send.getString()).isEqualTo("content");
                assertThat(send.getHttpDataType()).isEqualTo(InterfaceHttpData.HttpDataType.Attribute);
                assertThat(send.isInMemory()).isFalse();
                assertThat(send.getCharset()).isEqualTo(Charset.defaultCharset());
                assertThat(send.definedLength()).isEqualTo(10);
                assertThat(send.getMaxSize()).isEqualTo(1000);
                assertThat(send.deleteOnExit()).isTrue();
                assertThat(send.getBaseDirectory()).isEqualTo("/tmp");
            }
            assertThat(send.isAccessible()).isFalse();
        }
    }

    @Test
    void testDiskFileUploadSend() throws IOException {

        try (DiskFileUpload data = new DiskFileUpload("test", "", "text/plain", null, StandardCharsets.UTF_8, 10, "/tmp", true)) {
            data.addContent(Helpers.copiedBuffer("content", Charset.defaultCharset()), false);
            data.setMaxSize(1000);
            assertThat(data.isCompleted()).isFalse();
            final DiskFileUpload send = (DiskFileUpload) data.send().receive();
            try (send) {
                assertThat(data.isAccessible()).isFalse();
                assertThat(send.isAccessible()).isTrue();
                assertThat(send.isCompleted()).isFalse();
                assertThat(send.getString()).isEqualTo("content");
                assertThat(send.getHttpDataType()).isEqualTo(InterfaceHttpData.HttpDataType.FileUpload);
                assertThat(send.isInMemory()).isFalse();
                assertThat(send.getCharset()).isEqualTo(Charset.defaultCharset());
                assertThat(send.definedLength()).isEqualTo(10);
                assertThat(send.getMaxSize()).isEqualTo(1000);
                assertThat(send.deleteOnExit()).isTrue();
                assertThat(send.getBaseDirectory()).isEqualTo("/tmp");
            }
            assertThat(send.isAccessible()).isFalse();
        }
    }

    private static void doTestAddContentExceedsSize(final HttpData httpData, String expectedMessage) {
        final Buffer content = DefaultBufferAllocators.preferredAllocator().allocate(0);
        content.writeBytes(BYTES);

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(new ThrowableAssert.ThrowingCallable() {

                    @Override
                    public void call() throws Throwable {
                        httpData.addContent(content, false);
                    }
                })
                .withMessage(expectedMessage);
    }

    private static void doTestSetContentExceedsSize(final HttpData httpData, String expectedMessage) {
        final Buffer content = DefaultBufferAllocators.preferredAllocator().allocate(0);
        content.writeBytes(BYTES);

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(new ThrowableAssert.ThrowingCallable() {

                    @Override
                    public void call() throws Throwable {
                        httpData.setContent(content);
                    }
                })
                .withMessage(expectedMessage);
    }
}
