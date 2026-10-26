package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 連携（MCP）の設定。使う人ごとの設定フォルダに、mcp.json として保存する。
 *
 * このファイルは、エディタ本体と、橋渡し役（McpBridge）の両方が読む。
 * 橋渡し役は、ここに書かれたポート番号と合言葉を使って、動いているエディタにつなぐ。
 */
public final class McpSettings {

    public static final int DEFAULT_PORT = 8765;

    // エディタの画面と、通信のスレッドの両方から読むので、volatile（いつ読んでも最新の値が見える）にしている
    public volatile boolean enabled;                // 前回、AI 連携を動かしたまま終了したか
    public volatile int port = DEFAULT_PORT;        // 待ち受けるポート番号
    public volatile String token;                   // 合言葉（これを知っているプログラムだけが使える）
    public volatile Path workspace;                 // AI に任せるフォルダ

    /**
     * 設定を置くフォルダ。OS ごとの決まりに合わせる。
     * 環境変数 DATUM_EDITOR_CONFIG_DIR があれば、そちらを使う（テストで、本物の設定を書き換えないため）。
     */
    public static Path directory() {
        String override = System.getenv("DATUM_EDITOR_CONFIG_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null && !appData.isBlank() ? appData : home, "DATUM-Editor");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "DATUM-Editor");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return (xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".config")).resolve("datum-editor");
    }

    private static Path file() {
        return directory().resolve("mcp.json");
    }

    /** 設定を読む。ファイルがない・壊れている場合は、初期値のままにする。 */
    @SuppressWarnings("unchecked")
    public static McpSettings load() {
        McpSettings settings = new McpSettings();
        try {
            if (Files.isRegularFile(file())
                    && Json.parse(Files.readString(file(), StandardCharsets.UTF_8)) instanceof Map<?, ?> raw) {
                Map<String, Object> json = (Map<String, Object>) raw;
                settings.enabled = Json.bool(json, "enabled", false);
                int port = Json.num(json, "port", DEFAULT_PORT);
                settings.port = (port >= 1024 && port <= 65535) ? port : DEFAULT_PORT;
                settings.token = Json.str(json, "token", null);
                String workspace = Json.str(json, "workspace", null);
                settings.workspace = (workspace == null || workspace.isBlank()) ? null : Path.of(workspace);
            }
        } catch (IOException | Json.JsonException | RuntimeException ex) {
            // 読めなければ初期値で始める（次に保存したときに、正しい形で書き直される）
        }
        return settings;
    }

    public void save() throws IOException {
        Path directory = directory();
        Files.createDirectories(directory);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("enabled", enabled);
        json.put("port", port);
        json.put("token", token);
        json.put("workspace", workspace == null ? null : workspace.toString());
        // 合言葉が入っているので、ほかの利用者から読めないようにする
        FileService.writeSafely(file(), Json.write(json), true);
    }
}
