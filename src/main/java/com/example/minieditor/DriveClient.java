package com.example.minieditor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;

/**
 * Google Drive との通信（ログインと、ファイルの一覧・読み込み・書き込み）。外部のライブラリは使わず、HTTP で直接やり取りする。
 *
 * ログインは「パソコン用アプリ」の決まった手順（OAuth 2.0 ＋ PKCE）で行う：
 *   1. ブラウザで Google のログイン画面を開く
 *   2. 許可されると、ブラウザがこの PC の小さな受け口（127.0.0.1）へ戻ってくるので、そこで「許可の印」を受け取る
 *   3. 印を Google に渡して、通行証（アクセストークン）と、通行証を作り直すための鍵（リフレッシュトークン）をもらう
 *
 * クライアント ID は、使う人がそれぞれ Google Cloud で作って設定に入れる（AI の API キーと同じ考え方）。
 * 頼む権限は drive.file だけ。これは「このアプリで作ったファイルだけ」を読み書きできる権限で、
 * Drive にあるほかのファイルは見えない。
 */
final class DriveClient {

    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    /** Drive 上のファイル1つ分。md5 は中身から計算した値で、変わったかどうかを比べるのに使う。 */
    record RemoteFile(String id, String name, String md5) {
    }

    /** 通信先。ふだんは Google だが、テストでは偽物のサーバーに向ける。 */
    record Endpoints(String auth, String token, String api, String upload) {
        static final Endpoints GOOGLE = new Endpoints(
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "https://www.googleapis.com/drive/v3",
                "https://www.googleapis.com/upload/drive/v3");

        /** テスト用：システムプロパティ datum.drive.endpoint があれば、そこをすべての通信先にする。 */
        static Endpoints current() {
            String base = System.getProperty("datum.drive.endpoint");
            if (base == null || base.isBlank()) {
                return GOOGLE;
            }
            return new Endpoints(base + "/auth", base + "/token", base + "/drive/v3", base + "/upload/drive/v3");
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final Endpoints endpoints;
    private final String clientId;
    private final String clientSecret;
    private String refreshToken;
    private String accessToken;
    private long accessExpires;     // System.currentTimeMillis() で、この時刻を過ぎたら作り直す

    DriveClient(String clientId, String clientSecret, String refreshToken) {
        this.endpoints = Endpoints.current();
        this.clientId = clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.refreshToken = refreshToken;
    }

    String refreshToken() {
        return refreshToken;
    }

    boolean loggedIn() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    // ---------- ログイン ----------

    /**
     * ブラウザでログインしてもらい、リフレッシュトークンを受け取る。待っている間は、呼んだスレッドが止まる。
     *
     * @param openBrowser ログイン画面の URL を受け取って、ブラウザで開く処理
     */
    void login(Consumer<String> openBrowser) throws IOException, InterruptedException {
        String verifier = randomText(48);
        String state = randomText(16);
        CompletableFuture<Map<String, String>> answer = new CompletableFuture<>();

        // ブラウザが戻ってくる受け口。この PC の中からしか届かない 127.0.0.1 で、空いているポートを使う
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            boolean ok = query.containsKey("code") && state.equals(query.get("state"));
            String page = "<!doctype html><meta charset='utf-8'><title>DATUM-Editor</title>"
                    + "<body style='font-family:sans-serif;padding:40px'><h2>"
                    + (ok ? "ログインできました。このタブは閉じて、エディタに戻ってください。"
                          : "ログインできませんでした。エディタに戻って、もう一度お試しください。")
                    + "</h2></body>";
            byte[] body = page.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            if (query.containsKey("code") || query.containsKey("error")) {
                answer.complete(query);
            }
        });
        server.start();
        try {
            String redirect = "http://127.0.0.1:" + server.getAddress().getPort();
            String url = endpoints.auth() + "?" + form(Map.of(
                    "client_id", clientId,
                    "redirect_uri", redirect,
                    "response_type", "code",
                    "scope", SCOPE,
                    "code_challenge", challenge(verifier),
                    "code_challenge_method", "S256",
                    "access_type", "offline",
                    "prompt", "consent",
                    "state", state));
            openBrowser.accept(url);

            Map<String, String> query;
            try {
                query = answer.get(5, TimeUnit.MINUTES);
            } catch (TimeoutException ex) {
                throw new IOException("5 分待ちましたが、ログインが終わりませんでした");
            } catch (ExecutionException ex) {
                throw new IOException(ex.getCause());
            }
            if (query.containsKey("error")) {
                throw new IOException("ログインが取り消されました（" + query.get("error") + "）");
            }
            if (!state.equals(query.get("state"))) {
                throw new IOException("ログインの返事が正しくありませんでした");
            }
            Map<String, Object> token = postForm(endpoints.token(), Map.of(
                    "code", query.get("code"),
                    "client_id", clientId,
                    "client_secret", clientSecret,
                    "redirect_uri", redirect,
                    "grant_type", "authorization_code",
                    "code_verifier", verifier));
            String refresh = Json.str(token, "refresh_token", null);
            if (refresh == null) {
                throw new IOException("リフレッシュトークンを受け取れませんでした");
            }
            refreshToken = refresh;
            takeAccess(token);
        } finally {
            server.stop(0);
        }
    }

    /** 通行証を返す。期限が近ければ、リフレッシュトークンで作り直す。 */
    private String access() throws IOException, InterruptedException {
        if (!loggedIn()) {
            throw new IOException("Google Drive にログインしていません");
        }
        if (accessToken == null || System.currentTimeMillis() > accessExpires) {
            Map<String, Object> token;
            try {
                token = postForm(endpoints.token(), Map.of(
                        "refresh_token", refreshToken,
                        "client_id", clientId,
                        "client_secret", clientSecret,
                        "grant_type", "refresh_token"));
            } catch (IOException ex) {
                if (ex.getMessage() != null && ex.getMessage().contains("invalid_grant")) {
                    // 許可が取り消された・期限が切れた。もう一度ログインしてもらう
                    refreshToken = null;
                    throw new IOException("Drive の許可が切れています。もう一度ログインしてください");
                }
                throw ex;
            }
            takeAccess(token);
        }
        return accessToken;
    }

    private void takeAccess(Map<String, Object> token) throws IOException {
        accessToken = Json.str(token, "access_token", null);
        if (accessToken == null) {
            throw new IOException("通行証（アクセストークン）を受け取れませんでした");
        }
        int seconds = Json.num(token, "expires_in", 3600);
        accessExpires = System.currentTimeMillis() + Math.max(60, seconds - 60) * 1000L;   // 少し早めに作り直す
    }

    // ---------- ファイルの操作 ----------

    /** 名前の決まったフォルダを、Drive のいちばん上で探す。なければ作る。 */
    String ensureFolder(String name) throws IOException, InterruptedException {
        String q = "name = '" + name.replace("\\", "\\\\").replace("'", "\\'") + "' and mimeType = '" + FOLDER_MIME
                + "' and 'root' in parents and trashed = false";
        Map<String, Object> found = getJson(endpoints.api() + "/files?" + form(Map.of("q", q, "fields", "files(id)")));
        for (Object item : Json.list(found, "files")) {
            if (item instanceof Map<?, ?> m && m.get("id") instanceof String id) {
                return id;
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("mimeType", FOLDER_MIME);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoints.api() + "/files?fields=id"))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.writeCompact(meta), StandardCharsets.UTF_8));
        return Json.str(sendJson(request), "id", null);
    }

    /** フォルダの中のファイル（フォルダは除く）の一覧。 */
    List<RemoteFile> list(String folderId) throws IOException, InterruptedException {
        List<RemoteFile> files = new ArrayList<>();
        String pageToken = null;
        do {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("q", "'" + folderId + "' in parents and trashed = false and mimeType != '" + FOLDER_MIME + "'");
            params.put("fields", "nextPageToken,files(id,name,md5Checksum)");
            params.put("pageSize", "1000");
            if (pageToken != null) {
                params.put("pageToken", pageToken);
            }
            Map<String, Object> page = getJson(endpoints.api() + "/files?" + form(params));
            for (Object item : Json.list(page, "files")) {
                if (item instanceof Map<?, ?> m && m.get("id") instanceof String id && m.get("name") instanceof String name) {
                    files.add(new RemoteFile(id, name, m.get("md5Checksum") instanceof String md5 ? md5 : ""));
                }
            }
            pageToken = Json.str(page, "nextPageToken", null);
        } while (pageToken != null);
        return files;
    }

    /** ファイルの中身を受け取る。 */
    byte[] download(String id) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.api() + "/files/" + id + "?alt=media"))
                .header("Authorization", "Bearer " + access()).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + response.statusCode() + "：" + errorText(new String(response.body(), StandardCharsets.UTF_8)));
        }
        return response.body();
    }

    /** 新しいファイルとして上げる。できたファイルを返す。 */
    RemoteFile create(String folderId, String name, byte[] content) throws IOException, InterruptedException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("parents", List.of(folderId));
        // メタデータと中身を、1回の送信にまとめる（multipart/related）
        String boundary = "datum" + randomText(12);
        byte[] head = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + Json.writeCompact(meta) + "\r\n--" + boundary + "\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        endpoints.upload() + "/files?uploadType=multipart&fields=id,name,md5Checksum"))
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return remoteFile(sendJson(request));
    }

    /** すでにあるファイルの中身を書き換える。 */
    RemoteFile update(String id, byte[] content) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        endpoints.upload() + "/files/" + id + "?uploadType=media&fields=id,name,md5Checksum"))
                .header("Content-Type", "application/octet-stream")
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(content));
        return remoteFile(sendJson(request));
    }

    /** 1つのファイルの今の状態。消されていれば（ごみ箱も含む）null。 */
    RemoteFile get(String id) throws IOException, InterruptedException {
        try {
            Map<String, Object> json = getJson(endpoints.api() + "/files/" + id + "?fields=id,name,md5Checksum,trashed");
            return Json.bool(json, "trashed", false) ? null : remoteFile(json);
        } catch (IOException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("HTTP 404")) {
                return null;
            }
            throw ex;
        }
    }

    /** Drive のごみ箱へ移す（30 日は Drive の画面から戻せる）。 */
    void trash(String id) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoints.api() + "/files/" + id + "?fields=id"))
                .header("Content-Type", "application/json; charset=utf-8")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"trashed\":true}", StandardCharsets.UTF_8));
        sendJson(request);
    }

    private static RemoteFile remoteFile(Map<String, Object> json) {
        return new RemoteFile(Json.str(json, "id", ""), Json.str(json, "name", ""), Json.str(json, "md5Checksum", ""));
    }

    // ---------- 通信の下回り ----------

    private Map<String, Object> getJson(String url) throws IOException, InterruptedException {
        return sendJson(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    /** 通行証を付けて送り、返ってきた JSON を受け取る。 */
    private Map<String, Object> sendJson(HttpRequest.Builder request) throws IOException, InterruptedException {
        request.header("Authorization", "Bearer " + access()).timeout(Duration.ofMinutes(2));
        return readJson(http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> postForm(String url, Map<String, String> params) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofMinutes(1))
                .POST(HttpRequest.BodyPublishers.ofString(form(params), StandardCharsets.UTF_8)).build();
        return readJson(http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(HttpResponse<String> response) throws IOException {
        if (response.statusCode() / 100 != 2) {
            String hint = switch (response.statusCode()) {
                case 401 -> "（もう一度ログインしてください）";
                case 403 -> "（Google Cloud で Drive API を有効にしたか確かめてください）";
                default -> "";
            };
            throw new IOException("HTTP " + response.statusCode() + "：" + errorText(response.body()) + hint);
        }
        try {
            if (Json.parse(response.body()) instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Json.JsonException ex) {
            // 下で知らせる
        }
        throw new IOException("Google からの返事を読めませんでした");
    }

    /** エラーの返事から、説明の文だけを取り出す。 */
    private static String errorText(String body) {
        try {
            if (Json.parse(body) instanceof Map<?, ?> m) {
                if (m.get("error") instanceof Map<?, ?> error && error.get("message") != null) {
                    return String.valueOf(error.get("message"));
                }
                if (m.get("error") instanceof String code) {
                    return code + (m.get("error_description") != null ? "：" + m.get("error_description") : "");
                }
            }
        } catch (Json.JsonException ex) {
            // JSON でなければ、そのまま見せる
        }
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }

    static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static String randomText(int bytes) {
        byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /** PKCE：合言葉（verifier）の SHA-256 を、ログイン画面に先に見せておく。 */
    private static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
