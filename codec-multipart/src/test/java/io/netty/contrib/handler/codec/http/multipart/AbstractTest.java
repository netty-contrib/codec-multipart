/*
 * Copyright 20202 The Netty Project
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
package io.netty.contrib.handler.codec.http.multipart;

import org.junit.jupiter.api.AfterAll;

import java.util.stream.IntStream;

/**
 * Abstract base class extended by all other tests.
 */
public class AbstractTest {

    @AfterAll
    static void afterAll() {
        if (Boolean.getBoolean("io.netty5.buffer.leakDetectionEnabled")) {
            IntStream.range(0, 10).forEach(i -> System.gc());
        }
    }

}