package com.example.minieditor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON の読み書き（Json）のテスト。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.JsonTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class JsonTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        writing();
        numbers();
        escapes();
        nesting();
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void writing() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nan", Double.NaN);
        map.put("inf", Double.POSITIVE_INFINITY);
        map.put("minus", Float.NEGATIVE_INFINITY);
        map.put("n", 3);
        map.put("x", 0.25);
        map.put("big", 1e20);
        for (String text : new String[]{Json.write(map), Json.writeCompact(map)}) {
            check(!text.contains("NaN") && !text.contains("Infinity"), "NaN や Infinity は書かない");
            Map<?, ?> back = (Map<?, ?>) Json.parse(text);
            check(back.containsKey("nan") && back.get("nan") == null && back.get("inf") == null,
                    "書けない数は null にして、読み直せる");
            check(back.get("n").equals(3.0) && back.get("x").equals(0.25) && back.get("big").equals(1e20),
                    "ふつうの数はそのまま読み直せる");
        }
    }

    private static void numbers() throws Exception {
        for (String ok : new String[]{"0", "-0", "12", "-3.5", "1e5", "1E+5", "2.5e-3", "1.0E20"}) {
            check(Json.parse(ok) instanceof Double, "数として読む: " + ok);
        }
        for (String bad : new String[]{"01", "1.", "+1", ".5", "-", "1e", "1e+", "--1", "1.2.3", "0x10"}) {
            check(fails(bad), "数として読まない: " + bad);
        }
    }

    private static void escapes() throws Exception {
        check("あ".equals(Json.parse("\"\\u3042\"")) && "\u00ff".equals(Json.parse("\"\\u00FF\"")),
                "\\u の16進数を読む");
        check(fails("\"\\u+123\"") && fails("\"\\u-123\"") && fails("\"\\u１２３４\"") && fails("\"\\u12\""),
                "\\u の後ろの + ・ - ・全角の数字・足りない桁は読まない");
    }

    private static void nesting() throws Exception {
        String ok = "[".repeat(500) + "]".repeat(500);
        check(!fails(ok), "500 段の入れ子は読める");
        Object value = Json.parse(ok);
        for (int i = 1; i < 500; i++) {
            value = ((List<?>) value).get(0);
        }
        check(((List<?>) value).isEmpty(), "500 段の中身もそのまま");
        check(fails("[".repeat(100000) + "]".repeat(100000)), "深すぎる配列は、落ちずに JsonException");
        check(fails("{\"a\":".repeat(100000) + "1" + "}".repeat(100000)), "深すぎるオブジェクトも同じ");
        check(fails("[".repeat(100000)), "閉じていない深い入れ子も同じ");
    }

    /** 読めずに JsonException になれば true（StackOverflowError などで落ちたら、そのまま投げる）。 */
    private static boolean fails(String text) {
        try {
            Json.parse(text);
            return false;
        } catch (Json.JsonException ex) {
            return true;
        }
    }
}
