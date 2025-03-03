package io.netty.contrib.handler.codec.http.multipart;

import io.netty5.buffer.Buffer;
import io.netty5.buffer.CompositeBuffer;
import io.netty5.util.Send;

import java.nio.charset.Charset;

abstract class AbstractDecoder implements PostBodyDecoder {
    final int undecodedLimit;
    final Charset charset;
    int compactionThreshold;

    Buffer buffer;
    boolean eof;

    AbstractDecoder(Builder builder) {
        this.undecodedLimit = builder.undecodedLimit;
        this.charset = builder.charset;
        this.compactionThreshold = builder.compactionThreshold;
    }

    @Override
    public void add(Send<Buffer> buffer) {
        if (eof) {
            throw new IllegalStateException("endInput() already called");
        }
        if (this.buffer != null && this.buffer.readableBytes() <= 0) {
            this.buffer.close();
            this.buffer = null;
        }
        if (this.buffer == null) {
            this.buffer = buffer.receive();
        } else {
            if (compactionThreshold >= 0 && this.buffer.writerOffset() >= compactionThreshold) {
                this.buffer.compact();
            }
            if (this.buffer.readableBytes() > undecodedLimit) {
                buffer.close();
                throw new HttpPostRequestDecoder.ErrorDataDecoderException("Undecoded data limit exceeded");
            }

            if (this.buffer instanceof CompositeBuffer) {
                ((CompositeBuffer) this.buffer).extendWith(buffer);
            } else {
                try (Buffer b = buffer.receive()) {
                    this.buffer.writeBytes(b);
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
        try (Buffer b = decodedContent().receive()) {
            return b.toString(charset);
        }
    }

    @Override
    public void close() {
        if (buffer != null) {
            buffer.close();
        }
    }
}
