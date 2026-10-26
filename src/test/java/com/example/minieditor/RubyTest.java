package com.example.minieditor;

import java.awt.Font;
import java.util.List;

import javax.swing.SwingUtilities;

/**
 * ルビ（NarouRuby と、縦書きの VerticalTextPane）のテスト。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.RubyTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class RubyTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        notation();
        SwingUtilities.invokeAndWait(RubyTest::vertical);   // 画面の部品は、画面のスレッドで動かす
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void notation() {
        check(NarouRuby.fits("東京", "とうきょう"), "ふつうのルビは記法にできる");
        check(NarouRuby.fits("一二三四五六七八九十", "かず"), "親文字 10 文字までは記法にできる");
        check(!NarouRuby.fits("一二三四五六七八九十壱", "かず"), "親文字 11 文字は記法にしない");
        check(!NarouRuby.fits("𠮷𠮷𠮷𠮷𠮷𠮷", "よし"), "2つぶんの字は 2 文字と数える（読み取りと同じ）");
        check(!NarouRuby.fits("a｜b", "x"), "｜を含む親文字は記法にしない");
        check(!NarouRuby.fits("字", "か（な"), "かっこを含むふりがなは記法にしない");

        String text = "一二三四五六七八九十壱と、a｜bと、東京";
        List<VerticalLayout.RubyRun> runs = List.of(
                new VerticalLayout.RubyRun(0, 11, "かず"),
                new VerticalLayout.RubyRun(13, 16, "x"),
                new VerticalLayout.RubyRun(18, 20, "とうきょう"));
        String saved = NarouRuby.write(text, runs, 0, text.length());
        NarouRuby.Parsed back = NarouRuby.read(saved);
        check(back.text().equals(text), "読み戻せないルビは外して保存し、本文は変わらない");
        check(back.rubies().size() == 1 && back.rubies().get(0).ruby().equals("とうきょう"), "読み戻せるルビは残る");
    }

    private static void vertical() {
        VerticalTextPane pane = new VerticalTextPane(new Font(Font.SERIF, Font.PLAIN, 20));
        pane.loadText("｜東京《とうきょう》へ行く。｜大阪《おおさか》へ行く。");
        String text = pane.getText();
        int first = text.indexOf("行く");
        int second = text.indexOf("行く", first + 1);
        pane.replaceRanges(List.of(new Searchable.Replacement(first, first + 2, "向かう"),
                new Searchable.Replacement(second, second + 2, "向かう")));
        check(pane.toPlainText().equals("｜東京《とうきょう》へ向かう。｜大阪《おおさか》へ向かう。"),
                "縦書きで「すべて置換」しても、ほかのルビは残る");

        pane.loadText("｜一二三四五六七八九《かず》");
        pane.replaceRange("十", 1, 1);
        check(pane.toPlainText().equals("｜一十二三四五六七八九《かず》"), "親文字の途中に打つと、10 文字までは親文字が伸びる");
        pane.replaceRange("壱", 1, 1);
        NarouRuby.Parsed back = NarouRuby.read(pane.toPlainText());
        check(back.text().equals("一壱十二三四五六七八九"), "11 文字目を打っても、保存して開き直すと本文は変わらない");
        check(!pane.toPlainText().contains("｜一壱"), "11 文字目は親文字に入れない");
    }
}
