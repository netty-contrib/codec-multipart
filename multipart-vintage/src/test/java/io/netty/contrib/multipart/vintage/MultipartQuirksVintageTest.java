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

import io.netty.contrib.multipart.DecoderQuirk;
import io.netty5.buffer.DefaultBufferAllocators;
import io.netty5.handler.codec.http.DefaultFullHttpRequest;
import io.netty5.handler.codec.http.FullHttpRequest;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpHeaderValues;
import io.netty5.handler.codec.http.HttpMethod;
import io.netty5.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ParameterizedClass
@ValueSource(booleans = {false, true})
class MultipartQuirksVintageTest {
    private final boolean quirk;
    private HttpDataFactory dataFactory;

    public MultipartQuirksVintageTest(boolean quirk) {
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
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", DefaultBufferAllocators.preferredAllocator().copyOf(body, StandardCharsets.UTF_8));
        request.headers().add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.MULTIPART_FORM_DATA + "; boundary=" + boundary);
        return request;
    }

    @Test
    void useFieldCharsetForDelimiterSearch() {
        HttpPostRequestDecoder.Builder builder = HttpPostRequestDecoder.builder().dataFactory(dataFactory);
        if (quirk) {
            builder.enableQuirks(DecoderQuirk.USE_FIELD_CHARSET_FOR_DELIMITER_SEARCH);
        }
        HttpPostRequestDecoder decoder = builder.build(fullRequest("ö", "--ö\ncontent-disposition: form-data; name=\"xyz\"\ncontent-type: text/plain; charset=iso-8859-1\n\nfoo\n--ö--\n"));
        assertEquals(quirk ? 0 : 1, decoder.getBodyHttpDatas().size());
    }
}
