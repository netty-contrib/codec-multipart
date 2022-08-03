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

import io.netty5.util.internal.ObjectUtil;
import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.DefaultBufferAllocators;
import io.netty5.buffer.api.Drop;
import io.netty5.buffer.api.Owned;
import io.netty5.buffer.api.internal.ResourceSupport;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This Attribute is only for Encoder and is used to insert special command between object if needed
 * (like Multipart Mixed mode)
 */
final class InternalAttribute extends ResourceSupport<HttpData, InternalAttribute> implements InterfaceHttpData {
    private List<Buffer> value;
    private final Charset charset;
    private int size;

    private final static Drop<InternalAttribute> drop = new Drop<>() {
        @Override
        public void drop(InternalAttribute data) {
        }

        @Override
        public Drop<InternalAttribute> fork() {
            return this;
        }

        @Override
        public void attach(InternalAttribute mixedFileUpload) {
        }
    };

    InternalAttribute(Charset charset) {
        super(drop);
        this.charset = charset;
        this.value = new ArrayList<>();
    }

    @Override
    public HttpDataType getHttpDataType() {
        return HttpDataType.InternalAttribute;
    }

    public void addValue(String value) {
        ObjectUtil.checkNotNullWithIAE(value, "value");
        Buffer buf = Helpers.copiedBuffer(value, charset);
        this.value.add(buf);
        size += buf.readableBytes();
    }

    public void addValue(String value, int rank) {
        ObjectUtil.checkNotNullWithIAE(value, "value");
        Buffer buf = Helpers.copiedBuffer(value, charset);
        this.value.add(rank, buf);
        size += buf.readableBytes();
    }

    public void setValue(String value, int rank) {
        ObjectUtil.checkNotNullWithIAE(value, "value");
        Buffer buf = Helpers.copiedBuffer(value, charset);
        Buffer old = this.value.set(rank, buf);
        if (old != null) {
            size -= old.readableBytes();
            old.close();
        }
        size += buf.readableBytes();
    }

    @Override
    public int hashCode() {
        return getName().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof InternalAttribute)) {
            return false;
        }
        InternalAttribute attribute = (InternalAttribute) o;
        return getName().equalsIgnoreCase(attribute.getName());
    }

    @Override
    public int compareTo(InterfaceHttpData o) {
        if (!(o instanceof InternalAttribute)) {
            throw new ClassCastException("Cannot compare " + getHttpDataType() +
                    " with " + o.getHttpDataType());
        }
        return compareTo((InternalAttribute) o);
    }

    public int compareTo(InternalAttribute o) {
        return getName().compareToIgnoreCase(o.getName());
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        for (Buffer elt : value) {
            result.append(elt.toString(charset));
        }
        return result.toString();
    }

    public int size() {
        return size;
    }

    /**
     * Returns a buffer composed of all values added in this class.
     * <br> The returned buffer must be closed by the caller.</br>
     *
     * @return a fresh composite buffer containing all values added in this class.
     * The buffer must be closed by the user.
     */
    public Buffer toBuffer() {
        return DefaultBufferAllocators.onHeapAllocator()
                .compose(value.stream().map(Buffer::send).collect(Collectors.toList()));
    }

    @Override
    public String getName() {
        return "InternalAttribute";
    }

    @Override
    protected RuntimeException createResourceClosedException() {
        return new RuntimeException("Resource closed");
    }

    @Override
    protected Owned<InternalAttribute> prepareSend() {
        return drop -> {
            InternalAttribute copy = new InternalAttribute(charset);
            copy.value = this.value;
            copy.size = this.size;
            this.value = Collections.emptyList(); // immutable list
            this.size = 0;
            return copy;
        };
    }
}

