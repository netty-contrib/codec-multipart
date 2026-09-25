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

import io.netty.handler.codec.DecoderException;

public class FormDecoderException extends DecoderException {
    public FormDecoderException() {
    }

    public FormDecoderException(String message, Throwable cause) {
        super(message, cause);
    }

    public FormDecoderException(String message) {
        super(message);
    }

    public FormDecoderException(Throwable cause) {
        super(cause);
    }
}
