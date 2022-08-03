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
import io.netty5.util.Send;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Mixed implementation using both in Memory and in File with a limit of size
 */
public class MixedFileUpload extends ResourceSupport<HttpData, MixedFileUpload> implements FileUpload {

    private final String baseDir;

    private final boolean deleteOnExit;

    private FileUpload fileUpload;

    private final long limitSize;

    private final long definedSize;
    private long maxSize = DefaultHttpDataFactory.MAXSIZE;

    private final static Drop<MixedFileUpload> drop = new Drop<MixedFileUpload>() {
        @Override
        public void drop(MixedFileUpload data) {
            data.delete();
        }

        @Override
        public Drop<MixedFileUpload> fork() {
            return this;
        }

        @Override
        public void attach(MixedFileUpload mixedFileUpload) {
        }
    };

    private MixedFileUpload(String baseDir, boolean deleteOnExit, FileUpload fileUpload, long limitSize, long definedSize, long maxSize) {
        super (drop);
        this.baseDir = baseDir;
        this.deleteOnExit = deleteOnExit;
        this.fileUpload = fileUpload;
        this.limitSize = limitSize;
        this.definedSize = definedSize;
        this.maxSize = maxSize;
    }

    public MixedFileUpload(String name, String filename, String contentType,
            String contentTransferEncoding, Charset charset, long size,
            long limitSize) {
        this(name, filename, contentType, contentTransferEncoding,
                charset, size, limitSize, DiskFileUpload.baseDirectory, DiskFileUpload.deleteOnExitTemporaryFile);
    }

    public MixedFileUpload(String name, String filename, String contentType,
            String contentTransferEncoding, Charset charset, long size,
            long limitSize, String baseDir, boolean deleteOnExit) {
        super (drop);
        this.limitSize = limitSize;
        if (size > this.limitSize) {
            fileUpload = new DiskFileUpload(name, filename, contentType,
                    contentTransferEncoding, charset, size);
        } else {
            fileUpload = new MemoryFileUpload(name, filename, contentType,
                    contentTransferEncoding, charset, size);
        }
        definedSize = size;
        this.baseDir = baseDir;
        this.deleteOnExit = deleteOnExit;
    }

    @Override
    public long getMaxSize() {
        return maxSize;
    }

    @Override
    public void setMaxSize(long maxSize) {
        this.maxSize = maxSize;
        fileUpload.setMaxSize(maxSize);
    }

    @Override
    public void checkSize(long newSize) throws IOException {
        if (maxSize >= 0 && newSize > maxSize) {
            throw new IOException("Size exceed allowed maximum capacity");
        }
    }

    @Override
    public void addContent(Buffer buffer, boolean last)
            throws IOException {
        checkAccessible(buffer);
        if (fileUpload instanceof MemoryFileUpload) {
            try {
                checkSize(fileUpload.length() + buffer.readableBytes());
                if (fileUpload.length() + buffer.readableBytes() > limitSize) {
                    DiskFileUpload diskFileUpload = new DiskFileUpload(fileUpload
                            .getName(), fileUpload.getFilename(), fileUpload
                            .getContentType(), fileUpload
                            .getContentTransferEncoding(), fileUpload.getCharset(),
                            definedSize, baseDir, deleteOnExit);
                    diskFileUpload.setMaxSize(maxSize);
                    Buffer data = fileUpload.getBuffer();
                    if (data != null && data.readableBytes() > 0) {
                        //diskFileUpload.addContent(data.retain(), false);
                        diskFileUpload.addContent(data, false); // TODO should data be "sent" ?
                    }
                    // release old upload
                    fileUpload.close();

                    fileUpload = diskFileUpload;
                }
            } catch (IOException e) {
                buffer.close();
                throw e;
            } catch (Exception e) {
                buffer.close();
                throw new IOException(e);
            }
        }
        fileUpload.addContent(buffer, last);
    }

    @Override
    public void delete() {
        fileUpload.delete();
    }

    @Override
    public byte[] get() throws IOException {
        return fileUpload.get();
    }

    @Override
    public Buffer getBuffer() throws IOException {
        return fileUpload.getBuffer();
    }

    @Override
    public Charset getCharset() {
        return fileUpload.getCharset();
    }

    @Override
    public String getContentType() {
        return fileUpload.getContentType();
    }

    @Override
    public String getContentTransferEncoding() {
        return fileUpload.getContentTransferEncoding();
    }

    @Override
    public String getFilename() {
        return fileUpload.getFilename();
    }

    @Override
    public String getString() throws IOException {
        return fileUpload.getString();
    }

    @Override
    public String getString(Charset encoding) throws IOException {
        return fileUpload.getString(encoding);
    }

    @Override
    public boolean isCompleted() {
        return fileUpload.isCompleted();
    }

    @Override
    public boolean isInMemory() {
        return fileUpload.isInMemory();
    }

    @Override
    public long length() {
        return fileUpload.length();
    }

    @Override
    public long definedLength() {
        return fileUpload.definedLength();
    }

    @Override
    public boolean renameTo(File dest) throws IOException {
        checkAccessible();
        return fileUpload.renameTo(dest);
    }

    @Override
    public void setCharset(Charset charset) {
        checkAccessible();
        fileUpload.setCharset(charset);
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
            if (fileUpload instanceof MemoryFileUpload) {
                FileUpload memoryUpload = fileUpload;
                // change to Disk
                fileUpload = new DiskFileUpload(memoryUpload
                        .getName(), memoryUpload.getFilename(), memoryUpload
                        .getContentType(), memoryUpload
                        .getContentTransferEncoding(), memoryUpload.getCharset(),
                        definedSize, baseDir, deleteOnExit);
                fileUpload.setMaxSize(maxSize);

                // release old upload
                try {
                    memoryUpload.close();
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
        }
        fileUpload.setContent(buffer);
    }

    @Override
    public void setContent(File file) throws IOException {
        checkAccessible();
        checkSize(file.length());
        if (file.length() > limitSize) {
            if (fileUpload instanceof MemoryFileUpload) {
                FileUpload memoryUpload = fileUpload;

                // change to Disk
                fileUpload = new DiskFileUpload(memoryUpload
                        .getName(), memoryUpload.getFilename(), memoryUpload
                        .getContentType(), memoryUpload
                        .getContentTransferEncoding(), memoryUpload.getCharset(),
                        definedSize, baseDir, deleteOnExit);
                fileUpload.setMaxSize(maxSize);

                // release old upload
                try {
                    memoryUpload.close();
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
        }
        fileUpload.setContent(file);
    }

    @Override
    public void setContent(InputStream inputStream) throws IOException {
        checkAccessible();
        if (fileUpload instanceof MemoryFileUpload) {
            FileUpload memoryUpload = fileUpload;

            // change to Disk
            fileUpload = new DiskFileUpload(fileUpload
                    .getName(), fileUpload.getFilename(), fileUpload
                    .getContentType(), fileUpload
                    .getContentTransferEncoding(), fileUpload.getCharset(),
                    definedSize, baseDir, deleteOnExit);
            fileUpload.setMaxSize(maxSize);

            // release old upload
            try {
                memoryUpload.close();
            } catch (Exception e) {
               throw new IOException(e);
            }
        }
        fileUpload.setContent(inputStream);
    }

    @Override
    public void setContentType(String contentType) {
        fileUpload.setContentType(contentType);
    }

    @Override
    public void setContentTransferEncoding(String contentTransferEncoding) {
        fileUpload.setContentTransferEncoding(contentTransferEncoding);
    }

    @Override
    public void setFilename(String filename) {
        fileUpload.setFilename(filename);
    }

    @Override
    public HttpDataType getHttpDataType() {
        return fileUpload.getHttpDataType();
    }

    @Override
    public String getName() {
        return fileUpload.getName();
    }

    @Override
    public int hashCode() {
        return fileUpload.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return fileUpload.equals(obj);
    }

    @Override
    public int compareTo(InterfaceHttpData o) {
        return fileUpload.compareTo(o);
    }

    @Override
    public String toString() {
        return "Mixed: " + fileUpload;
    }

    @Override
    public Buffer getChunk(int length) throws IOException {
        return fileUpload.getChunk(length);
    }

    @Override
    public File getFile() throws IOException {
        return fileUpload.getFile();
    }

    @Override
    public FileUpload copy() {
        return fileUpload.copy();
    }

    @Override
    public FileUpload replace(Buffer content) {
        return fileUpload.replace(content);
    }

    @Override
    public Buffer content() {
        return fileUpload.content();
    }

    @Override
    protected Owned<MixedFileUpload> prepareSend() {
        Send<HttpData> send = fileUpload.send();
        return drop -> {
            FileUpload received = (FileUpload) send.receive();
            MixedFileUpload copy = new MixedFileUpload(baseDir, deleteOnExit, received, limitSize, definedSize, maxSize);
            return copy;
        };
    }

    @Override
    protected RuntimeException createResourceClosedException() {
        return new IllegalStateException("Resource closed");
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
