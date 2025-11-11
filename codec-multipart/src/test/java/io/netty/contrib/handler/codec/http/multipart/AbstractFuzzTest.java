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

import com.code_intelligence.jazzer.Jazzer;
import com.code_intelligence.jazzer.junit.DictionaryEntries;
import io.micronaut.fuzzing.util.ByteSplitter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.stream.Stream;

@ExtendWith(MultipartComparisonTest.InitJazzer.class)
public abstract class AbstractFuzzTest {
    protected static final String BOUNDARY = "a";
    public static final String FUZZ_SEPARATOR_STR = "SEP";
    public static final ByteSplitter FUZZ_SPLITTER = ByteSplitter.create(FUZZ_SEPARATOR_STR);

    protected static final List<String> JAZZER_ARGS = List.of(
            //"-only_ascii=1"
    );

    static {
        for (int i = 0; i < JAZZER_ARGS.size(); i++) {
            System.setProperty("jazzer.internal.arg." + i, JAZZER_ARGS.get(i));
        }
    }

    @SuppressWarnings("unused")
    public static class InitJazzer implements Extension {
        static {
            // force init outer class
            List<?> l = JAZZER_ARGS;
        }
    }

    protected static void minimize(Class<? extends AbstractFuzzTest> testClass, String crashPath) throws Throwable {
        Jazzer.main(Stream.concat(
                JAZZER_ARGS.stream(),
                Stream.of(
                        "--target_class=" + testClass.getName(),
                        "-max_total_time=60",
                        "-minimize_crash=1", crashPath
                )
        ).toArray(String[]::new));
    }

    protected ByteBuf next(byte[] bytes, ByteSplitter.ChunkIterator itr) {
        itr.proceed();
        ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer(itr.length());
        buffer.writeBytes(bytes, itr.start(), itr.length());
        return buffer;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @DictionaryEntries({
            "--" + BOUNDARY,
            "--" + BOUNDARY + "--",
            "content-transfer-encoding",
            "7bit",
            "8bit",
            "binary",
            "content-length",
            "content-type",
            "multipart/mixed",
            "boundary=",
            "charset=",
            "utf-8",
            "us-ascii",
            "utf-16",
            "form-data",
            "attachment",
            "file",
            ";",
            "=",
            "filename",
            "filename*",
            "'",
            "application/octet-stream",
            "\"",
            ",",
            ":",
            FUZZ_SEPARATOR_STR,

            "content-type: multipart/mixed; boundary=b",
            "--b", "--b--"
    })
    @interface MultipartFuzzTest {
    }
}
