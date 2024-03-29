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

import io.netty5.buffer.DefaultBufferAllocators;
import io.netty5.handler.codec.http.DefaultHttpRequest;
import io.netty5.handler.codec.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;

import static io.netty5.handler.codec.http.HttpMethod.POST;
import static io.netty5.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test DeleteFileOnExitHook
 */
@ExtendWith(GCExtension.class)
public class DeleteFileOnExitHookTest {
    private static final HttpRequest REQUEST = new DefaultHttpRequest(HTTP_1_1, POST, "/form");
    private static final String HOOK_TEST_TMP = "target/DeleteFileOnExitHookTest/tmp";
    private FileUpload fu;

    @BeforeEach
    public void setUp() throws IOException {
        DefaultHttpDataFactory defaultHttpDataFactory = new DefaultHttpDataFactory(true);
        defaultHttpDataFactory.setBaseDir(HOOK_TEST_TMP);
        defaultHttpDataFactory.setDeleteOnExit(true);

        File baseDir = new File(HOOK_TEST_TMP);
        baseDir.mkdirs();  // we don't need to clean it since it is in volatile files anyway

        fu = defaultHttpDataFactory.createFileUpload(
                REQUEST, "attribute1", "tmp_f.txt", "text/plain", null, null, 0);
        fu.setContent(DefaultBufferAllocators.onHeapAllocator().copyOf(new byte[]{1, 2, 3, 4}));

        assertTrue(fu.getFile().exists());
    }

    @Test
    public void testSimulateTriggerDeleteFileOnExitHook() {

        // simulate app exit
        DeleteFileOnExitHook.runHook();

        File[] files = new File(HOOK_TEST_TMP).listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(DiskFileUpload.prefix);
            }
        });

        assertEquals(0, files.length);
    }

    @Test
    public void testAfterHttpDataReleaseCheckFileExist() throws Exception {

        String filePath = fu.getFile().getPath();
        assertTrue(DeleteFileOnExitHook.checkFileExist(filePath));

        fu.close();
        assertFalse(DeleteFileOnExitHook.checkFileExist(filePath));
    }
}
