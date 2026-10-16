package starmp;

import java.util.*;

/** Tiny JSON helper: flat objects of string/number/boolean values, which is all smnet speaks. */
final class Json {
    private Json() {}

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** Builds {"k":v,...} from alternating key / value (String, Number or Boolean). */
    static String obj(Object... kv) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) b.append(',');
            b.append(quote(String.valueOf(kv[i]))).append(':');
            Object v = kv[i + 1];
            if (v instanceof String) b.append(quote((String) v)); else b.append(v);
        }
        return b.append('}').toString();
    }

    static Map<String, Object> parse(String s) {
        Map<String, Object> m = new LinkedHashMap<>();
        int[] p = {0};
        ws(s, p);
        if (p[0] >= s.length() || s.charAt(p[0]) != '{') return m;
        p[0]++;
        while (true) {
            ws(s, p);
            if (p[0] >= s.length()) return m;
            char c = s.charAt(p[0]);
            if (c == '}') return m;
            if (c == ',') { p[0]++; continue; }
            String k = str(s, p);
            ws(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ':') p[0]++;
            ws(s, p);
            m.put(k, val(s, p));
        }
    }

    private static void ws(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
    }

    private static Object val(String s, int[] p) {
        char c = s.charAt(p[0]);
        if (c == '"') return str(s, p);
        int st = p[0];
        if (c == '{' || c == '[') { // skip nested value
            int depth = 0; boolean inStr = false;
            while (p[0] < s.length()) {
                char d = s.charAt(p[0]++);
                if (inStr) { if (d == '\\') p[0]++; else if (d == '"') inStr = false; }
                else if (d == '"') inStr = true;
                else if (d == '{' || d == '[') depth++;
                else if (d == '}' || d == ']') { if (--depth == 0) break; }
            }
            return s.substring(st, p[0]);
        }
        while (p[0] < s.length() && ",}".indexOf(s.charAt(p[0])) < 0) p[0]++;
        String t = s.substring(st, p[0]).trim();
        if (t.equals("true")) return Boolean.TRUE;
        if (t.equals("false")) return Boolean.FALSE;
        if (t.equals("null")) return null;
        try { return Long.parseLong(t); } catch (NumberFormatException e) {}
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { return t; }
    }

    private static String str(String s, int[] p) {
        StringBuilder b = new StringBuilder();
        p[0]++; // opening quote
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]++);
            if (c == '"') break;
            if (c == '\\' && p[0] < s.length()) {
                char e = s.charAt(p[0]++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16));
                        p[0] += 4; break;
                    default: b.append(e);
                }
            } else b.append(c);
        }
        return b.toString();
    }
}
