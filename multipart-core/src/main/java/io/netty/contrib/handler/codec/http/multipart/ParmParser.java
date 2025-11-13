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

import io.netty5.handler.codec.http.QueryStringDecoder;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * Parser for {@code Content-} headers of the form {@code type; attribute=value}
 */
abstract class ParmParser {
    /**
     * Visit the type.
     *
     * @param type The type
     */
    abstract void visitType(String type);

    boolean decodeExtendedAttribute(String attribute) {
        return false;
    }

    /**
     * Visit an attribute.
     *
     * @param attribute The attribute key
     * @return {@code true} iff the attribute value should be parsed
     */
    abstract boolean visitAttribute(String attribute);

    /**
     * Visit a full attribute. Only called if {@link #visitAttribute(String)} returned {@code true}.
     *
     * @param attribute The attribute key
     * @param value     The potentially unescaped/unquoted attribute value
     */
    abstract void visitAttributeValue(String attribute, String value);

    final void run(String headerValue) {
        int typeEnd = headerValue.indexOf(';');
        String type;
        if (typeEnd == -1) {
            type = headerValue;
            typeEnd = headerValue.length();
        } else {
            type = headerValue.substring(0, typeEnd);
        }
        visitType(type);
        for (int parameterStart = typeEnd + 1; parameterStart < headerValue.length(); ) {
            int attributeEnd = headerValue.indexOf('=', parameterStart);
            if (attributeEnd == -1) {
                break;
            }
            while (Character.isWhitespace(headerValue.charAt(parameterStart))) {
                parameterStart++;
            }
            String attribute = headerValue.substring(parameterStart, attributeEnd);
            boolean extended = attribute.endsWith("*") && decodeExtendedAttribute(attribute);
            String trimmedAttribute = extended ? attribute.substring(0, attribute.length() - 1) : attribute;
            boolean needParameterValue = visitAttribute(trimmedAttribute);

            String parameterValue = null;
            int parameterValueEnd = attributeEnd + 1;
            if (extended) {
                int firstQuote = headerValue.indexOf('\'', parameterValueEnd);
                if (firstQuote == -1) {
                    break;
                }
                int secondQuote = headerValue.indexOf('\'', firstQuote + 1);
                if (secondQuote == -1) {
                    break;
                }
                parameterValueEnd = headerValue.indexOf(';', secondQuote + 1);
                if (parameterValueEnd == -1) {
                    parameterValueEnd = headerValue.length();
                }
                if (needParameterValue) {
                    Charset charset;
                    String charsetName = headerValue.substring(attributeEnd + 1, firstQuote);
                    if (charsetName.isEmpty()) {
                        charset = StandardCharsets.UTF_8;
                    } else {
                        try {
                            charset = Charset.forName(charsetName);
                        } catch (UnsupportedCharsetException | IllegalCharsetNameException e) {
                            charset = null;
                        }
                    }
                    if (charset != null) {
                        parameterValue = QueryStringDecoder.decodeComponent(headerValue.substring(secondQuote + 1, parameterValueEnd), charset);
                    }
                }
            } else if (parameterValueEnd < headerValue.length() && headerValue.charAt(parameterValueEnd) == '"') {
                StringBuilder valueBuilder = needParameterValue ? new StringBuilder() : null;
                boolean quoted = false;
                while (parameterValueEnd < headerValue.length()) {
                    char c = headerValue.charAt(parameterValueEnd++);
                    if (c == '"') {
                        quoted = !quoted;
                    } else {
                        if (!quoted && c == ';') {
                            parameterValueEnd--;
                            break;
                        } else if (quoted && c == '\\' && parameterValueEnd < headerValue.length()) {
                            if (needParameterValue) {
                                valueBuilder.append(headerValue.charAt(parameterValueEnd));
                            }
                            parameterValueEnd++;
                        } else {
                            if (needParameterValue) {
                                valueBuilder.append(c);
                            }
                        }
                    }
                }
                if (needParameterValue) {
                    parameterValue = valueBuilder.toString();
                }
            } else {
                parameterValueEnd = headerValue.indexOf(';', parameterValueEnd);
                if (parameterValueEnd == -1) {
                    parameterValueEnd = headerValue.length();
                }
                if (needParameterValue) {
                    parameterValue = headerValue.substring(attributeEnd + 1, parameterValueEnd);
                }
            }
            if (parameterValue != null) {
                visitAttributeValue(trimmedAttribute, parameterValue);
            }
            parameterStart = parameterValueEnd + 1;
        }
    }
}
