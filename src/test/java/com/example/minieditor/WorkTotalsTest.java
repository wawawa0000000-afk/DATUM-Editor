package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 作品の集計（WorkTotals）のテスト。
 *
 * 一時フォルダを作って、終わったら消す（原稿のフォルダは使わない）。
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -Djava.awt.headless=true -cp target/classes:target/test-classes com.example.minieditor.WorkTotalsTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class WorkTotalsTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("datum-work-totals-test");
        try {
            targets(base);
            hiddenAndSubfolders(base);
            order(base);
            countsMatchCountChars(base);
            modesDiffer(base);
            openTextsWin(base);
            crlf(base);
            skipped(base);
            empty(base);
        } finally {
            deleteTree(base);
        }
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 対象は .txt・.md・.text だけ。ほかの拡張子（.png・.json）は数えない。 */
    private static void targets(Path base) throws IOException {
        Path root = folder(base, "targets");
        write(root.resolve("a.txt"), "あいう");
        write(root.resolve("b.md"), "えお");
        write(root.resolve("c.text"), "かきくけ");
        write(root.resolve("d.TXT"), "さし");
        write(root.resolve("e.png"), "数えない");
        write(root.resolve("f.json"), "{\"x\":\"数えない\"}");
        write(root.resolve("g.docx"), "数えない");
        WorkTotals.Totals totals = WorkTotals.count(root, WritingStats.CountMode.ALL, false, Map.of());
        check(names(totals).equals(List.of("a.txt", "b.md", "c.text", "d.TXT")), "対象は .txt・.md・.text だけ（大文字の拡張子も含む）");
        check(totals.total() == 3 + 2 + 4 + 2, "対象のファイルの字数の合計（png・json・docx は入らない）");
    }

    /** 隠しファイル・隠しフォルダ・ごみ箱の中・サブフォルダの中は、数えない（直下だけ）。 */
    private static void hiddenAndSubfolders(Path base) throws IOException {
        Path root = folder(base, "hidden");
        write(root.resolve("a.txt"), "表");
        write(root.resolve(".secret.txt"), "隠しファイル");
        write(root.resolve(".git/a.txt"), "隠しフォルダ");
        write(root.resolve(".datum-trash/old.txt"), "ごみ箱");
        write(root.resolve("sub/b.txt"), "サブフォルダ");
        Files.createDirectories(root.resolve("folder.txt"));     // 名前がテキストでも、フォルダは数えない
        WorkTotals.Totals totals = WorkTotals.count(root, WritingStats.CountMode.ALL, false, Map.of());
        check(names(totals).equals(List.of("a.txt")), "隠しファイル・隠しフォルダ・ごみ箱・サブフォルダの中は数えない");
        check(totals.total() == 1, "隠しなどを除いた字数の合計");
    }

    /** 並びは FolderSidebar と同じ（名前の順。数字は数として比べる）。 */
    private static void order(Path base) throws IOException {
        Path root = folder(base, "order");
        for (String name : List.of("b.txt", "10.txt", "2.txt", "a.md")) {
            write(root.resolve(name), name);
        }
        WorkTotals.Totals totals = WorkTotals.count(root, WritingStats.CountMode.ALL, false, Map.of());
        List<String> expected = new ArrayList<>(names(totals));
        List<String> sorted = new ArrayList<>(expected);
        sorted.sort(FolderSidebar::naturalCompare);
        check(expected.equals(sorted), "並び順が FolderSidebar の名前の順と同じ");
        check(expected.equals(List.of("2.txt", "10.txt", "a.md", "b.txt")), "数字は数として比べる（2 は 10 より前）");
    }

    /** 字数は WritingStats.countChars と一致し、合計は各話の和になる。 */
    private static void countsMatchCountChars(Path base) throws IOException {
        Path root = folder(base, "count");
        String one = "東京は晴れ。\n明日は雨。";
        String two = "　「こんにちは」と言った。";
        write(root.resolve("01.txt"), one);
        write(root.resolve("02.txt"), two);
        boolean match = true;
        boolean sum = true;
        for (WritingStats.CountMode mode : WritingStats.CountMode.values()) {
            WorkTotals.Totals totals = WorkTotals.count(root, mode, false, Map.of());
            int expectedOne = WritingStats.countChars(one, mode, false);
            int expectedTwo = WritingStats.countChars(two, mode, false);
            match &= totals.entries().get(0).chars() == expectedOne && totals.entries().get(1).chars() == expectedTwo;
            sum &= totals.total() == expectedOne + expectedTwo;
            sum &= totals.mode().equals(mode.label);
        }
        check(match, "字数は WritingStats.countChars と一致する（3つの数え方とも）");
        check(sum, "合計は各話の字数の和で、数え方の名前も付く");
        WorkTotals.Totals ruby = WorkTotals.count(folder(base, "ruby"), WritingStats.CountMode.BODY, true, Map.of());
        check(ruby.entries().isEmpty() && ruby.total() == 0, "対象が1つもないフォルダは、entries が空で total が 0");
        Path rubyRoot = folder(base, "ruby2");
        String rubyText = "｜東京《とうきょう》へ行く";
        write(rubyRoot.resolve("r.txt"), rubyText);
        WorkTotals.Totals withRuby = WorkTotals.count(rubyRoot, WritingStats.CountMode.BODY, true, Map.of());
        check(withRuby.total() == WritingStats.countChars(rubyText, WritingStats.CountMode.BODY, true),
                "ルビの記法は、rubyNotation に従って数える（countChars と一致）");
    }

    /** 数え方を変えると、字数が変わる（全体と空白・改行を除く）。 */
    private static void modesDiffer(Path base) throws IOException {
        Path root = folder(base, "modes");
        write(root.resolve("a.txt"), "a b\nc");
        int all = WorkTotals.count(root, WritingStats.CountMode.ALL, false, Map.of()).total();
        int noSpace = WorkTotals.count(root, WritingStats.CountMode.NO_SPACE, false, Map.of()).total();
        check(all != noSpace && noSpace == 3, "数え方を変えると字数が変わる（全体 " + all + "、空白・改行を除く " + noSpace + "）");
    }

    /** 開いている未保存の文書は、ディスクより優先して数える。 */
    private static void openTextsWin(Path base) throws IOException {
        Path root = folder(base, "open");
        Path file = root.resolve("open.txt");
        write(file, "ディスクの中身");
        Map<Path, String> open = new HashMap<>();
        open.put(file.toAbsolutePath().normalize(), "開いている文書の本文です");
        WorkTotals.Totals totals = WorkTotals.count(root, WritingStats.CountMode.ALL, false, open);
        int expected = WritingStats.countChars("開いている文書の本文です", WritingStats.CountMode.ALL, false);
        check(totals.entries().size() == 1 && totals.entries().get(0).chars() == expected,
                "開いている未保存の文書を、ディスクより優先して数える");
    }

    /** CRLF のファイルも、LF のファイルと同じ字数になる。 */
    private static void crlf(Path base) throws IOException {
        Path root = folder(base, "crlf");
        write(root.resolve("a_lf.txt"), "一行目\n二行目\n三行目\n");
        write(root.resolve("b_crlf.txt"), "一行目\r\n二行目\r\n三行目\r\n");
        WorkTotals.Totals totals = WorkTotals.count(root, WritingStats.CountMode.ALL, false, Map.of());
        check(totals.entries().size() == 2 && totals.entries().get(0).chars() == totals.entries().get(1).chars(),
                "CRLF のファイルが、LF のファイルと同じ字数になる");
    }

    /** 読めないファイル・2MB を超えるファイルは飛ばし、skippedAny が true になる。 */
    private static void skipped(Path base) throws IOException {
        Path root = folder(base, "skipped");
        write(root.resolve("ok.txt"), "読める");
        Files.write(root.resolve("broken.txt"), new byte[]{(byte) 0xff, (byte) 0xfe, 'x'});   // UTF-8 として読めない
        byte[] big = new byte[(int) WorkTotals.MAX_FILE_BYTES + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        Files.write(root.resolve("big.txt"), big);
        WorkTotals.Totals totals = WorkTotals.count(root, WritingStats.CountMode.ALL, false, Map.of());
        check(names(totals).equals(List.of("ok.txt")), "読めないファイル・大きすぎるファイルは一覧に入らない");
        check(totals.skippedAny(), "飛ばしたときは skippedAny が true になる");
        WorkTotals.Totals clean = WorkTotals.count(folder(base, "clean"), WritingStats.CountMode.ALL, false, Map.of());
        check(!clean.skippedAny(), "何も飛ばさなければ skippedAny は false");
    }

    /** 対象が1つもないフォルダは、entries が空で total が 0。 */
    private static void empty(Path base) throws IOException {
        Path root = folder(base, "empty");
        write(root.resolve("picture.png"), "数えない");
        WorkTotals.Totals totals = WorkTotals.count(root, WritingStats.CountMode.ALL, false, Map.of());
        check(totals.entries().isEmpty() && totals.total() == 0, "対象が1つもなければ、entries は空・total は 0");
    }

    private static List<String> names(WorkTotals.Totals totals) {
        List<String> names = new ArrayList<>();
        for (WorkTotals.Entry entry : totals.entries()) {
            names.add(entry.name());
        }
        return names;
    }

    private static Path folder(Path base, String name) throws IOException {
        return Files.createDirectories(base.resolve(name));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path base) {
        try (Stream<Path> stream = Files.walk(base)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ex) {
                    System.out.println("一時フォルダを消せませんでした: " + p);
                }
            });
        } catch (IOException ex) {
            System.out.println("一時フォルダを消せませんでした: " + base);
        }
    }
}
