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
package io.netty.contrib.multipart;

import io.netty.handler.codec.http.HttpHeaderValues;

final class MultipartContentDisposition extends ParmParser implements ContentDisposition {
    private final String headerValue;

    private boolean parsed;
    private String name;
    private String filename;

    MultipartContentDisposition(String headerValue) {
        this.headerValue = headerValue;
    }

    private void parse() {
        if (!parsed) {
            run(headerValue);
            parsed = true;
        }
    }

    @Override
    public String name() {
        parse();
        return name;
    }

    @Override
    public String fileName() {
        parse();
        return filename;
    }

    @Override
    void visitType(String type) {
    }

    @Override
    boolean decodeExtendedAttribute(String attribute) {
        return true;
    }

    @Override
    boolean visitAttribute(String attribute) {
        return HttpHeaderValues.FILENAME.contentEqualsIgnoreCase(attribute) ||
                HttpHeaderValues.NAME.contentEqualsIgnoreCase(attribute);
    }

    @Override
    void visitAttributeValue(String attribute, String value) {
        if (HttpHeaderValues.FILENAME.contentEqualsIgnoreCase(attribute)) {
            filename = value;
        } else {
            assert HttpHeaderValues.NAME.contentEqualsIgnoreCase(attribute);
            name = value;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MultipartContentDisposition &&
                ((MultipartContentDisposition) o).headerValue.equals(headerValue);
    }

    @Override
    public int hashCode() {
        return headerValue.hashCode();
    }
}
