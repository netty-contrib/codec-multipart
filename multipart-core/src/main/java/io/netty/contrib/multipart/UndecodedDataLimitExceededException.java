package io.netty.contrib.multipart;

/**
 * Exception when the maximum data buffer size is reached, e.g. when a form field name is too long for the buffer
 */
public class UndecodedDataLimitExceededException extends FormDecoderException {
    public UndecodedDataLimitExceededException() {
        super("Undecoded data limit exceeded");
    }
}
