package com.example.minieditor;

import java.util.List;

/**
 * 検索・置換の一致を探す処理（FindMatcher）のテスト。画面は使わない。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.FindMatcherTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class FindMatcherTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    /** 本文の全部を探す（範囲は文書全体）。 */
    private static FindMatcher.Result all(String text, String keyword, String replacement, boolean regex, boolean caseSensitive) {
        return FindMatcher.find(text, keyword, replacement, regex, caseSensitive, 0, text.length());
    }

    public static void main(String[] args) {
        plain();
        regex();
        scope();
        errors();
        timeLimit();
        ruby();
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void plain() {
        FindMatcher.Result r = all("ab AB aB", "Ab", "", false, false);
        check(r.error() == null && r.hits().size() == 3, "通常モードで、大文字小文字を区別せずに 3 件見つかる");

        r = all("axb a.b", "a.b", "", false, true);
        check(r.hits().size() == 1 && r.hits().get(0).start() == 4, "通常モードで、. は文字として扱う（axb には当たらない）");

        r = all("a$b", "a$b", "$1", false, true);
        check(r.hits().size() == 1 && r.hits().get(0).replacement().equals("$1"), "通常モードで、置換の $1 はそのまま入る");

        r = all("a\\b", "a\\b", "x\\y", false, true);
        check(r.hits().size() == 1 && r.hits().get(0).replacement().equals("x\\y"), "通常モードで、置換の \\ はそのまま入る");

        r = all("x", "", "", false, true);
        check(r.hits().isEmpty() && r.error() == null, "空の keyword は、一致なし・error なし");

        r = all("İİ ab", "AB", "", false, false);
        check(r.error() == null && r.hits().size() == 1, "İ（U+0130）を含む本文でも、一致が見つかる");
        check(r.hits().size() == 1 && r.hits().get(0).start() == 3 && r.hits().get(0).end() == 5,
                "İ を含む本文でも、位置と長さが本文のまま（3〜5）");
    }

    private static void regex() {
        FindMatcher.Result r = all("1 22 333", "\\d+", "", true, true);
        check(r.hits().size() == 3, "正規表現で、\\d+ が全部見つかる");

        r = all("令和2年と、平成30年", "(\\d+)年", "$1 年", true, true);
        check(r.hits().size() == 2 && r.hits().get(0).replacement().equals("2 年")
                && r.hits().get(1).replacement().equals("30 年"), "正規表現で、$1 の置換が効く");

        r = all("a1b22", "\\d+", "[$0]", true, true);
        check(r.hits().size() == 2 && r.hits().get(0).replacement().equals("[1]")
                && r.hits().get(1).replacement().equals("[22]"), "複数の一致で、置換後の文字列はそれぞれの一致の分だけ");

        r = all("abc", "x*", "y", true, true);
        check(r.hits().isEmpty() && r.error() == null, "正規表現の空の一致（x*）は飛ばす。error は null");
    }

    private static void scope() {
        FindMatcher.Result r = FindMatcher.find("abcabc", "a", "", false, true, 1, 4);
        check(r.hits().size() == 1 && r.hits().get(0).start() == 3, "範囲（from, to）の中だけを探す");

        r = FindMatcher.find("abcabc", "c", "", false, true, 0, 2);
        check(r.hits().isEmpty(), "範囲の外の文字には当たらない");

        r = FindMatcher.find("ab", "^b", "", true, true, 1, 2);
        check(r.hits().isEmpty(), "範囲の始まりで ^ は効かない（行頭扱いしない）");

        r = FindMatcher.find("ab", "(?<=a)b", "", true, true, 1, 2);
        check(r.hits().size() == 1 && r.hits().get(0).start() == 1, "範囲の外の a を、後読みで見られる");
    }

    private static void errors() {
        FindMatcher.Result r = all("abc", "(", "", true, true);
        check(r.error() != null && r.hits().isEmpty(), "不正な正規表現は error が入り、一致は空");
        check("正規表現が正しくありません".equals(r.error()), "不正な正規表現の文は「正規表現が正しくありません」");

        r = all("abc", "(a)", "$9", true, true);
        check(r.error() != null && r.hits().isEmpty(), "グループが無い置換（$9）は error が入る");
        check("置換文字列が正しくありません".equals(r.error()), "不正な置換の文は「置換文字列が正しくありません」");
    }

    private static void timeLimit() {
        String text = "a".repeat(40);
        long began = System.nanoTime();
        FindMatcher.Result r = all(text, "(.*a){16}b", "", true, true);
        long seconds = (System.nanoTime() - began) / 1_000_000_000L;
        check("時間がかかりすぎたので、止めました".equals(r.error()) && r.hits().isEmpty(),
                "壊れやすい正規表現（(.*a){16}b）は、時間切れの文で止まる（" + seconds + " 秒）");
        check(seconds <= 10, "時間切れは 10 秒以内に起きる");
    }

    private static void ruby() {
        check(FindMatcher.touchesRuby("東｜京", ""), "一致に ｜ を含むと、ルビをまたぐ");
        check(FindMatcher.touchesRuby("東京", "東《とう》京"), "置換に《を含むと、ルビをまたぐ");
        check(!FindMatcher.touchesRuby("東京", "大阪"), "ルビの記号を含まなければ、またがない");

        FindMatcher.Result r = all("東京", "東", "", false, true);
        check(r.hits().size() == 1 && r.hits().get(0).replacement().isEmpty(), "置換が空の一致も見つかる");
    }
}
