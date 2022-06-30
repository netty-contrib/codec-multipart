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
import io.netty5.buffer.api.internal.ResourceSupport;
import io.netty5.channel.ChannelException;
import io.netty5.handler.codec.http.HttpConstants;
import io.netty5.util.internal.ObjectUtil;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.regex.Pattern;

import static io.netty5.util.internal.ObjectUtil.checkNonEmpty;

/**
 * Abstract HttpData implementation
 */
public abstract class AbstractHttpData extends ResourceSupport<HttpData, AbstractHttpData> implements HttpData {

    private static final Pattern STRIP_PATTERN = Pattern.compile("(?:^\\s+|\\s+$|\\n)");
    private static final Pattern REPLACE_PATTERN = Pattern.compile("[\\r\\t]");
    protected final static byte[] EMPTY_ARRAY = new byte[0];

    private final String name;
    protected long definedSize;
    protected long size;
    private Charset charset = HttpConstants.DEFAULT_CHARSET;
    private boolean completed;
    private long maxSize = DefaultHttpDataFactory.MAXSIZE;

    private final static Drop<AbstractHttpData> drop = new Drop<AbstractHttpData>() {
        @Override
        public void drop(AbstractHttpData data) {
            data.delete();
        }

        @Override
        public Drop<AbstractHttpData> fork() {
            return this;
        }

        @Override
        public void attach(AbstractHttpData mixedFileUpload) {
        }
    };

    protected AbstractHttpData(String name, Charset charset, long size) {
        super(drop);
        ObjectUtil.checkNotNullWithIAE(name, "name");

        name = REPLACE_PATTERN.matcher(name).replaceAll(" ");
        name = STRIP_PATTERN.matcher(name).replaceAll("");

        this.name = checkNonEmpty(name, "name");
        if (charset != null) {
            setCharset(charset);
        }
        definedSize = size;
    }

    protected AbstractHttpData(AbstractHttpData copy) {
        super(drop);
        this.name = copy.name;
        this.charset = copy.charset;
        this.definedSize = copy.definedSize;
        this.size = copy.size;
        this.completed = copy.completed;
        this.maxSize = copy.maxSize;
    }

    @Override
    public long getMaxSize() {
        return maxSize;
    }

    @Override
    public void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
    }

    @Override
    public void checkSize(long newSize) throws IOException {
        if (maxSize >= 0 && newSize > maxSize) {
            throw new IOException("Size exceed allowed maximum capacity");
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isCompleted() {
        return completed;
    }

    protected void setCompleted() {
        completed = true;
    }

    @Override
    public Charset getCharset() {
        return charset;
    }

    @Override
    public void setCharset(Charset charset) {
        this.charset = ObjectUtil.checkNotNullWithIAE(charset, "charset");
    }

    @Override
    public long length() {
        return size;
    }

    @Override
    public long definedLength() {
        return definedSize;
    }

    @Override
    public Buffer content() {
        checkAccessible();
        try {
            return getBuffer();
        } catch (IOException e) {
            throw new ChannelException(e);
        }
    }

    @Override
    protected RuntimeException createResourceClosedException() {
        return new IllegalStateException("Resource closed");
    }

    protected void checkAccessible() {
        if (! isAccessible()) {
            throw new IllegalStateException(getClass().getName()
                    + " is innaccessible");
        }
    }

    protected void checkAccessible(Buffer cleanup) {
        if (! isAccessible()) {
            if (cleanup != null && cleanup.isAccessible()) {
                cleanup.close();
            }
            throw new IllegalStateException(getClass().getName()
                    + " is innaccessible");
        }
    }

}
