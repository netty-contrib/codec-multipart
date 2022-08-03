/*
 * Copyright 2012 The Netty Project
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

import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.Drop;
import io.netty5.buffer.api.Owned;
import io.netty5.buffer.api.internal.ResourceSupport;
import io.netty5.handler.codec.http.HttpConstants;
import io.netty5.util.Send;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Mixed implementation using both in Memory and in File with a limit of size
 */
public class MixedAttribute extends ResourceSupport<HttpData, MixedAttribute> implements Attribute {
    private final String baseDir;
    private final boolean deleteOnExit;
    private Attribute attribute;

    private final long limitSize;
    private long maxSize = DefaultHttpDataFactory.MAXSIZE;

    private final static Drop<MixedAttribute> drop = new Drop<MixedAttribute>() {
        @Override
        public void drop(MixedAttribute data) {
            data.delete();
        }

        @Override
        public Drop<MixedAttribute> fork() {
            return this;
        }

        @Override
        public void attach(MixedAttribute mixedFileUpload) {
        }
    };

    private MixedAttribute(String baseDir, boolean deleteOnExit, long limitSize, Attribute attribute, long maxSize) {
        super (drop);
        this.baseDir = baseDir;
        this.deleteOnExit = deleteOnExit;
        this.limitSize = limitSize;
        this.attribute = attribute;
        this.maxSize = maxSize;
    }

    public MixedAttribute(String name, long limitSize) {
        this(name, limitSize, HttpConstants.DEFAULT_CHARSET);
    }

    public MixedAttribute(String name, long definedSize, long limitSize) {
        this(name, definedSize, limitSize, HttpConstants.DEFAULT_CHARSET);
    }

    public MixedAttribute(String name, long limitSize, Charset charset) {
        this(name, limitSize, charset, DiskAttribute.baseDirectory, DiskAttribute.deleteOnExitTemporaryFile);
    }

    public MixedAttribute(String name, long limitSize, Charset charset, String baseDir, boolean deleteOnExit) {
        super (drop);
        this.limitSize = limitSize;
        attribute = new MemoryAttribute(name, charset);
        this.baseDir = baseDir;
        this.deleteOnExit = deleteOnExit;
    }

    public MixedAttribute(String name, long definedSize, long limitSize, Charset charset) {
        this(name, definedSize, limitSize, charset,
                DiskAttribute.baseDirectory, DiskAttribute.deleteOnExitTemporaryFile);
    }

    public MixedAttribute(String name, long definedSize, long limitSize, Charset charset,
                          String baseDir, boolean deleteOnExit) {
        super (drop);
        this.limitSize = limitSize;
        attribute = new MemoryAttribute(name, definedSize, charset);
        this.baseDir = baseDir;
        this.deleteOnExit = deleteOnExit;
    }

    public MixedAttribute(String name, String value, long limitSize) {
        this(name, value, limitSize, HttpConstants.DEFAULT_CHARSET,
                DiskAttribute.baseDirectory, DiskFileUpload.deleteOnExitTemporaryFile);
    }

    public MixedAttribute(String name, String value, long limitSize, Charset charset) {
        this(name, value, limitSize, charset,
                DiskAttribute.baseDirectory, DiskFileUpload.deleteOnExitTemporaryFile);
    }

    public MixedAttribute(String name, String value, long limitSize, Charset charset,
                          String baseDir, boolean deleteOnExit) {
        super (drop);
        this.limitSize = limitSize;
        if (value.length() > this.limitSize) {
            try {
                attribute = new DiskAttribute(name, value, charset, baseDir, deleteOnExit);
            } catch (IOException e) {
                // revert to Memory mode
                try {
                    attribute = new MemoryAttribute(name, value, charset);
                } catch (IOException ignore) {
                    throw new IllegalArgumentException(e);
                }
            }
        } else {
            try {
                attribute = new MemoryAttribute(name, value, charset);
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }
        }
        this.baseDir = baseDir;
        this.deleteOnExit = deleteOnExit;
    }

    @Override
    public long getMaxSize() {
        return maxSize;
    }

    @Override
    public void setMaxSize(long maxSize) {
        checkAccessible();
        this.maxSize = maxSize;
        attribute.setMaxSize(maxSize);
    }

    @Override
    public void checkSize(long newSize) throws IOException {
        checkAccessible();
        if (maxSize >= 0 && newSize > maxSize) {
            throw new IOException("Size exceed allowed maximum capacity");
        }
    }

    @Override
    public void addContent(Buffer buffer, boolean last) throws IOException {
        checkAccessible(buffer);
        if (attribute instanceof MemoryAttribute) {
            try {
                checkSize(attribute.length() + buffer.readableBytes());
                if (attribute.length() + buffer.readableBytes() > limitSize) {
                    DiskAttribute diskAttribute = new DiskAttribute(attribute
                            .getName(), attribute.definedLength(), baseDir, deleteOnExit);
                    diskAttribute.setMaxSize(maxSize);
                    if (((MemoryAttribute) attribute).getBuffer() != null) {
                        diskAttribute.addContent(((MemoryAttribute) attribute)
                            .getBuffer(), false);
                    }
                    attribute = diskAttribute;
                }
            } catch (IOException e) {
                buffer.close();
                throw e;
            }
        }
        attribute.addContent(buffer, last);
    }

    @Override
    public void delete() {
        attribute.delete();
    }

    @Override
    public byte[] get() throws IOException {
        return attribute.get();
    }

    @Override
    public Buffer getBuffer() throws IOException {
        return attribute.getBuffer();
    }

    @Override
    public Charset getCharset() {
        return attribute.getCharset();
    }

    @Override
    public String getString() throws IOException {
        return attribute.getString();
    }

    @Override
    public String getString(Charset encoding) throws IOException {
        return attribute.getString(encoding);
    }

    @Override
    public boolean isCompleted() {
        return attribute.isCompleted();
    }

    @Override
    public boolean isInMemory() {
        return attribute.isInMemory();
    }

    @Override
    public long length() {
        return attribute.length();
    }

    @Override
    public long definedLength() {
        return attribute.definedLength();
    }

    @Override
    public boolean renameTo(File dest) throws IOException {
        return attribute.renameTo(dest);
    }

    @Override
    public void setCharset(Charset charset) {
        attribute.setCharset(charset);
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
            if (attribute instanceof MemoryAttribute) {
                // change to Disk
                attribute = new DiskAttribute(attribute.getName(), attribute.definedLength(), baseDir, deleteOnExit);
                attribute.setMaxSize(maxSize);
            }
        }
        attribute.setContent(buffer);
    }

    @Override
    public void setContent(File file) throws IOException {
        checkAccessible();
        checkSize(file.length());
        if (file.length() > limitSize) {
            if (attribute instanceof MemoryAttribute) {
                // change to Disk
                attribute = new DiskAttribute(attribute.getName(), attribute.definedLength(), baseDir, deleteOnExit);
                attribute.setMaxSize(maxSize);
            }
        }
        attribute.setContent(file);
    }

    @Override
    public void setContent(InputStream inputStream) throws IOException {
        checkAccessible();
        if (attribute instanceof MemoryAttribute) {
            // change to Disk even if we don't know the size
            attribute = new DiskAttribute(attribute.getName(), attribute.definedLength(), baseDir, deleteOnExit);
            attribute.setMaxSize(maxSize);
        }
        attribute.setContent(inputStream);
    }

    @Override
    public HttpDataType getHttpDataType() {
        return attribute.getHttpDataType();
    }

    @Override
    public String getName() {
        return attribute.getName();
    }

    @Override
    public int hashCode() {
        return attribute.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return attribute.equals(obj);
    }

    @Override
    public int compareTo(InterfaceHttpData o) {
        return attribute.compareTo(o);
    }

    @Override
    public String toString() {
        return "Mixed: " + attribute;
    }

    @Override
    public String getValue() throws IOException {
        return attribute.getValue();
    }

    @Override
    public void setValue(String value) throws IOException {
        attribute.setValue(value);
    }

    @Override
    public Buffer getChunk(int length) throws IOException {
        return attribute.getChunk(length);
    }

    @Override
    public File getFile() throws IOException {
        return attribute.getFile();
    }

    @Override
    public Attribute copy() {
        return attribute.copy();
    }

    @Override
    public Attribute replace(Buffer content) {
        return attribute.replace(content);
    }

    @Override
    public Buffer content() {
        return attribute.content();
    }

    @Override
    protected Owned<MixedAttribute> prepareSend() {
        Send<HttpData> send = attribute.send();
        return drop -> {
            Attribute receivedAttr = (Attribute) send.receive();
            MixedAttribute copy = new MixedAttribute(baseDir, deleteOnExit, limitSize, receivedAttr, maxSize);
            return copy;
        };
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
