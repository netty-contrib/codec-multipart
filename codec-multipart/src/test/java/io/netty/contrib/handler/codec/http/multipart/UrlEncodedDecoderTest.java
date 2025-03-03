package io.netty.contrib.handler.codec.http.multipart;

import io.netty5.buffer.DefaultBufferAllocators;
import io.netty5.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class UrlEncodedDecoderTest {
    private void expectField(PostBodyDecoder decoder, String name, String value) {
        Assertions.assertEquals(PostBodyDecoder.Event.BEGIN_FIELD, decoder.next());

        Assertions.assertEquals(PostBodyDecoder.Event.HEADER, decoder.next());
        Assertions.assertEquals(HttpHeaderNames.CONTENT_DISPOSITION, decoder.headerName());
        var cd = Assertions.assertInstanceOf(ContentDisposition.class, decoder.parsedHeaderValue());
        Assertions.assertEquals(name, cd.name());
        Assertions.assertNull(cd.fileName());

        Assertions.assertEquals(PostBodyDecoder.Event.HEADERS_COMPLETE, decoder.next());

        Assertions.assertEquals(PostBodyDecoder.Event.CONTENT, decoder.next());
        Assertions.assertEquals(value, decoder.decodedContentString());

        Assertions.assertEquals(PostBodyDecoder.Event.FIELD_COMPLETE, decoder.next());
    }

    @Test
    public void simple() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            decoder.add(DefaultBufferAllocators.preferredAllocator()
                    .copyOf("foo=bar&fizz=buzz", StandardCharsets.UTF_8).send());
            decoder.endInput();

            expectField(decoder, "foo", "bar");
            expectField(decoder, "fizz", "buzz");
            Assertions.assertNull(decoder.next());
        }
    }

    @Test
    public void decodePlus() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            decoder.add(DefaultBufferAllocators.preferredAllocator()
                    .copyOf("foo=xyz+abc", StandardCharsets.UTF_8).send());
            decoder.endInput();

            expectField(decoder, "foo", "xyz abc");
            Assertions.assertNull(decoder.next());
        }
    }

    @Test
    public void decodePercent() {
        try (PostBodyDecoder decoder = PostBodyDecoder.builder().forUrlEncodedData()) {
            decoder.add(DefaultBufferAllocators.preferredAllocator()
                    .copyOf("foo=xyz%20abc", StandardCharsets.UTF_8).send());
            decoder.endInput();

            expectField(decoder, "foo", "xyz abc");
            Assertions.assertNull(decoder.next());
        }
    }
}