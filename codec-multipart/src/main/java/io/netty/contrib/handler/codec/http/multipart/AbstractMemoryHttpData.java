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

import io.netty5.buffer.Buffer;
import io.netty5.buffer.CompositeBuffer;
import io.netty5.buffer.DefaultBufferAllocators;
import io.netty5.buffer.internal.InternalBufferUtils;
import io.netty5.handler.codec.http.HttpConstants;
import io.netty5.util.internal.ObjectUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Abstract Memory HttpData implementation
 */
public abstract class AbstractMemoryHttpData extends AbstractHttpData {

    private Buffer byteBuf;

    protected AbstractMemoryHttpData(String name, Charset charset, long size) {
        super(name, charset, size);
        byteBuf = DefaultBufferAllocators.preferredAllocator().allocate(0);
    }

    @Override
    public void setContent(Buffer buffer) throws IOException {
        checkAccessible(buffer);
        ObjectUtil.checkNotNullWithIAE(buffer, "buffer");
        long localsize = buffer.readableBytes();
        try {
            checkSize(localsize);
        } catch (IOException e) {
            buffer.close();
            throw e;
        }
        if (definedSize > 0 && definedSize < localsize) {
            buffer.close();
            throw new IOException("Out of size: " + localsize + " > " +
                    definedSize);
        }
        if (byteBuf != null) {
            byteBuf.close();
        }
        setContentInternal(buffer, localsize);
    }

    protected final void setContentInternal(Buffer buffer, long size) {
        this.byteBuf = buffer;
        this.size = size;
        setCompleted();
    }

    @Override
    public void setContent(InputStream inputStream) throws IOException {
        checkAccessible();

        ObjectUtil.checkNotNullWithIAE(inputStream, "inputStream");
        byte[] bytes = new byte[4096 * 4];
        Buffer buffer = DefaultBufferAllocators.preferredAllocator().allocate(0);
        int written = 0;
        try {
            int read;
            while ((read = inputStream.read(bytes)) > 0) {
                buffer.writeBytes(bytes, 0, read);
                written += read;
                checkSize(written);
            }
        } catch (IOException e) {
            buffer.close();
            throw e;
        }
        size = written;
        if (definedSize > 0 && definedSize < size) {
            buffer.close();
            throw new IOException("Out of size: " + size + " > " + definedSize);
        }
        if (byteBuf != null) {
            byteBuf.close();
        }
        byteBuf = buffer;
        setCompleted();
    }

    @Override
    public void addContent(Buffer buffer, boolean last)
            throws IOException {
        checkAccessible(buffer);
        if (buffer != null) {
            long localsize = buffer.readableBytes();
            try {
                checkSize(size + localsize);
            } catch (IOException e) {
                buffer.close();
                throw e;
            }
            if (definedSize > 0 && definedSize < size + localsize) {
                buffer.close();
                throw new IOException("Out of size: " + (size + localsize) +
                        " > " + definedSize);
            }
            size += localsize;
            if (byteBuf == null) {
                byteBuf = buffer;
            } else if (localsize == 0) {
                // Nothing to add and byteBuf already exists
                buffer.close();
            } else if (byteBuf.readableBytes() == 0) {
                // Previous buffer is empty, so just replace it
                byteBuf.close();
                byteBuf = buffer;
            } else if (CompositeBuffer.isComposite(this.byteBuf)) {
                CompositeBuffer cbb = (CompositeBuffer) this.byteBuf;
                cbb.extendWith(buffer.send());
            } else {
                byteBuf = DefaultBufferAllocators.onHeapAllocator().compose(Arrays.asList(this.byteBuf.send(), buffer.send()));
            }
        }
        if (last) {
            setCompleted();
        } else {
            ObjectUtil.checkNotNullWithIAE(buffer, "buffer");
        }
    }

    @Override
    public void setContent(File file) throws IOException {
        checkAccessible();
        ObjectUtil.checkNotNullWithIAE(file, "file");

        long newsize = file.length();
        if (newsize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("File too big to be loaded in memory");
        }
        checkSize(newsize);
        Buffer buf = DefaultBufferAllocators.onHeapAllocator().allocate((int) newsize);
        try (RandomAccessFile accessFile = new RandomAccessFile(file, "r");
             FileChannel fileChannel = accessFile.getChannel()) {
            int bytesRead = 0;
            int remaining = (int) newsize;
            do {
                buf.ensureWritable(remaining);
                int bytes = buf.transferFrom(fileChannel, remaining);
                if (bytes == -1) {
                    break;
                }
                bytesRead += bytes;
                remaining -= bytes;
            } while (bytesRead < newsize);
        }

        if (byteBuf != null) {
            byteBuf.close();
        }
        byteBuf = buf;
        size = newsize;
        setCompleted();
    }

    @Override
    public void delete() {
        if (byteBuf != null) {
            if (byteBuf.isAccessible()) {
                byteBuf.close();
            }
            byteBuf = null;
        }
    }

    @Override
    public byte[] get() {
        if (byteBuf == null) {
            return EMPTY_ARRAY;
        }
        byte[] array = new byte[byteBuf.readableBytes()];
        byteBuf.copyInto(byteBuf.readerOffset(), array, 0, byteBuf.readableBytes());
        return array;
    }

    @Override
    public String getString() {
        return getString(HttpConstants.DEFAULT_CHARSET);
    }

    @Override
    public String getString(Charset encoding) {
        if (byteBuf == null) {
            return "";
        }
        if (encoding == null) {
            encoding = HttpConstants.DEFAULT_CHARSET;
        }
        return byteBuf.toString(encoding);
    }

    /**
     * Utility to go from a In Memory FileUpload
     * to a Disk (or another implementation) FileUpload
     * @return the attached ByteBuf containing the actual bytes
     */
    @Override
    public Buffer getBuffer() {
        return byteBuf;
    }

    @Override
    public Buffer getChunk(int length) {
        int readableBytes = byteBuf.readableBytes();
        if (byteBuf == null || length == 0 || readableBytes == 0) {
            return DefaultBufferAllocators.preferredAllocator().allocate(0);
        }
        return byteBuf.readSplit(Math.min(readableBytes, length));
    }

    @Override
    public boolean isInMemory() {
        return true;
    }

    @Override
    public boolean renameTo(File dest) throws IOException {
        ObjectUtil.checkNotNullWithIAE(dest, "dest");
        if (byteBuf == null) {
            // empty file
            if (!dest.createNewFile()) {
                throw new IOException("file exists already: " + dest);
            }
            return true;
        }
        int length = byteBuf.readableBytes();
        try(RandomAccessFile accessFile = new RandomAccessFile(dest, "rw");
            FileChannel fileChannel = accessFile.getChannel()) {
            int written;

            do {
                if ((written = byteBuf.transferTo(fileChannel, length)) == -1) {
                    break;
                }
                length -= written;
            } while (length > 0);
            fileChannel.force(false);
        }
        return length == 0;
    }

    @Override
    public File getFile() throws IOException {
        throw new IOException("Not represented by a file");
    }

    @Override
    protected RuntimeException createResourceClosedException() {
        return InternalBufferUtils.bufferIsClosed(getBuffer());
    }

}
