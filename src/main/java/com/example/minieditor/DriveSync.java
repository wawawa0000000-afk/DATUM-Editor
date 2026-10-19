package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 手元のフォルダ（写し）と、Drive の「DATUM-Editor」フォルダを同じ中身にする。
 *
 * どちらが変わったかは、前回そろえたときの中身の md5 と比べて決める（時計のずれに左右されない）：
 *   手元だけ変わった → 上げる ／ Drive だけ変わった → 取り込む
 *   両方変わった → 手元の版を上げ、Drive の版は「名前（Drive版）」として手元に残す（どちらも消えない）
 *   片方で消した → もう片方も、ごみ箱へ移す（Drive はごみ箱、手元は .datum-trash。どちらも戻せる）
 * 中のフォルダ（サブフォルダ）は扱わず、いちばん上のファイルだけをそろえる。
 *
 * ログインの鍵と、前回そろえたときの記録は、設定フォルダの drive.json に保存する（本人だけが読める権限）。
 */
final class DriveSync {

    static final String REMOTE_FOLDER = "DATUM-Editor";
    static final String TRASH = ".datum-trash";

    /** 同期の結果。画面に短く知らせるために使う。 */
    record Result(int uploaded, int downloaded, int trashed, List<String> notes) {
        String summary() {
            String text = "Drive と同期しました（上げた " + uploaded + "・取り込んだ " + downloaded
                    + (trashed > 0 ? "・ごみ箱へ " + trashed : "") + "）";
            return notes.isEmpty() ? text : text + "　" + String.join("／", notes);
        }
    }

    private record Synced(String id, String md5) {
    }

    private final DriveClient client;
    private final Path local;
    private String folderId;
    private final Map<String, Synced> synced = new LinkedHashMap<>();

    private DriveSync(DriveClient client, Path local) {
        this.client = client;
        this.local = local;
    }

    /** 設定から作る。前回のログインと同期の記録があれば読み込む。 */
    static DriveSync load(String clientId, String clientSecret, Path local) {
        Map<String, Object> state = readState();
        String savedClient = Json.str(state, "clientId", null);
        // クライアント ID を変えたら、前のログインは使えないので、記録を捨てる
        boolean same = clientId.equals(savedClient);
        DriveSync sync = new DriveSync(new DriveClient(clientId, clientSecret,
                same ? Json.str(state, "refreshToken", null) : null), local);
        if (same && local.toString().equals(Json.str(state, "localFolder", null))) {
            sync.folderId = Json.str(state, "folderId", null);
            if (state.get("files") instanceof Map<?, ?> files) {
                files.forEach((name, value) -> {
                    if (name instanceof String n && value instanceof Map<?, ?> m
                            && m.get("id") instanceof String id && m.get("md5") instanceof String md5) {
                        sync.synced.put(n, new Synced(id, md5));
                    }
                });
            }
        }
        return sync;
    }

    /** 直接ログインのとき、手元の写しを置くフォルダの初期値（ホームの下）。 */
    static Path defaultLocal() {
        return Path.of(System.getProperty("user.home"), "DATUM-Editor-Drive");
    }

    boolean loggedIn() {
        return client.loggedIn();
    }

    Path localFolder() {
        return local;
    }

    /** ブラウザでログインする（終わるまで、呼んだスレッドが止まる）。 */
    synchronized void login(java.util.function.Consumer<String> openBrowser, String clientId) throws IOException, InterruptedException {
        client.login(openBrowser);
        folderId = null;
        synced.clear();
        saveState(clientId);
    }

    /** ログアウトする（この PC に残した鍵を消す。Drive のファイルはそのまま）。 */
    static void logout() throws IOException {
        Files.deleteIfExists(stateFile());
    }

    // ---------- 同期 ----------

    /**
     * 両方をそろえる。
     *
     * @param isOpen 編集中のタブで開いているファイルか。開いているファイルは、上書きして取り込まない（編集が消えないように）
     */
    synchronized Result syncAll(Predicate<Path> isOpen, String clientId) throws IOException, InterruptedException {
        Files.createDirectories(local);
        String folder = folder();
        Map<String, DriveClient.RemoteFile> remote = new LinkedHashMap<>();
        for (DriveClient.RemoteFile file : client.list(folder)) {
            remote.putIfAbsent(file.name(), file);     // Drive は同じ名前を許すので、2つ目以降は扱わない
        }
        Map<String, String> mine = localFiles();
        TreeSet<String> names = new TreeSet<>();
        names.addAll(remote.keySet());
        names.addAll(mine.keySet());
        names.addAll(synced.keySet());

        int up = 0;
        int down = 0;
        int trashed = 0;
        List<String> notes = new ArrayList<>();
        try {
            for (String name : names) {
                String l = mine.get(name);
                DriveClient.RemoteFile r = remote.get(name);
                Synced s = synced.get(name);
                Path path = local.resolve(name);
                if (l != null && r != null) {
                    if (l.equals(r.md5())) {
                        synced.put(name, new Synced(r.id(), l));
                    } else if (s != null && l.equals(s.md5())) {
                        // Drive だけ変わった
                        if (isOpen.test(path)) {
                            notes.add(name + " は開いているので取り込みませんでした");
                        } else {
                            write(path, client.download(r.id()));
                            synced.put(name, new Synced(r.id(), r.md5()));
                            down++;
                        }
                    } else if (s != null && r.md5().equals(s.md5())) {
                        // 手元だけ変わった
                        synced.put(name, new Synced(r.id(), client.update(r.id(), Files.readAllBytes(path)).md5()));
                        up++;
                    } else {
                        // 両方で変わった：Drive の版を別の名前で手元に残してから、手元の版を上げる
                        String copy = conflictName(name, mine.keySet());
                        write(local.resolve(copy), client.download(r.id()));
                        synced.put(name, new Synced(r.id(), client.update(r.id(), Files.readAllBytes(path)).md5()));
                        DriveClient.RemoteFile created = client.create(folder, copy, Files.readAllBytes(local.resolve(copy)));
                        synced.put(copy, new Synced(created.id(), created.md5()));
                        notes.add(name + " は両方で書き換えられていたので、Drive の版を「" + copy + "」として残しました");
                        up += 2;
                        down++;
                    }
                } else if (l != null) {
                    if (s != null && l.equals(s.md5())) {
                        // Drive で消された（手元は前回のまま）→ 手元もごみ箱へ
                        if (isOpen.test(path)) {
                            notes.add(name + " は Drive で消されましたが、開いているので残しました");
                            synced.remove(name);
                        } else {
                            moveToTrash(path);
                            synced.remove(name);
                            trashed++;
                        }
                    } else {
                        // 新しく作った、または Drive で消されたあとに書き換えた → 上げる
                        DriveClient.RemoteFile created = client.create(folder, name, Files.readAllBytes(path));
                        synced.put(name, new Synced(created.id(), created.md5()));
                        up++;
                    }
                } else if (r != null) {
                    if (s != null && r.md5().equals(s.md5())) {
                        // 手元で消した（Drive は前回のまま）→ Drive もごみ箱へ
                        client.trash(r.id());
                        synced.remove(name);
                        trashed++;
                    } else {
                        write(path, client.download(r.id()));
                        synced.put(name, new Synced(r.id(), r.md5()));
                        down++;
                    }
                } else {
                    synced.remove(name);    // 両方で消えた
                }
            }
        } finally {
            saveState(clientId);            // 途中で失敗しても、そこまでの記録は残す
        }
        return new Result(up, down, trashed, notes);
    }

    /**
     * 保存した1つのファイルだけを上げる（保存のたびに裏で呼ぶ）。
     * Drive 側がほかの PC で書き換えられていたら、上書きせずに知らせる（同期で両方の版を残す）。
     *
     * @return 知らせる文。何もしなかったときは null
     */
    synchronized String upload(Path path, String clientId) throws IOException, InterruptedException {
        if (!isTopLevel(path)) {
            return null;
        }
        String name = path.getFileName().toString();
        byte[] content = Files.readAllBytes(path);
        String md5 = md5(content);
        Synced s = synced.get(name);
        try {
            if (s != null) {
                if (md5.equals(s.md5())) {
                    return null;
                }
                DriveClient.RemoteFile now = client.get(s.id());
                if (now != null && !now.md5().equals(s.md5())) {
                    return name + " は Drive 側でも書き換えられています。「Drive と同期」で両方の版を残せます";
                }
                if (now != null) {
                    synced.put(name, new Synced(s.id(), client.update(s.id(), content).md5()));
                    return "Drive に上げました: " + name;
                }
            }
            DriveClient.RemoteFile created = client.create(folder(), name, content);
            synced.put(name, new Synced(created.id(), created.md5()));
            return "Drive に上げました: " + name;
        } finally {
            saveState(clientId);
        }
    }

    /** このファイルは同期の対象か（写しのフォルダのすぐ下にある、ふつうのファイル）。 */
    boolean isTopLevel(Path path) {
        Path p = path.toAbsolutePath().normalize();
        return p.getParent() != null && p.getParent().equals(local.toAbsolutePath().normalize()) && include(p);
    }

    private String folder() throws IOException, InterruptedException {
        if (folderId == null) {
            folderId = client.ensureFolder(REMOTE_FOLDER);
        }
        return folderId;
    }

    /** 手元のファイルと、その md5。隠しファイルや、書きかけの一時ファイルは除く。 */
    private Map<String, String> localFiles() throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(local)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (include(p)) {
                    map.put(p.getFileName().toString(), md5(Files.readAllBytes(p)));
                }
            }
        }
        return map;
    }

    private static boolean include(Path p) {
        String name = p.getFileName().toString();
        return Files.isRegularFile(p) && !name.startsWith(".") && !name.endsWith(".tmp") && !name.endsWith("~");
    }

    /** 「名前（Drive版）.txt」。すでにあれば番号を付ける。 */
    static String conflictName(String name, java.util.Set<String> taken) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String candidate = base + "（Drive版）" + ext;
        for (int i = 2; taken.contains(candidate); i++) {
            candidate = base + "（Drive版 " + i + "）" + ext;
        }
        return candidate;
    }

    /** 書いている途中で止まっても元のファイルが壊れないように、別の名前で書いてから入れ替える。 */
    private static void write(Path path, byte[] content) throws IOException {
        Path temp = path.resolveSibling(path.getFileName() + ".drive.tmp");
        Files.write(temp, content);
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    }

    private void moveToTrash(Path path) throws IOException {
        Path trash = local.resolve(TRASH);
        Files.createDirectories(trash);
        Path target = trash.resolve(path.getFileName());
        for (int i = 2; Files.exists(target); i++) {
            target = trash.resolve(i + "-" + path.getFileName());
        }
        Files.move(path, target);
    }

    static String md5(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---------- 記録（drive.json） ----------

    private static Path stateFile() {
        return McpSettings.directory().resolve("drive.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readState() {
        try {
            if (Files.isRegularFile(stateFile())
                    && Json.parse(Files.readString(stateFile(), StandardCharsets.UTF_8)) instanceof Map<?, ?> raw) {
                return (Map<String, Object>) raw;
            }
        } catch (IOException | Json.JsonException | RuntimeException ex) {
            // 読めなければ、ログインしていない状態から始める
        }
        return new LinkedHashMap<>();
    }

    private void saveState(String clientId) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("clientId", clientId);
        json.put("refreshToken", client.refreshToken());
        json.put("localFolder", local.toString());
        json.put("folderId", folderId);
        Map<String, Object> files = new LinkedHashMap<>();
        synced.forEach((name, s) -> files.put(name, Map.of("id", s.id(), "md5", s.md5())));
        json.put("files", files);
        Files.createDirectories(McpSettings.directory());
        Path file = stateFile();
        Path temp = file.resolveSibling("drive.json.tmp");
        // ログインの鍵が入っているので、ほかの利用者から読めないようにする
        try {
            Files.deleteIfExists(temp);
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ex) {
            // Windows など。利用者ごとのフォルダ（AppData）の中なので、もともと本人しか読めない
        }
        Files.writeString(temp, Json.write(json), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
