package com.example.minieditor;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * 横書きの画面用のルビの位置（RubySpans）のテスト。読み方が NarouRuby.parse と同じになることも確かめる。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -Djava.awt.headless=true -cp target/classes:target/test-classes com.example.minieditor.RubySpansTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class RubySpansTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        bar();
        noBar();
        brokenAndNewline();
        order();
        empty();
        SwingUtilities.invokeAndWait(RubySpansTest::painting);     // 画面の部品は、画面のスレッドで動かす
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** ｜東京《とうきょう》：親文字・ふりがな・記法の範囲が、文字の位置どおりか。 */
    private static void bar() {
        String text = "今日は｜東京《とうきょう》へ行く。";
        List<RubySpans.Span> spans = RubySpans.find(text);
        check(spans.size() == 1, "｜東京《とうきょう》が1つ見つかる");
        if (spans.size() == 1) {
            RubySpans.Span s = spans.get(0);
            check(s.baseStart() == 4 && s.baseEnd() == 6, "親文字は「東京」の位置（4〜6）");
            check(s.reading().equals("とうきょう"), "ふりがなは「とうきょう」");
            check(s.markupStart() == 3 && s.markupEnd() == 13, "記法の全体は「｜」から「》」まで（3〜13）");
            check(text.substring(s.markupStart(), s.markupEnd()).equals("｜東京《とうきょう》"), "記法の範囲の文字が正しい");
        }
        check(RubySpans.find("｜東京(とうきょう)").size() == 1, "かっこ（半角）のふりがなも見つかる");
        check(RubySpans.find("｜東京（とうきょう）").size() == 1, "かっこ（全角）のふりがなも見つかる");
        check(RubySpans.find("|東京《とうきょう》").size() == 1, "半角の｜でも見つかる");
        check(RubySpans.find("｜《そのまま》").isEmpty(), "｜《》は「ルビにしない」の打ち消しで、見つからない");
    }

    /** 記号の無い「東京《とうきょう》」は、NarouRuby と同じく親文字になる。 */
    private static void noBar() {
        String text = "そして東京《とうきょう》へ。";
        List<RubySpans.Span> spans = RubySpans.find(text);
        check(spans.size() == 1, "記号の無い「東京《とうきょう》」も1つ見つかる");
        if (spans.size() == 1) {
            RubySpans.Span s = spans.get(0);
            check(s.baseStart() == 3 && s.baseEnd() == 5, "記号が無いときの親文字は、直前の漢字の並び（3〜5）");
            check(s.markupStart() == 3 && s.markupEnd() == 12, "記号が無いときも、記法の範囲は親文字の頭から《》まで");
        }
        check(RubySpans.find("（注）の文").isEmpty(), "ひらがな・カタカナ以外のかっこは、ルビにしない");
        check(RubySpans.find("東京（とうきょう）").size() == 1, "記号が無い全角かっこも、NarouRuby と同じく親文字になる");
        check(sameAsParser("そして東京《とうきょう》へ。"), "記号の無いものは NarouRuby.parse と同じ結果");
        check(sameAsParser("今日は｜東京《とうきょう》と、大阪(おおさか)と、京都《きょうと》。"),
                "混ざった文章も NarouRuby.parse と同じ結果");
    }

    /** 閉じの《》が無いもの、改行をまたぐものは見つからない。 */
    private static void brokenAndNewline() {
        check(RubySpans.find("｜東京《とうきょう").isEmpty(), "閉じの《》が無いものは見つからない");
        check(RubySpans.find("東京《とうきょう").isEmpty(), "閉じの《》が無い記号なしのものも見つからない");
        check(RubySpans.find("｜東京《とう\nきょう》").isEmpty(), "ふりがなの中で改行をまたぐものは見つからない");
        List<RubySpans.Span> afterBreak = RubySpans.find("｜東\n京《とうきょう》");
        check(afterBreak.size() == 1 && afterBreak.get(0).baseStart() == 3 && afterBreak.get(0).baseEnd() == 4,
                "｜の後で改行があれば、改行の後の「京」だけが親文字になる（NarouRuby と同じ）");
        check(sameAsParser("｜東\n京《とうきょう》"), "改行を挟んだ形も NarouRuby.parse と同じ結果");
        check(sameAsParser("｜東京《とうきょう\n》"), "改行をまたぐものも NarouRuby.parse と同じく見つからない");
    }

    /** 同じ文書に2つ以上あるとき、先頭から順に並ぶ。 */
    private static void order() {
        String text = "｜一《いち》と漢字《かんじ》と｜二《に》。";
        List<RubySpans.Span> spans = RubySpans.find(text);
        check(spans.size() == 3, "3つのルビが見つかる");
        if (spans.size() == 3) {
            check(spans.get(0).reading().equals("いち") && spans.get(1).reading().equals("かんじ")
                    && spans.get(2).reading().equals("に"), "ふりがなが、本文の順に並ぶ");
            check(spans.get(0).markupStart() < spans.get(1).markupStart()
                    && spans.get(1).markupStart() < spans.get(2).markupStart(), "記法の位置が、先頭から増えていく");
        }
    }

    /** 空の本文、ルビの無い文章は、空の一覧。 */
    private static void empty() {
        check(RubySpans.find("").isEmpty(), "空の本文は空の一覧");
        check(RubySpans.find("ルビの無い文章です。ふつうの（注）も、ただの括弧もない。").isEmpty(),
                "ルビの無い文章は空の一覧");
    }

    /**
     * 横書きの「ルビを上に表示」の描き方（TextEditorPane.paintRuby）。画像に描いて確かめる。
     * 記法（｜と《…》）だけが行の地の色で覆われ、親文字は見えたまま。描き直す範囲を絞って描いても、全体を描いたときと同じ絵になる。
     */
    private static void painting() {
        boolean before = TextEditorPane.rubyAbove;
        TextEditorPane.rubyAbove = true;
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                sb.append("今日は｜東京《とうきょう》へ行く。漢字《かんじ》と｜山田太郎《やまだたろう》、大阪(おおさか)。\n");
            }
            TextEditorPane pane = new TextEditorPane(new Font(Font.SERIF, Font.PLAIN, 20));
            pane.loadText(sb.toString());
            JTextArea area = area(pane);
            pane.setSize(900, 500);
            layout(pane);
            area.setCaretPosition(0);
            BufferedImage full = draw(area, null);
            Rectangle visible = area.getVisibleRect();     // 画面に見えている所（描き直す範囲は、いつもこの中）
            int width = visible.width;
            int height = visible.height;

            // 前後を空の行にした「｜東京《とうきょう》」：｜ と 《とうきょう》 は地の色だけ、東京 には字があり、上の行にふりがながある
            TextEditorPane single = new TextEditorPane(new Font(Font.SERIF, Font.PLAIN, 20));
            single.loadText("\n｜東京《とうきょう》へ。\n\n");
            JTextArea one = area(single);
            single.setSize(600, 200);
            layout(single);
            one.setCaretPosition(0);
            BufferedImage image = draw(one, null);
            Rectangle bar = rect(one, 1);
            Rectangle base = rect(one, 2);          // 東
            Rectangle open = rect(one, 4);          // 《
            Rectangle after = rect(one, 11);        // 》の次（へ）
            check(plain(image, bar.x, base.x, bar.y, bar.height), "記法の ｜ は、行の地の色で覆われる");
            check(plain(image, open.x, after.x, bar.y, bar.height), "記法の 《とうきょう》 は、行の地の色で覆われる");
            check(!plain(image, base.x, open.x, bar.y, bar.height), "親文字の「東京」は、覆われずに見える");
            check(!plain(image, base.x, open.x, bar.y - bar.height / 2, bar.height / 2), "ふりがなが、親文字の上（1つ上の行の下の部分）に描かれる");
            check(plain(image, after.x + 2 * bar.height, after.x + 4 * bar.height, bar.y - bar.height / 2, bar.height / 2),
                    "ふりがなは、親文字から離れた所には描かれない");

            // 選択・検索の色とカーソルは、覆った記法の上でも消えない
            single.select(2, 8);                    // 「京《とう」まで選ぶ
            BufferedImage selected = draw(one, null);
            check(colorAt(selected, open, one.getSelectionColor()), "選んだ範囲の色は、覆った 《 の上でも消えない");
            check(!plain(selected, base.x, open.x, bar.y, bar.height), "選んでいても、親文字は見える");
            one.setCaretPosition(0);
            java.awt.Color searchColor = new java.awt.Color(0xB08A20);
            single.setHighlights(List.of(new EditorPane.Highlight(4, 11, searchColor)));
            check(colorAt(draw(one, null), rect(one, 6), searchColor), "検索の色は、覆った 《とうきょう》 の上でも消えない");
            single.setHighlights(List.of());
            one.setCaretPosition(6);
            one.getCaret().setVisible(true);
            BufferedImage withCaret = draw(one, null);
            one.getCaret().setVisible(false);
            Rectangle caret = rect(one, 6);
            check(!plain(withCaret, caret.x, caret.x + 2, caret.y, caret.height), "カーソルは、覆った記法の上でも見える");

            // 折り返し：親文字が行末で分かれても、親文字は全部見え、記法は全部覆われる
            String wrapped = "\nあいうえおかきくけこさしすせそ｜山田太郎《やまだたろう》たちつ。\n\n";
            int markupStart = wrapped.indexOf('｜');
            boolean wrapOk = true;
            for (int size = 360; size <= 620 && wrapOk; size += 10) {
                TextEditorPane wrap = new TextEditorPane(new Font(Font.SERIF, Font.PLAIN, 20));
                wrap.loadText(wrapped);
                wrap.setWrap(true);
                JTextArea w = area(wrap);
                wrap.setSize(size, 200);
                layout(wrap);
                layout(wrap);
                w.setCaretPosition(0);
                BufferedImage img = draw(w, null);
                for (int p = markupStart; p < markupStart + 13 && wrapOk; p++) {
                    boolean isBase = p >= markupStart + 1 && p < markupStart + 5;
                    if (cellPlain(w, img, p) == isBase) {
                        wrapOk = false;
                        System.out.println("     幅 " + size + " の " + (p - markupStart) + " 字目（" + wrapped.charAt(p) + "）がちがう");
                    }
                }
            }
            check(wrapOk, "折り返しで親文字が分かれても、親文字は見え、記法は覆われる（幅 360〜620）");

            // 描き直す範囲を絞っても（カーソルの点滅など）、その範囲は全体を描いたときと同じ
            boolean same = true;
            java.util.Random random = new java.util.Random(3);
            for (int i = 0; i < 200 && same; i++) {
                int x = random.nextInt(width - 10);
                int y = random.nextInt(height - 10);
                Rectangle clip = new Rectangle(x, y, 2 + random.nextInt(Math.min(300, width - x - 1)),
                        2 + random.nextInt(Math.min(120, height - y - 1)));
                same = sameInside(full, draw(area, clip), clip);
                if (!same) {
                    System.out.println("     ちがう範囲: " + clip);
                }
            }
            check(same, "描き直す範囲を絞って描いても、その範囲は全体を描いたときと同じ（でたらめな 200 か所）");
        } catch (Exception ex) {
            check(false, "画像に描いて確かめられませんでした: " + ex);
        } finally {
            TextEditorPane.rubyAbove = before;
        }
    }

    private static JTextArea area(TextEditorPane pane) throws ReflectiveOperationException {
        java.lang.reflect.Field field = TextEditorPane.class.getDeclaredField("area");
        field.setAccessible(true);
        return (JTextArea) field.get(pane);
    }

    /** 画面に出さずに、部品の並びと大きさを決める。 */
    private static void layout(java.awt.Component c) {
        if (c instanceof java.awt.Container container) {
            container.doLayout();
            for (java.awt.Component child : container.getComponents()) {
                layout(child);
            }
        }
    }

    private static Rectangle rect(JTextArea area, int offset) throws javax.swing.text.BadLocationException {
        return area.modelToView2D(offset).getBounds();
    }

    /** 文字の部品を、画像に描く（clip があれば、その範囲だけを描き直す）。 */
    private static BufferedImage draw(JTextArea area, Rectangle clip) {
        BufferedImage image = new BufferedImage(area.getWidth(), Math.min(area.getHeight(), 500), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        if (clip != null) {
            g.setClip(clip);
        }
        area.paint(g);
        g.dispose();
        return image;
    }

    /** x0〜x1・y〜y+h の四角の中が、1色だけ（字が描かれていない）か。 */
    private static boolean plain(BufferedImage image, int x0, int x1, int y, int h) {
        int color = image.getRGB(x0, y);
        for (int x = x0; x < x1; x++) {
            for (int yy = Math.max(0, y); yy < y + h; yy++) {
                if (image.getRGB(x, yy) != color) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 四角 r の真ん中の点が color か。 */
    private static boolean colorAt(BufferedImage image, Rectangle r, java.awt.Color color) {
        return image.getRGB(r.x + 4, r.y + r.height / 2) == color.getRGB();
    }

    /** p の字の升（次の字の左端まで。行の終わりの字は字の幅）が、1色だけか。 */
    private static boolean cellPlain(JTextArea area, BufferedImage image, int p) throws javax.swing.text.BadLocationException {
        Rectangle r = rect(area, p);
        Rectangle next = rect(area, p + 1);
        int right = next.y == r.y ? next.x : r.x + area.getFontMetrics(area.getFont()).stringWidth(area.getText(p, 1));
        return plain(image, r.x, right, r.y, r.height);
    }

    private static boolean sameInside(BufferedImage a, BufferedImage b, Rectangle clip) {
        for (int x = clip.x; x < clip.x + clip.width; x++) {
            for (int y = clip.y; y < clip.y + clip.height; y++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** RubySpans の結果が、NarouRuby.parse と同じ範囲・ふりがなになっているか。 */
    private static boolean sameAsParser(String text) {
        List<NarouRuby.Span> parsed = NarouRuby.parse(text);
        List<RubySpans.Span> spans = RubySpans.find(text);
        int count = 0;
        for (NarouRuby.Span p : parsed) {
            if (text.substring(p.start(), p.end()).indexOf('\n') >= 0) {
                continue;
            }
            RubySpans.Span s = spans.get(count++);
            if (s.baseStart() != p.baseStart() || s.baseEnd() != p.baseEnd()
                    || s.markupStart() != p.start() || s.markupEnd() != p.end()
                    || !s.reading().equals(text.substring(p.rubyStart(), p.rubyEnd()))) {
                return false;
            }
        }
        return count == spans.size();
    }
}
