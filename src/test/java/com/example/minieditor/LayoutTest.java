package com.example.minieditor;

import java.util.List;
import java.util.Random;

/**
 * 縦書きの配置（VerticalLayout）のテスト。
 * 段落が列の一番下までちょうど埋まったときに、空の列ができないこと。
 * それ以外の原稿では、置き場所も列の数（＝原稿用紙の枚数）も、直す前と変わらないこと。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.LayoutTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class LayoutTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        fullColumn();
        unchanged();
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static VerticalLayout layout(String text, int rows) {
        return new VerticalLayout(text, rows, List.of());
    }

    private static void fullColumn() {
        VerticalLayout a = layout("あいうえ\nかき", 4);
        check(a.totalColumns == 2, "4字の列にちょうど4字の段落：列は2つ（空の列ができない）");
        check(a.col[5] == 1 && a.row[5] == 0, "次の段落は、すぐ隣の列の一番上から");
        check(a.col[4] == 0 && a.row[4] == 4, "ちょうど埋めた段落の改行は、前の列の一番下のすぐ下");

        VerticalLayout b = layout("あいうえ。\nか", 4);
        check(b.totalColumns == 2 && b.col[6] == 1 && b.row[6] == 0, "一番下のマスに句読点を入れた場合も、空の列ができない");

        VerticalLayout c = layout("あいうえ\n\nか", 4);
        check(c.totalColumns == 3 && c.col[6] == 2, "そのあとの空行は、今までどおり空の列1つ");

        VerticalLayout d = layout("あいう\nか", 4);
        check(d.totalColumns == 2 && d.col[3] == 0 && d.row[3] == 3, "埋まっていない段落は、今までどおり");

        // 20字×20行の原稿用紙1枚：20字ちょうどの段落を20個並べると、ちょうど1枚（20列）に収まる
        StringBuilder sheet = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sheet.append("あ".repeat(20)).append(i < 19 ? "\n" : "");
        }
        VerticalLayout e = layout(sheet.toString(), 20);
        check(e.usedColumns() == 20, "20字の段落20個は、原稿用紙1枚（20列）に収まる（PDF も1枚）");
        VerticalLayout f = layout(sheet + "\n", 20);
        check(f.usedColumns() == 20, "ファイルの最後が改行でも、PDF に白紙のページを足さない");
        check(layout("", 20).usedColumns() == 1, "空の文書でも、PDF は1枚");
    }

    /**
     * いろいろな文章で、直す前の配置（OldLayout）と比べる。
     * 違ってよいのは、列をちょうど埋めた段落の改行と、そのあとの文字の列だけ（1つずつ前へ詰まる）。
     */
    private static void unchanged() {
        Random random = new Random(20261027);
        String pieces = "あいう漢字、。」）「（12!?\n\n𠮷ー";
        int same = 0;
        int fewer = 0;
        boolean ok = true;
        for (int trial = 0; trial < 3000; trial++) {
            int rows = 3 + random.nextInt(6);
            StringBuilder sb = new StringBuilder();
            int length = random.nextInt(80);
            for (int i = 0; i < length; i++) {
                int k = random.nextInt(pieces.length());
                if (Character.isHighSurrogate(pieces.charAt(k))) {
                    sb.append(pieces, k, k + 2);
                } else if (!Character.isLowSurrogate(pieces.charAt(k))) {
                    sb.append(pieces.charAt(k));
                }
            }
            String text = sb.toString();
            OldLayout old = new OldLayout(text, rows);
            VerticalLayout now = layout(text, rows);
            if (old.fullBreaks == 0) {
                boolean equal = old.totalColumns == now.totalColumns;
                for (int i = 0; i <= text.length() && equal; i++) {
                    equal = old.col[i] == now.col[i] && old.row[i] == now.row[i];
                }
                ok &= equal;
                same++;
            } else {
                ok &= now.totalColumns == old.totalColumns - old.fullBreaks;
                fewer++;
            }
        }
        check(ok, "ちょうど埋まる段落がない原稿（" + same + " 通り）は、置き場所も列の数も変わらない。"
                + "ある原稿（" + fewer + " 通り）は、その数だけ列が減るだけ");
    }

    /** 直す前の VerticalLayout の、置き場所を決める部分の写し（比べるためだけに使う）。 */
    private static final class OldLayout {
        final int[] col;
        final int[] row;
        final int totalColumns;
        int fullBreaks;                 // 列をちょうど埋めた段落のあとの改行の数（直したあとは、その数だけ列が減る）

        OldLayout(String text, int rowsPerColumn) {
            String hanging = "、。，．」』）】〕〉》";
            String opening = "「『（【〔〈《［｛";
            int n = text.length();
            col = new int[n + 1];
            row = new int[n + 1];
            boolean[] pairTail = new boolean[n + 1];
            for (int i = 0; i + 1 < n; i++) {
                if (isPair(text, i)) {
                    pairTail[i + 1] = true;
                    i++;
                }
            }
            int rows = Math.max(1, rowsPerColumn);
            int c = 0;
            int r = 0;
            boolean wrapped = false;
            int tuckedCount = 0;
            int lastPlaced = -1;
            for (int i = 0; i < n; i++) {
                char ch = text.charAt(i);
                boolean lowSurrogate = i > 0 && Character.isLowSurrogate(ch) && Character.isHighSurrogate(text.charAt(i - 1));
                if (lowSurrogate || pairTail[i]) {
                    col[i] = col[i - 1];
                    row[i] = row[i - 1];
                    continue;
                }
                if (ch != '\n' && r == 0 && wrapped && tuckedCount < 2 && lastPlaced >= 0 && hanging.indexOf(ch) >= 0) {
                    col[i] = c - 1;
                    row[i] = rows - 1;
                    ++tuckedCount;
                    continue;
                }
                if (ch != '\n' && r == rows - 1 && rows > 1 && opening.indexOf(ch) >= 0 && i + 1 < n && text.charAt(i + 1) != '\n') {
                    c++;
                    r = 0;
                    wrapped = true;
                    tuckedCount = 0;
                    lastPlaced = -1;
                }
                col[i] = c;
                row[i] = r;
                if (ch == '\n') {
                    if (wrapped && r == 0) {
                        fullBreaks++;
                    }
                    c++;
                    r = 0;
                    wrapped = false;
                    tuckedCount = 0;
                    lastPlaced = -1;
                } else {
                    lastPlaced = i;
                    r++;
                    if (r >= rows) {
                        c++;
                        r = 0;
                        wrapped = true;
                        tuckedCount = 0;
                    }
                }
            }
            col[n] = c;
            row[n] = r;
            totalColumns = c + 1;
        }

        private static boolean isPair(String text, int i) {
            char a = text.charAt(i);
            char b = text.charAt(i + 1);
            boolean digits = isDigit(a) && isDigit(b);
            boolean marks = isMark(a) && isMark(b);
            if (!digits && !marks) {
                return false;
            }
            char before = i > 0 ? text.charAt(i - 1) : ' ';
            char after = i + 2 < text.length() ? text.charAt(i + 2) : ' ';
            return digits ? !isWord(before) && !isWord(after) : !isMark(before) && !isMark(after);
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static boolean isMark(char c) {
            return c == '!' || c == '?';
        }

        private static boolean isWord(char c) {
            return isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '.' || c == ',';
        }
    }
}
