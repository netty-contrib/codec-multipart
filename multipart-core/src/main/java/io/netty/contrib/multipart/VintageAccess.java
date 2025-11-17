package io.netty.contrib.multipart;

import java.nio.charset.Charset;

public class VintageAccess {
    private VintageAccess() {
    }

    public static VintageAccess.MultipartDecoder forBoundaryWithPrefix(PostBodyDecoder.Builder builder, String boundary) {
        return builder.forBoundary0(boundary);
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
        void setQuirkMode(boolean quirkMode);

        int getCompactionThreshold();

        void setCompactionThreshold(int compactionThreshold);

        boolean isEof();
    }
}
