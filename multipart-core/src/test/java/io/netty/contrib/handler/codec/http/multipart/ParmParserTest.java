/*
 * Copyright 2024 The Netty Project
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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

class ParmParserTest {
    @Test
    public void simple() {
        Tester tester = new Tester();
        tester.run("foo;att1=val1;att2=val2");
        Assertions.assertEquals("foo", tester.type);
        Assertions.assertEquals(Map.of("att1", "val1", "att2", "val2"), tester.attributes);
    }

    @Test
    public void quoted() {
        Tester tester = new Tester();
        tester.run("foo;att1=\"va\\\"l1\";att2=\"val2\"");
        Assertions.assertEquals("foo", tester.type);
        Assertions.assertEquals(Map.of("att1", "va\"l1", "att2", "val2"), tester.attributes);
    }

    @Test
    public void encoded() {
        Tester tester = new Tester();
        tester.run("foo;att1*=UTF-8''%C3%B6;att2*=UTF-16LE''%E4%00");
        Assertions.assertEquals("foo", tester.type);
        Assertions.assertEquals(Map.of("att1", "ö", "att2", "ä"), tester.attributes);
    }

    private static class Tester extends ParmParser {
        final Map<String, String> attributes = new HashMap<>();
        String type;

        @Override
        void visitType(String type) {
            this.type = type;
        }

        @Override
        boolean decodeExtendedAttribute(String attribute) {
            return true;
        }

        @Override
        boolean visitAttribute(String attribute) {
            return true;
        }

        @Override
        void visitAttributeValue(String attribute, String value) {
            attributes.put(attribute, value);
        }
    }
}