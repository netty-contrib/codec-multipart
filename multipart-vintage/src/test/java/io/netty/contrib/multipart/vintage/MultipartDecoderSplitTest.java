package io.netty.contrib.multipart.vintage;

import io.netty.contrib.multipart.PostBodyDecoder;

public class MultipartDecoderSplitTest extends AbstractDecoderSplitTest {
    @Override
    protected PostBodyDecoder createDecoder() {
        return PostBodyDecoder.builder().forMultipartBoundary(BOUNDARY);
    }
}
