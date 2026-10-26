package com.jump.minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

final class MiniJson {
    private final String input;
    private int index;

    private MiniJson(String input) {
        this.input = input;
    }

    static Map<String, Object> parseObject(String input) {
        Object value = new MiniJson(input).parseDocument();
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("JSON root must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) map;
        return result;
    }

    private Object parseDocument() {
        skipWhitespace();
        Object value = parseValue();
        skipWhitespace();
        if (index != input.length()) fail("Trailing content");
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (index >= input.length()) fail("Expected value");
        return switch (input.charAt(index)) {
            case '{' -> parseObjectValue();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't' -> parseLiteral("true", Boolean.TRUE);
            case 'f' -> parseLiteral("false", Boolean.FALSE);
            case 'n' -> parseLiteral("null", null);
            default -> parseNumber();
        };
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> result = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) return result;
        while (true) {
            result.add(parseValue());
            skipWhitespace();
            if (consume(']')) return result;
            expect(',');
        }
    }

    private Map<String, Object> parseObjectValue() {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) return result;
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            result.put(key, parseValue());
            skipWhitespace();
            if (consume('}')) return result;
            expect(',');
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (index < input.length()) {
            char current = input.charAt(index++);
            if (current == '"') return value.toString();
            if (current != '\\') {
                if (current < 0x20) fail("Control character in string");
                value.append(current);
                continue;
            }
            if (index >= input.length()) fail("Incomplete escape");
            char escaped = input.charAt(index++);
            switch (escaped) {
                case '"', '\\', '/' -> value.append(escaped);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> value.append(parseUnicode());
                default -> fail("Unsupported escape");
            }
        }
        fail("Unterminated string");
        return "";
    }

    private char parseUnicode() {
        if (index + 4 > input.length()) fail("Incomplete unicode escape");
        try {
            char result = (char) Integer.parseInt(input.substring(index, index + 4), 16);
            index += 4;
            return result;
        } catch (NumberFormatException error) {
            fail("Invalid unicode escape");
            return 0;
        }
    }

    private Number parseNumber() {
        int start = index;
        if (consume('-')) {
            // sign consumed
        }
        if (consume('0')) {
            // zero cannot be followed by another integer digit
        } else {
            requireDigit();
            while (hasDigit()) index++;
        }
        boolean floating = false;
        if (consume('.')) {
            floating = true;
            requireDigit();
            while (hasDigit()) index++;
        }
        if (index < input.length() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
            floating = true;
            index++;
            if (index < input.length() && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++;
            requireDigit();
            while (hasDigit()) index++;
        }
        String token = input.substring(start, index);
        try {
            return floating ? Double.valueOf(token) : Long.valueOf(token);
        } catch (NumberFormatException error) {
            fail("Invalid number");
            return 0;
        }
    }

    private Object parseLiteral(String token, Object value) {
        if (!input.startsWith(token, index)) fail("Invalid literal");
        index += token.length();
        return value;
    }

    private void requireDigit() {
        if (!hasDigit()) fail("Expected digit");
    }

    private boolean hasDigit() {
        return index < input.length() && Character.isDigit(input.charAt(index));
    }

    private void skipWhitespace() {
        while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++;
    }

    private boolean consume(char expected) {
        if (index < input.length() && input.charAt(index) == expected) {
            index++;
            return true;
        }
        return false;
    }

    private void expect(char expected) {
        if (!consume(expected)) fail("Expected '" + expected + "'");
    }

    private void fail(String message) {
        throw new IllegalArgumentException(message + " at byte " + index);
    }
}
