/*
 * Copyright 2017 The Netty Project
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

import io.netty5.handler.codec.http.DefaultHttpRequest;
import io.netty5.handler.codec.http.HttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.netty.contrib.handler.codec.http.multipart.HttpPostBodyUtil.DEFAULT_TEXT_CONTENT_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static io.netty5.handler.codec.http.HttpHeaderValues.IDENTITY;
import static io.netty5.handler.codec.http.HttpMethod.POST;
import static io.netty5.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(GCExtension.class)
public class DefaultHttpDataFactoryTest {
    // req1 equals req2
    private static final HttpRequest req1 = new DefaultHttpRequest(HTTP_1_1, POST, "/form");
    private static final HttpRequest req2 = new DefaultHttpRequest(HTTP_1_1, POST, "/form");

    private DefaultHttpDataFactory factory;

    @BeforeAll
    public static void assertReq1EqualsReq2() {
        // Before doing anything, assert that the requests are equal
        assertEquals(req1.hashCode(), req2.hashCode());
        assertTrue(req1.equals(req2));
    }

    @BeforeEach
    public void setupFactory() {
        factory = new DefaultHttpDataFactory();
    }

    @AfterEach
    public void cleanupFactory() {
        factory.cleanAllHttpData();
    }

    @Test
    public void customBaseDirAndDeleteOnExit() {
        final DefaultHttpDataFactory defaultHttpDataFactory = new DefaultHttpDataFactory(true);
        final String dir = "target/DefaultHttpDataFactoryTest/customBaseDirAndDeleteOnExit";
        defaultHttpDataFactory.setBaseDir(dir);
        defaultHttpDataFactory.setDeleteOnExit(true);
        final Attribute attr = defaultHttpDataFactory.createAttribute(req1, "attribute1");
        final FileUpload fu = defaultHttpDataFactory.createFileUpload(
                req1, "attribute1", "f.txt", "text/plain", null, null, 0);
        assertEquals(dir, DiskAttribute.class.cast(attr).getBaseDirectory());
        assertEquals(dir, DiskFileUpload.class.cast(fu).getBaseDirectory());
        assertTrue(DiskAttribute.class.cast(attr).deleteOnExit());
        assertTrue(DiskFileUpload.class.cast(fu).deleteOnExit());
    }

    @Test
    public void cleanRequestHttpDataShouldIdentifiesRequestsByTheirIdentities() throws Exception {
        // Create some data belonging to req1 and req2
        Attribute attribute1 = factory.createAttribute(req1, "attribute1", "value1");
        Attribute attribute2 = factory.createAttribute(req2, "attribute2", "value2");
        FileUpload file1 = factory.createFileUpload(
                req1, "file1", "file1.txt",
                DEFAULT_TEXT_CONTENT_TYPE, IDENTITY.toString(), UTF_8, 123
        );
        FileUpload file2 = factory.createFileUpload(
                req2, "file2", "file2.txt",
                DEFAULT_TEXT_CONTENT_TYPE, IDENTITY.toString(), UTF_8, 123
        );
        file1.setContent(Helpers.copiedBuffer("file1 content", UTF_8));
        file2.setContent(Helpers.copiedBuffer("file2 content", UTF_8));

        // Assert that they are not deleted
        attribute1.usingBuffer(buf -> assertNotNull(buf));
        attribute2.usingBuffer(buf -> assertNotNull(buf));
        file1.usingBuffer(buf -> assertNotNull(buf));
        file2.usingBuffer(buf -> assertNotNull(buf));

        // Clean up by req1
        factory.cleanRequestHttpData(req1);

        // Assert that data belonging to req1 has been cleaned up
        attribute1.usingBuffer(buf -> assertNull(buf));
        file1.usingBuffer(buf -> assertNull(buf));

        // But not req2
        attribute2.usingBuffer(buf -> assertNotNull(buf));
        file2.usingBuffer(buf -> assertNotNull(buf));
    }

    @Test
    public void removeHttpDataFromCleanShouldIdentifiesDataByTheirIdentities() throws Exception {
        // Create some equal data items belonging to the same request
        Attribute attribute1 = factory.createAttribute(req1, "attribute", "value");
        Attribute attribute2 = factory.createAttribute(req1, "attribute", "value");
        FileUpload file1 = factory.createFileUpload(
                req1, "file", "file.txt",
                DEFAULT_TEXT_CONTENT_TYPE, IDENTITY.toString(), UTF_8, 123
        );
        FileUpload file2 = factory.createFileUpload(
                req1, "file", "file.txt",
                DEFAULT_TEXT_CONTENT_TYPE, IDENTITY.toString(), UTF_8, 123
        );
        file1.setContent(Helpers.copiedBuffer("file content", UTF_8));
        file2.setContent(Helpers.copiedBuffer("file content", UTF_8));

        // Before doing anything, assert that the data items are equal
        assertEquals(attribute1.hashCode(), attribute2.hashCode());
        assertTrue(attribute1.equals(attribute2));
        assertEquals(file1.hashCode(), file2.hashCode());
        assertTrue(file1.equals(file2));

        // Remove attribute2 and file2 from being cleaned up by factory
        factory.removeHttpDataFromClean(req1, attribute2);
        factory.removeHttpDataFromClean(req1, file2);

        // Clean up by req1
        factory.cleanRequestHttpData(req1);

        // Assert that attribute1 and file1 have been cleaned up
        attribute1.usingBuffer(buf -> assertNull(buf));
        file1.usingBuffer(buf -> assertNull(buf));

        // But not attribute2 and file2
        attribute2.usingBuffer(buf -> assertNotNull(buf));
        file2.usingBuffer(buf -> assertNotNull(buf));

        // Cleanup attribute2 and file2 manually to avoid memory leak, not via factory
        attribute2.close();
        file2.close();
    }
}
