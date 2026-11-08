package com.example.minieditor;

import java.util.List;
import java.util.Random;

/**
 * 縦書きの配置（VerticalLayout）のテスト。
 * 段落が列の一番下までちょうど埋まったときに、空の列ができないこと。
 * それ以外の原稿では、置き場所も列の数（＝原稿用紙の枚数）も、直す前と変わらないこと。
 * 前の配置を使い回して並べ直した結果が、全部を並べ直した結果と同じこと。
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
        clusters();
        incremental();
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

    /** 異体字セレクタや結合文字は、前の文字と同じマスに入る（1マスを取らず、カーソルも途中に止めない）。 */
    private static void clusters() {
        VerticalLayout z = layout("あ\u200D\nい", 20);
        check(z.col[3] == 1 && z.row[3] == 0, "ZWJ のすぐ後ろの改行は、くっつけずに改行する");
        String ivs = "葛\uDB40\uDD00城";              // 葛 + 異体字セレクタ（U+E0100、サロゲートペア）
        VerticalLayout a = layout(ivs, 20);
        check(a.col[1] == 0 && a.row[1] == 0 && a.row[2] == 0 && a.row[3] == 1, "異体字セレクタは、前の字と同じマス");
        check(a.isDrawn(0) && !a.isDrawn(1) && !a.isDrawn(2), "異体字セレクタは、前の字と一緒に描く");
        String marks = "か\u3099き\uFE0Fe\u0301";       // か + 結合用の濁点、き + 異体字セレクタ、e + アクセント
        VerticalLayout b = layout(marks, 20);
        check(b.row[1] == 0 && b.row[2] == 1 && b.row[3] == 1 && b.row[4] == 2 && b.row[5] == 2 && b.row[6] == 3,
                "結合文字も、前の字と同じマス");
        check(VerticalLayout.clusterStart(ivs, 2) == 0 && VerticalLayout.clusterEnd(ivs, 0) == 3
                && VerticalLayout.clusterEnd(marks, 0) == 2, "ひとまとまりの始まりと終わり");
        VerticalLayout c = layout("\n\u3099あ", 20);
        check(c.col[1] == 1 && c.row[1] == 0 && c.row[2] == 1, "改行のすぐ後ろの結合文字は、くっつける相手がないので1マス");
        VerticalLayout d = layout("あいう\u3099え", 3);
        check(d.col[3] == 0 && d.row[3] == 2 && d.col[4] == 1 && d.row[4] == 0, "列の一番下の字に付いた結合文字は、次の列へ行かない");
        VerticalLayout e = layout("12\u20E3", 20);
        check(e.isDrawn(0) && e.isDrawn(1) && e.row[1] == 1 && e.row[2] == 1, "結合文字の付いた数字は、縦中横にしない");
    }

    /**
     * 前の配置を渡して、変わった段落だけを並べ直したときに、全部を並べ直したときと同じになるか。
     * 書き足し・消す・置き換えを、続けて何度もしたあとで比べる（禁則処理・縦中横・結合文字・改行を混ぜる）。
     */
    private static void incremental() {
        Random random = new Random(20261108);
        String pieces = "あいう漢字、。」）「（12!?\n\n\u3099ー";
        boolean ok = true;
        int compared = 0;
        for (int trial = 0; trial < 300 && ok; trial++) {
            int rows = 2 + random.nextInt(7);
            String text = randomText(random, pieces, random.nextInt(200));
            VerticalLayout chained = layout(text, rows);
            for (int step = 0; step < 30 && ok; step++) {
                int start = random.nextInt(text.length() + 1);
                int end = Math.min(text.length(), start + (random.nextInt(3) == 0 ? random.nextInt(8) : 0));
                text = text.substring(0, start) + randomText(random, pieces, random.nextInt(4)) + text.substring(end);
                if (random.nextInt(15) == 0) {
                    rows = 2 + random.nextInt(7);       // 1列の文字数が変わったときは、全部を並べ直す
                }
                chained = new VerticalLayout(text, rows, List.of(), chained);
                ok = sameLayout(chained, layout(text, rows), text);
                compared++;
            }
        }
        check(ok, "変わった段落だけ並べ直しても、全部を並べ直したときと同じ（" + compared + " 回の書き換えで比べた）");

        // 長い原稿の真ん中で1文字打つ：前後の段落は写し、後ろの段落は列がずれる
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("あ".repeat(i % 37)).append("「12」、。\n");
        }
        String before = sb.toString();
        VerticalLayout a = layout(before, 20);
        int middle = before.length() / 2;
        String after = before.substring(0, middle) + "\n" + before.substring(middle);
        check(sameLayout(new VerticalLayout(after, 20, List.of(), a), layout(after, 20), after),
                "長い原稿の真ん中で改行を足しても、全部を並べ直したときと同じ");
        String removed = before.substring(0, middle) + before.substring(middle + 30);
        check(sameLayout(new VerticalLayout(removed, 20, List.of(), a), layout(removed, 20), removed),
                "長い原稿の真ん中で、段落をまたいで消しても同じ");
    }

    private static String randomText(Random random, String pieces, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(pieces.charAt(random.nextInt(pieces.length())));
        }
        return sb.toString();
    }

    /** 2つの配置が、置き場所・列の数・描き方（縦中横・句読点を一緒に入れたマス）まで同じか。 */
    private static boolean sameLayout(VerticalLayout a, VerticalLayout b, String text) {
        if (a.totalColumns != b.totalColumns || a.usedColumns() != b.usedColumns()
                || !java.util.Arrays.equals(a.col, b.col) || !java.util.Arrays.equals(a.row, b.row)) {
            return false;
        }
        try {
            for (String name : new String[] {"pairHead", "pairTail", "tucked", "hasTucked"}) {
                java.lang.reflect.Field field = VerticalLayout.class.getDeclaredField(name);
                field.setAccessible(true);
                if (!java.util.Objects.deepEquals(field.get(a), field.get(b))) {
                    return false;
                }
            }
        } catch (ReflectiveOperationException ex) {
            return false;
        }
        return true;
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
