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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class NotEnoughDataDecoderExceptionTest {
    @Test
    void incompleteInputDoesNotCaptureAStackTrace() {
        NotEnoughDataDecoderException exception = new NotEnoughDataDecoderException();
        assertEquals("Not enough multipart chunks", exception.getMessage());
        assertEquals(0, exception.getStackTrace().length);
        assertSame(exception, exception.fillInStackTrace());
        IllegalStateException cause = new IllegalStateException("source");
        assertSame(cause, new NotEnoughDataDecoderException(cause).getCause());
    }
}
