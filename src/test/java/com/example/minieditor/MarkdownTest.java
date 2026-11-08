package com.example.minieditor;

/**
 * Markdown の読み取り（Markdown.analyze）のテスト。画面は使わない。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -Djava.awt.headless=true -cp target/classes:target/test-classes com.example.minieditor.MarkdownTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class MarkdownTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        headings();
        emphasis();
        code();
        quoteListRule();
        checklist();
        table();
        links();
        japanese();
        shape();
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** text の中の part（最初に見つかった所）の全部の字が、bits をすべて持つか。 */
    private static boolean has(Markdown.Result r, String text, String part, int bits) {
        int at = text.indexOf(part);
        if (at < 0) {
            return false;
        }
        for (int i = at; i < at + part.length(); i++) {
            if ((r.flags[i] & bits) != bits) {
                return false;
            }
        }
        return true;
    }

    /** part の字のどれもが、bits を持たないか。 */
    private static boolean lacks(Markdown.Result r, String text, String part, int bits) {
        int at = text.indexOf(part);
        if (at < 0) {
            return false;
        }
        for (int i = at; i < at + part.length(); i++) {
            if ((r.flags[i] & bits) != 0) {
                return false;
            }
        }
        return true;
    }

    private static void headings() {
        String t = "# 題\n## 小見出し ##\n####### 七つ\n#なし\n";
        Markdown.Result r = Markdown.analyze(t);
        check(r.kind[0] == Markdown.Kind.HEADING && r.level[0] == 1, "「# 題」は見出し1");
        check(r.flags[0] == Markdown.MARK && has(r, t, "題", Markdown.HEAD), "# は記号、題は見出しの字");
        check(r.kind[1] == Markdown.Kind.HEADING && r.level[1] == 2, "「## ...」は見出し2");
        int closing = t.indexOf("小見出し ##") + 5;
        check(r.flags[closing] == Markdown.MARK && r.flags[closing + 1] == Markdown.MARK && has(r, t, "小見出し", Markdown.HEAD),
                "終わりの ## も記号。字は見出し");
        check(r.kind[2] == Markdown.Kind.NORMAL, "# が7つは見出しではない");
        check(r.kind[3] == Markdown.Kind.NORMAL, "「#」のあとに空白がなければ見出しではない");
    }

    private static void emphasis() {
        String t = "ふつう **太字** と *斜体* と ~~消す~~ と ***両方***\n";
        Markdown.Result r = Markdown.analyze(t);
        check(has(r, t, "太字", Markdown.BOLD) && lacks(r, t, "太字", Markdown.ITALIC), "** は太字");
        check(has(r, t, "斜体", Markdown.ITALIC) && lacks(r, t, "斜体", Markdown.BOLD), "* は斜体");
        check(has(r, t, "消す", Markdown.STRIKE), "~~ は取り消し線");
        check(has(r, t, "両方", Markdown.BOLD | Markdown.ITALIC), "*** は太字と斜体");
        check(has(r, t, "**", Markdown.MARK), "記号は MARK");
        check(lacks(r, t, "ふつう", Markdown.BOLD | Markdown.ITALIC | Markdown.MARK), "ふつうの字には印が付かない");

        String nested = "**太字の中の *斜体* です**\n";
        Markdown.Result n = Markdown.analyze(nested);
        check(has(n, nested, "斜体", Markdown.BOLD | Markdown.ITALIC) && has(n, nested, "太字の中の", Markdown.BOLD),
                "入れ子：太字の中の斜体");

        String none = "2 * 3 * 4 と snake_case_name と ** 空白 ** と *閉じない\n";
        Markdown.Result z = Markdown.analyze(none);
        check(z.flags[none.indexOf('*')] == 0, "「2 * 3」は強調にしない");
        check(lacks(z, none, "snake_case_name", Markdown.ITALIC | Markdown.MARK), "単語の中の _ は斜体にしない");
        check(lacks(z, none, "** 空白 **", Markdown.BOLD), "記号の内側が空白なら強調にしない");
        check(lacks(z, none, "*閉じない", Markdown.ITALIC), "閉じない * は強調にしない");

        String escaped = "\\*字\\* と \\\\\n";
        Markdown.Result e = Markdown.analyze(escaped);
        check(has(e, escaped, "\\", Markdown.MARK) && lacks(e, escaped, "字", Markdown.ITALIC | Markdown.MARK),
                "\\* は字として使う（\\ は記号、* は強調にならない）");
    }

    private static void code() {
        String t = "文 `a **b** c` 文 ``x ` y``\n```java\n# 見出しではない **x**\n```\nあと **太**\n";
        Markdown.Result r = Markdown.analyze(t);
        check(has(r, t, "a **b** c", Markdown.CODE) && lacks(r, t, "a **b** c", Markdown.BOLD),
                "インラインコードの中は、強調を読まない");
        check(has(r, t, "x ` y", Markdown.CODE), "`` で囲めば、中に ` を入れられる");
        check(r.kind[1] == Markdown.Kind.FENCE && r.kind[2] == Markdown.Kind.CODE && r.kind[3] == Markdown.Kind.FENCE,
                "``` で囲んだ行は、コードブロック");
        check(r.flags[t.indexOf("# 見出し")] == Markdown.CODE, "コードブロックの中は、見出しも強調も読まない");
        check(r.kind[4] == Markdown.Kind.NORMAL && has(r, t, "太", Markdown.BOLD), "ブロックを閉じたあとは、ふつうに戻る");

        String open = "```\nずっとコード\n";
        Markdown.Result o = Markdown.analyze(open);
        check(o.kind[1] == Markdown.Kind.CODE, "閉じていないブロックは、終わりまでコード");

        String tilde = "~~~\n中\n~~~\n";
        Markdown.Result tl = Markdown.analyze(tilde);
        check(tl.kind[0] == Markdown.Kind.FENCE && tl.kind[1] == Markdown.Kind.CODE && tl.kind[2] == Markdown.Kind.FENCE,
                "~~~ も区切りになる");
    }

    private static void quoteListRule() {
        String t = "> 引用 **太**\n>> 深い\n- 箇条\n  * 入れ子\n1. 番号\n10) 括弧\n---\n* * *\n-ハイフンだけ\n";
        Markdown.Result r = Markdown.analyze(t);
        check(r.kind[0] == Markdown.Kind.QUOTE && r.level[0] == 1 && has(r, t, ">", Markdown.MARK), "> は引用");
        check(has(r, t, "太", Markdown.BOLD), "引用の中の強調も読む");
        check(r.kind[1] == Markdown.Kind.QUOTE && r.level[1] == 2, ">> は深さ2の引用");
        check(r.kind[2] == Markdown.Kind.LIST && has(r, t, "-", Markdown.BULLET), "- は箇条書き");
        check(r.kind[3] == Markdown.Kind.LIST, "字下げした * も箇条書き（入れ子）");
        check(r.kind[4] == Markdown.Kind.LIST && has(r, t, "1.", Markdown.BULLET), "1. は番号付き");
        check(r.kind[5] == Markdown.Kind.LIST, "10) も番号付き");
        check(r.kind[6] == Markdown.Kind.RULE && r.kind[7] == Markdown.Kind.RULE, "--- と * * * は区切り線");
        check(r.kind[8] == Markdown.Kind.NORMAL, "「-ハイフンだけ」は箇条書きでも区切り線でもない");
    }

    private static void checklist() {
        String t = "- [ ] 未\n- [x] 済 **太**\n- [X] 大文字\n- [] 違う\n- [xx] 違う2\n";
        Markdown.Result r = Markdown.analyze(t);
        check(has(r, t, "[ ]", Markdown.CHECK) && lacks(r, t, "未", Markdown.DONE), "- [ ] は未完了");
        check(has(r, t, "[x]", Markdown.CHECK) && has(r, t, "済 ", Markdown.DONE) && has(r, t, "太", Markdown.BOLD | Markdown.DONE),
                "- [x] は済み（字は DONE。中の強調も読む）");
        check(has(r, t, "大文字", Markdown.DONE), "[X] も済み");
        check(lacks(r, t, "[] 違う", Markdown.CHECK), "[] はチェックではない");
        check(lacks(r, t, "[xx]", Markdown.CHECK), "[xx] はチェックではない");
    }

    private static void table() {
        String t = "| 名 | 値 |\n|:--|--:|\n| a | `b|c` |\n文\n| 外 | です |\n";
        Markdown.Result r = Markdown.analyze(t);
        check(r.kind[0] == Markdown.Kind.TABLE && has(r, t, "名", Markdown.BOLD), "見出しの行は表で、太字");
        check(r.kind[1] == Markdown.Kind.TABLE_RULE && r.flags[t.indexOf("--")] == Markdown.MARK, "|:--|--:| は表の線（記号）");
        check(r.kind[2] == Markdown.Kind.TABLE && r.flags[t.indexOf("| a")] == Markdown.MARK, "表の行。| は記号");
        check(lacks(r, t, "b|c", Markdown.MARK), "コードの中の | は記号にしない");
        check(r.kind[3] == Markdown.Kind.NORMAL, "| のない行で表は終わる");
        check(r.kind[4] == Markdown.Kind.NORMAL, "線のない | の行は、表ではない");

        String lone = "a | b\n";
        check(Markdown.analyze(lone).kind[0] == Markdown.Kind.NORMAL, "| があるだけの行は表ではない");
    }

    private static void links() {
        String t = "[青空](https://example.com/a_b) と ![絵](a.png) と [かっこだけ] と [x](y\n";
        Markdown.Result r = Markdown.analyze(t);
        check(has(r, t, "青空", Markdown.LINK) && has(r, t, "](https://example.com/a_b)", Markdown.MARK),
                "リンク：字は LINK、場所は記号");
        check(lacks(r, t, "a_b", Markdown.ITALIC), "場所の中の _ は強調にしない");
        check(has(r, t, "絵", Markdown.LINK), "画像は、字だけリンクと同じ扱い");
        check(lacks(r, t, "かっこだけ", Markdown.LINK), "場所のない [ ] はリンクではない");
        check(lacks(r, t, "[x](y", Markdown.LINK), "閉じていないリンクは読まない");
    }

    private static void japanese() {
        String t = "**「強調」**と、*やわらかく*。｜漢字《かんじ》は **そのまま**\n　全角の字下げ **太**\n";
        Markdown.Result r = Markdown.analyze(t);
        check(has(r, t, "「強調」", Markdown.BOLD), "** の中がかぎかっこでも効く");
        check(has(r, t, "やわらかく", Markdown.ITALIC), "日本語の斜体");
        check(lacks(r, t, "｜漢字《かんじ》", Markdown.MARK | Markdown.BOLD), "ルビの記法は触らない");
        check(has(r, t, "そのまま", Markdown.BOLD), "ルビのあとの太字も効く");
        check(r.kind[1] == Markdown.Kind.NORMAL && has(r, t, "太", Markdown.BOLD), "全角スペースの字下げの行は、ふつうの段落");
    }

    private static void shape() {
        String crlf = "# 題\r\n本文 **太**\r\n";
        Markdown.Result c = Markdown.analyze(crlf);
        check(c.kind[0] == Markdown.Kind.HEADING && has(c, crlf, "太", Markdown.BOLD), "CRLF でも読める");

        Markdown.Result empty = Markdown.analyze("");
        check(empty != null && empty.kind.length == 1 && empty.length == 0, "空の文書でも落ちない");

        String t = "a\n\nbcd\n";
        Markdown.Result r = Markdown.analyze(t);
        check(r.lineOf(0) == 0 && r.lineOf(2) == 1 && r.lineOf(3) == 2 && r.lineOf(6) == 2 && r.lineOf(7) == 3,
                "lineOf：位置から行を求める");
        check(r.lineEnd(0) == 1 && r.lineEnd(1) == 2 && r.lineEnd(2) == 6 && r.lineEnd(3) == 7, "lineEnd：行の終わり（改行は含まない）");

        check(Markdown.analyze("a".repeat(Markdown.LIMIT + 1)) == null, "長すぎる文書は読まない（null）");

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 20000; i++) {
            many.append("段落 **太** と `コード` と [リンク](x) です。\n");
        }
        long start = System.nanoTime();
        Markdown.Result big = Markdown.analyze(many.toString());
        long ms = (System.nanoTime() - start) / 1_000_000;
        check(big != null && ms < 1000, "約 70 万字を 1 秒以内に読める（" + ms + " ms）");
    }
}
