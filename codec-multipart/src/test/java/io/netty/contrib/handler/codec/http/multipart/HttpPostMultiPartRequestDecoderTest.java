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

import io.netty.util.CharsetUtil;
import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.DefaultBufferAllocators;
import io.netty5.handler.codec.http.DefaultFullHttpRequest;
import io.netty5.handler.codec.http.DefaultHttpContent;
import io.netty5.handler.codec.http.DefaultHttpRequest;
import io.netty5.handler.codec.http.FullHttpRequest;
import io.netty5.handler.codec.http.HttpConstants;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class HttpPostMultiPartRequestDecoderTest {

    @Test
    public void testDecodeFullHttpRequestWithNoContentTypeHeader() {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", DefaultBufferAllocators.preferredAllocator().allocate(0));
        try {
            new HttpPostMultipartRequestDecoder(req);
            fail("Was expecting an ErrorDataDecoderException");
        } catch (HttpPostRequestDecoder.ErrorDataDecoderException expected) {
            // expected
        } finally {
            req.close();
        }
    }

    @Test
    public void testDecodeFullHttpRequestWithInvalidCharset() {
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", DefaultBufferAllocators.preferredAllocator().allocate(0));
        req.headers().set(HttpHeaderNames.CONTENT_TYPE,
                "multipart/form-data; boundary=--89421926422648 [; charset=UTF-8]");

        try {
            new HttpPostMultipartRequestDecoder(req);
            fail("Was expecting an ErrorDataDecoderException");
        } catch (HttpPostRequestDecoder.ErrorDataDecoderException expected) {
            // expected
        } finally {
            req.close();
        }
    }

    @Test
    public void testDecodeFullHttpRequestWithInvalidPayloadReleaseBuffer() {
        String content = "\n--861fbeab-cd20-470c-9609-d40a0f704466\n" +
                "Content-Disposition: form-data; name=\"image1\"; filename*=\"'some.jpeg\"\n" +
                        "Content-Type: image/jpeg\n" +
                        "Content-Length: 1\n" +
                        "x\n" +
                        "--861fbeab-cd20-470c-9609-d40a0f704466--\n";

        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload",
                Helpers.copiedBuffer(content, CharsetUtil.US_ASCII));
        req.headers().set("content-type", "multipart/form-data; boundary=861fbeab-cd20-470c-9609-d40a0f704466");
        req.headers().set("content-length", content.length());

        try {
            new HttpPostMultipartRequestDecoder(req);
            fail("Was expecting an ErrorDataDecoderException");
        } catch (HttpPostRequestDecoder.ErrorDataDecoderException expected) {
            // expected
        } finally {
            req.close();
        }
    }

    @Test
    public void testDelimiterExceedLeftSpaceInCurrentBuffer() throws IOException {
        String delimiter = "--861fbeab-cd20-470c-9609-d40a0f704466";
        String suffix = '\n' + delimiter + "--\n";
        byte[] bsuffix = suffix.getBytes(CharsetUtil.UTF_8);
        int partOfDelimiter = bsuffix.length / 2;
        int bytesLastChunk = 355 - partOfDelimiter; // to try to have an out of bound since content is > delimiter
        byte[] bsuffix1 = Arrays.copyOf(bsuffix, partOfDelimiter);
        byte[] bsuffix2 = Arrays.copyOfRange(bsuffix, partOfDelimiter, bsuffix.length);
        String prefix = delimiter + "\n" +
                        "Content-Disposition: form-data; name=\"image\"; filename=\"guangzhou.jpeg\"\n" +
                        "Content-Type: image/jpeg\n" +
                        "Content-Length: " + bytesLastChunk + "\n\n";
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        request.headers().set("content-type", "multipart/form-data; boundary=861fbeab-cd20-470c-9609-d40a0f704466");
        request.headers().set("content-length", prefix.length() + bytesLastChunk + suffix.length());

        // Factory using Memory mode
        HttpDataFactory factory = new DefaultHttpDataFactory(false);
        HttpPostMultipartRequestDecoder decoder = new HttpPostMultipartRequestDecoder(factory, request);
        Buffer buf = Helpers.copiedBuffer(prefix.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultHttpContent(buf);
        decoder.offer(httpContent);
        assertNotNull((HttpData) decoder.currentPartialHttpData());
        httpContent.close();
        // Chunk less than Delimiter size but containing part of delimiter
        byte[] body = new byte[bytesLastChunk + bsuffix1.length];
        Arrays.fill(body, (byte) 2);
        for (int i = 0; i < bsuffix1.length; i++) {
            body[bytesLastChunk + i] = bsuffix1[i];
        }
        Buffer content = Helpers.copiedBuffer(body);
        httpContent = new DefaultHttpContent(content);
        decoder.offer(httpContent); // Ouf of range before here
        assertNotNull(((HttpData) decoder.currentPartialHttpData()).getBuffer());
        httpContent.close();
        content = Helpers.copiedBuffer(bsuffix2);
        httpContent = new DefaultHttpContent(content);
        decoder.offer(httpContent);
        assertNull((HttpData) decoder.currentPartialHttpData());
        httpContent.close();
        decoder.offer(Helpers.defaultLastHttpContent());
        FileUpload data = (FileUpload) decoder.getBodyHttpDatas().get(0);
        assertEquals(data.length(), bytesLastChunk);
        assertEquals(true, data.isInMemory());

        factory.cleanAllHttpData();
        decoder.destroy();
    }

    private void commonTestBigFileDelimiterInMiddleChunk(HttpDataFactory factory, boolean inMemory)
            throws IOException {
        int nbChunks = 100;
        int bytesPerChunk = 100000;
        int bytesLastChunk = 10000;
        int fileSize = bytesPerChunk * nbChunks + bytesLastChunk; // set Xmx to a number lower than this and it crashes

        String delimiter = "--861fbeab-cd20-470c-9609-d40a0f704466";
        String prefix = delimiter + "\n" +
                "Content-Disposition: form-data; name=\"image\"; filename=\"guangzhou.jpeg\"\n" +
                "Content-Type: image/jpeg\n" +
                "Content-Length: " + fileSize + "\n" +
                "\n";

        String suffix1 = "\n" +
                "--861fbeab-";
        String suffix2 = "cd20-470c-9609-d40a0f704466--\n";
        String suffix = suffix1 + suffix2;

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        request.headers().set("content-type", "multipart/form-data; boundary=861fbeab-cd20-470c-9609-d40a0f704466");
        request.headers().set("content-length", prefix.length() + fileSize + suffix.length());

        HttpPostMultipartRequestDecoder decoder = new HttpPostMultipartRequestDecoder(factory, request);
        Buffer buf = Helpers.copiedBuffer(prefix.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultHttpContent(buf);
        decoder.offer(httpContent);
        assertNotNull(((HttpData) decoder.currentPartialHttpData()).getBuffer());
        httpContent.close();

        byte[] body = new byte[bytesPerChunk];
        Arrays.fill(body, (byte) 1);
        // Set first bytes as CRLF to ensure it is correctly getting the last CRLF
        body[0] = HttpConstants.CR;
        body[1] = HttpConstants.LF;
        for (int i = 0; i < nbChunks; i++) {
            Buffer content = Helpers.copiedBuffer(body, 0, bytesPerChunk);
            httpContent = new DefaultHttpContent(content);
            decoder.offer(httpContent); // **OutOfMemory previously here**
            assertNotNull(((HttpData) decoder.currentPartialHttpData()).getBuffer());
            httpContent.close();
        }

        byte[] bsuffix1 = suffix1.getBytes(CharsetUtil.UTF_8);
        byte[] previousLastbody = new byte[bytesLastChunk - bsuffix1.length];
        byte[] bdelimiter = delimiter.getBytes(CharsetUtil.UTF_8);
        byte[] lastbody = new byte[2 * bsuffix1.length];
        Arrays.fill(previousLastbody, (byte) 1);
        previousLastbody[0] = HttpConstants.CR;
        previousLastbody[1] = HttpConstants.LF;
        Arrays.fill(lastbody, (byte) 1);
        // put somewhere a not valid delimiter
        for (int i = 0; i < bdelimiter.length; i++) {
            previousLastbody[i + 10] = bdelimiter[i];
        }
        lastbody[0] = HttpConstants.CR;
        lastbody[1] = HttpConstants.LF;
        for (int i = 0; i < bsuffix1.length; i++) {
            lastbody[bsuffix1.length + i] = bsuffix1[i];
        }

        Buffer content2 = Helpers.copiedBuffer(previousLastbody, 0, previousLastbody.length);
        httpContent = new DefaultHttpContent(content2);
        decoder.offer(httpContent);
        assertNotNull(((HttpData) decoder.currentPartialHttpData()).getBuffer());
        httpContent.close();
        content2 = Helpers.copiedBuffer(lastbody, 0, lastbody.length);
        httpContent = new DefaultHttpContent(content2);
        decoder.offer(httpContent);
        assertNotNull(((HttpData) decoder.currentPartialHttpData()).getBuffer());
        httpContent.close();
        content2 = Helpers.copiedBuffer(suffix2.getBytes(CharsetUtil.UTF_8));
        httpContent = new DefaultHttpContent(content2);
        decoder.offer(httpContent);
        assertNull(decoder.currentPartialHttpData());
        httpContent.close();
        decoder.offer(Helpers.defaultLastHttpContent());

        FileUpload data = (FileUpload) decoder.getBodyHttpDatas().get(0);
        assertEquals(data.length(), fileSize);
        assertEquals(inMemory, data.isInMemory());
        if (data.isInMemory()) {
            // To be done only if not inMemory: assertEquals(data.get().length, fileSize);
            assertFalse(data.getBuffer().capacity() < 1024 * 1024,
                    "Capacity should be higher than 1M");
        }
        assertTrue(decoder.getCurrentAllocatedCapacity() < 1024 * 1024,
                "Capacity should be less than 1M");
        factory.cleanAllHttpData();
        decoder.destroy();
    }

    @Test
    public void testBIgFileUploadDelimiterInMiddleChunkDecoderDiskFactory() throws IOException {
        // Factory using Disk mode
        HttpDataFactory factory = new DefaultHttpDataFactory(true);

        commonTestBigFileDelimiterInMiddleChunk(factory, false);
    }

    @Test
    public void testBIgFileUploadDelimiterInMiddleChunkDecoderMemoryFactory() throws IOException {
        // Factory using Memory mode
        HttpDataFactory factory = new DefaultHttpDataFactory(false);

        commonTestBigFileDelimiterInMiddleChunk(factory, true);
    }

    @Test
    public void testBIgFileUploadDelimiterInMiddleChunkDecoderMixedFactory() throws IOException {
        // Factory using Mixed mode, where file shall be on Disk
        HttpDataFactory factory = new DefaultHttpDataFactory(10000);

        commonTestBigFileDelimiterInMiddleChunk(factory, false);
    }

    @Test
    public void testNotBadReleaseBuffersDuringDecodingDiskFactory() throws Exception {
        // Using Disk Factory
        HttpDataFactory factory = new DefaultHttpDataFactory(true);
        commonNotBadReleaseBuffersDuringDecoding(factory, false);
    }
    @Test
    public void testNotBadReleaseBuffersDuringDecodingMemoryFactory() throws Exception {
        // Using Memory Factory
        HttpDataFactory factory = new DefaultHttpDataFactory(false);
        commonNotBadReleaseBuffersDuringDecoding(factory, true);
    }
    @Test
    public void testNotBadReleaseBuffersDuringDecodingMixedFactory() throws Exception {
        // Using Mixed Factory
        HttpDataFactory factory = new DefaultHttpDataFactory(100);
        commonNotBadReleaseBuffersDuringDecoding(factory, false);
    }

    private static void commonNotBadReleaseBuffersDuringDecoding(HttpDataFactory factory, boolean inMemory)
            throws Exception {
        int nbItems = 20;
        int bytesPerItem = 1000;
        int maxMemory = 500;

        String prefix1 = "\n--861fbeab-cd20-470c-9609-d40a0f704466\n" +
                "Content-Disposition: form-data; name=\"image";
        String prefix2 =
                "\"; filename=\"guangzhou.jpeg\"\n" +
                        "Content-Type: image/jpeg\n" +
                        "Content-Length: " + bytesPerItem + "\n" + "\n";

        String suffix = "\n--861fbeab-cd20-470c-9609-d40a0f704466--\n";

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        request.headers().set("content-type", "multipart/form-data; boundary=861fbeab-cd20-470c-9609-d40a0f704466");
        request.headers().set("content-length", nbItems * (prefix1.length() + prefix2.length() + 2 + bytesPerItem)
                + suffix.length());
        HttpPostMultipartRequestDecoder decoder = new HttpPostMultipartRequestDecoder(factory, request);
        decoder.setDiscardThreshold(maxMemory);
        for (int rank = 0; rank < nbItems; rank++) {
            byte[] bp1 = prefix1.getBytes(CharsetUtil.UTF_8);
            byte[] bp2 = prefix2.getBytes(CharsetUtil.UTF_8);
            byte[] prefix = new byte[bp1.length + 2 + bp2.length];
            for (int i = 0; i < bp1.length; i++) {
                prefix[i] = bp1[i];
            }
            byte[] brank = Integer.toString(10 + rank).getBytes(CharsetUtil.UTF_8);
            prefix[bp1.length] = brank[0];
            prefix[bp1.length + 1] = brank[1];
            for (int i = 0; i < bp2.length; i++) {
                prefix[bp1.length + 2 + i] = bp2[i];
            }
            Buffer buf = Helpers.copiedBuffer(prefix);
            DefaultHttpContent httpContent = new DefaultHttpContent(buf);
            decoder.offer(httpContent);
            httpContent.close();
            byte[] body = new byte[bytesPerItem];
            Arrays.fill(body, (byte) rank);
            Buffer content = Helpers.copiedBuffer(body, 0, bytesPerItem);
            httpContent = new DefaultHttpContent(content);
            decoder.offer(httpContent);
            httpContent.close();
        }
        byte[] lastbody = suffix.getBytes(CharsetUtil.UTF_8);
        Buffer content2 = Helpers.copiedBuffer(lastbody, 0, lastbody.length);
        DefaultHttpContent httpContent = new DefaultHttpContent(content2);
        decoder.offer(httpContent);
        httpContent.close();
        decoder.offer(Helpers.defaultLastHttpContent());

        for (int rank = 0; rank < nbItems; rank++) {
            FileUpload data = (FileUpload) decoder.getBodyHttpData("image" + (10 + rank));
            assertEquals(bytesPerItem, data.length());
            assertEquals(inMemory, data.isInMemory());
            byte[] body = new byte[bytesPerItem];
            Arrays.fill(body, (byte) rank);
            assertTrue(Arrays.equals(body, data.get()));
        }
        // To not be done since will load full file on memory: assertEquals(data.get().length, fileSize);
        // Not mandatory since implicitely called during destroy of decoder
        for (InterfaceHttpData httpData: decoder.getBodyHttpDatas()) {
            httpData.close();
            factory.removeHttpDataFromClean(request, httpData);
        }
        factory.cleanAllHttpData();
        decoder.destroy();
    }

    // Issue #11668
    private static void commonTestFileDelimiterLFLastChunk(HttpDataFactory factory, boolean inMemory)
            throws IOException {
        int nbChunks = 2;
        int bytesPerChunk = 100000;
        int bytesLastChunk = 10000;
        int fileSize = bytesPerChunk * nbChunks + bytesLastChunk; // set Xmx to a number lower than this and it crashes

        String delimiter = "--861fbeab-cd20-470c-9609-d40a0f704466";
        String prefix = delimiter + "\n" +
                        "Content-Disposition: form-data; name=\"image\"; filename=\"guangzhou.jpeg\"\n" +
                        "Content-Type: image/jpeg\n" +
                        "Content-Length: " + fileSize + "\n" +
                        "\n";

        String suffix = "--861fbeab-cd20-470c-9609-d40a0f704466--";
        byte[] bsuffix = suffix.getBytes(CharsetUtil.UTF_8);
        byte[] bsuffixReal = new byte[bsuffix.length + 2];
        for (int i = 0; i < bsuffix.length; i++) {
            bsuffixReal[1 + i] = bsuffix[i];
        }
        bsuffixReal[0] = HttpConstants.LF;
        bsuffixReal[bsuffixReal.length - 1] = HttpConstants.CR;
        byte[] lastbody = {HttpConstants.LF};

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload");
        request.headers().set("content-type", "multipart/form-data; boundary=861fbeab-cd20-470c-9609-d40a0f704466");
        // +4 => 2xCRLF (beginning, end)
        request.headers().set("content-length", prefix.length() + fileSize + suffix.length() + 4);

        HttpPostMultipartRequestDecoder decoder = new HttpPostMultipartRequestDecoder(factory, request);
        Buffer buf = Helpers.copiedBuffer(prefix.getBytes(CharsetUtil.UTF_8));
        DefaultHttpContent httpContent = new DefaultHttpContent(buf);
        decoder.offer(httpContent);
        assertNotNull(((HttpData) decoder.currentPartialHttpData()).getBuffer());
        httpContent.close();

        byte[] body = new byte[bytesPerChunk];
        Arrays.fill(body, (byte) 1);
        // Set first bytes as CRLF to ensure it is correctly getting the last CRLF
        body[0] = HttpConstants.CR;
        body[1] = HttpConstants.LF;
        for (int i = 0; i < nbChunks; i++) {
            Buffer content = Helpers.copiedBuffer(body, 0, bytesPerChunk);
            httpContent = new DefaultHttpContent(content);
            decoder.offer(httpContent); // **OutOfMemory previously here**
            assertNotNull(((HttpData) decoder.currentPartialHttpData()).getBuffer());
            httpContent.close();
        }
        // Last -2 body = content + CR but no delimiter
        byte[] previousLastbody = new byte[bytesLastChunk + 1];
        Arrays.fill(previousLastbody, (byte) 1);
        previousLastbody[bytesLastChunk] = HttpConstants.CR;
        Buffer content2 = Helpers.copiedBuffer(previousLastbody, 0, previousLastbody.length);
        httpContent = new DefaultHttpContent(content2);
        decoder.offer(httpContent);
        assertNotNull(decoder.currentPartialHttpData());
        httpContent.close();
        // Last -1 body = LF+delimiter+CR but no LF
        content2 = Helpers.copiedBuffer(bsuffixReal, 0, bsuffixReal.length);
        httpContent = new DefaultHttpContent(content2);
        decoder.offer(httpContent);
        assertNull(decoder.currentPartialHttpData());
        httpContent.close();
        // Last (LF)
        content2 = Helpers.copiedBuffer(lastbody, 0, lastbody.length);
        httpContent = new DefaultHttpContent(content2);
        decoder.offer(httpContent);
        assertNull(decoder.currentPartialHttpData());
        httpContent.close();
        // End
        decoder.offer(Helpers.defaultLastHttpContent());

        FileUpload data = (FileUpload) decoder.getBodyHttpDatas().get(0);
        assertEquals(data.length(), fileSize);
        assertEquals(inMemory, data.isInMemory());
        if (data.isInMemory()) {
            // To be done only if not inMemory: assertEquals(data.get().length, fileSize);
            assertFalse(data.getBuffer().capacity() < fileSize,
                        "Capacity should be at least file size");
        }
        assertTrue(decoder.getCurrentAllocatedCapacity() < fileSize,
                   "Capacity should be less than 1M");
        InterfaceHttpData[] httpDatas = decoder.getBodyHttpDatas().toArray(new InterfaceHttpData[0]);
        factory.cleanAllHttpData();
        decoder.destroy();
    }

    @Test
    public void testFileDelimiterLFLastChunkDecoderDiskFactory() throws IOException {
        // Factory using Disk mode
        HttpDataFactory factory = new DefaultHttpDataFactory(true);

        commonTestFileDelimiterLFLastChunk(factory, false);
    }

    @Test
    public void testFileDelimiterLFLastChunkDecoderMemoryFactory() throws IOException {
        // Factory using Memory mode
        HttpDataFactory factory = new DefaultHttpDataFactory(false);

        commonTestFileDelimiterLFLastChunk(factory, true);
    }

    @Test
    public void testFileDelimiterLFLastChunkDecoderMixedFactory() throws IOException {
        // Factory using Mixed mode, where file shall be on Disk
        HttpDataFactory factory = new DefaultHttpDataFactory(10000);

        commonTestFileDelimiterLFLastChunk(factory, false);
    }

}
