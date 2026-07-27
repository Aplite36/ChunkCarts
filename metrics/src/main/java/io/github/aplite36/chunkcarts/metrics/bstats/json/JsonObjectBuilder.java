/*
 * Vendored from bStats (https://github.com/Bastian/bstats-metrics), unmodified apart from the
 * package name. Vendored rather than shaded because MetricsBase refuses to run from a package
 * starting with `org.bstats`, and relocating a dependency would mean a shading plugin on all
 * forty-five loader targets.
 *
 * MIT License
 *
 * Copyright (c) 2021 Bastian Oppermann
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.aplite36.chunkcarts.metrics.bstats.json;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * An extremely simple JSON builder.
 *
 * <p>While this class is neither feature-rich nor the most performant one,
 * it's sufficient enough for its use-case.
 */
public class JsonObjectBuilder {

    private StringBuilder builder = new StringBuilder();
    private boolean hasAtLeastOneField = false;

    public JsonObjectBuilder() {
        builder.append("{");
    }

    /**
     * Appends a null field to the JSON.
     *
     * @param key The key of the field.
     * @return A reference to this object.
     */
    public JsonObjectBuilder appendNull(String key) {
        appendFieldUnescaped(key, "null");
        return this;
    }

    /**
     * Appends a string field to the JSON.
     *
     * @param key The key of the field.
     * @param value The value of the field.
     * @return A reference to this object.
     */
    public JsonObjectBuilder appendField(String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("JSON value must not be null");
        }
        appendFieldUnescaped(key, "\"" + escape(value) + "\"");
        return this;
    }

    /**
     * Appends an integer field to the JSON.
     *
     * @param key The key of the field.
     * @param value The value of the field.
     * @return A reference to this object.
     */
    public JsonObjectBuilder appendField(String key, int value) {
        appendFieldUnescaped(key, String.valueOf(value));
        return this;
    }

    /**
     * Appends an object to the JSON.
     *
     * @param key The key of the field.
     * @param object The object.
     * @return A reference to this object.
     */
    public JsonObjectBuilder appendField(String key, JsonObject object) {
        if (object == null) {
            throw new IllegalArgumentException("JSON object must not be null");
        }
        appendFieldUnescaped(key, object.toString());
        return this;
    }

    /**
     * Appends a string array to the JSON.
     *
     * @param key The key of the field.
     * @param values The string array.
     * @return A reference to this object.
     */
    public JsonObjectBuilder appendField(String key, String[] values) {
        if (values == null) {
            throw new IllegalArgumentException("JSON values must not be null");
        }
        String escapedValues = Arrays.stream(values)
                .map(value -> "\"" + escape(value) + "\"")
                .collect(Collectors.joining(","));
        appendFieldUnescaped(key, "[" + escapedValues + "]");
        return this;
    }

    /**
     * Appends an integer array to the JSON.
     *
     * @param key The key of the field.
     * @param values The integer array.
     * @return A reference to this object.
     */
    public JsonObjectBuilder appendField(String key, int[] values) {
        if (values == null) {
            throw new IllegalArgumentException("JSON values must not be null");
        }
        String escapedValues = Arrays.stream(values)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(","));
        appendFieldUnescaped(key, "[" + escapedValues + "]");
        return this;
    }

    /**
     * Appends an object array to the JSON.
     *
     * @param key The key of the field.
     * @param values The integer array.
     * @return A reference to this object.
     */
    public JsonObjectBuilder appendField(String key, JsonObject[] values) {
        if (values == null) {
            throw new IllegalArgumentException("JSON values must not be null");
        }
        String escapedValues = Arrays.stream(values)
                .map(JsonObject::toString)
                .collect(Collectors.joining(","));
        appendFieldUnescaped(key, "[" + escapedValues + "]");
        return this;
    }

    /**
     * Appends a field to the object.
     *
     * @param key The key of the field.
     * @param escapedValue The escaped value of the field.
     */
    private void appendFieldUnescaped(String key, String escapedValue) {
        if (builder == null) {
            throw new IllegalStateException("JSON has already been built");
        }
        if (key == null) {
            throw new IllegalArgumentException("JSON key must not be null");
        }
        if (hasAtLeastOneField) {
            builder.append(",");
        }
        builder.append("\"").append(escape(key)).append("\":").append(escapedValue);

        hasAtLeastOneField = true;
    }

    /**
     * Builds the JSON string and invalidates this builder.
     *
     * @return The built JSON string.
     */
    public JsonObject build() {
        if (builder == null) {
            throw new IllegalStateException("JSON has already been built");
        }
        JsonObject object = new JsonObject(builder.append("}").toString());
        builder = null;
        return object;
    }

    /**
     * Escapes the given string like stated in https://www.ietf.org/rfc/rfc4627.txt.
     *
     * <p>This method escapes only the necessary characters '"', '\'. and '\u0000' - '\u001F'.
     * Compact escapes are not used (e.g., '\n' is escaped as "\u000a" and not as "\n").
     *
     * @param value The value to escape.
     * @return The escaped value.
     */
    private static String escape(String value) {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                builder.append("\\\"");
            } else if (c == '\\') {
                builder.append("\\\\");
            } else if (c <= '\u000F') {
                builder.append("\\u000").append(Integer.toHexString(c));
            } else if (c <= '\u001F') {
                builder.append("\\u00").append(Integer.toHexString(c));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /**
     * A super simple representation of a JSON object.
     *
     * <p>This class only exists to make methods of the {@link JsonObjectBuilder} type-safe and
     * not allow a raw string inputs for methods like {@link JsonObjectBuilder#appendField(String, JsonObject)}.
     */
    public static class JsonObject {

        private final String value;

        private JsonObject(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

}