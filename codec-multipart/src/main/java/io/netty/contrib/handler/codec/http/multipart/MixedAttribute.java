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

import io.netty5.buffer.Owned;
import io.netty5.handler.codec.http.HttpConstants;
import io.netty5.util.Send;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Mixed implementation using both in Memory and in File with a limit of size
 */
public class MixedAttribute extends AbstractMixedHttpData<Attribute> implements Attribute {
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
        this(name, 0, limitSize, charset, baseDir, deleteOnExit);
    }

    public MixedAttribute(String name, long definedSize, long limitSize, Charset charset) {
        this(name, definedSize, limitSize, charset,
                DiskAttribute.baseDirectory, DiskAttribute.deleteOnExitTemporaryFile);
    }

    public MixedAttribute(String name, long definedSize, long limitSize, Charset charset,
                          String baseDir, boolean deleteOnExit) {
        super(limitSize, baseDir, deleteOnExit,
                new MemoryAttribute(name, definedSize, charset));
    }

    public MixedAttribute(String name, String value, long limitSize) {
        this(name, value, limitSize, HttpConstants.DEFAULT_CHARSET,
                DiskAttribute.baseDirectory, DiskFileUpload.deleteOnExitTemporaryFile);
    }

    public MixedAttribute(String name, String value, long limitSize, Charset charset) {
        this(name, value, limitSize, charset,
                DiskAttribute.baseDirectory, DiskFileUpload.deleteOnExitTemporaryFile);
    }

    private static Attribute makeInitialAttributeFromValue(String name, String value, long limitSize, Charset charset,
                                                           String baseDir, boolean deleteOnExit) {
        if (value.length() > limitSize) {
            try {
                return new DiskAttribute(name, value, charset, baseDir, deleteOnExit);
            } catch (IOException e) {
                // revert to Memory mode
                try {
                    return new MemoryAttribute(name, value, charset);
                } catch (IOException ignore) {
                    throw new IllegalArgumentException(e);
                }
            }
        } else {
            try {
                return new MemoryAttribute(name, value, charset);
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }

    public MixedAttribute(String name, String value, long limitSize, Charset charset,
                          String baseDir, boolean deleteOnExit) {
        super(limitSize, baseDir, deleteOnExit,
                makeInitialAttributeFromValue(name, value, limitSize, charset, baseDir, deleteOnExit));
    }

    public MixedAttribute(String baseDir, boolean deleteOnExit, long limitSize, Attribute attribute) {
        super(limitSize, baseDir, deleteOnExit, attribute);
    }

    @Override
    public String getValue() throws IOException {
        return wrapped.getValue();
    }

    @Override
    public void setValue(String value) throws IOException {
        wrapped.setValue(value);
    }

    @Override
    Attribute makeDiskData() {
        DiskAttribute diskAttribute = new DiskAttribute(getName(), definedLength(), baseDir, deleteOnExit);
        diskAttribute.setMaxSize(getMaxSize());
        return diskAttribute;
    }

    @Override
    protected Owned<AbstractMixedHttpData<?>> prepareSend() {
        Send<HttpData> send = wrapped.send();
        return drop -> {
            Attribute receivedAttr = (Attribute) send.receive();
            MixedAttribute copy = new MixedAttribute(baseDir, deleteOnExit, limitSize, receivedAttr);
            return copy;
        };
    }
}
