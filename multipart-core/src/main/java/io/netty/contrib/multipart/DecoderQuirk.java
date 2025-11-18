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

/**
 * Fine-grained compatibility toggles ("quirks") for the post body streaming parser.
 * <p>
 * These switches reproduce specific behaviors of the legacy {@code HttpPostRequestDecoder} implementation.
 * They exist to ensure drop-in compatibility for applications that rely on historical edge cases or bugs.
 * <p>
 * Quirks can be enabled individually, all at once, or disabled selectively via the decoder builders. When migrating
 * code, prefer enabling none and only turning on the specific quirks required by your clients.
 */
public enum DecoderQuirk {
    // MULTIPART

    /**
     * When a chunk ends during header parsing, revisit the entire header block on the next iteration.
     * <p>
     * Legacy decoders would roll back to the start of the current header block when the input chunk ended in the middle
     * of header parsing, and re-parse on the next {@link PostBodyDecoder#next()} call.
     */
    RESCAN_HEADERS_ON_CHUNK_BOUNDARY,

    /**
     * Parse headers using the legacy splitting logic.
     * <p>
     * The modern parser uses structured parsing for {@code Content-Type} parameters, but the legacy decoder exposes
     * raw tokens. Enabling this quirk switches to the legacy split and fills the {@code quirkHeader} array so the
     * vintage wrapper can continue consuming headers exactly like before.
     */
    LEGACY_HEADER_SPLITTING,

    /**
     * Stop parsing additional headers in a part immediately after encountering a {@code Content-Type: multipart/mixed}
     * header and emit {@code BEGIN_MIXED}.
     * <p>
     * The legacy decoder short-circuited header parsing as soon as it saw a mixed part, deferring remaining headers
     * to subsequent processing. This quirk preserves that behavior.
     */
    STOP_AFTER_MULTIPART_MIXED_HEADER,

    /**
     * A part is normally terminated by {@code \n--boundary}. At the start of a part however, the boundary does not
     * have to be preceded by a line break (i.e. as long as nothing comes before it).
     * <p>
     * This quirk replicates a bug where the parser would accept a boundary without a preceding line break even when it
     * appears further along in a part, as long as the boundary appears at the start of an input chunk.
     */
    INVERSE_DELIMITER_AT_BUFFER_START,

    /**
     * When an input chunk boundary appears in the middle of a part, we have to hold back some bytes in case they are
     * part of the boundary sequence. This quirk replicates old behavior that was slightly more conservative than
     * necessary when holding back line breaks.
     */
    CONSERVATIVE_LF_BACKTRACK,

    /**
     * When an input chunk ends with {@code \r}, we need to hold back that byte in case it is part of a boundary at the
     * start of the next chunk.
     * <p>
     * This quirk replicates a bug where the {@code \r} would be incorrectly emitted as part of the part content when
     * the part length is not known exactly.
     */
    FORWARD_CHUNK_CR,

    /**
     * Use the part-specific charset (derived from the part headers) for delimiter detection as soon as headers are
     * complete. This should not really matter since boundaries are supposed to be ASCII anyway.
     */
    USE_FIELD_CHARSET_FOR_DELIMITER_SEARCH,

    /**
     * A multipart/mixed part contains a sort of "nested multipart" with its own boundary. This quirk replicates
     * behavior where the old parser would ignore boundaries of the original document when they appear in the nested
     * multipart before that nested multipart is complete.
     */
    DISABLE_EARLY_MIXED_END,

    /**
     * The old decoder would not skip whitespace and control characters if the entire input buffer was filled with
     * them. This can lead to slight parsing differences. Real-world impact is probably minimal.
     */
    CONSERVATIVE_WHITESPACE_SKIP,

    // URL ENCODED

    /**
     * The old URL parser would UTF-8 decode the input before percent-decoding, but the whatwg spec recommends percent
     * decoding before UTF-8 decoding. This can lead to subtle differences when a code point is encoded as multiple
     * UTF-8 code units, but only some of those code units are percent-encoded for some reason. Likely irrelevant in
     * practice.
     */
    EARLY_DECODE,

    /**
     * When the input ends with CR, the old parser would not commit to ending the current form encoded value until an
     * associated LF was seen.
     */
    WAIT_ON_CR,

    /**
     * The old decoder would check validity of CRLF earlier than necessary.
     */
    EARLY_CRLF_CHECK,

    /**
     * According to the whatwg spec, percent encoded sequences with non-hex values should be left as-is. This quirk
     * throws an exception instead, to imitate old parser behavior.
     */
    REFUSE_NON_HEX_PERCENT_DECODE,

    /**
     * According to the whatwg spec, percent encoded sequences that end early ({@code %a}) should be left as-is. This
     * quirk throws an exception instead, to imitate old parser behavior.
     */
    REFUSE_SHORT_PERCENT_DECODE,
}
