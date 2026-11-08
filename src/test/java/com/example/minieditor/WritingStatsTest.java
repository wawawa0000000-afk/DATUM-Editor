package com.example.minieditor;

import java.awt.Font;
import java.util.List;

import javax.swing.SwingUtilities;

import static com.example.minieditor.WritingStats.CountMode.ALL;
import static com.example.minieditor.WritingStats.CountMode.BODY;
import static com.example.minieditor.WritingStats.CountMode.NO_SPACE;

/**
 * 字数の数え方（WritingStats.countChars・charCountText）のテスト。既存の count・manuscriptSheets が変わらないことも確かめる。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.WritingStatsTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class WritingStatsTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        counting();
        selection();
        sameAsBefore();
        existing();
        SwingUtilities.invokeAndWait(WritingStatsTest::existingPane);   // 画面の部品は、画面のスレッドで動かす
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void counting() {
        // ルビ（横書きの記法）：ALL は記号ごと数え、BODY は親文字だけ
        check(WritingStats.countChars("｜東京《とうきょう》", ALL, true) == 10, "ルビの記法は、全体では記号も読みも数える（10字）");
        check(WritingStats.countChars("｜東京《とうきょう》", BODY, true) == 2, "ルビを除くと、親文字の2字だけ");
        check(WritingStats.countChars("｜東京《とう きょう》", NO_SPACE, true) == 10, "ルビの読みの空白は、空白を除くモードで減る");
        check(WritingStats.countChars("｜東京《とう きょう》", BODY, true) == 2, "ルビの読みに空白があっても、ルビを除くと親文字だけ");
        check(WritingStats.countChars("前｜東京《とうきょう》後", BODY, true) == 4, "ルビの前後の本文は数える（4字）");
        check(WritingStats.countChars("｜東京", BODY, true) == 3, "閉じていない記法は、ルビにせず、そのまま数える");
        check(WritingStats.countChars("｜《そのまま》", BODY, true) == 7, "ルビにしない記法（｜《）は、文字どおり数える");
        check(WritingStats.countChars("｜東京《とうきょう》", BODY, false) == 10, "縦書きの本文（記法なし）は、｜《》もそのまま数える");

        // 傍点：1文字ずつ「・」のルビが付く
        check(WritingStats.countChars("｜字《・》", ALL, true) == 5, "傍点の記法は、全体では5字（｜ 字 《 ・ 》）");
        check(WritingStats.countChars("｜字《・》｜字《・》", BODY, true) == 2, "傍点を除くと、親文字の2字だけ");

        // 縦中横：本文の文字として、2字と数える
        check(WritingStats.countChars("12", ALL, false) == 2, "縦中横（12）は、2字と数える");
        check(WritingStats.countChars("!?", BODY, false) == 2, "縦中横（!?）も、2字と数える");

        // 結合文字：コードポイントで数える（か＋濁点の U+3099 は2字）
        check(WritingStats.countChars("が", ALL, false) == 2, "結合文字は、別々に1字と数える（か＋U+3099 は2字）");
        check(WritingStats.countChars("が", NO_SPACE, false) == 2, "結合文字は、空白を除くモードでも2字");

        // サロゲートペア：1字と数える
        check(WritingStats.countChars("𠮷", ALL, false) == 1, "サロゲートペア（𠮷）は1字");
        check(WritingStats.countChars("𠮷𠮷", BODY, false) == 2, "サロゲートペアの2字は、2字と数える");
        check(WritingStats.countChars("𠮷｜東京《とうきょう》", BODY, true) == 3, "サロゲートペアとルビが混ざっても、親文字だけを数える");

        // 改行
        check(WritingStats.countChars("あ\nい", ALL, false) == 3, "全体では、改行も1字と数える");
        check(WritingStats.countChars("あ\nい", NO_SPACE, false) == 2, "空白・改行を除くモードでは、改行を除く");
        check(WritingStats.countChars("あ\nい", BODY, false) == 2, "ルビを除くモードでも、改行は数えない");

        // 空白
        String spaces = "あ い　う\tえ";
        check(WritingStats.countChars(spaces, ALL, false) == 7, "全体では、半角・全角の空白とタブも数える（7字）");
        check(WritingStats.countChars(spaces, NO_SPACE, false) == 4, "空白を除くと4字");
        check(WritingStats.countChars(" ", NO_SPACE, false) == 0, "改行なしのスペース（NBSP）も空白として除く");
        check(WritingStats.countChars(spaces, BODY, false) == 7, "ルビを除くモードでは、空白は数える（改行だけ除く）");

        // 空の文字列
        check(WritingStats.countChars("", ALL, true) == 0, "空の文字列は、全体でも0字");
        check(WritingStats.countChars("", NO_SPACE, true) == 0, "空の文字列は、空白を除くモードでも0字");
        check(WritingStats.countChars("", BODY, true) == 0, "空の文字列は、ルビを除くモードでも0字");

        // 数え方の名前
        check(WritingStats.CountMode.fromId("noSpace") == NO_SPACE, "設定の名前 noSpace は、空白・改行を除く");
        check(WritingStats.CountMode.fromId("body") == BODY, "設定の名前 body は、ルビを除く");
        check(WritingStats.CountMode.fromId("知らない名前") == ALL, "知らない名前は、全体にする");
        check(WritingStats.CountMode.fromId(null) == ALL, "名前がない（null）は、全体にする");
    }

    private static void selection() {
        Stub pane = new Stub("あ｜東京《とうきょう》い\nう", 1, 12);
        check(WritingStats.charCountText(pane, ALL, true).equals("14 字（全体）    選択 11 字"),
                "選んだ範囲（ルビの記法を含む）を、同じ数え方で数える: " + WritingStats.charCountText(pane, ALL, true));
        check(WritingStats.charCountText(pane, BODY, true).equals("5 字（ルビ・改行を除く）    選択 3 字"),
                "ルビを除くモードでは、全文と選択範囲の両方から読みを除く: " + WritingStats.charCountText(pane, BODY, true));

        Stub none = new Stub("あいう", 2, 2);
        check(WritingStats.charCountText(none, ALL, false).equals("3 字（全体）"), "選んでいないときは「選択」を出さない");

        Stub empty = new Stub("", 0, 0);
        check(WritingStats.charCountText(empty, NO_SPACE, false).equals("0 字（空白・改行を除く）"), "空の文書は 0 字");
    }

    /**
     * 速くした countChars・charCountText が、前の数え方（コードポイントを1つずつ調べる）と同じ数になるか。
     * 空白の種類・改行・サロゲートペア・対になっていないサロゲート・ルビを混ぜた文字列で比べる。
     */
    private static void sameAsBefore() {
        String[] samples = {
            "", "あ", "\n", "𠮷", "\uD842", "\uDFB7", "\uD842\uD842\uDFB7", "\uD842\uDFB7\uDFB7", "a\uD842",
            "あ い　う\tえ\nお\r\n", "\u00A0\u2003\u3000x", "｜東京《とう きょう》\n𠮷野家《よしのや》　が",
        };
        java.util.Random random = new java.util.Random(7);
        String pool = "あ漢字 　\n\t｜《》()（）𠮷\uD842\uDFB7\u00A0\u3099a";
        boolean all = true;
        for (int round = 0; round < 2000; round++) {
            String text;
            if (round < samples.length) {
                text = samples[round];
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = random.nextInt(30); i > 0; i--) {
                    sb.append(pool.charAt(random.nextInt(pool.length())));
                }
                text = sb.toString();
            }
            for (WritingStats.CountMode mode : WritingStats.CountMode.values()) {
                for (boolean ruby : new boolean[]{false, true}) {
                    if (WritingStats.countChars(text, mode, ruby) != countBefore(text, mode, ruby)) {
                        all = false;
                        System.out.println("     ちがう: " + mode + " " + ruby + " [" + text + "]");
                    }
                }
            }
            int cut = text.isEmpty() ? 0 : random.nextInt(text.length() + 1);
            Stub pane = new Stub(text, 0, cut);
            int total = countBefore(text, ALL, true);
            if (!WritingStats.charCountText(pane, ALL, true, total).equals(WritingStats.charCountText(pane, ALL, true))) {
                all = false;
                System.out.println("     ちがう（覚えた字数）: [" + text + "]");
            }
        }
        check(all, "速くした数え方は、前の数え方と同じ数になる（決まった例と、でたらめな 2000 通り）");
        check(WritingStats.charCountText(new Stub("あいう", 0, 2), ALL, false, 99).equals("99 字（全体）    選択 2 字"),
                "覚えた全体の字数をそのまま出し、選択だけを数える");
        check(WritingStats.charCountText(new Stub("あいう", 2, 9), ALL, false, 3).equals("3 字（全体）    選択 1 字"),
                "選択が文書からはみ出していたら、文書の終わりまでを数える");
    }

    /** 前の countChars（コードポイントを1つずつ調べる）。sameAsBefore で比べるための写し。 */
    private static int countBefore(String text, WritingStats.CountMode mode, boolean rubyNotation) {
        String body = mode == BODY && rubyNotation ? NarouRuby.strip(text) : text;
        int n = 0;
        for (int i = 0; i < body.length(); ) {
            int c = body.codePointAt(i);
            i += Character.charCount(c);
            boolean space = Character.isWhitespace(c) || Character.isSpaceChar(c);
            if (mode == ALL || mode == NO_SPACE && !space || mode == BODY && c != '\n') {
                n++;
            }
        }
        return n;
    }

    /** 既存の数え方（原稿用紙の枚数・日々の字数）は、今までどおり。 */
    private static void existing() {
        check(WritingStats.manuscriptSheets("") == 1, "原稿用紙：空は1枚");
        check(WritingStats.manuscriptSheets("あ".repeat(400)) == 1, "原稿用紙：400字は1枚");
        check(WritingStats.manuscriptSheets("あ".repeat(401)) == 2, "原稿用紙：401字は2枚");
        check(WritingStats.manuscriptSheets("𠮷".repeat(400) + "あ") == 2, "原稿用紙：𠮷が400字＋あは、コードポイントで401字と数えて2枚");
        check(WritingStats.manuscriptSheets("あ\n".repeat(19) + "い") == 1, "原稿用紙：段落ごとに行を改める（20行は1枚）");
    }

    /** 既存の count（日々の字数）は、改行を除いて、コードポイントで数える。画面の部品（縦書き）で確かめる。 */
    private static void existingPane() {
        VerticalTextPane pane = new VerticalTextPane(new Font(Font.SERIF, Font.PLAIN, 20));
        pane.loadText("あ\n𠮷い｜東京《とうきょう》");
        check(WritingStats.count(pane) == 5, "既存の count：改行とルビの読みを除いて、コードポイントで5字（あ 𠮷 い 東 京）");
    }

    /** 選んだ範囲と文字列だけを使うための、最小の相手。使わない約束は、何もしない。 */
    private static final class Stub implements Searchable {
        private final String text;
        private final int start;
        private final int end;

        Stub(String text, int start, int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }

        @Override public String getText() { return text; }
        @Override public int getSelectionStart() { return start; }
        @Override public int getSelectionEnd() { return end; }
        @Override public void setHighlights(List<EditorPane.Highlight> highlights) { }
        @Override public void reveal(int offset) { }
        @Override public void select(int start, int end) { }
        @Override public void replaceRange(String replacement, int start, int end) { }
        @Override public void replaceRanges(List<Replacement> replacements) { }
    }
}
