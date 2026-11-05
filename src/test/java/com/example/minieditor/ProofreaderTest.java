package com.example.minieditor;

import java.util.List;

import com.example.minieditor.Proofreader.Category;
import com.example.minieditor.Proofreader.Issue;

/**
 * 校正（Proofreader）の記号の規則のテスト。半角のピリオド・ハイフン・全角のマイナスの連続を見る。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.ProofreaderTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class ProofreaderTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        periods();
        hyphens();
        existingRules();
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    // 記号の項目（SYMBOL）だけを取り出す
    private static List<Issue> symbols(String text) {
        return Proofreader.check(text).stream()
                .filter(issue -> issue.category() == Category.SYMBOL)
                .toList();
    }

    private static void periods() {
        String text = "待って...";
        List<Issue> found = symbols(text);
        check(found.size() == 1, "「待って...」は半角の3つのピリオドを1件にする");
        check(found.size() == 1 && "……".equals(found.get(0).fix()), "「...」の直し方は「……」");
        check(found.size() == 1 && text.substring(found.get(0).start(), found.get(0).end()).equals("..."),
                "「...」の範囲はピリオドの全部");

        found = symbols("..");
        check(found.size() == 1 && "……".equals(found.get(0).fix()), "「..」（2つ）も1件で、直し方は「……」");

        text = "......";
        found = symbols(text);
        check(found.size() == 1 && text.substring(found.get(0).start(), found.get(0).end()).equals(text),
                "「......」（6つ）は1件で、範囲は6字の全部");
    }

    private static void hyphens() {
        List<Issue> found = symbols("--");
        check(found.size() == 1 && "――".equals(found.get(0).fix()), "「--」は1件で、直し方は「――」");

        String text = "－－";
        found = symbols(text);
        check(found.size() == 1 && "――".equals(found.get(0).fix()), "全角の「－－」は1件で、直し方は「――」");
        check(found.size() == 1 && text.substring(found.get(0).start(), found.get(0).end()).equals(text),
                "全角の「－－」の範囲は2字の全部");

        found = symbols("a---b");
        check(found.size() == 1 && "a---b".substring(found.get(0).start(), found.get(0).end()).equals("---"),
                "「a---b」の3つのハイフンは1件で、範囲は3字");

        check(symbols("――").isEmpty(), "既に「――」なら指摘しない");
        check(symbols("ーー").isEmpty(), "長音記号「ーー」は指摘しない");
        check(symbols("-").isEmpty(), "単独の半角ハイフンは指摘しない");
        check(symbols("－").isEmpty(), "単独の全角マイナスは指摘しない");
        check(symbols("日本-語").isEmpty(), "単語の中の1つのハイフンは指摘しない");
        check(symbols("1.5").isEmpty(), "数字の間の1つのピリオドは指摘しない");
        check(symbols("終わり.").isEmpty(), "文末の1つのピリオドは指摘しない");
        check(symbols(".").isEmpty(), "単独のピリオドは指摘しない");
    }

    // 既存の規則と重ならないことを確かめる
    private static void existingRules() {
        List<Issue> found = symbols("…");
        check(found.size() == 1 && found.get(0).message().startsWith("三点リーダーは2つ続けて"),
                "「…」は既存の規則の1件だけ（新しい規則では指摘しない）");
        check(symbols("……").isEmpty(), "既に「……」なら指摘しない");

        found = symbols("・・・");
        check(found.size() == 1 && "……".equals(found.get(0).fix()),
                "「・・・」は既存の規則の1件だけ（新しい規則で増えない）");

        found = symbols("待って…");
        check(found.size() == 1 && "……".equals(found.get(0).fix()),
                "「待って…」は既存の規則の1件だけ");
    }
}
