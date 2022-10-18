/*
 * Copyright 2020 The Netty Project
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
import io.netty5.buffer.BufferUtil;
import io.netty5.buffer.Buffer;
import io.netty5.buffer.Owned;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link AbstractDiskHttpData} test cases
 */
@ExtendWith(GCExtension.class)
public class AbstractDiskHttpDataTest {

    @Test
    public void testGetChunk() throws Exception {
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
            Buffer buf1 = test.getChunk(1024);
            assertEquals(buf1.readerOffset(), 0);
            assertEquals(buf1.writerOffset(), 1024);
            Buffer buf2 = test.getChunk(1024);
            assertEquals(buf2.readerOffset(), 0);
            assertEquals(buf2.writerOffset(), 1024);
            assertFalse(Arrays.equals(BufferUtil.getBytes(buf1), BufferUtil.getBytes(buf2)),
                    "Arrays should not be equal");
            buf1.close();
            buf2.close();
        }
    }

    private static final class TestHttpData extends AbstractDiskHttpData {

        private TestHttpData(String name, Charset charset, long size) {
            super(name, charset, size);
        }

        @Override
        protected String getDiskFilename() {
            return null;
        }

        @Override
        protected String getPrefix() {
            return null;
        }

        @Override
        protected String getBaseDirectory() {
            return null;
        }

        @Override
        protected String getPostfix() {
            return null;
        }

        @Override
        protected boolean deleteOnExit() {
            return false;
        }

        @Override
        public HttpDataType getHttpDataType() {
            return null;
        }

        @Override
        public HttpData copy() {
            return null;
        }

        @Override
        public HttpData replace(Buffer content) {
            return null;
        }

        @Override
        public int compareTo(InterfaceHttpData o) {
            return 0;
        }

        @Override
        protected Owned<AbstractHttpData> prepareSend() {
            return drop -> {
                TestHttpData test = new TestHttpData(getName(), getCharset(), definedLength());
                return test;
            };
        }
    }
}
