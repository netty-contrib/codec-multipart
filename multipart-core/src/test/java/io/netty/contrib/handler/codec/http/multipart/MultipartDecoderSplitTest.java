package io.netty.contrib.handler.codec.http.multipart;

import io.netty.contrib.multipart.PostBodyDecoder;

public class MultipartDecoderSplitTest extends AbstractDecoderSplitTest {
    @Override
    protected PostBodyDecoder createDecoder() {
        return PostBodyDecoder.builder().forMultipartBoundary(BOUNDARY);
    }
}
