/*
 * Copyright 2025 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.contrib.multipart.vintage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.contrib.multipart.DecoderQuirk;
import io.netty.contrib.multipart.FormDecoderException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ParameterizedClass
@ValueSource(booleans = {false, true})
class MultipartQuirksVintageTest {
    private final boolean quirk;
    private HttpDataFactory dataFactory;

    MultipartQuirksVintageTest(boolean quirk) {
        this.quirk = quirk;
    }

    @BeforeEach
    void setUp() {
        dataFactory = new DefaultHttpDataFactory(false);
    }

    @AfterEach
    void tearDown() {
        dataFactory.cleanAllHttpData();
    }

    private static FullHttpRequest fullRequest(String boundary, String body) {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/",
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers().add(HttpHeaderNames.CONTENT_TYPE,
                HttpHeaderValues.MULTIPART_FORM_DATA + "; boundary=" + boundary);
        return request;
    }

    @Test
    void useFieldCharsetForDelimiterSearch() {
        HttpPostRequestDecoder.Builder builder = HttpPostRequestDecoder.builder().dataFactory(dataFactory);
        if (quirk) {
            // with this quirk, the delimiter is not found at all, so we also need to allow a missing close delimiter
            builder.enableQuirks(DecoderQuirk.USE_FIELD_CHARSET_FOR_DELIMITER_SEARCH,
                    DecoderQuirk.ALLOW_MISSING_CLOSE_DELIMITER);
        }
        HttpPostRequestDecoder decoder = builder.build(fullRequest("ö",
                "--ö\ncontent-disposition: form-data; name=\"xyz\"\n" +
                        "content-type: text/plain; charset=iso-8859-1\n\nfoo\n--ö--\n"));
        try {
            assertEquals(quirk ? 0 : 1, decoder.getBodyHttpDatas().size());
        } finally {
            decoder.destroy();
        }
    }

    private HttpPostRequestDecoder legacyHeaderSplittingDecoder(String body) {
        HttpPostRequestDecoder.Builder builder = HttpPostRequestDecoder.builder().dataFactory(dataFactory);
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.LEGACY_HEADER_SPLITTING);
        }
        return builder.build(fullRequest("a", body));
    }

    @Test
    void contentTypeNameParameterDoesNotOverrideFieldName() {
        HttpPostRequestDecoder decoder = legacyHeaderSplittingDecoder("--a\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"real.pdf\"\r\n" +
                "Content-Type: application/pdf; name=\"x.pdf\"\r\n" +
                "\r\n" +
                "v\r\n" +
                "--a--\r\n");
        try {
            FileUpload upload = assertInstanceOf(FileUpload.class, decoder.getBodyHttpDatas().get(0));
            assertEquals(quirk ? "x.pdf" : "file", upload.getName());
            assertEquals("real.pdf", upload.getFilename());
            assertEquals("application/pdf", upload.getContentType());
        } finally {
            decoder.destroy();
        }
    }

    @Test
    void contentTypeFilenameParameterDoesNotOverrideFilename() {
        HttpPostRequestDecoder decoder = legacyHeaderSplittingDecoder("--a\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"real.pdf\"\r\n" +
                "Content-Type: application/pdf; filename=\"x.pdf\"\r\n" +
                "\r\n" +
                "v\r\n" +
                "--a--\r\n");
        try {
            FileUpload upload = assertInstanceOf(FileUpload.class, decoder.getBodyHttpDatas().get(0));
            assertEquals("file", upload.getName());
            assertEquals(quirk ? "x.pdf" : "real.pdf", upload.getFilename());
        } finally {
            decoder.destroy();
        }
    }

    @Test
    void contentTypeFilenameParameterDoesNotTurnFieldIntoFileUpload() throws IOException {
        HttpPostRequestDecoder decoder = legacyHeaderSplittingDecoder("--a\r\n" +
                "Content-Disposition: form-data; name=\"field\"\r\n" +
                "Content-Type: text/plain; filename=\"x.pdf\"; charset=UTF-8\r\n" +
                "\r\n" +
                "v\r\n" +
                "--a--\r\n");
        try {
            InterfaceHttpData data = decoder.getBodyHttpDatas().get(0);
            assertEquals("field", data.getName());
            if (quirk) {
                assertEquals("x.pdf", assertInstanceOf(FileUpload.class, data).getFilename());
            } else {
                Attribute attribute = assertInstanceOf(Attribute.class, data);
                assertEquals("v", attribute.getValue());
                assertEquals(StandardCharsets.UTF_8, attribute.getCharset());
            }
        } finally {
            decoder.destroy();
        }
    }

    @Test
    void contentTypeContentLengthParameterDoesNotOverrideLength() throws IOException {
        HttpPostRequestDecoder decoder = legacyHeaderSplittingDecoder("--a\r\n" +
                "Content-Disposition: form-data; name=\"field\"\r\n" +
                "Content-Type: text/plain; content-length=100\r\n" +
                "\r\n" +
                "value\r\n" +
                "--a--\r\n");
        try {
            Attribute attribute = assertInstanceOf(Attribute.class, decoder.getBodyHttpDatas().get(0));
            assertEquals("field", attribute.getName());
            assertEquals("value", attribute.getValue());
            assertEquals(quirk ? 100 : 0, attribute.definedLength());
        } finally {
            decoder.destroy();
        }
    }
    private HttpPostRequestDecoder missingCloseDecoder() {
        HttpPostRequestDecoder.Builder builder = HttpPostRequestDecoder.builder().dataFactory(dataFactory);
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.ALLOW_MISSING_CLOSE_DELIMITER);
        }
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
        request.headers().add(HttpHeaderNames.CONTENT_TYPE,
                HttpHeaderValues.MULTIPART_FORM_DATA + "; boundary=a");
        return builder.build(request);
    }

    private static void offer(HttpPostRequestDecoder decoder, String body, boolean last) {
        ByteBuf buf = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
        HttpContent content = last ? new DefaultLastHttpContent(buf) : new DefaultHttpContent(buf);
        try {
            decoder.offer(content);
        } finally {
            content.release();
        }
    }

    private static List<String> values(HttpPostRequestDecoder decoder) throws IOException {
        List<String> values = new ArrayList<>();
        for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
            values.add(((Attribute) data).getValue());
        }
        return values;
    }

    @Test
    void validCloseDelimiter() throws IOException {
        HttpPostRequestDecoder decoder = missingCloseDecoder();
        try {
            offer(decoder, "--a\ncontent-disposition: form-data; name=\"x\"\n\nfoo\n", false);
            offer(decoder, "--a\ncontent-disposition: form-data; name=\"y\"\n\nbar\n--a--\n", true);
            assertEquals(List.of("foo", "bar"), values(decoder));
        } finally {
            decoder.destroy();
        }
    }

    @Test
    void validCloseDelimiterWithTrailingCr() throws IOException {
        HttpPostRequestDecoder decoder = missingCloseDecoder();
        try {
            offer(decoder, "--a\r\ncontent-disposition: form-data; name=\"x\"\r\n\r\nfoo\r\n--a--\r", true);
            assertEquals(List.of("foo"), values(decoder));
        } finally {
            decoder.destroy();
        }
    }

    @Test
    void validCloseDelimiterInEmptyLastChunk() throws IOException {
        HttpPostRequestDecoder decoder = missingCloseDecoder();
        try {
            offer(decoder, "--a\ncontent-disposition: form-data; name=\"x\"\n\nfoo\n--a--\n", false);
            offer(decoder, "", true);
            assertEquals(List.of("foo"), values(decoder));
        } finally {
            decoder.destroy();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"--a\n", "--a"})
    void missingCloseDelimiter(String end) throws IOException {
        HttpPostRequestDecoder decoder = missingCloseDecoder();
        try {
            offer(decoder, "--a\ncontent-disposition: form-data; name=\"x\"\n\nfoo\n" + end, false);
            if (quirk) {
                offer(decoder, "", true);
                // a trailing "--a" without line break is not a delimiter, so the part is incomplete and dropped
                assertEquals(end.equals("--a") ? List.of() : List.of("foo"), values(decoder));
            } else {
                assertThrows(FormDecoderException.class, () -> offer(decoder, "", true));
                // the truncated body must not look complete
                assertThrows(HttpPostRequestDecoder.NotEnoughDataDecoderException.class,
                        decoder::getBodyHttpDatas);
            }
        } finally {
            decoder.destroy();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "--a\ncontent-disposition: form-data; name=\"y\"\n",
            "--a\ncontent-disposition: form-data; name=\"y\"\n\nba",
    })
    void truncatedNextPart(String truncated) throws IOException {
        HttpPostRequestDecoder decoder = missingCloseDecoder();
        try {
            offer(decoder, "--a\ncontent-disposition: form-data; name=\"x\"\n\nfoo\n", false);
            if (quirk) {
                offer(decoder, truncated, true);
                // legacy: the truncated part is silently dropped, and the prefix looks like a complete form
                assertEquals(List.of("foo"), values(decoder));
            } else {
                assertThrows(FormDecoderException.class, () -> offer(decoder, truncated, true));
                assertThrows(HttpPostRequestDecoder.NotEnoughDataDecoderException.class,
                        decoder::getBodyHttpDatas);
            }
        } finally {
            decoder.destroy();
        }
    }

    @Test
    void offerAfterLastChunk() throws IOException {
        HttpPostRequestDecoder decoder = missingCloseDecoder();
        try {
            offer(decoder, "--a\ncontent-disposition: form-data; name=\"x\"\n\nfoo\n--a--\n", true);
            if (quirk) {
                // legacy: further input is accepted (and ignored, since it's in the epilogue)
                offer(decoder, "bar", true);
            } else {
                assertThrows(IllegalStateException.class, () -> offer(decoder, "bar", true));
            }
            assertEquals(List.of("foo"), values(decoder));
        } finally {
            decoder.destroy();
        }
    }

    @Test
    void missingCloseDelimiterFullRequest() {
        HttpPostRequestDecoder.Builder builder = HttpPostRequestDecoder.builder().dataFactory(dataFactory);
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.ALLOW_MISSING_CLOSE_DELIMITER);
        }
        FullHttpRequest request = fullRequest("a", "--a\ncontent-disposition: form-data; name=\"x\"\n\nfoo\n");
        try {
            if (quirk) {
                HttpPostRequestDecoder decoder = builder.build(request);
                try {
                    // the field is never completed
                    assertEquals(0, decoder.getBodyHttpDatas().size());
                } finally {
                    decoder.destroy();
                }
            } else {
                assertThrows(FormDecoderException.class, () -> builder.build(request));
            }
        } finally {
            request.release();
        }
    }
}
