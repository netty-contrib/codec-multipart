package io.netty.contrib.multipart;

import io.netty.handler.codec.DecoderException;

public class FormDecoderException extends DecoderException {
    public FormDecoderException() {
    }

    public FormDecoderException(String message, Throwable cause) {
        super(message, cause);
    }

    public FormDecoderException(String message) {
        super(message);
    }

    public FormDecoderException(Throwable cause) {
        super(cause);
    }
}
