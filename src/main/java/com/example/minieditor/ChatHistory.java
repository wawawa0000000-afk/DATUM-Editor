package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * AI 会話欄のやり取りを、設定フォルダの ai-chats に1つずつ保存する。
 * あとで「履歴」から選ぶと、その続きから話せる。古いものから消して、KEEP 個まで残す。
 */
final class ChatHistory {

    static final int KEEP = 50;

    /** 履歴の一覧に出す1行分。 */
    record Summary(String id, String title, long updated) {
    }

    private ChatHistory() {
    }

    static Path folder() {
        return McpSettings.directory().resolve("ai-chats");
    }

    /** 新しい会話の名前（ファイル名）。日時なので、並べると古い順になる。 */
    static String newId() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
    }

    /** 保存してある会話を、新しい順に返す。読めないファイルは飛ばす。 */
    @SuppressWarnings("unchecked")
    static List<Summary> list() {
        List<Summary> result = new ArrayList<>();
        for (Path file : files()) {
            try {
                if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> raw) {
                    Map<String, Object> json = (Map<String, Object>) raw;
                    long updated = json.get("updated") instanceof Number n ? n.longValue() : 0;
                    result.add(new Summary(idOf(file), Json.str(json, "title", "（題名なし）"), updated));
                }
            } catch (IOException | Json.JsonException ex) {
                // 壊れたファイルは一覧に出さない
            }
        }
        result.sort(Comparator.comparingLong(Summary::updated).reversed());
        return result;
    }

    /** 1つ読む。なければ・壊れていれば null。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> load(String id) {
        try {
            Path file = folder().resolve(id + ".json");
            if (Files.isRegularFile(file) && Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> raw) {
                return (Map<String, Object>) raw;
            }
        } catch (IOException | Json.JsonException ex) {
            // 読めなければ、ないのと同じに扱う
        }
        return null;
    }

    /** 保存して、多すぎる分を古い順に消す。 */
    static void save(String id, Map<String, Object> record) throws IOException {
        Files.createDirectories(folder());
        // 原稿の中身も入るので、ほかの利用者から読めないようにする
        FileService.writeSafely(folder().resolve(id + ".json"), Json.write(record), true);
        List<Summary> all = list();
        for (int i = KEEP; i < all.size(); i++) {
            Files.deleteIfExists(folder().resolve(all.get(i).id() + ".json"));
        }
    }

    private static List<Path> files() {
        if (!Files.isDirectory(folder())) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(folder())) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private static String idOf(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".json".length());
    }
}
