package com.example.minieditor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * フォルダの中を横断して探す処理（画面には触らない）。
 *
 * 探すのは .txt・.md・.json だけ。隠しファイル・隠しフォルダ（ごみ箱の .datum-trash も含む）・リンクは対象にしない。
 * 1つの文章の中の探し方は FindMatcher に任せる。.json は、本文とカードの文字だけを探す（McpTools.searchableText）。
 */
final class FolderSearch {

    /**
     * 一致1件。start・end は、探した文章の中の位置。
     * jsonBody が true のときは、.json の本文・カードの文字の中の位置なので、ファイルの中の位置ではない。
     * line は 1 から数えた行番号。lineText は一致の入っている行（長いときは、前後を切ったもの）。
     */
    record Hit(Path file, int start, int end, int line, String lineText, boolean jsonBody) {
    }

    /**
     * 探した結果。hits は出した件数、files は探したファイルの数。
     * limited は 500 件で打ち切ったとき、cancelled は途中で止めたとき、error は止まった理由（画面に出す文）。
     */
    record Outcome(int hits, int files, boolean limited, boolean cancelled, String error) {
    }

    /** 一覧に出す件数の上限。これを超えて見つかったら、そこで打ち切る。 */
    static final int MAX_HITS = 500;

    /** フォルダの下へたどる深さの上限。 */
    private static final int MAX_DEPTH = 8;

    /** 読むファイルの大きさの上限（McpTools と同じ）。 */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /** 行を切って見せるときに、一致の前後に残す文字数。 */
    private static final int CONTEXT = 60;

    private FolderSearch() {
    }

    /**
     * root の中を探す。一致は1件ずつ onHit に渡す。cancelled が true を返したら、そこで止める。
     * openTexts は、開いている文書の中身（絶対パス → 保存形式の文字）。そのファイルは、ディスクではなくこの文字を探す。
     */
    static Outcome search(Path root, String keyword, boolean regex, boolean caseSensitive,
            Map<Path, String> openTexts, Consumer<Hit> onHit, BooleanSupplier cancelled) {
        if (keyword.isEmpty()) {
            return new Outcome(0, 0, false, false, null);
        }
        // 正規表現が正しいかは、探すファイルが1つも無くても分かるように、最初に確かめる
        FindMatcher.Result check = FindMatcher.find("x", keyword, "", regex, caseSensitive, 0, 1);
        if (check.error() != null) {
            return new Outcome(0, 0, false, false, check.error());
        }

        Path base = root.toAbsolutePath().normalize();
        List<Path> files;
        try {
            files = listFiles(base);
        } catch (IOException | UncheckedIOException ex) {
            return new Outcome(0, 0, false, false, "フォルダを読めませんでした: " + ex.getMessage());
        }
        Map<Path, String> open = new HashMap<>();
        openTexts.forEach((path, text) -> open.put(path.toAbsolutePath().normalize(), text));

        int hits = 0;
        int searched = 0;
        for (Path file : files) {
            if (cancelled.getAsBoolean()) {
                return new Outcome(hits, searched, false, true, null);
            }
            String content;
            try {
                if (Files.size(file) > MAX_FILE_BYTES) {
                    continue;
                }
                String unsaved = open.get(file);
                content = unsaved != null ? unsaved : Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                continue;                   // 読めないファイルは飛ばして、ほかを探し続ける
            }
            searched++;
            String text = McpTools.searchableText(file, content);
            // 本文を取り出した（.json の本文・カード）ときだけ true。ふつうの文章なら、中身がそのまま返っている
            boolean jsonBody = EditorFrame.isJsonPath(file) && !text.equals(content);
            FindMatcher.Result result = FindMatcher.find(text, keyword, "", regex, caseSensitive, 0, text.length());
            if (result.error() != null) {
                return new Outcome(hits, searched, false, false, result.error());
            }
            int line = 1;                   // 一致は位置の順に並んでいるので、行の数えは前から続けて数える
            int counted = 0;
            for (FindMatcher.Hit match : result.hits()) {
                if (hits == MAX_HITS) {
                    return new Outcome(hits, searched, true, false, null);
                }
                for (int i = counted; i < match.start(); i++) {
                    if (text.charAt(i) == '\n') {
                        line++;
                    }
                }
                counted = match.start();
                onHit.accept(describe(file, text, match.start(), match.end(), line, jsonBody));
                hits++;
            }
        }
        return new Outcome(hits, searched, false, false, null);
    }

    /** base の下の、探す対象のファイルを、パスの順に集める。リンクと、隠しファイル・隠しフォルダは入れない。 */
    private static List<Path> listFiles(Path base) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(base, MAX_DEPTH)) {
            stream.filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !isHidden(base, p))
                    .filter(FolderSearch::isTextFile)
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    /** 名前が「.」で始まるもの（.datum-trash や .git など）と、その中にあるもの。 */
    private static boolean isHidden(Path base, Path path) {
        for (Path part : base.relativize(path)) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTextFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".json");
    }

    /** 一致の位置から、行の文字を作る。長い行は、一致の前後 CONTEXT 文字だけを見せる（前後が切れたら「…」）。 */
    private static Hit describe(Path file, String text, int start, int end, int line, boolean jsonBody) {
        int from = start;
        while (from > 0 && start - from < CONTEXT && text.charAt(from - 1) != '\n') {
            from--;
        }
        int to = end;
        while (to < text.length() && to - end < CONTEXT && text.charAt(to) != '\n') {
            to++;
        }
        StringBuilder shown = new StringBuilder();
        if (from > 0 && text.charAt(from - 1) != '\n') {
            shown.append('…');
        }
        shown.append(text.substring(from, to).replace("\r", "").replace('\n', ' '));
        if (to < text.length() && text.charAt(to) != '\n') {
            shown.append('…');
        }
        return new Hit(file, start, end, line, shown.toString(), jsonBody);
    }
}
