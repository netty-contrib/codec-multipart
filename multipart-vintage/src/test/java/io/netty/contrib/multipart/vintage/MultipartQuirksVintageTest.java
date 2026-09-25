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

import io.netty.buffer.Unpooled;
import io.netty.contrib.multipart.DecoderQuirk;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            builder.enableQuirks(DecoderQuirk.USE_FIELD_CHARSET_FOR_DELIMITER_SEARCH);
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

    private static FullHttpRequest requestWithContentType(String contentType, String body) {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/",
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        request.headers().add(HttpHeaderNames.CONTENT_TYPE, contentType);
        return request;
    }

    private HttpPostRequestDecoder contentTypeDetectionDecoder(String contentType) {
        HttpPostRequestDecoder.Builder builder = HttpPostRequestDecoder.builder().dataFactory(dataFactory);
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.LEGACY_MULTIPART_CONTENT_TYPE_DETECTION);
        }
        return builder.build(requestWithContentType(contentType,
                "--a\r\ncontent-disposition: form-data; name=\"field\"\r\n\r\nvalue\r\n--a--\r\n"));
    }

    @Test
    void mixedCaseMultipartMediaType() throws IOException {
        HttpPostRequestDecoder decoder = contentTypeDetectionDecoder("Multipart/Form-Data; boundary=a");
        try {
            assertEquals(!quirk, decoder.isMultipart());
            if (!quirk) {
                Attribute attribute = assertInstanceOf(Attribute.class, decoder.getBodyHttpData("field"));
                assertEquals("value", attribute.getValue());
            }
        } finally {
            decoder.destroy();
        }
    }

    @Test
    void upperCaseMultipartMediaType() {
        HttpPostRequestDecoder decoder = contentTypeDetectionDecoder("MULTIPART/FORM-DATA; boundary=a");
        try {
            assertEquals(!quirk, decoder.isMultipart());
        } finally {
            decoder.destroy();
        }
    }

    @Test
    void multipartMediaTypeWithInvalidSuffix() {
        HttpPostRequestDecoder decoder = contentTypeDetectionDecoder("multipart/form-datax; boundary=a");
        try {
            assertEquals(quirk, decoder.isMultipart());
        } finally {
            decoder.destroy();
        }
    }

    @Test
    void mixedCaseMultipartMediaTypeWithInvalidSuffix() {
        HttpPostRequestDecoder decoder = contentTypeDetectionDecoder("Multipart/Form-Datax; boundary=a");
        try {
            assertFalse(decoder.isMultipart());
        } finally {
            decoder.destroy();
        }
    }

    @Test
    void regularMultipartMediaType() {
        HttpPostRequestDecoder decoder = contentTypeDetectionDecoder("multipart/form-data; boundary=a");
        try {
            assertTrue(decoder.isMultipart());
        } finally {
            decoder.destroy();
        }
    }

    @Test
    void staticIsMultipartKeepsLegacyBehavior() {
        // the static method is not tied to a builder, so it always replicates legacy Netty
        assertFalse(HttpPostRequestDecoder.isMultipart(
                requestWithContentType("Multipart/Form-Data; boundary=a", "")));
        assertTrue(HttpPostRequestDecoder.isMultipart(
                requestWithContentType("multipart/form-datax; boundary=a", "")));
        assertTrue(HttpPostRequestDecoder.isMultipart(
                requestWithContentType("multipart/form-data; boundary=a", "")));
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedConstructorKeepsLegacyBehavior() {
        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(dataFactory, requestWithContentType(
                "Multipart/Form-Data; boundary=a", "a=b"));
        try {
            assertFalse(decoder.isMultipart());
        } finally {
            decoder.destroy();
        }
    }
}
