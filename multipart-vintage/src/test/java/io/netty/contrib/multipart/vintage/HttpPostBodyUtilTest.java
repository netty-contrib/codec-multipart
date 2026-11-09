/*
 * Copyright 2026 The Netty Project
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpPostBodyUtilTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "text/plain",
            "text/plain; charset=UTF-8",
            "a/b;c",
            "multipart/mixed; boundary=\"a/b\"",
    })
    void validContentType(String contentType) {
        assertTrue(HttpPostBodyUtil.isContentTypeForMultiPartValid(contentType));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "foo",
            "/",
            "text/",
            "/plain",
            "a/;b",
            "a;b/c",
            "mal;form=\"e/d\"",
            "text/pl\u0000ain",
            "text/pl\u007fain",
            "text/plain\r\nX-Injected: x",
    })
    void invalidContentType(String contentType) {
        assertFalse(HttpPostBodyUtil.isContentTypeForMultiPartValid(contentType));
    }

    @Test
    void nullContentType() {
        assertFalse(HttpPostBodyUtil.isContentTypeForMultiPartValid(null));
    }
}
