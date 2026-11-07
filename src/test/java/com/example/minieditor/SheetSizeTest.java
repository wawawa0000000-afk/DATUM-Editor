package com.example.minieditor;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原稿用紙の字数・行数（SheetSize）のテスト。一覧の値、設定の保存と読み込み、枚数（WritingStats.manuscriptSheets）への効き方を確かめる。
 *
 * AppSettings は実際の設定フォルダを使わず、メモリ上の入れ物（非公開のコンストラクタで作る）で試す。
 * 既定の 20×20 のままで始めるので、main の最初で既定の値を確かめる。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -Djava.awt.headless=true -cp target/classes:target/test-classes com.example.minieditor.SheetSizeTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class SheetSizeTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        defaults();
        choices();
        outsideChoices();
        settings();
        sheets();
        SheetSize.setChars(SheetSize.DEFAULT);
        SheetSize.setLines(SheetSize.DEFAULT);
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** メモリ上だけの AppSettings（実際の設定フォルダには書かない）。 */
    private static AppSettings memory(Map<String, Object> data) throws Exception {
        Constructor<AppSettings> constructor = AppSettings.class.getDeclaredConstructor(Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(data);
    }

    private static void defaults() {
        check(SheetSize.chars() == 20 && SheetSize.lines() == 20, "既定は 20字×20行");
        check(SheetSize.DEFAULT == 20, "既定の値は 20");
    }

    private static void choices() {
        int[] expected = {10, 15, 20, 25, 30, 40};
        check(java.util.Arrays.equals(SheetSize.CHOICES, expected), "選べる値は 10, 15, 20, 25, 30, 40 の6つ");
        for (int value : SheetSize.CHOICES) {
            SheetSize.setChars(value);
            SheetSize.setLines(value);
            check(SheetSize.chars() == value && SheetSize.lines() == value, value + " は字数・行数とも設定できる");
        }
        SheetSize.setChars(SheetSize.DEFAULT);
        SheetSize.setLines(SheetSize.DEFAULT);
    }

    private static void outsideChoices() {
        SheetSize.setChars(25);
        SheetSize.setChars(12);
        check(SheetSize.chars() == 20, "一覧にない 12 を字数に入れると 20 に戻る");
        SheetSize.setLines(25);
        SheetSize.setLines(12);
        check(SheetSize.lines() == 20, "一覧にない 12 を行数に入れると 20 に戻る");
        SheetSize.setChars(0);
        check(SheetSize.chars() == 20, "0 も 20 に戻る");
        SheetSize.setLines(100);
        check(SheetSize.lines() == 20, "一覧にない 100 も 20 に戻る");
        SheetSize.setChars(SheetSize.DEFAULT);
        SheetSize.setLines(SheetSize.DEFAULT);
    }

    private static void settings() throws Exception {
        check("sheetChars".equals(AppSettings.SHEET_CHARS) && "sheetLines".equals(AppSettings.SHEET_LINES),
                "設定の名前は sheetChars と sheetLines");

        Map<String, Object> data = new LinkedHashMap<>();
        AppSettings settings = memory(data);
        SheetSize.setChars(25);
        SheetSize.setLines(40);
        SheetSize.save(settings);
        check(settings.getInt(AppSettings.SHEET_CHARS, 0) == 25 && settings.getInt(AppSettings.SHEET_LINES, 0) == 40,
                "保存すると、設定に 25 と 40 が入る");

        SheetSize.setChars(SheetSize.DEFAULT);
        SheetSize.setLines(SheetSize.DEFAULT);
        SheetSize.load(settings);
        check(SheetSize.chars() == 25 && SheetSize.lines() == 40, "保存した値を読み込むと、25字×40行に戻る");

        SheetSize.setChars(15);
        SheetSize.setLines(15);
        SheetSize.load(memory(new LinkedHashMap<>()));
        check(SheetSize.chars() == 20 && SheetSize.lines() == 20, "設定がなければ、20字×20行で始める");

        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put(AppSettings.SHEET_CHARS, 12);
        bad.put(AppSettings.SHEET_LINES, "abc");
        SheetSize.load(memory(bad));
        check(SheetSize.chars() == 20 && SheetSize.lines() == 20, "一覧にない値や壊れた値は、20 に戻す");

        for (int value : SheetSize.CHOICES) {
            AppSettings round = memory(new LinkedHashMap<>());
            SheetSize.setChars(value);
            SheetSize.setLines(value);
            SheetSize.save(round);
            SheetSize.setChars(SheetSize.DEFAULT);
            SheetSize.setLines(SheetSize.DEFAULT);
            SheetSize.load(round);
            check(SheetSize.chars() == value && SheetSize.lines() == value, value + " は保存して読み込んでも同じ");
        }
        SheetSize.setChars(SheetSize.DEFAULT);
        SheetSize.setLines(SheetSize.DEFAULT);
    }

    private static void sheets() {
        check(WritingStats.manuscriptSheets("あ".repeat(400)) == 1, "既定 20×20: 400字は1枚");
        check(WritingStats.manuscriptSheets("あ".repeat(401)) == 2, "既定 20×20: 401字は2枚");

        SheetSize.setChars(10);
        SheetSize.setLines(40);
        check(WritingStats.manuscriptSheets("あ".repeat(400)) == 1, "10字×40行: 400字は1枚（10×40 = 400）");
        check(WritingStats.manuscriptSheets("あ".repeat(401)) == 2, "10字×40行: 401字は2枚");
        check(WritingStats.manuscriptSheets("あ\n".repeat(39) + "あ") == 1, "10字×40行: 1字ずつの段落40行は1枚");
        check(WritingStats.manuscriptSheets("あ\n".repeat(40) + "あ") == 2, "10字×40行: 1字ずつの段落41行は2枚");

        SheetSize.setChars(40);
        SheetSize.setLines(10);
        check(WritingStats.manuscriptSheets("あ".repeat(400)) == 1, "40字×10行: 400字は1枚");
        check(WritingStats.manuscriptSheets("あ".repeat(401)) == 2, "40字×10行: 401字は2枚");

        SheetSize.setChars(SheetSize.DEFAULT);
        SheetSize.setLines(SheetSize.DEFAULT);
    }
}
