package com.example.minieditor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 作品の字数を、話（ファイル）ごとに数える処理（画面には触らない）。
 *
 * 1つのファイルを1話として数える。対象は、フォルダの直下の .txt・.md・.text だけ（隠しファイル・隠しフォルダ・
 * ごみ箱は除き、サブフォルダの中へはたどらない）。並びは FolderSidebar の名前の順と同じ。
 * 開いている文書は、ディスクを読まず、画面の中の文字（未保存の変更を含む）で数える。
 */
final class WorkTotals {

    /** 1つの話。file は絶対パス、name はファイル名、chars は字数。 */
    record Entry(Path file, String name, int chars) {
    }

    /**
     * 集計の結果。entries は名前の順、total は各話の字数の和。
     * skippedAny は、読めないファイル・大きすぎるファイルを飛ばしたとき true。mode は数え方の名前（画面に出す）。
     */
    record Totals(List<Entry> entries, int total, boolean skippedAny, String mode) {
    }

    /** 読むファイルの大きさの上限（FolderSearch と同じ）。これを超えるファイルは飛ばす。 */
    static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private WorkTotals() {
    }

    /**
     * root の直下の文章を、話ごとに数える。openTexts は、開いている文書の中身（絶対パス → 保存形式の文字）。
     * rubyNotation は、横書きの記法（｜親文字《ルビ》）として数えるか（ステータスバーと同じ判定）。
     */
    static Totals count(Path root, WritingStats.CountMode mode, boolean rubyNotation, Map<Path, String> openTexts) {
        Path base = root.toAbsolutePath().normalize();
        Map<Path, String> open = new HashMap<>();
        openTexts.forEach((path, text) -> open.put(path.toAbsolutePath().normalize(), text));
        List<Entry> entries = new ArrayList<>();
        boolean skippedAny = false;
        int total = 0;
        for (Path file : FolderSidebar.listSorted(base)) {
            if (!Files.isRegularFile(file) || !isTarget(file)) {
                continue;
            }
            String text = textOf(file, open);
            if (text == null) {
                skippedAny = true;
                continue;
            }
            text = text.replace("\r\n", "\n");      // 既存のまとめ開きと同じく、改行をそろえてから数える
            int chars = WritingStats.countChars(text, mode, rubyNotation);
            entries.add(new Entry(file, file.getFileName().toString(), chars));
            total += chars;
        }
        return new Totals(entries, total, skippedAny, mode.label);
    }

    /** 中身を読む。開いている文書なら、ディスクを読まずにその文字を使う。大きすぎる・読めないときは null。 */
    private static String textOf(Path file, Map<Path, String> open) {
        String unsaved = open.get(file);
        if (unsaved != null) {
            return unsaved;
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return null;
            }
            return new FileService().readText(file);
        } catch (IOException ex) {
            return null;                // 読めないファイルは飛ばして、ほかを数え続ける
        }
    }

    /** 対象の拡張子か（.txt・.md・.text。大文字でも可）。 */
    static boolean isTarget(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".text");
    }
}
