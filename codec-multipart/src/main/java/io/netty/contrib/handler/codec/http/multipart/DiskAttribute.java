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
import io.netty5.buffer.api.DefaultBufferAllocators;
import io.netty5.buffer.api.Owned;
import io.netty5.channel.ChannelException;
import io.netty5.handler.codec.http.HttpConstants;
import io.netty5.util.internal.ObjectUtil;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Disk implementation of Attributes
 */
public class DiskAttribute extends AbstractDiskHttpData implements Attribute {
    public static String baseDirectory;

    public static boolean deleteOnExitTemporaryFile = true;

    public static final String prefix = "Attr_";

    public static final String postfix = ".att";

    private String baseDir;

    private boolean deleteOnExit;

    /**
     * Constructor used for huge Attribute
     */
    public DiskAttribute(String name) {
        this(name, HttpConstants.DEFAULT_CHARSET);
    }

    public DiskAttribute(String name, String baseDir, boolean deleteOnExit) {
        this(name, HttpConstants.DEFAULT_CHARSET);
        this.baseDir = baseDir == null ? baseDirectory : baseDir;
        this.deleteOnExit = deleteOnExit;
    }

    public DiskAttribute(String name, long definedSize) {
        this(name, definedSize, HttpConstants.DEFAULT_CHARSET, baseDirectory, deleteOnExitTemporaryFile);
    }

    public DiskAttribute(String name, long definedSize, String baseDir, boolean deleteOnExit) {
        this(name, definedSize, HttpConstants.DEFAULT_CHARSET);
        this.baseDir = baseDir == null ? baseDirectory : baseDir;
        this.deleteOnExit = deleteOnExit;
    }

    public DiskAttribute(String name, Charset charset) {
        this(name, charset, baseDirectory, deleteOnExitTemporaryFile);
    }

    public DiskAttribute(String name, Charset charset, String baseDir, boolean deleteOnExit) {
        super(name, charset, 0);
        this.baseDir = baseDir == null ? baseDirectory : baseDir;
        this.deleteOnExit = deleteOnExit;
    }

    public DiskAttribute(String name, long definedSize, Charset charset) {
        this(name, definedSize, charset, baseDirectory, deleteOnExitTemporaryFile);
    }

    public DiskAttribute(String name, long definedSize, Charset charset, String baseDir, boolean deleteOnExit) {
        super(name, charset, definedSize);
        this.baseDir = baseDir == null ? baseDirectory : baseDir;
        this.deleteOnExit = deleteOnExit;
    }

    public DiskAttribute(String name, String value) throws IOException {
        this(name, value, HttpConstants.DEFAULT_CHARSET);
    }

    public DiskAttribute(String name, String value, Charset charset) throws IOException {
        this(name, value, charset, baseDirectory, deleteOnExitTemporaryFile);
    }

    public DiskAttribute(String name, String value, Charset charset,
                         String baseDir, boolean deleteOnExit) throws IOException {
        super(name, charset, 0); // Attribute have no default size
        setValue(value);
        this.baseDir = baseDir == null ? baseDirectory : baseDir;
        this.deleteOnExit = deleteOnExit;
    }

    protected DiskAttribute(DiskAttribute copy) {
        super(copy);
        this.baseDir = copy.baseDir;
        this.deleteOnExit = copy.deleteOnExit;
    }

    @Override
    public HttpDataType getHttpDataType() {
        return HttpDataType.Attribute;
    }

    @Override
    public String getValue() throws IOException {
        checkAccessible();
        byte [] bytes = get();
        return new String(bytes, getCharset());
    }

    @Override
    public void setValue(String value) throws IOException {
        checkAccessible();
        ObjectUtil.checkNotNullWithIAE(value, "value");
        byte [] bytes = value.getBytes(getCharset());
        checkSize(bytes.length);
        Buffer buffer = DefaultBufferAllocators.onHeapAllocator().copyOf(bytes);
        if (definedSize > 0) {
            definedSize = buffer.readableBytes();
        }
        setContent(buffer);
    }

    @Override
    public void addContent(Buffer buffer, boolean last) throws IOException {
        checkAccessible(buffer);
        final long newDefinedSize = size + buffer.readableBytes();
        try {
            checkSize(newDefinedSize);
        } catch (IOException e) {
            buffer.close();
            throw e;
        }
        if (definedSize > 0 && definedSize < newDefinedSize) {
            definedSize = newDefinedSize;
        }
        super.addContent(buffer, last);
    }

    @Override
    public int hashCode() {
        return getName().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Attribute)) {
            return false;
        }
        Attribute attribute = (Attribute) o;
        return getName().equalsIgnoreCase(attribute.getName());
    }

    @Override
    public int compareTo(InterfaceHttpData o) {
        if (!(o instanceof Attribute)) {
            throw new ClassCastException("Cannot compare " + getHttpDataType() +
                    " with " + o.getHttpDataType());
        }
        return compareTo((Attribute) o);
    }

    public int compareTo(Attribute o) {
        return getName().compareToIgnoreCase(o.getName());
    }

    @Override
    public String toString() {
        try {
            return getName() + '=' + getValue();
        } catch (IOException e) {
            return getName() + '=' + e;
        }
    }

    @Override
    protected boolean deleteOnExit() {
        return deleteOnExit;
    }

    @Override
    protected String getBaseDirectory() {
        return baseDir;
    }

    @Override
    protected String getDiskFilename() {
        return getName() + postfix;
    }

    @Override
    protected String getPostfix() {
        return postfix;
    }

    @Override
    protected String getPrefix() {
        return prefix;
    }

    @Override
    public Attribute copy() {
        checkAccessible();
        final Buffer content = content();
        return replace(content != null ? content.copy() : null);
    }

    @Override
    public Attribute replace(Buffer content) {
        checkAccessible();
        DiskAttribute attr = new DiskAttribute(getName(), baseDir, deleteOnExit);
        attr.setCharset(getCharset());
        if (content != null) {
            try {
                attr.setContent(content);
            } catch (IOException e) {
                throw new ChannelException(e);
            }
        }
        attr.setCompleted(isCompleted());
        return attr;
    }

    @Override
    protected Owned<AbstractHttpData> prepareSend() {
        return drop -> {
            DiskAttribute attr = new DiskAttribute(this);
            return attr;
        };
    }
}

