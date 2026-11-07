package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 定型文（Snippets）のテスト。
 *
 * 一時フォルダを作って、終わったら消す（設定フォルダや原稿のフォルダは使わない）。
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -Djava.awt.headless=true -cp target/classes:target/test-classes com.example.minieditor.SnippetsTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class SnippetsTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("datum-snippets-test");
        try {
            parse();
            menuLabel();
            ensureFile(base);
            load(base);
            dates();
            locale();
        } finally {
            deleteTree(base);
        }
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 空の行・全角の空白だけの行は捨て、前後の空白（半角・全角）は除く。「#」で始まる行は捨てる。 */
    private static void parse() {
        List<String> got = Snippets.parse("  よろしくお願いします。  \r\n\n　　\n# 説明の行\n\t\n　本文　\n#\n最後");
        check(got.equals(List.of("よろしくお願いします。", "本文", "最後")),
                "parse：空行・全角の空白だけの行・「#」の行を捨て、前後の空白を除く: " + got);

        List<String> dup = Snippets.parse("同じ文\n同じ文\n");
        check(dup.equals(List.of("同じ文", "同じ文")), "parse：同じ文が2回あっても、2回残す: " + dup);

        List<String> crlf = Snippets.parse("一行目\r\n二行目\r\n");
        check(crlf.equals(List.of("一行目", "二行目")), "parse：\\r\\n の改行で分ける: " + crlf);

        List<String> hashInside = Snippets.parse("text # not comment\n  #前の空白があっても説明");
        check(hashInside.equals(List.of("text # not comment")),
                "parse：途中の「#」は説明ではない・行頭の「#」（前の空白あり）は説明: " + hashInside);

        List<String> empty = Snippets.parse("");
        check(empty.isEmpty(), "parse：空の文字からは、空の一覧: " + empty);

        String longLine = "長".repeat(200);
        List<String> longOne = Snippets.parse(longLine);
        check(longOne.size() == 1 && longOne.get(0).equals(longLine), "parse：200字を超える1行も、全文をそのまま使う");
    }

    /** メニューの表示は、40字（コードポイント）まで。41字以上は40字＋「…」。挿入は全文（この関数は使わない）。 */
    private static void menuLabel() {
        String forty = "あ".repeat(40);
        check(Snippets.menuLabel(forty).equals(forty), "menuLabel：40字はそのまま");
        String fortyOne = "い".repeat(41);
        check(Snippets.menuLabel(fortyOne).equals("い".repeat(40) + "…"), "menuLabel：41字は40字＋「…」");
        String emoji = "😀".repeat(41);
        check(Snippets.menuLabel(emoji).equals("😀".repeat(40) + "…"), "menuLabel：字数はコードポイントで数える（サロゲートペアを割らない）");
        check(Snippets.menuLabel("短い文").equals("短い文"), "menuLabel：短い文はそのまま");
    }

    /** 無ければ、説明の行だけで作る（親フォルダも作る）。有れば、中身は変えない。 */
    private static void ensureFile(Path base) throws IOException {
        Path dir = base.resolve("ensure/deeper/nested");
        Path file = Snippets.fileIn(dir);
        check(Snippets.fileIn(dir).getFileName().toString().equals("定型文.txt"), "fileIn：設定フォルダの中の「定型文.txt」");

        Snippets.ensureFile(file);
        check(Files.isRegularFile(file), "ensureFile：無ければ作る（親フォルダが無くても作る）");
        String created = Files.readString(file, StandardCharsets.UTF_8);
        check(created.startsWith("# 1行に1つ、定型文を書きます。") && created.lines().allMatch(l -> l.isEmpty() || l.startsWith("#")),
                "ensureFile：作ったファイルには説明の行だけが入る");
        check(Snippets.load(file).isEmpty(), "ensureFile：説明の行だけのファイルは、定型文が0件");

        Files.writeString(file, "既存の文\n# 説明\n別の文\n", StandardCharsets.UTF_8);
        Snippets.ensureFile(file);
        String kept = Files.readString(file, StandardCharsets.UTF_8);
        check(kept.equals("既存の文\n# 説明\n別の文\n"), "ensureFile：有れば中身を変えない（既存の内容が残る）");
    }

    /** 無いファイルは空の一覧で、作らない。UTF-8 の日本語が読める。読めないときは空の一覧。 */
    private static void load(Path base) throws IOException {
        Path dir = base.resolve("load");
        Path missing = Snippets.fileIn(dir);
        check(Snippets.load(missing).isEmpty(), "load：無いファイルは空の一覧");
        check(!Files.exists(missing) && !Files.exists(dir), "load：無いファイルは作らない（フォルダも作らない）");

        Files.createDirectories(dir);
        Files.writeString(missing, "# 説明\n日本語の定型文です。\n\n二つ目。\n", StandardCharsets.UTF_8);
        check(Snippets.load(missing).equals(List.of("日本語の定型文です。", "二つ目。")),
                "load：UTF-8 の日本語が読める（説明と空の行は除く）: " + Snippets.load(missing));

        Path bad = dir.resolve("bad.txt");
        Files.write(bad, new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0x00, (byte) 0x81});
        check(Snippets.load(bad).isEmpty(), "load：UTF-8 として読めないファイルは空の一覧");
    }

    private static void dates() {
        check(Snippets.dateJapanese(LocalDate.of(2026, 11, 7)).equals("2026年11月7日"), "dateJapanese：2026年11月7日（0埋めしない）");
        check(Snippets.dateJapanese(LocalDate.of(2026, 1, 1)).equals("2026年1月1日"), "dateJapanese：2026年1月1日（0埋めしない）");
        check(Snippets.dateIso(LocalDate.of(2026, 11, 7)).equals("2026-11-07"), "dateIso：2026-11-07");
        check(Snippets.dateIso(LocalDate.of(2026, 1, 1)).equals("2026-01-01"), "dateIso：2026-01-01（0埋め）");
        check(Snippets.time(LocalDateTime.of(2026, 11, 7, 14, 5)).equals("14:05"), "time：14:05（0埋め）");
        check(Snippets.time(LocalDateTime.of(2026, 11, 7, 0, 0)).equals("00:00"), "time：00:00");
        check(Snippets.time(LocalDateTime.of(2026, 11, 7, 23, 59)).equals("23:59"), "time：23:59（24時間制）");
        check(Snippets.dateTimeIso(LocalDateTime.of(2026, 11, 7, 14, 5)).equals("2026-11-07 14:05"), "dateTimeIso：2026-11-07 14:05");
    }

    /** 既定のロケールを変えても、日付・時刻の文字は同じになる。確かめたあと、元に戻す。 */
    private static void locale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"));
            check(Snippets.dateIso(LocalDate.of(2026, 11, 7)).equals("2026-11-07")
                            && Snippets.dateJapanese(LocalDate.of(2026, 11, 7)).equals("2026年11月7日")
                            && Snippets.time(LocalDateTime.of(2026, 11, 7, 14, 5)).equals("14:05")
                            && Snippets.dateTimeIso(LocalDateTime.of(2026, 11, 7, 14, 5)).equals("2026-11-07 14:05"),
                    "既定のロケールを変えても、日付・時刻は同じ（タイ・仏暦の設定でも西暦・0埋め）");
        } finally {
            Locale.setDefault(before);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
