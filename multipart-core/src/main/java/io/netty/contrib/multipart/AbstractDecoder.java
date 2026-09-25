/*
 * Copyright 2025 The Netty Project
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
package io.netty.contrib.multipart;

import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;

abstract class AbstractDecoder implements PostBodyDecoder {
    final int undecodedLimit;
    final Charset charset;
    int compactionThreshold;
    private int remainingFieldLimit;

    ByteBuf buffer;
    /**
     * Whether {@link #buffer} was allocated by this decoder, so that we may write to it. If {@code false}, it is a
     * buffer that was passed to {@link #add(ByteBuf)} and must not be modified.
     */
    private boolean bufferOwned;
    boolean eof;

    AbstractDecoder(Builder builder) {
        this.undecodedLimit = builder.undecodedLimit;
        this.charset = builder.charset;
        this.compactionThreshold = builder.compactionThreshold;
        this.remainingFieldLimit = builder.maxFields < 0 ? Integer.MAX_VALUE : builder.maxFields;
    }

    final void checkNewField() {
        if (--remainingFieldLimit < 0) {
            throw new TooManyFormFieldsException();
        }
    }

    @Override
    public void add(ByteBuf buffer) {
        if (eof) {
            throw new IllegalStateException("endInput() already called");
        }
        if (!buffer.isReadable()) {
            buffer.release();
            return;
        }
        if (this.buffer != null && this.buffer.readableBytes() <= 0) {
            this.buffer.release();
            this.buffer = null;
        }
        if (this.buffer == null) {
            this.buffer = buffer;
            bufferOwned = false;
        } else {
            if (this.buffer.readableBytes() > undecodedLimit) {
                buffer.release();
                throw new UndecodedDataLimitExceededException();
            }

            try {
                if (!bufferOwned || this.buffer.maxWritableBytes() < buffer.readableBytes()) {
                    // The held buffer may still be visible to the caller (e.g. the content of an HttpContent), may be
                    // read-only, or may be too small. Copy the remaining bytes into a buffer we own instead of
                    // appending to it.
                    ByteBuf newBuffer = this.buffer.alloc()
                            .buffer(this.buffer.readableBytes() + buffer.readableBytes());
                    newBuffer.writeBytes(this.buffer);
                    this.buffer.release();
                    this.buffer = newBuffer;
                    bufferOwned = true;
                } else if (compactionThreshold >= 0 && this.buffer.writerIndex() >= compactionThreshold) {
                    this.buffer.discardSomeReadBytes();
                }
                this.buffer.writeBytes(buffer);
            } finally {
                buffer.release();
            }
        }
    }

    @Override
    public void endInput() {
        eof = true;
    }

    @Override
    public String decodedContentString() {
        ByteBuf byteBuf = decodedContent();
        try {
            return byteBuf.toString(charset);
        } finally {
            byteBuf.release();
        }
    }

    @Override
    public void close() {
        if (buffer != null) {
            buffer.release();
            buffer = null;
        }
    }
}
