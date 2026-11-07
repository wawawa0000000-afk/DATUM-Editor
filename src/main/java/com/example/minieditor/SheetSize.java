package com.example.minieditor;

/**
 * 原稿用紙の大きさ（字数と行数）。画面・PDF・枚数の表示は、ここの値を使う。
 *
 * 字数は、1行（縦の1列）に入る字の数。行数は、原稿用紙の行（縦の列）の数。1枚は「字数 × 行数」。
 * 既定は 20 × 20（400字詰め）。一覧にない数は、既定の 20 に戻す。
 * 値は AppSettings に "sheetChars"（字数）と "sheetLines"（行数）で覚える。
 */
public final class SheetSize {

    /** 字数と行数で選べる値（この中の数だけを使う）。 */
    public static final int[] CHOICES = {10, 15, 20, 25, 30, 40};

    /** 既定の値（今までの 20字×20行）。 */
    public static final int DEFAULT = 20;

    private static int chars = DEFAULT;
    private static int lines = DEFAULT;

    private SheetSize() {
    }

    /** 字数（1行に入る字の数）。 */
    public static int chars() {
        return chars;
    }

    /** 行数（1枚の列の数）。 */
    public static int lines() {
        return lines;
    }

    /** 字数を変える。一覧にない数は、既定の 20 にする。 */
    public static void setChars(int value) {
        chars = choose(value);
    }

    /** 行数を変える。一覧にない数は、既定の 20 にする。 */
    public static void setLines(int value) {
        lines = choose(value);
    }

    /** 一覧にある数ならその数を、なければ既定の 20 を返す。 */
    static int choose(int value) {
        for (int choice : CHOICES) {
            if (choice == value) {
                return value;
            }
        }
        return DEFAULT;
    }

    /** 設定から読む。読めないときは 20。 */
    public static void load(AppSettings settings) {
        setChars(settings.getInt(AppSettings.SHEET_CHARS, DEFAULT));
        setLines(settings.getInt(AppSettings.SHEET_LINES, DEFAULT));
    }

    /** 設定に入れる。ファイルに書くのは、呼んだあとに AppSettings.save() で行う。 */
    public static void save(AppSettings settings) {
        settings.put(AppSettings.SHEET_CHARS, chars);
        settings.put(AppSettings.SHEET_LINES, lines);
    }
}
