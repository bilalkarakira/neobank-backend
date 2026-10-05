package com.bilal.cardnet.shared;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for the two small HTTP surfaces: write maps, lists, strings, numbers and booleans; read one flat
 * object of strings, numbers, booleans and null. (cardnet has no JSON library on purpose.)
 */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> quote(s, out);
            case Boolean b -> out.append(b);
            case Integer n -> out.append(n);
            case Long n -> out.append(n);
            case Double n -> out.append(n);
            case BigDecimal n -> out.append(n.toPlainString());
            case Number n -> out.append(n);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            case Collection<?> list -> {
                out.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(item, out);
                }
                out.append(']');
            }
            default -> quote(String.valueOf(value), out);
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** A new ordered object to put values into. */
    public static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    /** @throws IllegalArgumentException for anything that is not one flat object */
    public static Map<String, Object> parseFlat(String text) {
        Parser p = new Parser(text);
        Map<String, Object> result = p.object();
        p.end();
        return result;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s == null ? "" : s;
        }

        Map<String, Object> object() {
            skip();
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skip();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                skip();
                String key = string();
                skip();
                expect(':');
                skip();
                map.put(key, value());
                skip();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("expected , or }");
                }
            }
        }

        void end() {
            skip();
            if (i != s.length()) {
                throw error("unexpected text after the object");
            }
        }

        private Object value() {
            char c = peek();
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (start == i) {
                throw error("a value was expected (nested objects and arrays are not supported)");
            }
            String number = s.substring(start, i);
            try {
                return number.contains(".") || number.contains("e") || number.contains("E") ? (Object) Double.valueOf(number) : (Object) Long.valueOf(number);
            } catch (NumberFormatException e) {
                throw error("bad number " + number);
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"', '\\', '/' -> out.append(e);
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw error("bad unicode escape");
                            }
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw error("bad escape");
                    }
                } else {
                    out.append(c);
                }
            }
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            return s.charAt(i);
        }

        private char next() {
            char c = peek();
            i++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) {
                throw error("expected " + c);
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("bad JSON at " + i + ": " + message);
        }
    }

    /** Convenience for building a list of objects. */
    public static List<Object> list() {
        return new ArrayList<>();
    }
}
