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
import io.netty5.buffer.Owned;
import io.netty5.util.Send;
import io.netty5.channel.ChannelException;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpHeaderValues;
import io.netty5.util.internal.ObjectUtil;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Default FileUpload implementation that stores file into memory.<br><br>
 *
 * Warning: be aware of the memory limitation.
 */
public class MemoryFileUpload extends AbstractMemoryHttpData implements FileUpload {

    private String filename;

    private String contentType;

    private String contentTransferEncoding;

    public MemoryFileUpload(String name, String filename, String contentType,
            String contentTransferEncoding, Charset charset, long size) {
        super(name, charset, size);
        setFilename(filename);
        setContentType(contentType);
        setContentTransferEncoding(contentTransferEncoding);
    }

    @Override
    public HttpDataType getHttpDataType() {
        return HttpDataType.FileUpload;
    }

    @Override
    public String getFilename() {
        return filename;
    }

    @Override
    public void setFilename(String filename) {
        this.filename = ObjectUtil.checkNotNullWithIAE(filename, "filename");
    }

    @Override
    public int hashCode() {
        return FileUploadUtil.hashCode(this);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FileUpload && FileUploadUtil.equals(this, (FileUpload) o);
    }

    @Override
    public int compareTo(InterfaceHttpData o) {
        if (!(o instanceof FileUpload)) {
            throw new ClassCastException("Cannot compare " + getHttpDataType() +
                    " with " + o.getHttpDataType());
        }
        return compareTo((FileUpload) o);
    }

    public int compareTo(FileUpload o) {
        return FileUploadUtil.compareTo(this, o);
    }

    @Override
    public void setContentType(String contentType) {
        this.contentType = ObjectUtil.checkNotNullWithIAE(contentType, "contentType");
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public String getContentTransferEncoding() {
        return contentTransferEncoding;
    }

    @Override
    public void setContentTransferEncoding(String contentTransferEncoding) {
        this.contentTransferEncoding = contentTransferEncoding;
    }

    @Override
    public String toString() {
        return HttpHeaderNames.CONTENT_DISPOSITION + ": " +
               HttpHeaderValues.FORM_DATA + "; " + HttpHeaderValues.NAME + "=\"" + getName() +
            "\"; " + HttpHeaderValues.FILENAME + "=\"" + filename + "\"\r\n" +
            HttpHeaderNames.CONTENT_TYPE + ": " + contentType +
            (getCharset() != null? "; " + HttpHeaderValues.CHARSET + '=' + getCharset().name() + "\r\n" : "\r\n") +
            HttpHeaderNames.CONTENT_LENGTH + ": " + length() + "\r\n" +
            "Completed: " + isCompleted() +
            "\r\nIsInMemory: " + isInMemory();
    }

    @Override
    public FileUpload copy() {
        return replace(byteBuf != null ? byteBuf.copy() : byteBuf);
    }

    @Override
    public FileUpload replace(Buffer content) {
        checkAccessible(content);
        MemoryFileUpload upload = new MemoryFileUpload(
                getName(), getFilename(), getContentType(), getContentTransferEncoding(), getCharset(), size);
        if (content != null) {
            try {
                upload.setContent(content);
            } catch (IOException e) {
                throw new ChannelException(e);
            }
        }
        upload.setCompleted(isCompleted());
        return upload;
    }

    @Override
    protected Owned<AbstractHttpData> prepareSend() {
        Send<Buffer> send = byteBuf.send();

        return drop -> {
            Buffer received = send.receive();
            MemoryFileUpload upload = new MemoryFileUpload(
                    getName(), getFilename(), getContentType(), getContentTransferEncoding(), getCharset(), size);
            upload.setContentInternal(received, received.readableBytes());
            upload.setCompleted(isCompleted());
            upload.definedSize = definedLength();
            upload.setMaxSize(getMaxSize());
            return upload;
        };
    }

}
