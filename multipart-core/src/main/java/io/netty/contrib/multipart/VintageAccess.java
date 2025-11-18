package io.netty.contrib.multipart;


import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;

public class VintageAccess {
    private VintageAccess() {
    }

    public static VintageAccess.MultipartDecoder forBoundaryWithPrefix(PostBodyDecoder.Builder builder, String boundary) {
        return builder.forBoundary0(boundary);
    }

    public static int maxFields(PostBodyDecoder.Builder builder) {
        return builder.maxFields;
    }

    public static String cleanString(String s) {
        return io.netty.contrib.multipart.MultipartDecoder.cleanString(s);
    }

    public interface MultipartDecoder extends PostBodyDecoder {
        String[] getQuirkHeader();

        boolean isMixed();

        int getCurrentAllocatedCapacity();

        int getCompactionThreshold();

        void setCompactionThreshold(int compactionThreshold);

        void setQuirkPartCharset(Charset quirkPartCharset);

        void setQuirkDefinedLength(long quirkDefinedLength);

        Charset getCharset();

        /**
         * Whether a specific quirk is enabled.
         */
        boolean hasQuirk(DecoderQuirk quirk);
    }

    public interface UrlEncodedDecoder extends PostBodyDecoder {
        ByteBuf undecodedContent();

        void decodeComponent(ByteBuf buffer, boolean key);

        int getCompactionThreshold();

        void setCompactionThreshold(int compactionThreshold);

        boolean isEof();
    }
}
