package io.netty.contrib.handler.codec.http.multipart;

public class UrlEncodedDecoderSplitTest extends AbstractDecoderSplitTest {
    @Override
    protected PostBodyDecoder createDecoder() {
        return PostBodyDecoder.builder().forUrlEncodedData();
    }

    public static void main(String[] args) throws Throwable {
        minimize(UrlEncodedDecoderSplitTest.class, "codec-multipart/src/test/resources/io/netty/contrib/handler/codec/http/multipart/UrlEncodedDecoderSplitTestInputs/compare/crash-f701ae99ec8904a3a8e8d8142a1825aa6bb6e2a6");
    }

    @SuppressWarnings("unused")
    public static void fuzzerTestOneInput(byte[] bytes) {
        new UrlEncodedDecoderSplitTest().compare(bytes);
    }
}
