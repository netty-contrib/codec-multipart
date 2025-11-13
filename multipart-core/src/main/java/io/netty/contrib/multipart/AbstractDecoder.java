package io.netty.contrib.multipart;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;

import java.nio.charset.Charset;

abstract class AbstractDecoder implements PostBodyDecoder {
    final int undecodedLimit;
    final Charset charset;
    int compactionThreshold;
    private int remainingFieldLimit;

    ByteBuf buffer;
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
        if (this.buffer != null && this.buffer.readableBytes() <= 0) {
            this.buffer.release();
            this.buffer = null;
        }
        if (this.buffer == null) {
            this.buffer = buffer;
        } else {
            if ((compactionThreshold >= 0 && this.buffer.writerIndex() >= compactionThreshold) ||
                    this.buffer.writerIndex() + buffer.readableBytes() > buffer.maxCapacity()) {
                this.buffer.discardSomeReadBytes();
            }
            if (this.buffer.readableBytes() > undecodedLimit) {
                buffer.release();
                throw new FormDecoderException("Undecoded data limit exceeded");
            }

            if (this.buffer instanceof CompositeByteBuf) {
                ((CompositeByteBuf) this.buffer).addComponent(true, buffer);
            } else {
                try {
                    if (this.buffer.writerIndex() + buffer.readableBytes() > buffer.maxCapacity()) {
                        ByteBuf newBuffer = this.buffer.alloc().buffer();
                        newBuffer.writeBytes(this.buffer);
                        this.buffer.release();
                        this.buffer = newBuffer;
                    }
                    this.buffer.writeBytes(buffer);
                } finally {
                    buffer.release();
                }
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
