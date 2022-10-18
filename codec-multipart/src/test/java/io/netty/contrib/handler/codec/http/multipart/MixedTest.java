/*
 * Copyright 2022 The Netty Project
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

import io.netty5.buffer.Buffer;
import io.netty5.buffer.DefaultBufferAllocators;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;

@ExtendWith(GCExtension.class)
public class MixedTest {
    @Test
    public void mixAttributeClosed() throws IOException {
        MixedAttribute attribute = new MixedAttribute("foo", 100);
        byte[] bytes1 = new byte[90];
        Buffer buf1 = DefaultBufferAllocators.onHeapAllocator().allocate(bytes1.length);
        buf1.writeBytes(bytes1);
        attribute.setContent(buf1);
        Assertions.assertTrue(buf1.isAccessible());

        byte[] bytes2 = new byte[110];
        Buffer buf2 = DefaultBufferAllocators.onHeapAllocator().allocate(bytes2.length);
        buf2.writeBytes(bytes2);
        attribute.setContent(buf2); // buf1 should be closed because we have changed to Disk. buf2 should be also closed.
        Assertions.assertFalse(buf1.isAccessible());
        Assertions.assertFalse(buf2.isAccessible());
        attribute.close();
    }

    @Test
    public void mixedFileUploadClosed() throws IOException {
        MixedFileUpload upload = new MixedFileUpload("foo", "foo", "foo", "UTF-8", StandardCharsets.UTF_8, 0, 100);
        byte[] bytes1 = new byte[90];
        Buffer buf1 = DefaultBufferAllocators.onHeapAllocator().allocate(bytes1.length);
        buf1.writeBytes(bytes1);
        upload.setContent(buf1);
        Assertions.assertTrue(buf1.isAccessible());

        byte[] bytes2 = new byte[110];
        Buffer buf2 = DefaultBufferAllocators.onHeapAllocator().allocate(bytes2.length);
        buf2.writeBytes(bytes2);
        upload.setContent(buf2); // buf1 should be closed because we have changed to Disk. buf2 should be also closed.
        Assertions.assertFalse(buf1.isAccessible());
        Assertions.assertFalse(buf2.isAccessible());
        upload.close();
    }
}
