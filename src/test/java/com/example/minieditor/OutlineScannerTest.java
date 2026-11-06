package com.example.minieditor;

import java.util.List;

/**
 * 見出しの判定（OutlineScanner）のテスト。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.OutlineScannerTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class OutlineScannerTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        hits();
        misses();
        positions();
        order();
        carriageReturn();
        longTitle();
        empty();
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void hits() {
        List<OutlineScanner.Heading> headings = OutlineScanner.scan(
                "第一話　はじまり\n第12話\n第十二話\n　第三章\n第２話");
        check(headings.size() == 5, "5 つの見出しが当たる（件数 " + headings.size() + "）");
        if (headings.size() == 5) {
            check(headings.get(0).title().equals("第一話　はじまり"), "「第一話　はじまり」の title");
            check(headings.get(1).title().equals("第12話"), "「第12話」の title");
            check(headings.get(2).title().equals("第十二話"), "「第十二話」の title");
            check(headings.get(3).title().equals("第三章"), "「　第三章」は前の空白を除いた title");
            check(headings.get(4).title().equals("第２話"), "「第２話」（全角数字）の title");
        }
    }

    private static void misses() {
        check(OutlineScanner.scan("本文の第一話の話").isEmpty(), "行の途中の「第一話」は当たらない");
        check(OutlineScanner.scan("第").isEmpty(), "「第」だけは当たらない");
        check(OutlineScanner.scan("第一").isEmpty(), "「第一」（話・章がない）は当たらない");
        check(OutlineScanner.scan("第一節").isEmpty(), "「第一節」は対象外");
        check(OutlineScanner.scan("\n\n").isEmpty(), "空行は当たらない");
    }

    private static void positions() {
        String text = "はじめに\n第一話　はじまり\n本文です。\n第二話\n";
        List<OutlineScanner.Heading> headings = OutlineScanner.scan(text);
        check(headings.size() == 2, "位置の確認用の見出しが 2 つ当たる");
        if (headings.size() == 2) {
            OutlineScanner.Heading first = headings.get(0);
            OutlineScanner.Heading second = headings.get(1);
            check(first.line() == 2 && second.line() == 4, "line は 1 から数えた行番号");
            check(text.substring(first.start(), first.end()).equals("第一話　はじまり"), "start・end で本文の該当の行を取れる");
            check(text.substring(second.start(), second.end()).equals("第二話"), "2 つ目の start・end も該当の行と一致");
        }
    }

    private static void order() {
        List<OutlineScanner.Heading> headings = OutlineScanner.scan("第三章\n途中\n第一章\n第二章");
        check(headings.size() == 3, "3 つの見出しが当たる");
        if (headings.size() == 3) {
            check(headings.get(0).title().equals("第三章") && headings.get(1).title().equals("第一章")
                    && headings.get(2).title().equals("第二章"), "見出しは本文の順番どおりに並ぶ");
        }
    }

    private static void carriageReturn() {
        String text = "第一話\r\n本文\r\n";
        List<OutlineScanner.Heading> headings = OutlineScanner.scan(text);
        check(headings.size() == 1, "行末に \\r があっても見出しが当たる");
        if (headings.size() == 1) {
            OutlineScanner.Heading heading = headings.get(0);
            check(heading.end() == 3 && text.charAt(heading.end()) == '\r', "end は \\r の手前");
            check(heading.title().equals("第一話"), "title に \\r は入らない");
        }
    }

    private static void longTitle() {
        String title = "第一話" + "あ".repeat(60);
        List<OutlineScanner.Heading> headings = OutlineScanner.scan(title);
        check(headings.size() == 1, "長い見出しも当たる");
        if (headings.size() == 1) {
            check(headings.get(0).title().equals(title.substring(0, 60) + "…"), "60 文字を超えると 60 文字に切って「…」を付ける");
            check(headings.get(0).end() == title.length(), "切っても end は行の全部");
        }
        String exact = "第一話" + "あ".repeat(57);
        check(OutlineScanner.scan(exact).get(0).title().equals(exact), "ちょうど 60 文字は切らない");
    }

    private static void empty() {
        check(OutlineScanner.scan("").isEmpty(), "空の本文は空の一覧");
    }
}
