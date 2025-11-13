package io.netty.contrib.multipart;

public class NotEnoughDataDecoderException extends FormDecoderException {
    public NotEnoughDataDecoderException() {
        super("Not enough multipart chunks");
    }

    public NotEnoughDataDecoderException(Throwable cause) {
        super(cause);
    }
}
