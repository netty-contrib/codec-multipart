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
     * <p>
     * The completed headers of the current block remain part of the retained, undecoded data (and thus count towards
     * the undecoded data limit), like in the legacy decoder. They are not parsed again, however, and each header is
     * only reported once as a {@link PostBodyDecoder.Event#HEADER} event. Since the legacy decoder applied re-parsed
     * headers idempotently, this has no observable effect on the resulting fields.
     */
    RESCAN_HEADERS_ON_CHUNK_BOUNDARY,

    /**
     * Parse headers using the legacy splitting logic.
     * <p>
     * The modern parser uses structured parsing for {@code Content-Type} parameters, but the legacy decoder exposes
     * raw tokens. Enabling this quirk switches to the legacy split and fills the {@code quirkHeader} array so the
     * vintage wrapper can continue consuming headers exactly like before.
     * <p>
     * With this quirk, the vintage multipart decoder also stores {@code Content-Type} parameters such as {@code name},
     * {@code filename} and {@code content-length} as part metadata, overriding the values from
     * {@code Content-Disposition} and {@code Content-Length}, matching legacy Netty.
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
     * At the start of a part, the boundary does not have to be preceded by a line break, so when an input chunk ends
     * with a partial boundary at that position (e.g. {@code -} or {@code --bound}), we need to hold back those bytes in
     * case the rest of the boundary follows in the next chunk.
     * <p>
     * This quirk replicates a bug where these bytes would be incorrectly emitted as part content, and the remainder of
     * the boundary (and possibly following parts) would be treated as content as well.
     */
    FORWARD_PART_START_DELIMITER_PREFIX,

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
     * Surface a {@link NullPointerException} (wrapped in an {@code ErrorDataDecoderException}) when a multipart part's
     * {@code Content-Disposition} header is missing the required {@code name} parameter, instead of throwing a
     * descriptive {@code ErrorDataDecoderException} that names the missing parameter.
     * <p>
     * This quirk replicates a bug where the legacy decoder dereferenced the {@code name} attribute without validating
     * its presence, so callers observed an opaque NPE message instead of a clear validation error.
     */
    NPE_ON_MISSING_CONTENT_DISPOSITION_NAME,

    /**
     * Treat a line starting with the boundary as the end of the current part, regardless of what follows the boundary.
     * <p>
     * A boundary delimiter line must be followed by a line break (or the close marker {@code --}), otherwise it is part
     * of the content. This quirk replicates a bug where the legacy decoder would end the current part as soon as it
     * saw the boundary, even if it was followed by other data (e.g. {@code --boundaryX}), and would then fail to parse
     * the next delimiter line and wait for more data indefinitely.
     */
    IGNORE_DELIMITER_SUFFIX,

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

    /**
     * Without this quirk, a line ending ({@code \r\n} or a bare {@code \n}) after a field terminates the form, and
     * must be followed by the end of input. Any data after the line ending is rejected with a
     * {@link FormDecoderException}, as is a lone {@code \r} at the end of input. Line endings also terminate a field
     * that has no value, e.g. {@code a\r\n} is decoded as a field {@code a} with an empty value.
     * <p>
     * The old decoder was more lenient: it silently discarded any data after the line ending, accepted a lone
     * {@code \r} at the end of input as a line ending, and treated line endings in a field name as part of that
     * name. This quirk replicates that behavior.
     */
    LENIENT_END_OF_LINE,
}
