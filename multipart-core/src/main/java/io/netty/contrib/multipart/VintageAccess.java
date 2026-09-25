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


import io.netty.buffer.ByteBuf;

import java.nio.charset.Charset;

public final class VintageAccess {
    private VintageAccess() {
    }

    public static VintageAccess.MultipartDecoder forBoundaryWithPrefix(PostBodyDecoder.Builder builder,
                                                                       String boundary) {
        return builder.forBoundary0(boundary);
    }

    public static PostBodyDecoder.Builder copy(PostBodyDecoder.Builder builder) {
        return builder.copy();
    }

    public static int maxFields(PostBodyDecoder.Builder builder) {
        return builder.maxFields;
    }

    public static boolean hasQuirk(PostBodyDecoder.Builder builder, DecoderQuirk quirk) {
        return builder.multipartQuirks.contains(quirk);
    }

    public static String cleanString(String s) {
        return io.netty.contrib.multipart.MultipartDecoder.cleanString(s);
    }

    public interface MultipartDecoder extends PostBodyDecoder {
        String[] getQuirkHeader();

        boolean isMixed();

        int getCurrentAllocatedCapacity();

        int getCompactionThreshold();

        void setCompactionThreshold(int compactionThreshold);

        void setQuirkPartCharset(Charset quirkPartCharset);

        void setQuirkDefinedLength(long quirkDefinedLength);

        Charset getCharset();

        /**
         * Whether a specific quirk is enabled.
         */
        boolean hasQuirk(DecoderQuirk quirk);
    }

    public interface UrlEncodedDecoder extends PostBodyDecoder {
        ByteBuf undecodedContent();

        void decodeComponent(ByteBuf buffer, boolean key);

        int getCompactionThreshold();

        void setCompactionThreshold(int compactionThreshold);

        boolean isEof();
    }
}
