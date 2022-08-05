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

import io.netty5.buffer.api.Owned;
import io.netty5.util.Send;

import java.nio.charset.Charset;

/**
 * Mixed implementation using both in Memory and in File with a limit of size
 */
public class MixedFileUpload extends AbstractMixedHttpData<FileUpload> implements FileUpload {

    public MixedFileUpload(String name, String filename, String contentType,
            String contentTransferEncoding, Charset charset, long size,
            long limitSize) {
        this(name, filename, contentType, contentTransferEncoding,
                charset, size, limitSize, DiskFileUpload.baseDirectory, DiskFileUpload.deleteOnExitTemporaryFile);
    }

    public MixedFileUpload(String name, String filename, String contentType,
            String contentTransferEncoding, Charset charset, long size,
            long limitSize, String baseDir, boolean deleteOnExit) {
        super(limitSize, baseDir, deleteOnExit,
                size > limitSize ?
                        new DiskFileUpload(name, filename, contentType, contentTransferEncoding, charset, size) :
                        new MemoryFileUpload(name, filename, contentType, contentTransferEncoding, charset, size)
        );
    }

    private MixedFileUpload(long limitSize, String baseDir, boolean deleteOnExit, FileUpload fileUpload) {
        super(limitSize, baseDir, deleteOnExit, fileUpload);
    }

    @Override
    public String getContentType() {
        return wrapped.getContentType();
    }

    @Override
    public String getContentTransferEncoding() {
        return wrapped.getContentTransferEncoding();
    }

    @Override
    public String getFilename() {
        return wrapped.getFilename();
    }

    @Override
    public void setContentType(String contentType) {
        wrapped.setContentType(contentType);
    }

    @Override
    public void setContentTransferEncoding(String contentTransferEncoding) {
        wrapped.setContentTransferEncoding(contentTransferEncoding);
    }

    @Override
    public void setFilename(String filename) {
        wrapped.setFilename(filename);
    }

    @Override
    FileUpload makeDiskData() {
        DiskFileUpload diskFileUpload = new DiskFileUpload(
                getName(), getFilename(), getContentType(), getContentTransferEncoding(), getCharset(), definedLength(),
                baseDir, deleteOnExit);
        diskFileUpload.setMaxSize(getMaxSize());
        return diskFileUpload;
    }

    @Override
    protected Owned<AbstractMixedHttpData<?>> prepareSend() {
        Send<HttpData> send = wrapped.send();
        return drop -> {
            FileUpload received = (FileUpload) send.receive();
            return new MixedFileUpload(limitSize, baseDir, deleteOnExit, received);
        };
    }
}
