package com.example.minieditor;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

/**
 * 行間・字間・余白の設定（Spacing）のテスト。
 * 既定（標準）は、今までの描き方と同じ値になることを確かめる。
 */
public class SpacingTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        defaults();
        levels();
        outsideLevels();
        settings();
        reset();
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** メモリ上だけの AppSettings（実際の設定フォルダには書かない）。 */
    private static AppSettings memory(Map<String, Object> data) throws Exception {
        Constructor<AppSettings> constructor = AppSettings.class.getDeclaredConstructor(Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(data);
    }

    private static void reset() {
        Spacing.setLine(Spacing.DEFAULT);
        Spacing.setChars(Spacing.DEFAULT);
        Spacing.setMargin(Spacing.DEFAULT);
    }

    private static void defaults() {
        check(Spacing.DEFAULT == 1, "既定の段階は 1（標準）");
        check(Spacing.line() == 1 && Spacing.chars() == 1 && Spacing.margin() == 1, "既定は3つとも標準");
        check(Spacing.lineFactor() == 1.85f, "標準の縦の行間は 1.85（今までと同じ）");
        check(Spacing.charFactor() == 1.12f, "標準の縦の字間は 1.12（今までと同じ）");
        check(Spacing.marginFactor() == 1f, "標準の余白は 1 倍（今までと同じ）");
        check(java.util.Arrays.equals(Spacing.NAMES, new String[] {"狭い", "標準", "広い"}), "段階の名前は 狭い・標準・広い");
    }

    private static void levels() {
        Spacing.setLine(0);
        check(Spacing.lineFactor() == 1.6f, "縦の行間の狭いは 1.6");
        Spacing.setLine(2);
        check(Spacing.lineFactor() == 2.2f, "縦の行間の広いは 2.2");
        Spacing.setChars(0);
        check(Spacing.charFactor() == 1.0f, "縦の字間の狭いは 1.0");
        Spacing.setChars(2);
        check(Spacing.charFactor() == 1.3f, "縦の字間の広いは 1.3");
        Spacing.setMargin(0);
        check(Spacing.marginFactor() == 0.5f, "余白の狭いは 0.5 倍");
        Spacing.setMargin(2);
        check(Spacing.marginFactor() == 1.5f, "余白の広いは 1.5 倍");
        reset();
    }

    private static void outsideLevels() {
        Spacing.setLine(2);
        Spacing.setLine(3);
        check(Spacing.line() == 1, "一覧にない 3 は標準に戻る");
        Spacing.setChars(2);
        Spacing.setChars(-1);
        check(Spacing.chars() == 1, "負の数は標準に戻る");
        Spacing.setMargin(0);
        Spacing.setMargin(9);
        check(Spacing.margin() == 1, "一覧にない 9 は標準に戻る");
        reset();
    }

    private static void settings() throws Exception {
        AppSettings empty = memory(new HashMap<>());
        Spacing.load(empty);
        check(Spacing.line() == 1 && Spacing.chars() == 1 && Spacing.margin() == 1, "保存がなければ標準");

        Map<String, Object> data = new HashMap<>();
        data.put(AppSettings.SPACING_LINE, 2);
        data.put(AppSettings.SPACING_CHAR, 0);
        data.put(AppSettings.SPACING_MARGIN, 7);
        AppSettings stored = memory(data);
        Spacing.load(stored);
        check(Spacing.line() == 2 && Spacing.chars() == 0, "保存された行間・字間を読む");
        check(Spacing.margin() == 1, "読めない余白は標準になる");

        AppSettings target = memory(new HashMap<>());
        Spacing.setLine(0);
        Spacing.setChars(2);
        Spacing.setMargin(2);
        Spacing.save(target);
        reset();
        Spacing.load(target);
        check(Spacing.line() == 0 && Spacing.chars() == 2 && Spacing.margin() == 2, "保存した値を読み直せる");
        reset();
    }
}
