package io.netty.contrib.handler.codec.http.multipart;

/**
 * Parsed representation of the {@code Content-Disposition} header, giving access to the file name.
 */
public interface ContentDisposition extends ParsedHeaderValue {
    /**
     * The field name specified in this header.
     *
     * @return The name, or {@code null} if not given
     */
    String name();

    /**
     * The file name specified in this header.
     *
     * @return The file name, or {@code null} if not given
     */
    String fileName();
}
