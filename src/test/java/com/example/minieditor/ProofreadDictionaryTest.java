package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 校正辞書（ProofreadDictionary）のテスト。
 *
 * 一時フォルダを作って、終わったら消す（設定フォルダや原稿のフォルダは使わない）。
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -Djava.awt.headless=true -cp target/classes:target/test-classes com.example.minieditor.ProofreadDictionaryTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class ProofreadDictionaryTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("datum-proofdict-test");
        try {
            parse();
            load(base);
            add(base);
            ensureFile(base);
            covers();
            filter();
        } finally {
            deleteTree(base);
        }
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 空の行・全角の空白だけの行は捨て、前後の空白（半角・全角）は除く。「#」の行は捨てる。同じ語は1つ。 */
    private static void parse() {
        Set<String> got = ProofreadDictionary.parse("  見れる  \r\n\n　　\n# 説明の行\n\t\n　本文　\n#\n最後");
        check(got.equals(Set.of("見れる", "本文", "最後")),
                "parse：空行・全角の空白だけの行・「#」の行を捨て、前後の空白を除く: " + got);

        Set<String> dup = ProofreadDictionary.parse("同じ語\n同じ語\n 同じ語 \n");
        check(dup.size() == 1 && dup.contains("同じ語"), "parse：同じ語が複数あっても1つとして扱う: " + dup);

        Set<String> crlf = ProofreadDictionary.parse("一行目\r\n二行目\r\n");
        check(crlf.equals(Set.of("一行目", "二行目")), "parse：\\r\\n の改行で分ける: " + crlf);

        Set<String> hashInside = ProofreadDictionary.parse("text # not comment\n  #前の空白があっても説明");
        check(hashInside.equals(Set.of("text # not comment")),
                "parse：途中の「#」は説明ではない・行頭の「#」（前の空白あり）は説明: " + hashInside);

        check(ProofreadDictionary.parse("").isEmpty(), "parse：空の文字からは、空の集合");
    }

    /** 無いファイルは空で、ファイルを作らない。UTF-8 の日本語が読める。読めないファイルも空。 */
    private static void load(Path base) throws IOException {
        Path missing = base.resolve("load-none").resolve(ProofreadDictionary.FILE_NAME);
        check(ProofreadDictionary.load(missing).isEmpty(), "load：ファイルが無いとき、空の集合");
        check(!Files.exists(missing) && !Files.exists(missing.getParent()),
                "load：ファイルが無いとき、ファイルもフォルダも作らない");

        Path file = base.resolve("load-utf8").resolve(ProofreadDictionary.FILE_NAME);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# 説明\n見れる\n来れる\n", StandardCharsets.UTF_8);
        check(ProofreadDictionary.load(file).equals(Set.of("見れる", "来れる")), "load：UTF-8 の日本語が読める");

        Path broken = base.resolve("load-broken").resolve(ProofreadDictionary.FILE_NAME);
        Files.createDirectories(broken.getParent());
        Files.write(broken, new byte[]{(byte) 0xff, (byte) 0xfe, 0x41, (byte) 0x80});
        check(ProofreadDictionary.load(broken).isEmpty(), "load：UTF-8 で読めないファイルは、空の集合");
    }

    /** 無ければ作って語が入る（説明の行も入る）。2回目は増えない。語が空・改行入りなら何もしない。 */
    private static void add(Path base) throws IOException {
        Path file = base.resolve("add").resolve("sub").resolve(ProofreadDictionary.FILE_NAME);
        ProofreadDictionary.add(file, "見れる");
        check(Files.isRegularFile(file), "add：無ければ、フォルダごと作る");
        String first = Files.readString(file, StandardCharsets.UTF_8);
        check(first.startsWith("# 1行に1語") && first.contains("見れる"),
                "add：説明の行と語が入る: " + first.replace("\n", "\\n"));
        check(ProofreadDictionary.load(file).equals(Set.of("見れる")), "add：読み直すと語が1つ");

        ProofreadDictionary.add(file, "見れる");
        check(Files.readString(file, StandardCharsets.UTF_8).equals(first), "add：同じ語の2回目は、ファイルを変えない");

        ProofreadDictionary.add(file, "来れる");
        check(ProofreadDictionary.load(file).equals(Set.of("見れる", "来れる")), "add：別の語は、末尾に追記される");

        Path noNewline = base.resolve("add-tail").resolve(ProofreadDictionary.FILE_NAME);
        Files.createDirectories(noNewline.getParent());
        Files.writeString(noNewline, "# 説明", StandardCharsets.UTF_8);
        ProofreadDictionary.add(noNewline, "語");
        check(ProofreadDictionary.load(noNewline).equals(Set.of("語")),
                "add：末尾に改行の無いファイルでも、語を別の行に入れる");

        Path empty = base.resolve("add-empty").resolve(ProofreadDictionary.FILE_NAME);
        ProofreadDictionary.add(empty, "");
        ProofreadDictionary.add(empty, "　 ");
        check(!Files.exists(empty), "add：空の語（空白だけも含む）は何もしない（ファイルも作らない）");

        ProofreadDictionary.add(empty, "改行\n入り");
        ProofreadDictionary.add(empty, "改行\r入り");
        check(!Files.exists(empty), "add：改行を含む語は何もしない（ファイルも作らない）");

        Path existing = base.resolve("add-keep").resolve(ProofreadDictionary.FILE_NAME);
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "見れる\n", StandardCharsets.UTF_8);
        ProofreadDictionary.add(existing, "語二つ目");
        ProofreadDictionary.add(existing, "");
        check(Files.readString(existing, StandardCharsets.UTF_8).equals("見れる\n語二つ目\n"),
                "add：空の語のあとも、既にある中身は変えない");
    }

    /** 無ければ説明の行入りで作る。既にあれば中身を変えない。 */
    private static void ensureFile(Path base) throws IOException {
        Path file = base.resolve("ensure").resolve("sub").resolve(ProofreadDictionary.FILE_NAME);
        ProofreadDictionary.ensureFile(file);
        check(Files.isRegularFile(file), "ensureFile：無ければ作る");
        String made = Files.readString(file, StandardCharsets.UTF_8);
        check(made.startsWith("# 1行に1語") && ProofreadDictionary.parse(made).isEmpty(),
                "ensureFile：作ったときは説明の行だけ（語は入らない）");

        Files.writeString(file, "見れる\n", StandardCharsets.UTF_8);
        ProofreadDictionary.ensureFile(file);
        check(Files.readString(file, StandardCharsets.UTF_8).equals("見れる\n"), "ensureFile：既にあれば中身を変えない");
    }

    /** 完全一致だけ当たる。「見れる」を入れたとき、「見れる」は当たり、「見れた」「見れる人」は当たらない。 */
    private static void covers() {
        Set<String> words = Set.of("見れる");
        check(ProofreadDictionary.covers(words, "見れる"), "covers：完全一致は当たる");
        check(!ProofreadDictionary.covers(words, "見れた"), "covers：「見れた」は当たらない");
        check(!ProofreadDictionary.covers(words, "見れる人"), "covers：「見れる人」は当たらない（前方一致も部分一致もしない）");
        check(!ProofreadDictionary.covers(words, "見れ"), "covers：一部の文字（「見れ」）は当たらない");
        check(!ProofreadDictionary.covers(Set.of(), "見れる"), "covers：空の辞書では、何も当たらない");
    }

    /** 辞書の語と完全一致する指摘だけを除く。範囲の文字で判定する（同じ言葉でも範囲が違えば、判定の対象は範囲の文字）。 */
    private static void filter() {
        String text = "彼は見れる。見れた。見れる人も来れる。";
        List<Proofreader.Issue> issues = List.of(
                issue(2, 5, "見れる"),          // 「見れる」
                issue(6, 9, "見れた"),          // 「見れた」
                issue(10, 14, "見れる人"),      // 「見れる人」
                issue(15, 18, "来れる"));       // 「来れる」
        List<Proofreader.Issue> kept = ProofreadDictionary.filter(issues, Set.of("見れる", "来れる"), text);
        check(kept.size() == 2 && kept.get(0).start() == 6 && kept.get(1).start() == 10,
                "filter：完全一致の「見れる」「来れる」だけを除き、「見れた」「見れる人」は残す: " + kept.size() + " 件");

        List<Proofreader.Issue> all = ProofreadDictionary.filter(issues, Set.of(), text);
        check(all.size() == issues.size(), "filter：辞書が空なら、一覧をそのまま返す");

        check(ProofreadDictionary.filter(issues, Set.of("見れる"), "").size() == issues.size(),
                "filter：範囲が文章の外にあるときは、除かずに残す（例外にしない）");
    }

    private static Proofreader.Issue issue(int start, int end, String message) {
        return new Proofreader.Issue(start, end, Proofreader.Category.values()[0], message, null);
    }

    private static void deleteTree(Path base) throws IOException {
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
