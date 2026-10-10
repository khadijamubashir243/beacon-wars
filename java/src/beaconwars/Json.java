package beaconwars;

import java.util.*;

/** Minimal JSON parser/writer for replay logs and tuning configs. */
public final class Json {
    private Json() {}

    public static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return quote((String) value);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?>) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(String.valueOf(entry.getKey()))).append(':').append(stringify(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?>) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) out.append(',');
                first = false;
                out.append(stringify(item));
            }
            return out.append(']').toString();
        }
        if (value.getClass().isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
                if (i > 0) out.append(',');
                out.append(stringify(java.lang.reflect.Array.get(value, i)));
            }
            return out.append(']').toString();
        }
        return quote(value.toString());
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
            }
        }
        return out.append('"').toString();
    }

    public static Object parse(String source) {
        Parser parser = new Parser(source);
        Object value = parser.value();
        parser.space();
        if (parser.index != source.length()) throw new IllegalArgumentException("Trailing JSON data");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String source) {
        Object value = parse(source);
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("JSON root must be an object");
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        final String text;
        int index;
        Parser(String text) { this.text = text; }
        void space() { while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++; }
        char take() {
            if (index >= text.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            return text.charAt(index++);
        }
        Object value() {
            space();
            if (index >= text.length()) throw new IllegalArgumentException("Expected JSON value");
            char ch = text.charAt(index);
            if (ch == '"') return string();
            if (ch == '{') return object();
            if (ch == '[') return array();
            if (ch == 't' && literal("true")) return Boolean.TRUE;
            if (ch == 'f' && literal("false")) return Boolean.FALSE;
            if (ch == 'n' && literal("null")) return null;
            return number();
        }
        boolean literal(String literal) {
            if (!text.startsWith(literal, index)) return false;
            index += literal.length();
            return true;
        }
        String string() {
            if (take() != '"') throw new IllegalArgumentException("Expected string");
            StringBuilder out = new StringBuilder();
            while (index < text.length()) {
                char ch = take();
                if (ch == '"') return out.toString();
                if (ch != '\\') { out.append(ch); continue; }
                char escape = take();
                switch (escape) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u': out.append((char) Integer.parseInt(text.substring(index, index + 4), 16)); index += 4; break;
                    default: throw new IllegalArgumentException("Invalid string escape");
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }
        Map<String, Object> object() {
            take(); space();
            Map<String, Object> result = new LinkedHashMap<>();
            if (index < text.length() && text.charAt(index) == '}') { index++; return result; }
            while (true) {
                space();
                String key = string();
                space();
                if (take() != ':') throw new IllegalArgumentException("Expected ':'");
                result.put(key, value());
                space();
                char separator = take();
                if (separator == '}') return result;
                if (separator != ',') throw new IllegalArgumentException("Expected ',' or '}'");
            }
        }
        List<Object> array() {
            take(); space();
            List<Object> result = new ArrayList<>();
            if (index < text.length() && text.charAt(index) == ']') { index++; return result; }
            while (true) {
                result.add(value());
                space();
                char separator = take();
                if (separator == ']') return result;
                if (separator != ',') throw new IllegalArgumentException("Expected ',' or ']'");
            }
        }
        Number number() {
            int start = index;
            if (text.charAt(index) == '-') index++;
            while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
            boolean decimal = false;
            if (index < text.length() && text.charAt(index) == '.') {
                decimal = true; index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                decimal = true; index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
            }
            String token = text.substring(start, index);
            try {
                if (decimal) return Double.valueOf(token);
                return Long.valueOf(token);
            }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid JSON number: " + token); }
        }
    }
}
