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
 */package io.netty.contrib.handler.codec.http.multipart;

import io.netty5.buffer.Buffer;
import io.netty5.buffer.DefaultBufferAllocators;
import io.netty5.handler.codec.http.DefaultLastHttpContent;
import io.netty5.handler.codec.http.EmptyLastHttpContent;
import io.netty5.util.AsciiString;
import io.netty5.util.Resource;

import java.nio.charset.Charset;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * Various helper methods used by multipart implementation classes.
 */
public class Helpers {

    /**
     * A Consumer which can throw Exceptions. This utility class can be used to
     * wrap a throwing consumer into a regular java Consumer, and allows to
     * rethrow checked exceptions as unchecked RuntimeException.
     */
    @FunctionalInterface
    public interface ThrowingConsumer<T> extends Consumer<T> {

        @Override
        default void accept(final T e) {
            try {
                throwingAccept(e);
            } catch (Throwable ex) {
                rethrowChecked(ex);
            }
        }

        void throwingAccept(T e) throws Throwable;

        /**
         * Wraps a ThrowingConsumer which can throw an Exception into a regular java Consumer which does not
         * throw anything. If the consumer throws any exception, it will be rethrown as an unchecked
         * RuntimeException.
         *
         * @param consumer a throwing consumer which may throw an exception
         * @return the throwing consumer wrapped as a regular java consumer
         * @param <T> the type of the acceptable input
         */
        static <T> Consumer<T> unchecked(final ThrowingConsumer<T> consumer) {
            return consumer;
        }

        /**
         * Rethrow an exception as an unchecked RuntimeException, even if the Throwable is a
         * checked Exception.
         */
        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void rethrowChecked(Throwable ex) throws E {
            throw (E) ex;
        }
    }

    static Buffer copiedBuffer(String str, Charset charset) {
        return DefaultBufferAllocators.onHeapAllocator().copyOf(str, charset);
    }

    static Buffer copiedBuffer(byte[] bytes, int offset, int length) {
        Buffer buf = DefaultBufferAllocators.onHeapAllocator().allocate(length);
        buf.writeBytes(bytes, offset, length);
        return buf;
    }

    static Buffer copiedBuffer(byte[] bytes) {
        Buffer buf = DefaultBufferAllocators.onHeapAllocator().allocate(bytes.length);
        buf.writeBytes(bytes);
        return buf;
    }

    static Buffer toComposite(Buffer ... bufs) {
        return DefaultBufferAllocators.onHeapAllocator().compose(Stream.of(bufs).map(Resource::send).collect(Collectors.toList()));
    }

    static EmptyLastHttpContent emptyLastHttpContent() {
        return new EmptyLastHttpContent(DefaultBufferAllocators.preferredAllocator());
    }

    static DefaultLastHttpContent defaultLastHttpContent() {
        return new DefaultLastHttpContent(DefaultBufferAllocators.preferredAllocator().allocate(0));
    }

    static String toString(Buffer buf, int offset, int length, Charset charset) {
        byte[] bytes = new byte[length];
        buf.copyInto(offset, bytes, 0, length);
        if (US_ASCII.equals(charset)) {
            return new AsciiString(bytes).toString();
        }
        return new String(bytes, 0, length, charset);
    }

}
