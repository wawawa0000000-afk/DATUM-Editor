package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 校正の辞書（校正で指摘しない語）の読み方・書き方（画面には依存しない）。
 *
 * 辞書は、設定フォルダの「校正辞書.txt」に、1行に1語ずつ書く。
 * 「#」で始まる行は説明として読み飛ばす。ここに書いた語と同じ所は、校正の一覧に出さない。
 * 「校正」の欄の「辞書に登録」と、「編集」メニューの「校正辞書を編集…」から使う（ProofreadPanel・EditorFrame）。
 */
public final class ProofreadDictionary {

    /** 辞書のファイル名（設定フォルダの中）。 */
    static final String FILE_NAME = "校正辞書.txt";

    /** 新しく作った校正辞書.txt に最初に書く説明。 */
    private static final String HEADER = "# 1行に1語。ここに書いた語は、校正で指摘しません。";

    private ProofreadDictionary() {
    }

    /** 設定フォルダの中の校正辞書.txt。 */
    static Path fileIn(Path configDir) {
        return configDir.resolve(FILE_NAME);
    }

    /**
     * 辞書の語の一覧を作る。空の行と、「#」で始まる説明の行は捨てる。前後の空白は除く。
     * 同じ語が2つあっても、1つとして扱う。
     */
    static Set<String> parse(String content) {
        Set<String> words = new LinkedHashSet<>();
        for (String line : content.split("\r\n|\n", -1)) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            words.add(text);
        }
        return words;
    }

    /** 校正辞書.txt を読む。ファイルが無いとき・読めないときは、空の一覧を返す（ファイルは作らない）。 */
    static Set<String> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashSet<>();
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return new LinkedHashSet<>();
        }
    }

    /**
     * 語を辞書に追加する。既にあれば何もしない。語が空・改行入りのときも何もしない。
     * ファイルが無ければ、説明の行を入れて作る（親フォルダが無ければ作る）。
     */
    static void add(Path file, String word) throws IOException {
        if (word == null || word.contains("\n") || word.contains("\r")) {
            return;
        }
        String text = word.strip();         // 読むときも前後の空白を除くので、書くときも同じにそろえる
        if (text.isEmpty()) {
            return;
        }
        ensureFile(file);
        // 読めなかったときは、書き直すと中身が消えるので、読み取りの失敗をそのまま知らせる
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (parse(content).contains(text)) {
            return;
        }
        String separator = content.isEmpty() || content.endsWith("\n") ? "" : "\n";
        FileService.writeSafely(file, content + separator + text + "\n", false);
    }

    /**
     * 校正辞書.txt が無ければ、説明の行だけを入れて作る（親フォルダが無ければ作る）。
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
        FileService.writeSafely(file, HEADER + "\n", false);
    }

    /** 語が辞書に入っているか（完全一致だけ当たる。「見れる」を入れても「見れた」「見れる人」は当たらない）。 */
    static boolean covers(Set<String> words, String spanText) {
        return words.contains(spanText);
    }

    /**
     * 指摘の一覧から、辞書の語と完全に一致する所を除く。範囲の文字（text の start〜end）が、辞書の語と同じときだけ除く。
     * 辞書が空なら、一覧をそのまま返す。
     */
    static List<Proofreader.Issue> filter(List<Proofreader.Issue> issues, Set<String> words, String text) {
        List<Proofreader.Issue> kept = new ArrayList<>();
        for (Proofreader.Issue issue : issues) {
            boolean inRange = issue.start() >= 0 && issue.start() <= issue.end() && issue.end() <= text.length();
            if (!words.isEmpty() && inRange && covers(words, text.substring(issue.start(), issue.end()))) {
                continue;
            }
            kept.add(issue);
        }
        return kept;
    }
}
