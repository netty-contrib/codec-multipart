package io.netty.contrib.multipart.vintage;

import io.netty.contrib.multipart.PostBodyDecoder;

public class MultipartSequenceTest extends AbstractSequenceTest {
    @Override
    protected PostBodyDecoder createDecoder() {
        return PostBodyDecoder.builder().forMultipartBoundary(BOUNDARY);
    }
}
