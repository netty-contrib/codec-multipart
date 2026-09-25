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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.fail;

class PostBodyDecoderBuilderTest {
    @Test
    public void copyIncludesAllFields() throws Exception {
        PostBodyDecoder.Builder defaults = PostBodyDecoder.builder();
        PostBodyDecoder.Builder original = PostBodyDecoder.builder();
        int fieldCount = 0;
        for (Field field : PostBodyDecoder.Builder.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object defaultValue = field.get(defaults);
            Object value;
            if (field.getType() == int.class) {
                value = (Integer) defaultValue + 1;
            } else if (field.getType() == Charset.class) {
                value = StandardCharsets.UTF_16BE;
            } else if (field.getType() == EnumSet.class) {
                value = EnumSet.of(DecoderQuirk.values()[0]);
            } else {
                // a new field type was added, extend this test (and make sure copy() handles it)
                fail("Unhandled builder field " + field);
                return;
            }
            assertNotEquals(defaultValue, value, field.getName());
            field.set(original, value);
            fieldCount++;
        }
        assertNotEquals(0, fieldCount);

        PostBodyDecoder.Builder copy = original.copy();
        for (Field field : PostBodyDecoder.Builder.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertEquals(field.get(original), field.get(copy), field.getName());
        }
        assertNotSame(original.multipartQuirks, copy.multipartQuirks);
    }
}
