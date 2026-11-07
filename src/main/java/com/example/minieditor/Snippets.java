package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 定型文（よく使う文）の読み方と、日付・時刻の文字（画面には依存しない）。
 *
 * 定型文は、設定フォルダの「定型文.txt」に、1行に1つずつ書く。
 * 「#」で始まる行は説明として読み飛ばす。編集は、エディタでそのファイルを開いて行う。
 * 「編集」メニューの「定型文」から、中身を選んで挿入する（EditorFrame）。
 */
public final class Snippets {

    /** 定型文のファイル名（設定フォルダの中）。 */
    static final String FILE_NAME = "定型文.txt";

    /** メニューに出す文字数（コードポイント）。これを超える分は「…」で省く。挿入は全文。 */
    static final int MENU_LENGTH = 40;

    /** 新しく作った定型文.txt に最初に書く説明。 */
    private static final String HEADER = "# 1行に1つ、定型文を書きます。「#」で始まる行は無視されます。";

    private static final DateTimeFormatter JAPANESE_DATE = DateTimeFormatter.ofPattern("uuuu年M月d日", Locale.ROOT);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);

    private Snippets() {
    }

    /** 設定フォルダの中の定型文.txt。 */
    static Path fileIn(Path configDir) {
        return configDir.resolve(FILE_NAME);
    }

    /**
     * 定型文の一覧を作る。空の行と、「#」で始まる説明の行は捨てる。
     * 同じ文が2回あっても、捨てずにそのまま並べる。
     */
    static List<String> parse(String content) {
        List<String> snippets = new ArrayList<>();
        for (String line : content.split("\r\n|\n", -1)) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            snippets.add(text);
        }
        return snippets;
    }

    /** メニューに出す文字。40字（コードポイント）を超えるときは、40字で切って「…」を付ける。 */
    static String menuLabel(String snippet) {
        int count = snippet.codePointCount(0, snippet.length());
        if (count <= MENU_LENGTH) {
            return snippet;
        }
        return snippet.substring(0, snippet.offsetByCodePoints(0, MENU_LENGTH)) + "…";
    }

    /**
     * 定型文.txt が無ければ、説明の行だけを入れて作る（親フォルダが無ければ作る）。
     * 既にあれば、何もしない（中身は変えない）。
     */
    static void ensureFile(Path file) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, HEADER + "\n", StandardCharsets.UTF_8);
    }

    /** 定型文.txt を読む。ファイルが無いとき・読めないときは、空の一覧を返す（ファイルは作らない）。 */
    static List<String> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return new ArrayList<>();
        }
    }

    /** 日付の文字（例：2026年11月7日）。月・日は、1桁のときも0を付けない。 */
    static String dateJapanese(LocalDate d) {
        return JAPANESE_DATE.format(d);
    }

    /** 日付の文字（例：2026-11-07）。 */
    static String dateIso(LocalDate d) {
        return ISO_DATE.format(d);
    }

    /** 時刻の文字（例：14:05）。24時間制。 */
    static String time(LocalDateTime t) {
        return TIME.format(t);
    }

    /** 日時の文字（例：2026-11-07 14:05）。 */
    static String dateTimeIso(LocalDateTime t) {
        return ISO_DATE_TIME.format(t);
    }
}
