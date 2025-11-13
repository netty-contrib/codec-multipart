package io.netty.contrib.handler.codec.http.multipart;

public class MultipartSequenceTest extends AbstractSequenceTest {
    @Override
    protected PostBodyDecoder createDecoder() {
        return PostBodyDecoder.builder().forMultipartBoundary(BOUNDARY);
    }
}
