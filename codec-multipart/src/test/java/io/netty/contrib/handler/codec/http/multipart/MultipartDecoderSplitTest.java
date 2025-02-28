package io.netty.contrib.handler.codec.http.multipart;

public class MultipartDecoderSplitTest extends AbstractDecoderSplitTest {
    @Override
    protected PostBodyDecoder createDecoder() {
        return PostBodyDecoder.builder().forMultipartBoundary(BOUNDARY);
    }
}
