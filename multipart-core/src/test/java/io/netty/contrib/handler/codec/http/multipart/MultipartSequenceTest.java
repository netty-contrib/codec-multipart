package io.netty.contrib.handler.codec.http.multipart;

import io.netty.contrib.multipart.PostBodyDecoder;

public class MultipartSequenceTest extends AbstractSequenceTest {
    @Override
    protected PostBodyDecoder createDecoder() {
        return PostBodyDecoder.builder().forMultipartBoundary(BOUNDARY);
    }
}
