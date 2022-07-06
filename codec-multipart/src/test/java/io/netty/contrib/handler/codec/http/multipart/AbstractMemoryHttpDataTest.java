/*
 * Copyright 2012 The Netty Project
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

import io.netty5.util.internal.PlatformDependent;
import io.netty5.buffer.BufferInputStream;
import io.netty5.buffer.BufferUtil;
import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.Owned;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.netty5.util.CharsetUtil.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

/** {@link AbstractMemoryHttpData} test cases. */
public class AbstractMemoryHttpDataTest {

    @Test
    public void testSetContentFromFile() throws Exception {
        try(TestHttpData test = new TestHttpData("test", UTF_8, 0)) {
            File tmpFile = PlatformDependent.createTempFile(UUID.randomUUID().toString(), ".tmp", null);
            tmpFile.deleteOnExit();
            FileOutputStream fos = new FileOutputStream(tmpFile);
            byte[] bytes = new byte[4096];
            ThreadLocalRandom.current().nextBytes(bytes);
            try {
                fos.write(bytes);
                fos.flush();
            } finally {
                fos.close();
            }
            test.setContent(tmpFile);
            Buffer buf = test.getBuffer();
            assertEquals(buf.readerOffset(), 0);
            assertEquals(buf.writerOffset(), bytes.length);
            assertArrayEquals(bytes, test.get());
            assertArrayEquals(bytes, BufferUtil.getBytes(buf));
        }
    }

    @Test
    public void testRenameTo() throws Exception {
        try(TestHttpData test = new TestHttpData("test", UTF_8, 0)) {
            File tmpFile = PlatformDependent.createTempFile(UUID.randomUUID().toString(), ".tmp", null);
            tmpFile.deleteOnExit();
            final int totalByteCount = 4096;
            byte[] bytes = new byte[totalByteCount];
            ThreadLocalRandom.current().nextBytes(bytes);
            Buffer content = Helpers.copiedBuffer(bytes);
            test.setContent(content);
            boolean succ = test.renameTo(tmpFile);
            assertTrue(succ);
            FileInputStream fis = new FileInputStream(tmpFile);
            try {
                byte[] buf = new byte[totalByteCount];
                int count = 0;
                int offset = 0;
                int size = totalByteCount;
                while ((count = fis.read(buf, offset, size)) > 0) {
                    offset += count;
                    size -= count;
                    if (offset >= totalByteCount || size <= 0) {
                        break;
                    }
                }
                assertArrayEquals(bytes, buf);
                assertEquals(0, fis.available());
            } finally {
                fis.close();
            }
        }
    }
    /**
     * Provide content into HTTP data with input stream.
     *
     * @throws Exception In case of any exception.
     */
    @Test
    public void testSetContentFromStream() throws Exception {
        // definedSize=0
        TestHttpData test = new TestHttpData("test", UTF_8, 0);
        String contentStr = "foo_test";
        Buffer buf = Helpers.copiedBuffer(contentStr.getBytes(UTF_8));
        BufferInputStream is = new BufferInputStream(buf.send());
        try {
            test.setContent(is);
            assertFalse(buf.readableBytes() > 0);
            assertEquals(test.getString(UTF_8), contentStr);
            buf = Helpers.copiedBuffer(contentStr.getBytes(UTF_8));
            Buffer buf2 = test.getBuffer();
            assertTrue(BufferUtil.equals(buf, buf.readerOffset(), buf2, buf2.readerOffset(), buf2.readableBytes()));
        } finally {
            is.close();
        }

        Random random = new SecureRandom();

        for (int i = 0; i < 20; i++) {
            // Generate input data bytes.
            int size = random.nextInt(Short.MAX_VALUE);
            byte[] bytes = new byte[size];

            random.nextBytes(bytes);

            // Generate parsed HTTP data block.
            TestHttpData data = new TestHttpData("name", UTF_8, 0);

            data.setContent(new ByteArrayInputStream(bytes));

            // Validate stored data.
            Buffer buffer = data.getBuffer();

            assertEquals(0, buffer.readerOffset());
            assertEquals(bytes.length, buffer.writerOffset());
            assertArrayEquals(bytes, BufferUtil.getBytes(buffer));
            assertArrayEquals(bytes, data.get());
        }
    }

    /** Memory-based HTTP data implementation for test purposes. */
    private static final class TestHttpData extends AbstractMemoryHttpData {
        /**
         * Constructs HTTP data for tests.
         *
         * @param name    Name of parsed data block.
         * @param charset Used charset for data decoding.
         * @param size    Expected data block size.
         */
        private TestHttpData(String name, Charset charset, long size) {
            super(name, charset, size);
        }

        @Override
        public InterfaceHttpData.HttpDataType getHttpDataType() {
            throw reject();
        }

        @Override
        protected Owned<AbstractHttpData> prepareSend() {
            throw reject();
        }

        @Override
        public HttpData copy() {
            throw reject();
        }

        @Override
        public HttpData replace(Buffer content) {
            throw reject();
        }

        @Override
        public int compareTo(InterfaceHttpData o) {
            throw reject();
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return super.equals(obj);
        }

        private static UnsupportedOperationException reject() {
            throw new UnsupportedOperationException("Should never be called.");
        }
    }
}