package com.example.minieditor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.swing.filechooser.FileSystemView;

/**
 * エディタ全体の設定。利用者ごとの設定フォルダに settings.json として保存するので、エディタを閉じても覚えている。
 *
 * 項目は「名前 → 値」の表（Map）で持ち、よく使うものだけ、名前を間違えないように専用のメソッドを用意した。
 * API キーも入るので、ファイルは本人だけが読める権限にする。
 */
public final class AppSettings {

    // 設定の名前（settings.json の中の項目名）
    public static final String LAST_FOLDER = "lastFolder";          // 最後に保存したフォルダ
    public static final String THEME = "theme";                     // "white" / "gray" / "navy" / "black"
    public static final String RECENT_COMMANDS = "recentCommands";  // コマンドパレットで最近使った操作
    public static final String AUTO_SAVE = "autoSaveToFile";        // 保存済みのファイルを自動で上書き保存するか
    public static final String HISTORY_LIMIT = "historyLimit";      // 1つのファイルにつき残す版の数
    public static final String DAILY_GOAL = "dailyGoal";            // 1日の目標字数（0 なら目標なし）
    public static final String DAILY = "daily";                     // 日ごとの執筆量 {"2026-10-17": 1234}
    public static final String SESSION = "session";                 // 前回閉じたときの状態（タブ・分割・ウィンドウ）
    public static final String SIDEBAR_FOLDER = "sidebarFolder";    // サイドバーに表示するフォルダ
    public static final String PROOF_OFF = "proofreadDisabled";     // 校正で、使わない項目の一覧
    public static final String AI_BACKEND = "aiBackend";            // "claude-code" / "anthropic" / "openai"
    public static final String AI_MODEL = "aiModel";
    public static final String AI_KEY = "aiApiKey";
    public static final String AI_URL = "aiBaseUrl";                // OpenAI 互換の接続先
    public static final String CLAUDE_COMMAND = "claudeCommand";    // Claude Code のコマンド（既定は claude）
    public static final String DRIVE_MODE = "driveMode";            // "off" / "folder"（同期フォルダ） / "api"（直接ログイン）
    public static final String DRIVE_FOLDER = "driveFolder";        // 同期フォルダ、または直接ログインのときの手元の写し
    public static final String DRIVE_CLIENT_ID = "driveClientId";   // 使う人が Google Cloud で作ったクライアント ID
    public static final String DRIVE_CLIENT_SECRET = "driveClientSecret";

    private final Map<String, Object> data;

    private AppSettings(Map<String, Object> data) {
        this.data = data;
    }

    private static Path file() {
        return McpSettings.directory().resolve("settings.json");
    }

    /** 設定を読む。ファイルがない・壊れている場合は、何も覚えていない状態で始める。 */
    @SuppressWarnings("unchecked")
    public static AppSettings load() {
        try {
            if (Files.isRegularFile(file())
                    && Json.parse(Files.readString(file(), StandardCharsets.UTF_8)) instanceof Map<?, ?> raw) {
                return new AppSettings(new LinkedHashMap<>((Map<String, Object>) raw));
            }
        } catch (IOException | Json.JsonException | RuntimeException ex) {
            // 読めなければ、何も覚えていない状態で始める
        }
        return new AppSettings(new LinkedHashMap<>());
    }

    /** ファイルに書く。書けなくても、エディタの作業は続けられるので、失敗は知らせない。 */
    public synchronized void save() {
        try {
            Files.createDirectories(McpSettings.directory());
            // 書いている途中で止まっても前の設定が壊れないように、別の名前で書いてから入れ替える
            Path temp = file().resolveSibling("settings.json.tmp");
            Files.writeString(temp, Json.write(data), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ex) {
                // Windows には、この形の権限がない（利用者のフォルダの中なので、ほかの人からは見えない）
            }
            try {
                Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            // 次に起動したときに、前の設定で始まるだけ
        }
    }

    // ---------- 値の読み書き ----------

    public synchronized String getString(String key, String fallback) {
        return data.get(key) instanceof String s && !s.isBlank() ? s : fallback;
    }

    public synchronized int getInt(String key, int fallback) {
        return data.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    public synchronized boolean getBool(String key, boolean fallback) {
        return data.get(key) instanceof Boolean b ? b : fallback;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> getMap(String key) {
        return data.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    public synchronized List<String> getStrings(String key) {
        List<String> list = new ArrayList<>();
        if (data.get(key) instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof String s) {
                    list.add(s);
                }
            }
        }
        return list;
    }

    /** 値を入れる（null なら項目を消す）。ファイルに書くのは save() を呼んだとき。 */
    public synchronized void put(String key, Object value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
    }

    // ---------- 「開く」「保存」の最初の場所 ----------

    /**
     * 「開く」「保存」の画面で、最初に表示するフォルダ。
     * 最後に保存したフォルダがあればそこ、なければ（または消されていれば）デスクトップ。
     */
    public Path startFolder() {
        String folder = getString(LAST_FOLDER, null);
        if (folder != null && Files.isDirectory(Path.of(folder))) {
            return Path.of(folder);
        }
        return desktop();
    }

    /** 保存に成功したフォルダを覚える。 */
    public void rememberFolder(Path folder) {
        if (folder == null || folder.toString().equals(getString(LAST_FOLDER, null))) {
            return;
        }
        put(LAST_FOLDER, folder.toString());
        save();
    }

    /** デスクトップのフォルダを探す。見つからなければ、ホームフォルダ。 */
    static Path desktop() {
        Path home = Path.of(System.getProperty("user.home"));
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            // Windows では、これがデスクトップを返す（OneDrive などで場所が変わっていても合う）
            Path desktop = FileSystemView.getFileSystemView().getHomeDirectory().toPath();
            if (Files.isDirectory(desktop)) {
                return desktop;
            }
        } else if (!os.contains("mac")) {
            // Linux では、言語の設定によって「デスクトップ」などの名前になっていることがあるので、OS に尋ねる
            try {
                Process process = new ProcessBuilder("xdg-user-dir", "DESKTOP").redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (process.waitFor(2, TimeUnit.SECONDS) && line != null && !line.isBlank()
                            && Files.isDirectory(Path.of(line.strip())) && !Path.of(line.strip()).equals(home)) {
                        return Path.of(line.strip());
                    }
                }
            } catch (IOException | RuntimeException ex) {
                // xdg-user-dir がない環境。下の、名前で探す方法に進む
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        for (String name : new String[]{"Desktop", "デスクトップ"}) {
            if (Files.isDirectory(home.resolve(name))) {
                return home.resolve(name);
            }
        }
        return home;
    }
}
