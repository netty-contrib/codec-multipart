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
import io.netty.contrib.multipart.PostBodyDecoder;
import io.netty5.buffer.Buffer;
import io.netty5.buffer.CompositeBuffer;
import io.netty5.buffer.DefaultBufferAllocators;
import org.junit.jupiter.api.Assertions;

import java.io.Closeable;

/**
 * This fuzz test verifies that the parsed stream of a single buffer is the same as that of a buffer arriving in
 * multiple chunks.
 */
abstract class AbstractDecoderSplitTest extends AbstractFuzzTest {
    protected abstract PostBodyDecoder createDecoder();

    @MultipartFuzzTest
    @FuzzTest(maxDuration = "2h")
    public void compare(byte[] bytes) {
        try (DecoderWrapper splitDecoder = new DecoderWrapper(createDecoder());
             DecoderWrapper jointDecoder = new DecoderWrapper(createDecoder())) {
            Assertions.assertEquals(jointDecoder.decoder.hasUnparsedHeaderValue(), splitDecoder.decoder.hasUnparsedHeaderValue());

            try (Buffer jointBuffer = DefaultBufferAllocators.preferredAllocator().allocate(bytes.length)) {
                ByteSplitter.ChunkIterator itr = FUZZ_SPLITTER.splitIterator(bytes);
                while (itr.hasNext()) {
                    itr.proceed();
                    jointBuffer.writeBytes(bytes, itr.start(), itr.length());
                }
                jointDecoder.decoder.add(jointBuffer.send());
            }

            ByteSplitter.ChunkIterator itr = FUZZ_SPLITTER.splitIterator(bytes);
            while (itr.hasNext()) {
                splitDecoder.decoder.add(next(bytes, itr).send());

                while (true) {
                    PostBodyDecoder.Event splitEvent;
                    try {
                        splitEvent = splitDecoder.next();
                    } catch (HttpPostRequestDecoder.ErrorDataDecoderException splitE) {
                        try {
                            jointDecoder.next();
                            Assertions.fail("Joint decoder should also fail", splitE);
                        } catch (HttpPostRequestDecoder.ErrorDataDecoderException jointE) {
                            Assertions.assertEquals(jointE.getMessage(), splitE.getMessage());
                        }
                        return;
                    }
                    if (splitEvent == null) {
                        break;
                    }
                    PostBodyDecoder.Event jointEvent = jointDecoder.next();
                    Assertions.assertEquals(jointEvent, splitEvent);
                    if (splitEvent == PostBodyDecoder.Event.HEADER) {
                        Assertions.assertEquals(jointDecoder.decoder.headerName(), splitDecoder.decoder.headerName());
                        Assertions.assertEquals(jointDecoder.decoder.parsedHeaderValue(), splitDecoder.decoder.parsedHeaderValue());
                        if (jointDecoder.decoder.hasUnparsedHeaderValue()) {
                            Assertions.assertEquals(jointDecoder.decoder.headerValue(), splitDecoder.decoder.headerValue());
                        }
                    } else if (splitEvent == PostBodyDecoder.Event.FIELD_COMPLETE) {
                        Assertions.assertEquals(jointDecoder.composite, splitDecoder.composite);
                        jointDecoder.clearBuffer();
                        splitDecoder.clearBuffer();
                    }
                }
            }
        }
    }

    private static class DecoderWrapper implements Closeable {
        private final PostBodyDecoder decoder;

        CompositeBuffer composite;

        DecoderWrapper(PostBodyDecoder decoder) {
            this.decoder = decoder;
        }

        PostBodyDecoder.Event next() {
            PostBodyDecoder.Event event = decoder.next();
            if (composite == null) {
                if (event != PostBodyDecoder.Event.CONTENT && event != PostBodyDecoder.Event.FIELD_COMPLETE) {
                    return event;
                }
                composite = DefaultBufferAllocators.preferredAllocator().compose();
            }
            while (event != PostBodyDecoder.Event.FIELD_COMPLETE) {
                if (event == null) {
                    return null;
                }
                composite.extendWith(decoder.decodedContent());
                event = decoder.next();
            }
            return PostBodyDecoder.Event.FIELD_COMPLETE;
        }

        @Override
        public void close() {
            clearBuffer();
            decoder.close();
        }

        void clearBuffer() {
            if (composite != null) {
                composite.close();
                composite = null;
            }
        }
    }
}
