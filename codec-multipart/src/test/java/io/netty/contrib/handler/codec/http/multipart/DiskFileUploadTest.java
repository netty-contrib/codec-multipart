/*
 * Copyright 2016 The Netty Project
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

import io.netty5.buffer.BufferInputStream;
import io.netty5.util.CharsetUtil;
import io.netty5.util.internal.PlatformDependent;
import io.netty5.buffer.BufferUtil;
import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.DefaultBufferAllocators;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

public class DiskFileUploadTest {
    @Test
    public void testSpecificCustomBaseDir() throws IOException {
        File baseDir = new File("target/DiskFileUploadTest/testSpecificCustomBaseDir");
        baseDir.mkdirs(); // we don't need to clean it since it is in volatile files anyway
        try (DiskFileUpload f =
                new DiskFileUpload("d1", "d1", "application/json", null, null, 100,
                        baseDir.getAbsolutePath(), false)) {

            f.setContent(DefaultBufferAllocators.preferredAllocator().allocate(0));

            assertTrue(f.getFile().getAbsolutePath().startsWith(baseDir.getAbsolutePath()));
            assertTrue(f.getFile().exists());
            assertEquals(0, f.getFile().length());
        }
    }

    @Test
    public final void testDiskFileUploadEquals() {
        try (DiskFileUpload f2 =
                new DiskFileUpload("d1", "d1", "application/json", null, null, 100)) {
            assertEquals(f2, f2);
        }
    }

     @Test
     public void testEmptyBufferSetMultipleTimes() throws IOException {
         try(DiskFileUpload f =
                 new DiskFileUpload("d1", "d1", "application/json", null, null, 100)) {

             f.setContent(DefaultBufferAllocators.preferredAllocator().allocate(0));

             assertTrue(f.getFile().exists());
             assertEquals(0, f.getFile().length());
             f.setContent(DefaultBufferAllocators.preferredAllocator().allocate(0));
             assertTrue(f.getFile().exists());
             assertEquals(0, f.getFile().length());
         }
     }

    @Test
    public void testEmptyBufferSetAfterNonEmptyBuffer() throws IOException {
        try(DiskFileUpload f =
                new DiskFileUpload("d1", "d1", "application/json", null, null, 100)) {

            f.setContent(DefaultBufferAllocators.onHeapAllocator().copyOf(new byte[]{1, 2, 3, 4}));

            assertTrue(f.getFile().exists());
            assertEquals(4, f.getFile().length());
            f.setContent(DefaultBufferAllocators.preferredAllocator().allocate(0));
            assertTrue(f.getFile().exists());
            assertEquals(0, f.getFile().length());
        }
    }

    @Test
    public void testNonEmptyBufferSetMultipleTimes() throws IOException {
        try(DiskFileUpload f =
                new DiskFileUpload("d1", "d1", "application/json", null, null, 100)) {

            f.setContent(DefaultBufferAllocators.onHeapAllocator().copyOf(new byte[]{1, 2, 3, 4}));

            assertTrue(f.getFile().exists());
            assertEquals(4, f.getFile().length());
            f.setContent(DefaultBufferAllocators.onHeapAllocator().copyOf(new byte[]{1, 2}));
            assertTrue(f.getFile().exists());
            assertEquals(2, f.getFile().length());
        }
    }

    @Test
    public void testAddContents() throws Exception {
        try (DiskFileUpload f1 = new DiskFileUpload("file1", "file1", "application/json", null, null, 0)) {
            byte[] jsonBytes = new byte[4096];
            ThreadLocalRandom.current().nextBytes(jsonBytes);

            f1.addContent(Helpers.copiedBuffer(jsonBytes, 0, 1024), false);
            f1.addContent(Helpers.copiedBuffer(jsonBytes, 1024, jsonBytes.length - 1024), true);
            assertArrayEquals(jsonBytes, f1.get());

            File file = f1.getFile();
            assertEquals(jsonBytes.length, file.length());

            FileInputStream fis = new FileInputStream(file);
            try {
                byte[] buf = new byte[jsonBytes.length];
                int offset = 0;
                int read = 0;
                int len = buf.length;
                while ((read = fis.read(buf, offset, len)) > 0) {
                    len -= read;
                    offset += read;
                    if (len <= 0 || offset >= buf.length) {
                        break;
                    }
                }
                assertArrayEquals(jsonBytes, buf);
            } finally {
                fis.close();
            }
        }
    }

    @Test
    public void testSetContentFromByteBuf() throws Exception {
        try (DiskFileUpload f1 = new DiskFileUpload("file2", "file2", "application/json", null, null, 0)) {
            String json = "{\"hello\":\"world\"}";
            byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
            f1.setContent(Helpers.copiedBuffer(bytes));
            assertEquals(json, f1.getString());
            assertArrayEquals(bytes, f1.get());
            File file = f1.getFile();
            assertEquals((long) bytes.length, file.length());
            assertArrayEquals(bytes, doReadFile(file, bytes.length));
        }
    }

    @Test
    public void testSetContentFromInputStream() throws Exception {
        String json = "{\"hello\":\"world\",\"foo\":\"bar\"}";
        try (DiskFileUpload f1 = new DiskFileUpload("file3", "file3", "application/json", null, null, 0)) {
            byte[] bytes = json.getBytes(CharsetUtil.UTF_8);

            try (Buffer buf = Helpers.copiedBuffer(bytes);
                 InputStream is = new BufferInputStream(buf.send())) {
                f1.setContent(is);
                assertEquals(json, f1.getString());
                assertArrayEquals(bytes, f1.get());
                File file = f1.getFile();
                assertEquals((long) bytes.length, file.length());
                assertArrayEquals(bytes, doReadFile(file, bytes.length));
            }
        }
    }

    @Test
    public void testAddContentFromByteBuf() throws Exception {
        testAddContentFromByteBuf0(false);
    }

    @Test
    public void testAddContentFromCompositeByteBuf() throws Exception {
        testAddContentFromByteBuf0(true);
    }

    private static void testAddContentFromByteBuf0(boolean composite) throws Exception {
        try (DiskFileUpload f1 = new DiskFileUpload("file3", "file3", "application/json", null, null, 0)) {
            byte[] bytes = new byte[4096];
            ThreadLocalRandom.current().nextBytes(bytes);

            final Buffer buffer;

            if (composite) {
                buffer = Helpers.toComposite(
                        Helpers.copiedBuffer(bytes, 0 , bytes.length / 2),
                        Helpers.copiedBuffer(bytes, bytes.length / 2, bytes.length / 2));
            } else {
                buffer = Helpers.copiedBuffer(bytes);
            }
            f1.addContent(buffer, true);
            Buffer buf = f1.getBuffer();
            assertEquals(buf.readerOffset(), 0);
            assertEquals(buf.writerOffset(), bytes.length);
            assertArrayEquals(bytes, BufferUtil.getBytes(buf));
        }
    }

    private static byte[] doReadFile(File file, int maxRead) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        try {
            byte[] buf = new byte[maxRead];
            int offset = 0;
            int read = 0;
            int len = buf.length;
            while ((read = fis.read(buf, offset, len)) > 0) {
                len -= read;
                offset += read;
                if (len <= 0 || offset >= buf.length) {
                    break;
                }
            }
            return buf;
        } finally {
            fis.close();
        }
    }

    @Test
    public void testDelete() throws Exception {
        String json = "{\"foo\":\"bar\"}";
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
        File tmpFile = null;
        DiskFileUpload f1 = new DiskFileUpload("file4", "file4", "application/json", null, null, 0);
        try (f1) {
            assertNull(f1.getFile());
            f1.setContent(Helpers.copiedBuffer(bytes));
            assertNotNull(tmpFile = f1.getFile());
        } finally {
            assertNull(f1.getFile());
            assertNotNull(tmpFile);
            assertFalse(tmpFile.exists());
        }
    }

    @Test
    public void setSetContentFromFileExceptionally() throws Exception {
        final long maxSize = 4;
        try (DiskFileUpload f1 = new DiskFileUpload("file5", "file5", "application/json", null, null, 0)) {
            f1.setMaxSize(maxSize);
            f1.setContent(Helpers.copiedBuffer(new byte[(int) maxSize]));
            File originalFile = f1.getFile();
            assertNotNull(originalFile);
            assertEquals(maxSize, originalFile.length());
            assertEquals(maxSize, f1.length());
            byte[] bytes = new byte[8];
            ThreadLocalRandom.current().nextBytes(bytes);
            File tmpFile = PlatformDependent.createTempFile(UUID.randomUUID().toString(), ".tmp", null);
            tmpFile.deleteOnExit();
            FileOutputStream fos = new FileOutputStream(tmpFile);
            try {
                fos.write(bytes);
                fos.flush();
            } finally {
                fos.close();
            }
            try {
                f1.setContent(tmpFile);
                fail("should not reach here!");
            } catch (IOException e) {
                assertNotNull(f1.getFile());
                assertEquals(originalFile, f1.getFile());
                assertEquals(maxSize, f1.length());
            }
        }
    }
}
