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

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.micronaut.fuzzing.util.ByteSplitter;
import io.netty5.buffer.Buffer;
import io.netty5.handler.codec.http.DefaultHttpContent;
import io.netty5.handler.codec.http.DefaultHttpRequest;
import io.netty5.handler.codec.http.DefaultLastHttpContent;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Assertions;

import java.io.Closeable;
import java.util.List;

/**
 * This fuzz test compares the behavior of {@link HttpPostMultipartRequestDecoder} with that of
 * {@link HttpPostMultipartRequestDecoderLegacy}, a copy of the old decoder implementation.
 */
public class MultipartComparisonTest extends AbstractFuzzTest {
    private static boolean logStackTraces = true;

    public static void main(String[] args) throws Throwable {
        minimize(MultipartComparisonTest.class, "src/test/resources/io/netty/contrib/handler/codec/http/multipart/MultiPartComparisonTestInputs/compare/crash-ad03f90654bb98077b5ca8093bfe6f7ba875341a");
    }

    @SuppressWarnings("unused")
    public static void fuzzerTestOneInput(byte[] bytes) {
        new MultipartComparisonTest().compare(bytes);
    }

    @MultipartFuzzTest
    @FuzzTest(maxDuration = "2h")
    public void compare(byte[] bytes) {
        try (Runner runner = new Runner()) {
            ByteSplitter.ChunkIterator itr = FUZZ_SPLITTER.splitIterator(bytes);
            while (itr.hasNext() && !runner.failed) {
                Buffer piece = next(bytes, itr);
                runner.offer(itr.hasNext() ? new DefaultLastHttpContent(piece) : new DefaultHttpContent(piece));
            }
        }
        logStackTraces = false;
    }

    private static class Runner implements Closeable {
        static final HttpRequest REQUEST = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        static final HttpDataFactory FACTORY = new DefaultHttpDataFactory(false);

        static {
            REQUEST.headers().add(HttpHeaderNames.CONTENT_TYPE, "multipart/form-data; boundary=" + BOUNDARY);
        }

        final HttpPostMultipartRequestDecoderLegacy a;
        final HttpPostMultipartRequestDecoder b;
        boolean failed = false;

        private Runner() {
            a = new HttpPostMultipartRequestDecoderLegacy(FACTORY, REQUEST);
            b = new HttpPostMultipartRequestDecoder(FACTORY, REQUEST);
        }

        void offer(HttpContent<?> content) {
            Exception exc1 = null;
            try {
                a.offer(content.copy());
            } catch (Exception e) {
                if (logStackTraces) {
                    e.printStackTrace();
                }
                exc1 = e;
                failed = true;
            }
            Exception exc2 = null;
            try {
                b.offer(content);
            } catch (Exception e) {
                if (logStackTraces) {
                    e.printStackTrace();
                }
                exc2 = e;
                failed = true;
            }
            Assertions.assertEquals(exc1 == null, exc2 == null);
            if (exc1 != null) {
                Assertions.assertEquals(exc1.getClass(), exc2.getClass());
                try {
                    Assertions.assertEquals(exc1.getMessage(), exc2.getMessage());
                } catch (AssertionError e) {
                    // NPE does not have consistent messages
                    boolean inconsistentMessage = false;
                    for (Class<?> cl : List.of(NullPointerException.class, ArrayIndexOutOfBoundsException.class, IndexOutOfBoundsException.class)) {
                        if ((cl.isInstance(exc1.getCause()) && cl.isInstance(exc2.getCause())) ||
                                (cl.isInstance(exc1) && cl.isInstance(exc2))) {
                            inconsistentMessage = true;
                            break;
                        }
                    }
                    if (!inconsistentMessage) {
                        exc1.printStackTrace();
                        exc2.printStackTrace();
                        throw e;
                    }
                }
            }
            compare(a, b);
        }

        @Override
        public void close() {
            a.destroy();
            b.destroy();
        }
    }

    private static void compare(HttpPostMultipartRequestDecoderLegacy a, HttpPostMultipartRequestDecoder b) {
        HttpData partialA = (HttpData) a.currentPartialHttpData();
        HttpData partialB = (HttpData) b.currentPartialHttpData();
        Assertions.assertEquals(partialA == null, partialB == null);
        if (partialA != null) {
            compare(partialA, partialB);
        }
        Assertions.assertEquals(a.bodyListHttpData.size(), b.bodyListHttpData.size());
        for (int i = 0; i < a.bodyListHttpData.size(); i++) {
            compare((HttpData) a.bodyListHttpData.get(i), (HttpData) b.bodyListHttpData.get(i));
        }
    }

    private static void compare(HttpData a, HttpData b) {
        Assertions.assertEquals(a.getName(), b.getName());
        Assertions.assertEquals(a.getHttpDataType(), b.getHttpDataType());
        Assertions.assertEquals(a.getCharset(), b.getCharset());
        Assertions.assertEquals(a.definedLength(), b.definedLength());
        Assertions.assertEquals(a.length(), b.length());
        Assertions.assertEquals(a.isCompleted(), b.isCompleted());
        Assertions.assertEquals(a.getMaxSize(), b.getMaxSize());
        if (a instanceof FileUpload) {
            Assertions.assertEquals(((FileUpload) a).getContentType(), ((FileUpload) b).getContentType());
            Assertions.assertEquals(((FileUpload) a).getContentTransferEncoding(), ((FileUpload) b).getContentTransferEncoding());
            Assertions.assertEquals(((FileUpload) a).getFilename(), ((FileUpload) b).getFilename());
        }
        Assertions.assertEquals(((AbstractMemoryHttpData) a).byteBuf, ((AbstractMemoryHttpData) b).byteBuf);
    }
}
