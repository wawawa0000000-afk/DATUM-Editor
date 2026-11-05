package com.example.minieditor;

/**
 * 入力補助の規則（画面には依存しない）。
 *
 * 次の2つを、表示メニューの「入力補助」から切り替える。どちらも既定は切（オフ）。
 *   段落頭の全角スペース … Enter で改行したあと、新しい行の頭に全角スペースを1字入れる
 *   かっこの自動閉じ     … 開きかっこを打ったら、対になる閉じかっこも一緒に入れて、間にカーソルを置く
 *
 * 打つ処理（TextEditorPane・VerticalTextPane）が、ここの規則を使う。
 */
public final class InputAssist {

    /** 段落の頭に入れる全角スペース（1字）。 */
    public static final String INDENT = "　";

    /** Enter で改行したあと、全角スペースを入れるか。 */
    static boolean indentOnBreak = false;

    /** 開きかっこを打ったとき、閉じかっこも一緒に入れるか。 */
    static boolean pairs = false;

    private InputAssist() {
    }

    /** 開きかっこに対になる閉じかっこ。対にならない文字なら null。 */
    static String closingFor(char open) {
        return switch (open) {
            case '「' -> "」";
            case '『' -> "』";
            case '（' -> "）";
            default -> null;
        };
    }

    /** 閉じかっこ（」』）のどれか）か。 */
    static boolean isClosing(char c) {
        return c == '」' || c == '』' || c == '）';
    }

    /** Enter で改行したあとに入れる文字。段落頭の全角スペースが切なら空。 */
    static String paragraphIndent() {
        return indentOnBreak ? INDENT : "";
    }
}
