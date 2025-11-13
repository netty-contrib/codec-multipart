package io.netty.contrib.multipart;

import io.netty5.buffer.Buffer;
import io.netty5.util.Send;

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

        Send<Buffer> sendUndecodedPartContent();

        boolean isMixed();

        int getCurrentAllocatedCapacity();

        boolean isQuirkMode();

        void setQuirkMode(boolean quirkMode);

        int getCompactionThreshold();

        void setCompactionThreshold(int compactionThreshold);

        void setQuirkPartCharset(Charset quirkPartCharset);

        void setQuirkDefinedLength(long quirkDefinedLength);

        Charset getCharset();
    }

    public interface UrlEncodedDecoder extends PostBodyDecoder {
        boolean isQuirkMode();

        void setQuirkMode(boolean quirkMode);

        int getCompactionThreshold();

        void setCompactionThreshold(int compactionThreshold);

        boolean isEof();
    }
}
