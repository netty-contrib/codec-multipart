package io.netty.contrib.handler.codec.http.multipart;

import io.netty5.handler.codec.http.DefaultHttpRequest;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;

public class UrlEncodedComparisonTest extends AbstractComparisonTest {
    static final HttpRequest REQUEST = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    static final HttpDataFactory FACTORY = new DefaultHttpDataFactory(false);

    @SuppressWarnings("unused")
    public static void fuzzerTestOneInput(byte[] bytes) {
        new UrlEncodedComparisonTest().compare(bytes);
    }

    @Override
    protected InterfaceHttpPostRequestDecoder createNormal() {
        return new HttpPostStandardRequestDecoder(FACTORY, REQUEST, StandardCharsets.UTF_8, -1, -1);
    }

    @Override
    protected InterfaceHttpPostRequestDecoder createLegacy() {
        return new HttpPostStandardRequestDecoderLegacy(FACTORY, REQUEST);
    }
}
