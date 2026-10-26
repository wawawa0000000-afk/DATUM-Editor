package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 自動バックアップ（異常終了からの復旧）。
 *
 * 未保存の変更がある文書を、30 秒ごとに設定フォルダの recovery へ書き出しておく。
 * ふつうに終了したときは消すので、次に起動したときに残っていれば「前回は保存されずに終わった」とわかる。
 *
 * エディタを2つ同時に動かしたときに取り違えないよう、動いているプログラムの番号（PID）ごとにフォルダを分け、
 * もう動いていないプログラムのフォルダだけを「復元できるもの」として扱う。
 * PC を再起動すると、同じ番号を別のプログラムが使うことがあるので、起動した時刻もフォルダに書いておき、両方で見分ける。
 */
public final class Recovery {

    /** 復元できる文書1つ。 */
    public record Entry(Path file, String title, String path, boolean raw, String savedAt, Map<String, Object> document) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final String STARTED = "started.txt";

    private final Path root;
    private final Path mine;
    private final Map<Integer, String> written = new HashMap<>();     // 最後に書いた内容（同じなら書き直さない）

    public Recovery() {
        this(McpSettings.directory().resolve("recovery"));
    }

    Recovery(Path root) {
        this.root = root;
        this.mine = root.resolve(Long.toString(ProcessHandle.current().pid()));
    }

    /** 未保存の文書を書き出し、保存済みになった文書や閉じた文書のぶんは消す。画面のスレッドから呼ぶ。 */
    public void save(List<EditorPane> panes) {
        Set<Integer> keep = new HashSet<>();
        for (EditorPane pane : panes) {
            if (!pane.isDirty()) {
                continue;
            }
            keep.add(pane.getDocumentId());
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("title", pane.getTitle());
            json.put("path", pane.getPath() == null ? null : pane.getPath().toString());
            json.put("raw", pane.isRawFile());
            json.put("savedAt", LocalDateTime.now().format(TIME));
            json.put("document", pane.toJson());
            String body = Json.writeCompact(json.get("document"));
            if (body.equals(written.get(pane.getDocumentId()))) {
                continue;                   // 前回から変わっていない
            }
            try {
                if (!Files.isDirectory(mine)) {
                    Files.createDirectories(mine);
                    String started = startedAt(ProcessHandle.current());
                    if (started != null) {
                        FileService.writeSafely(mine.resolve(STARTED), started, false);
                    }
                }
                FileService.writeSafely(mine.resolve(pane.getDocumentId() + ".json"), Json.write(json), false);
                written.put(pane.getDocumentId(), body);
            } catch (IOException ex) {
                // 書けなかったら、次の機会にもう一度試す
            }
        }
        // 要らなくなったぶんを消す
        for (Integer id : new ArrayList<>(written.keySet())) {
            if (!keep.contains(id)) {
                written.remove(id);
                try {
                    Files.deleteIfExists(mine.resolve(id + ".json"));
                } catch (IOException ex) {
                    // 次の機会に消す
                }
            }
        }
    }

    /** ふつうに終了したときに呼ぶ。このプログラムのバックアップを、すべて消す。 */
    public void clear() {
        deleteFolder(mine);
        written.clear();
    }

    /** 前回、保存されずに終わった文書の一覧（もう動いていないプログラムが残したもの）。 */
    @SuppressWarnings("unchecked")
    public List<Entry> leftovers() {
        List<Entry> entries = new ArrayList<>();
        for (Path folder : deadFolders()) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
                for (Path file : files) {
                    try {
                        if (Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof Map<?, ?> raw
                                && raw.get("document") instanceof Map<?, ?> document) {
                            Map<String, Object> json = (Map<String, Object>) raw;
                            entries.add(new Entry(file, Json.str(json, "title", "無題"), Json.str(json, "path", null),
                                    Json.bool(json, "raw", false), Json.str(json, "savedAt", ""),
                                    (Map<String, Object>) document));
                        }
                    } catch (IOException | Json.JsonException | RuntimeException ex) {
                        // 壊れたバックアップは飛ばす
                    }
                }
            } catch (IOException ex) {
                // 読めないフォルダは飛ばす
            }
        }
        entries.sort(Comparator.comparing(Entry::title));
        return entries;
    }

    /** 前回のバックアップを消す（復元し終えたとき、または、要らないと言われたとき）。 */
    public void discardLeftovers() {
        for (Path folder : deadFolders()) {
            deleteFolder(folder);
        }
    }

    /** 復元し終えた控えだけを消す。復元できなかった控えは、取り戻せるように残しておく。 */
    public void discard(List<Entry> restored) {
        for (Entry entry : restored) {
            try {
                Files.deleteIfExists(entry.file());
            } catch (IOException ex) {
                // 次の起動のときに、もう一度聞かれるだけ
            }
        }
        for (Path folder : deadFolders()) {
            try (Stream<Path> files = Files.list(folder)) {
                if (files.noneMatch(p -> p.getFileName().toString().endsWith(".json"))) {
                    deleteFolder(folder);   // 控えが残っていないフォルダは片付ける
                }
            } catch (IOException ex) {
                // 次の起動のときに片付ける
            }
        }
    }

    private List<Path> deadFolders() {
        List<Path> folders = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return folders;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path folder : stream) {
                String name = folder.getFileName().toString();
                if (!Files.isDirectory(folder) || !name.matches("\\d+") || folder.equals(mine)) {
                    continue;
                }
                boolean alive = ProcessHandle.of(Long.parseLong(name))
                        .filter(ProcessHandle::isAlive)
                        .map(process -> sameProcess(folder, process))
                        .orElse(false);
                if (!alive) {
                    folders.add(folder);
                }
            }
        } catch (IOException | NumberFormatException ex) {
            // 調べられなければ、何も復元しない
        }
        return folders;
    }

    /** そのフォルダを作ったプログラムが、今その番号で動いているプログラムと同じか（起動した時刻で比べる）。 */
    private static boolean sameProcess(Path folder, ProcessHandle process) {
        String started = startedAt(process);
        try {
            Path file = folder.resolve(STARTED);
            if (started == null || !Files.isRegularFile(file)) {
                return true;            // 比べられなければ、番号だけで判断する（動いているエディタの控えを取らないように）
            }
            return Files.readString(file, StandardCharsets.UTF_8).strip().equals(started);
        } catch (IOException ex) {
            return true;
        }
    }

    private static String startedAt(ProcessHandle process) {
        return process.info().startInstant().map(Object::toString).orElse(null);
    }

    private static void deleteFolder(Path folder) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ex) {
            // 消せなかったものは、次の起動のときに、もう一度消そうとする
        }
    }
}
