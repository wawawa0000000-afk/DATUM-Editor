package com.example.minieditor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 外部ライブラリを使わない、小さな JSON の読み書き。
 *
 * Java の値との対応：
 *   オブジェクト {...} ⇔ Map<String, Object>
 *   配列 [...]       ⇔ List<Object>
 *   文字列           ⇔ String
 *   数値             ⇔ Double（書くときは Integer などでもよい）
 *   true / false     ⇔ Boolean
 *   null             ⇔ null
 */
public final class Json {

    /** JSON として読めなかったときに投げる例外。 */
    public static class JsonException extends Exception {
        public JsonException(String message) {
            super(message);
        }
    }

    private Json() {
    }

    // ---------- 書き出し ----------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 改行や字下げを入れずに、1行で書き出す。
     * 通信に使う（「1行が1つのメッセージ」という決まりの相手に送るため、途中に改行を入れられない）。
     */
    public static String writeCompact(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCompact(sb, value);
        return sb.toString();
    }

    private static void writeCompact(StringBuilder sb, Object value) {
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (i++ > 0) {
                    sb.append(',');
                }
                quote(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeCompact(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeCompact(sb, list.get(i));
            }
            sb.append(']');
        } else {
            write(sb, value, 0);        // 文字列・数値・真偽・null は、ふつうの書き出しと同じ
        }
    }

    private static void write(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            quote(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");              // JSON には NaN や Infinity の書き方がない（書くと、次に読めなくなる）
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);            // 3.0 ではなく 3 と書く
            } else {
                sb.append(d);
            }
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                pad(sb, indent + 2);
                quote(sb, String.valueOf(entry.getKey()));
                sb.append(": ");
                write(sb, entry.getValue(), indent + 2);
                sb.append(++i < map.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                pad(sb, indent + 2);
                write(sb, list.get(i), indent + 2);
                sb.append(i + 1 < list.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            quote(sb, value.toString());
        }
    }

    private static void pad(StringBuilder sb, int count) {
        for (int i = 0; i < count; i++) {
            sb.append(' ');
        }
    }

    /** 文字列を "..." で囲み、特別な文字を \n などに置き換える（エスケープ）。 */
    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------- 読み込み ----------

    public static Object parse(String text) throws JsonException {
        Parser parser = new Parser(text);
        parser.skipSpaces();
        Object value = parser.readValue();
        parser.skipSpaces();
        if (parser.pos < text.length()) {
            throw parser.error("余分な文字があります");
        }
        return value;
    }

    /** JSON の数の書き方（01・1.・+1・.5 などは認めない）。 */
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    /** 入れ子の深さの上限。壊れたファイルや悪意のあるデータで、読み込みが StackOverflowError で落ちないように。 */
    private static final int MAX_DEPTH = 512;

    /** 文字を先頭から1文字ずつ読んでいく。pos が「今どこまで読んだか」。 */
    private static final class Parser {
        private final String s;
        private int pos = 0;
        private int depth = 0;

        Parser(String s) {
            // 先頭に BOM（見えない目印の文字）が付いているファイルもあるので、取り除く
            this.s = s.startsWith("﻿") ? s.substring(1) : s;
        }

        JsonException error(String message) {
            return new JsonException(message + "（" + pos + " 文字目）");
        }

        void skipSpaces() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() throws JsonException {
            if (pos >= s.length()) {
                throw error("値がありません");
            }
            char c = s.charAt(pos);
            if (c == '{' || c == '[') {
                if (++depth > MAX_DEPTH) {
                    throw error("入れ子が深すぎます");
                }
                Object value = c == '{' ? readObject() : readArray();
                depth--;
                return value;
            }
            if (c == '"') {
                return readString();
            }
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            return readNumber();
        }

        Map<String, Object> readObject() throws JsonException {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;                                   // '{' を読み飛ばす
            skipSpaces();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipSpaces();
                if (peek() != '"') {
                    throw error("キーは \" で始める必要があります");
                }
                String key = readString();
                skipSpaces();
                if (peek() != ':') {
                    throw error(": がありません");
                }
                pos++;
                skipSpaces();
                map.put(key, readValue());
                skipSpaces();
                char c = peek();
                pos++;
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error(", か } が必要です");
                }
            }
        }

        List<Object> readArray() throws JsonException {
            List<Object> list = new ArrayList<>();
            pos++;                                   // '[' を読み飛ばす
            skipSpaces();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipSpaces();
                list.add(readValue());
                skipSpaces();
                char c = peek();
                pos++;
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error(", か ] が必要です");
                }
            }
        }

        String readString() throws JsonException {
            StringBuilder sb = new StringBuilder();
            pos++;                                   // 始まりの '"' を読み飛ばす
            while (true) {
                if (pos >= s.length()) {
                    throw error("文字列が閉じられていません");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw error("\\ の後ろに文字がありません");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw error("\\u の後ろは4桁必要です");
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char h = s.charAt(pos + i);
                            int digit = h < 0x80 ? Character.digit(h, 16) : -1;
                            if (digit < 0) {            // parseInt と違い、+ や全角の数字は通さない
                                throw error("\\u の後ろが16進数ではありません");
                            }
                            code = code * 16 + digit;
                        }
                        sb.append((char) code);
                        pos += 4;
                    }
                    default -> throw error("使えないエスケープです: \\" + e);
                }
            }
        }

        Double readNumber() throws JsonException {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw error("読めない文字があります");
            }
            String number = s.substring(start, pos);
            if (!NUMBER.matcher(number).matches()) {
                throw error("数値として読めません");
            }
            return Double.valueOf(number);
        }

        char peek() throws JsonException {
            if (pos >= s.length()) {
                throw error("途中で終わっています");
            }
            return s.charAt(pos);
        }
    }

    // ---------- Map から値を取り出すときの補助 ----------

    public static String str(Map<String, Object> map, String key, String fallback) {
        return map.get(key) instanceof String s ? s : fallback;
    }

    public static int num(Map<String, Object> map, String key, int fallback) {
        return map.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    public static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        return map.get(key) instanceof Boolean b ? b : fallback;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> map, String key) {
        return map.get(key) instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }
}
