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
package io.netty.contrib.microbenchmarks.http.multipart;

import io.netty.contrib.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.contrib.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.contrib.handler.codec.http.multipart.InterfaceHttpData;
import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.BufferAllocator;
import io.netty5.handler.codec.http.DefaultHttpContent;
import io.netty5.handler.codec.http.DefaultHttpRequest;
import io.netty5.handler.codec.http.DefaultLastHttpContent;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpVersion;
import io.netty5.microbench.util.AbstractMicrobenchmark;
import io.netty5.util.CharsetUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Threads(1)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(jvmArgsAppend = {"-dsa",
        "-da",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+DebugNonSafepoints",
        "-Dio.netty5.leakDetection.level=disabled",        // changed to paranoid for detecting buffer leaks
        "-Dio.netty5.buffer.leakDetectionEnabled=false",   // changed to true for detecting buffer leaks
        "-Dio.netty5.buffer.lifecycleTracingEnabled=false" // changed to true for detecting buffer leaks
})
public class HttpPostMultipartRequestDecoderBenchmark
        extends AbstractMicrobenchmark {

    @State(Scope.Benchmark)
    public static class Context {
        final static String BOUNDARY = "01f136d9282f";

        final Supplier<Buffer> bodyStartBytesSupplier;
        final Supplier<Buffer> finalBigBytesSupplier;
        final Supplier<Buffer> bodyPartBigBytesSupplier;
        final Supplier<Buffer> intermediaryBytesSupplier;

        public Context() {
            int size = 8 * 1024;
            StringBuilder stringBuilder = new StringBuilder(size);
            stringBuilder.setLength(size);
            String data = stringBuilder.toString();

            byte[] bodyStartBytes = ("--" + BOUNDARY + "\n" +
                    "Content-Disposition: form-data; name=\"msg_id\"\n\n15200\n--" +
                    BOUNDARY +
                    "\nContent-Disposition: form-data; name=\"msg1\"; filename=\"file1.txt\"\n\n" +
                    data).getBytes(CharsetUtil.UTF_8);
            byte[] bodyPartBigBytes = data.getBytes(CharsetUtil.UTF_8);
            byte[] intermediaryBytes = ("\n--" + BOUNDARY +
                    "\nContent-Disposition: form-data; name=\"msg2\"; filename=\"file2.txt\"\n\n" +
                    data).getBytes(CharsetUtil.UTF_8);
            byte[] finalBigBytes = ("\n" + "--" + BOUNDARY + "--\n").getBytes(CharsetUtil.UTF_8);

            bodyStartBytesSupplier = BufferAllocator.onHeapUnpooled().constBufferSupplier(bodyStartBytes);
            finalBigBytesSupplier = BufferAllocator.onHeapUnpooled().constBufferSupplier(finalBigBytes);
            bodyPartBigBytesSupplier = BufferAllocator.onHeapUnpooled().constBufferSupplier(bodyPartBigBytes);
            intermediaryBytesSupplier = BufferAllocator.onHeapUnpooled().constBufferSupplier(intermediaryBytes);
        }
    }

    public double testHighNumberChunks(Context ctx, boolean big, boolean noDisk) {
        int chunkNumber = 64;

        Buffer firstBuf = ctx.bodyStartBytesSupplier.get();
        Buffer finalBuf = ctx.finalBigBytesSupplier.get();
        DefaultHttpRequest req =
                new DefaultHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.POST, "/up");
        req.headers().add(HttpHeaderNames.CONTENT_TYPE,
                          "multipart/form-data; boundary=" + ctx.BOUNDARY);

        long start = System.nanoTime();

        DefaultHttpDataFactory defaultHttpDataFactory =
                new DefaultHttpDataFactory(noDisk? 1024 * 1024 : 16 * 1024);
        HttpPostRequestDecoder decoder =
                new HttpPostRequestDecoder(defaultHttpDataFactory, req);

        try (firstBuf) {
            decoder.offer(new DefaultHttpContent(firstBuf));
        }

        for (int i = 1; i < chunkNumber; i++) {
            try (Buffer nextBuf = big ? ctx.bodyPartBigBytesSupplier.get() : ctx.intermediaryBytesSupplier.get()) {
                decoder.offer(new DefaultHttpContent(nextBuf));
            }
        }

        try(finalBuf) {
            decoder.offer(new DefaultLastHttpContent(finalBuf));
        }

        while (decoder.hasNext()) {
            InterfaceHttpData httpData = decoder.next();
        }

        long stop = System.nanoTime();
        double time = (stop - start) / 1000000.0;
        defaultHttpDataFactory.cleanAllHttpData();
        defaultHttpDataFactory.cleanRequestHttpData(req);
        decoder.destroy();
        return time;
    }

    @Benchmark
    public double multipartRequestDecoderHigh(Context ctx) {
        return testHighNumberChunks(ctx,false, true);
    }

    @Benchmark
    public double multipartRequestDecoderBig(Context ctx) {
        return testHighNumberChunks(ctx,true, true);
    }
}
