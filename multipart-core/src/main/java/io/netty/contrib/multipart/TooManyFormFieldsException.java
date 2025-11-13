package io.netty.contrib.multipart;

/**
 * Exception when the maximum number of fields for a given form is reached
 */
public final class TooManyFormFieldsException extends FormDecoderException {
    public TooManyFormFieldsException() {
        super("Too many form fields");
    }
}
