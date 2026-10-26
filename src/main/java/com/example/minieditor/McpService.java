package com.example.minieditor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * AI 連携（MCP）の窓口。エディタの中で小さな HTTP サーバーを動かして、AI からの頼みごとを受け付ける。
 *
 * 安全のために：
 *   ・この PC の中からしかつながらない（127.0.0.1 だけで待ち受ける）。
 *   ・合言葉（トークン）を知っているプログラムだけが使える。合言葉は、利用者ごとの設定ファイルにある。
 *   ・ブラウザで開いた悪意のあるページから勝手に使われないように、ブラウザが付ける Origin という印が
 *     付いた通信と、宛先（Host）がこの PC ではない通信は断る。
 *
 * HTTP サーバーは、JDK に入っている com.sun.net.httpserver を使っている（外部のライブラリではない）。
 */
public final class McpService {

    /** 返事に付ける印。ポートがふさがっているとき、相手が別の DATUM-Editor かどうかを見分けるのに使う（橋渡し役も確かめる）。 */
    static final String MARK_HEADER = "X-Datum-Editor";
    /**
     * 橋渡し役が、合言葉を送る前に「相手が本物のエディタか」を確かめるための見出し。
     * 橋渡し役が乱数（CHALLENGE_HEADER）を送り、エディタは「合言葉と乱数から作った証明」（PROOF_HEADER）を返す。
     * 合言葉を知らない別のプログラムには証明が作れないので、そこへ合言葉を渡さずに済む。
     */
    static final String CHALLENGE_HEADER = "X-Datum-Challenge";
    static final String PROOF_HEADER = "X-Datum-Proof";
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private final EditorFrame frame;
    private final McpSettings settings = McpSettings.load();
    // 画面のスレッドで書き換え、通信のスレッドから読むので、volatile にしている
    private volatile HttpServer http;
    private volatile McpServer server;
    private ExecutorService workers;            // 通信を受け付けるスレッドの組（止めるときに片付ける）

    public McpService(EditorFrame frame) {
        this.frame = frame;
    }

    public McpSettings settings() {
        return settings;
    }

    public boolean isRunning() {
        return http != null;
    }

    public boolean hasWorkspace() {
        return settings.workspace != null && Files.isDirectory(settings.workspace);
    }

    /** AI に任せるフォルダを決めて、設定に保存する。 */
    public void setWorkspace(Path folder) throws IOException {
        settings.workspace = folder.toRealPath();
        settings.save();
    }

    // ---------- 開始と停止 ----------

    public void start() throws IOException {
        if (http != null) {
            return;
        }
        if (!hasWorkspace()) {
            throw new IOException("AI に任せるフォルダが選ばれていません");
        }
        if (settings.token == null || settings.token.length() < 32) {
            settings.token = newToken();
        }
        server = new McpServer(new McpTools(frame, settings));

        // 前回と同じポートを使う。ほかのソフトが使っていたら、次の番号を順に試す
        HttpServer created = null;
        int port = settings.port;
        for (int tries = 0; tries < 10 && created == null; tries++, port++) {
            try {
                created = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
                settings.port = port;
            } catch (BindException ex) {
                if (isAnotherEditor(port)) {
                    throw new IOException("ほかの DATUM-Editor が、すでに AI 連携を使っています（ポート " + port
                            + "）。AI 連携を使えるのは、同時に1つのウィンドウだけです。");
                }
            }
        }
        if (created == null) {
            throw new IOException("空いているポートが見つかりませんでした（" + settings.port + " 番から 10 個を試しました）");
        }
        created.createContext("/mcp", this::handle);
        workers = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "mcp-http");
            thread.setDaemon(true);         // エディタを閉じるとき、このスレッドが終了のじゃまをしないようにする
            return thread;
        });
        created.setExecutor(workers);
        created.start();
        http = created;
        settings.enabled = true;
        settings.save();
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (workers != null) {
            workers.shutdownNow();          // 止めるたびにスレッドが残って、たまっていかないように
            workers = null;
        }
        settings.enabled = false;
        try {
            settings.save();
        } catch (IOException ex) {
            // 保存できなくても、止めること自体はできている
        }
    }

    /** 予想できない合言葉を作る（乱数 32 バイトを 16 進数にしたもの）。 */
    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** そのポートで待ち受けているのが、別の DATUM-Editor かどうかを確かめる。 */
    private static boolean isAnotherEditor(int port) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/mcp")
                    .toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(500);
            connection.setReadTimeout(500);
            connection.getResponseCode();
            return connection.getHeaderField(MARK_HEADER) != null;
        } catch (IOException ex) {
            return false;
        }
    }

    // ---------- 通信の受け付け ----------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set(MARK_HEADER, McpServer.SERVER_VERSION);
            int port = exchange.getLocalAddress().getPort();

            // 1) ブラウザ経由で、よそのページから使われるのを防ぐ
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!("127.0.0.1:" + port).equals(host) && !("localhost:" + port).equals(host)) {
                send(exchange, 403, "Forbidden: unexpected Host");
                return;
            }
            if (exchange.getRequestHeaders().getFirst("Origin") != null) {
                send(exchange, 403, "Forbidden: browser requests are not accepted");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                send(exchange, 405, "Method Not Allowed");
                return;
            }

            // 2) 橋渡し役からの「本物か」の問い合わせには、合言葉から作った証明を返す（合言葉そのものは出さない）
            String challenge = exchange.getRequestHeaders().getFirst(CHALLENGE_HEADER);
            if (challenge != null && exchange.getRequestHeaders().getFirst("Authorization") == null) {
                String proof = proof(settings.token, challenge);
                if (proof == null) {
                    send(exchange, 400, "Bad Request: bad challenge");
                    return;
                }
                exchange.getResponseHeaders().set(PROOF_HEADER, proof);
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // 3) 合言葉を確かめる
            if (!tokenMatches(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(exchange, 401, "Unauthorized");
                return;
            }
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.toLowerCase().startsWith("application/json")) {
                send(exchange, 415, "Unsupported Media Type: use application/json");
                return;
            }

            // 4) 中身を読んで、返事を作る
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readNBytes(MAX_BODY + 1);
            }
            if (body.length > MAX_BODY) {
                send(exchange, 413, "Payload Too Large");
                return;
            }
            String response = server.handle(new String(body, StandardCharsets.UTF_8));
            if (response == null) {
                exchange.sendResponseHeaders(202, -1);      // 「知らせ」には、中身のない返事をする決まり
                return;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (RuntimeException ex) {
            send(exchange, 500, "Internal Server Error");
        } finally {
            exchange.close();
        }
    }

    private static void send(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * 合言葉と乱数から作る証明（HMAC-SHA256 の 16 進数）。乱数が決まった形（16 進数 64 文字）でなければ null。
     * エディタが返し、橋渡し役が自分でも作って比べる。
     */
    static String proof(String token, String challenge) {
        if (token == null || challenge == null || !challenge.matches("[0-9a-f]{64}")) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            // ほかの使いみちの計算と混ざらないよう、決まった前置きを付ける
            return HexFormat.of().formatHex(mac.doFinal(("datum-editor-proof\n" + challenge).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);   // HmacSHA256 は、どの Java にも必ずある
        }
    }

    private boolean tokenMatches(String authorization) {
        String token = settings.token;
        if (authorization == null || token == null) {
            return false;
        }
        // MessageDigest.isEqual は、合っている文字数によって比べる時間が変わらない
        // （時間の差から合言葉を少しずつ当てられるのを防ぐ）
        return MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8),
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- つなぎ方の案内 ----------

    /**
     * AI のアプリに登録する「橋渡し役」の起動のしかた（プログラムと、渡す引数）。
     * 今このエディタを動かしているのと同じ Java・同じ JAR を指すようにする。
     */
    public static List<String> bridgeCommand() {
        String executable = ProcessHandle.current().info().command().orElse("java");
        String name = Path.of(executable).getFileName().toString().toLowerCase();
        if (name.startsWith("javaw")) {
            // Windows の javaw は画面用で、文字のやり取りには java のほうを使う
            Path console = Path.of(executable).resolveSibling(name.endsWith(".exe") ? "java.exe" : "java");
            if (Files.exists(console)) {
                executable = console.toString();
            }
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        if (name.startsWith("java")) {
            try {
                Path code = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (Files.isRegularFile(code)) {
                    command.add("-jar");
                    command.add(code.toString());
                } else {
                    command.add("-cp");                 // 開発中（JAR にまとめる前のクラスから動かしている場合）
                    command.add(code.toString());
                    command.add(Main.class.getName());
                }
            } catch (Exception ex) {
                command.add("-jar");
                command.add("minieditor.jar");
            }
        }
        // java 以外（インストーラーで入れた場合の起動ファイル）は、そのまま引数を渡せば動く
        command.add(McpBridge.OPTION);
        return command;
    }

    /** 「接続のしかた」に出す、Claude Code 用の登録コマンド。 */
    public static String claudeCodeCommand() {
        StringBuilder sb = new StringBuilder("claude mcp add " + McpServer.SERVER_NAME + " --");
        for (String part : bridgeCommand()) {
            sb.append(' ').append(quote(part));
        }
        return sb.toString();
    }

    /** 「接続のしかた」に出す、Claude Desktop の設定ファイルに書く内容。 */
    public static String claudeDesktopConfig() {
        List<String> command = bridgeCommand();
        Map<String, Object> entry = McpServer.map("command", command.get(0),
                "args", new ArrayList<Object>(command.subList(1, command.size())));
        return Json.write(McpServer.map("mcpServers", McpServer.map(McpServer.SERVER_NAME, entry)));
    }

    public String describeConnection() {
        return (isRunning() ? "AI 連携（MCP）は動いています。" : "AI 連携（MCP）は止まっています。") + "\n\n"
                + "■ AI に任せているフォルダ\n"
                + (settings.workspace == null ? "（まだ選ばれていません）" : settings.workspace) + "\n"
                + "AI は、このフォルダの中のファイルと、エディタで開いている文書だけを読み書きできます。\n\n"
                + "■ Claude Code から使う（ターミナルで、1回だけ実行します）\n"
                + claudeCodeCommand() + "\n\n"
                + "■ Claude Desktop から使う\n"
                + "設定 → 開発者 → 「構成を編集」で開く claude_desktop_config.json に、次を書き足して、Claude Desktop を再起動します。\n"
                + claudeDesktopConfig() + "\n"
                + "■ そのほかの AI（HTTP で直接つなぐ場合）\n"
                + "URL: http://127.0.0.1:" + settings.port + "/mcp\n"
                + "ヘッダー: Authorization: Bearer " + (settings.token == null ? "（開始すると作られます）" : settings.token) + "\n\n"
                + "■ 頼み方の例\n"
                + "「今開いている文書の誤字脱字を直して」「『猫』が出てくる所を全部探して」\n"
                + "「この文書を 第一章.txt という名前で保存して」「下書きフォルダの中を整理して」\n\n"
                + "■ ご注意\n"
                + "・AI に文書を読ませると、その内容は、お使いの AI のサービスに送られます。\n"
                + "・AI が書き換えた所は緑色になり、Ctrl+Z で元に戻せます。\n"
                + "・AI が「ごみ箱へ」入れたファイルは、フォルダの中の .datum-trash に残ります。\n"
                + "・つなぐ先は、エディタが起動していて、この「AI 連携」が動いている間だけ応えます。\n";
    }

    /** コマンドとして打てるように、空白などを含むものを引用符で囲む。 */
    private static String quote(String part) {
        if (part.matches("[A-Za-z0-9_./:=+\\-\\\\]+")) {
            return part;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? "\"" + part + "\"" : "'" + part.replace("'", "'\\''") + "'";
    }
}
