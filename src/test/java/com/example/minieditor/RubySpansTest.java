package com.example.minieditor;

import java.util.List;

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

    public static void main(String[] args) {
        bar();
        noBar();
        brokenAndNewline();
        order();
        empty();
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
