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
import io.netty5.buffer.Drop;
import io.netty5.buffer.internal.ResourceSupport;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.function.Consumer;

import static io.netty.contrib.handler.codec.http.multipart.Helpers.ThrowingConsumer.unchecked;

abstract class AbstractMixedHttpData<D extends HttpData> extends ResourceSupport<HttpData, AbstractMixedHttpData<? extends HttpData>> implements HttpData {
    final String baseDir;
    final boolean deleteOnExit;
    D wrapped;

    protected final long limitSize;

    private final static Drop<AbstractMixedHttpData<? extends HttpData>> drop = new Drop<>() {
        @Override
        public void drop(AbstractMixedHttpData<? extends HttpData> data) {
            data.delete();
        }

        @Override
        public Drop<AbstractMixedHttpData<? extends HttpData>> fork() {
            return this;
        }

        @Override
        public void attach(AbstractMixedHttpData<? extends HttpData> mixedFileUpload) {
        }
    };

    AbstractMixedHttpData(long limitSize, String baseDir, boolean deleteOnExit, D initial) {
        super (drop);
        this.limitSize = limitSize;
        this.wrapped = initial;
        this.baseDir = baseDir;
        this.deleteOnExit = deleteOnExit;
    }

    abstract D makeDiskData();

    @Override
    public long getMaxSize() {
        return wrapped.getMaxSize();
    }

    @Override
    public void setMaxSize(long maxSize) {
        checkAccessible();
        wrapped.setMaxSize(maxSize);
    }

    @Override
    public void withContent(Consumer<Buffer> bufferConsumer) {
        wrapped.withContent(bufferConsumer);
    }

    @Override
    public void checkSize(long newSize) throws IOException {
        checkAccessible();
        wrapped.checkSize(newSize);
    }

    @Override
    public long definedLength() {
        return wrapped.definedLength();
    }

    @Override
    public Charset getCharset() {
        return wrapped.getCharset();
    }

    @Override
    public String getName() {
        return wrapped.getName();
    }

    @Override
    public void addContent(Buffer buffer, boolean last) throws IOException {
        checkAccessible(buffer);
        if (wrapped instanceof AbstractMemoryHttpData) {
            try {
                checkSize(wrapped.length() + buffer.readableBytes());
                if (wrapped.length() + buffer.readableBytes() > limitSize) {
                    D diskData = makeDiskData();
                    // Because the diskData.addContent method throws an exception, use
                    // the Helpers.ThrowingConsumer.unchecked helper which allows
                    // to wrap a throwing consumer into a regular consumer
                    ((AbstractMemoryHttpData) wrapped).withBuffer(unchecked(data -> {
                        if (data != null && data.readableBytes() > 0) {
                            diskData.addContent(data, false); // data will be closed by this method
                        }
                    }));
                    wrapped.close();
                    wrapped = diskData;
                }
            } catch (IOException e) {
                buffer.close();
                throw e;
            }
        }
        wrapped.addContent(buffer, last);
    }

    @Override
    public void delete() {
        wrapped.delete();
    }

    @Override
    public byte[] get() throws IOException {
        return wrapped.get();
    }

    @Override
    public void withBuffer(Consumer<Buffer> bufferConsumer) throws IOException {
        wrapped.withBuffer(bufferConsumer);
    }

    @Override
    public String getString() throws IOException {
        return wrapped.getString();
    }

    @Override
    public String getString(Charset encoding) throws IOException {
        return wrapped.getString(encoding);
    }

    @Override
    public boolean isInMemory() {
        return wrapped.isInMemory();
    }

    @Override
    public long length() {
        return wrapped.length();
    }

    @Override
    public boolean renameTo(File dest) throws IOException {
        return wrapped.renameTo(dest);
    }

    @Override
    public void setCharset(Charset charset) {
        wrapped.setCharset(charset);
    }

    @Override
    public void setContent(Buffer buffer) throws IOException {
        checkAccessible(buffer);
        try {
            checkSize(buffer.readableBytes());
        } catch (IOException e) {
            buffer.close();
            throw e;
        }
        if (buffer.readableBytes() > limitSize) {
            if (wrapped instanceof AbstractMemoryHttpData) {
                // change to Disk
                D oldWrapped = wrapped;
                try (oldWrapped) {
                    wrapped = makeDiskData();
                }
            }
        }
        wrapped.setContent(buffer);
    }

    @Override
    public void setContent(File file) throws IOException {
        checkAccessible();
        checkSize(file.length());
        if (file.length() > limitSize) {
            if (wrapped instanceof AbstractMemoryHttpData) {
                // change to Disk
                D oldWrapped = wrapped;
                try (oldWrapped) {
                    wrapped = makeDiskData();
                }
            }
        }
        wrapped.setContent(file);
    }

    @Override
    public void setContent(InputStream inputStream) throws IOException {
        checkAccessible();
        if (wrapped instanceof AbstractMemoryHttpData) {
            // change to Disk even if we don't know the size
            D oldWrapped = wrapped;
            try(oldWrapped) {
                wrapped = makeDiskData();
            }
        }
        wrapped.setContent(inputStream);
    }

    @Override
    public boolean isCompleted() {
        return wrapped.isCompleted();
    }

    @Override
    public HttpDataType getHttpDataType() {
        return wrapped.getHttpDataType();
    }

    @Override
    public int hashCode() {
        return wrapped.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return wrapped.equals(obj);
    }

    @Override
    public int compareTo(InterfaceHttpData o) {
        return wrapped.compareTo(o);
    }

    @Override
    public String toString() {
        return "Mixed: " + wrapped;
    }

    @Override
    public Buffer getChunk(int length) throws IOException {
        return wrapped.getChunk(length);
    }

    @Override
    public File getFile() throws IOException {
        return wrapped.getFile();
    }

    @SuppressWarnings("unchecked")
    @Override
    public D copy() {
        return (D) wrapped.copy();
    }

    @SuppressWarnings("unchecked")
    @Override
    public D replace(Buffer content) {
        return (D) wrapped.replace(content);
    }

    @Override
    protected RuntimeException createResourceClosedException() {
        return new IllegalStateException("Resource is closed");
    }

    protected void checkAccessible() {
        if (! isAccessible()) {
            throw createResourceClosedException();
        }
    }

    protected void checkAccessible(Buffer cleanup) {
        if (! isAccessible()) {
            if (cleanup != null && cleanup.isAccessible()) {
                cleanup.close();
            }
            throw createResourceClosedException();
        }
    }
}
