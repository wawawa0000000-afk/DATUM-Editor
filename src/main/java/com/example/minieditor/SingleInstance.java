package com.example.minieditor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

/**
 * ファイルを指定して起動したとき（「DATUM-Editor.bat 原稿.txt」、アイコンへのドラッグ、「プログラムから開く」）に、
 * すでにエディタが開いていれば、新しいウィンドウを出さずに、そのウィンドウのタブで開いてもらう（VSCode と同じ動き）。
 *
 * しくみ：動いているエディタは、自分のパソコンの中だけから届く口（127.0.0.1 のポート）で待ち受け、
 * ポート番号と合言葉を設定フォルダの instance.json に書いておく。
 * あとから起動したほうは、それを読んで、合言葉とファイルの場所を1行ずつ送り、「OK」が返ってきたら終わる。
 * 届かなければ（エディタが動いていない・前回の書き置きが古い）、ふつうに起動する。
 */
final class SingleInstance {

    // 受け取る量の上限。同じパソコンのほかの利用者もこの口につなげるので、送られ続けても、
    // メモリがあふれたり、待ち受けを占められたりしないようにする
    private static final int MAX_TOKEN = 64;            // 合言葉の行（本物は 32 文字）
    private static final int MAX_PATH = 4096;           // ファイルの場所1行
    private static final int MAX_FILES = 100;           // 一度に開くファイルの数
    private static final long RECEIVE_MILLIS = 5000;    // 1つの受け取りにかけてよい時間

    private SingleInstance() {
    }

    private static Path file() {
        return McpSettings.directory().resolve("instance.json");
    }

    /** 動いているエディタに、files を開いてもらう。受け取ってもらえたら true。 */
    @SuppressWarnings("unchecked")
    static boolean sendToRunning(List<Path> files) {
        try {
            if (!Files.isRegularFile(file())
                    || !(Json.parse(Files.readString(file(), StandardCharsets.UTF_8)) instanceof Map<?, ?> raw)) {
                return false;
            }
            Map<String, Object> json = (Map<String, Object>) raw;
            int port = Json.num(json, "port", -1);
            String token = Json.str(json, "token", null);
            if (port <= 0 || token == null) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
                socket.setSoTimeout(3000);
                Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                out.write(token + "\n");
                for (Path path : files) {
                    out.write(path + "\n");
                }
                out.write("\n");                // 空の行が、送り終わりの印
                out.flush();
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                return "OK".equals(in.readLine());
            }
        } catch (IOException | Json.JsonException ex) {
            return false;                       // 届かなければ、自分で開く
        }
    }

    /** 待ち受けを始める。ほかの起動から届いたファイルは、画面のスレッドで open に渡す。 */
    static void listen(Consumer<List<Path>> open) {
        try {
            ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            byte[] secret = new byte[16];
            new SecureRandom().nextBytes(secret);
            String token = HexFormat.of().formatHex(secret);
            Files.createDirectories(file().getParent());
            FileService.writeSafely(file(), Json.write(Map.of("port", server.getLocalPort(), "token", token)), true);
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        receive(socket, token, open);
                    } catch (IOException ex) {
                        // 1つの受け取りに失敗しても、待ち受けは続ける
                    }
                }
            }, "single-instance");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException ex) {
            // 待ち受けられなくても、エディタはふつうに使える（2つ目の起動が、別のウィンドウになるだけ）
        }
    }

    static void receive(Socket socket, String token, Consumer<List<Path>> open) throws IOException {
        socket.setSoTimeout(3000);
        long deadline = System.nanoTime() + RECEIVE_MILLIS * 1_000_000;
        Reader in = new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8);
        String first = readLine(in, MAX_TOKEN, deadline);
        if (first == null || !MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return;                             // 合言葉が違う相手には、何もしない
        }
        List<Path> files = new ArrayList<>();
        String line;
        while ((line = readLine(in, MAX_PATH, deadline)) != null && !line.isEmpty()) {
            if (files.size() >= MAX_FILES) {
                return;                         // 多すぎるときは、何も開かない
            }
            try {
                files.add(Path.of(line));
            } catch (RuntimeException ex) {
                // ファイルの場所として読めない行は飛ばす
            }
        }
        if (line == null) {
            return;                             // 長すぎる行・時間切れ・途中で切れた：送り終わりの印（空の行）が来ていない
        }
        Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        out.write("OK\n");
        out.flush();
        SwingUtilities.invokeLater(() -> open.accept(files));
    }

    /**
     * 1行読む（改行は含めない。\r も取り除く）。max 文字を超えた・締め切りを過ぎた・行の途中で切れたときは null。
     * BufferedReader.readLine は、改行が来るまでいくらでも読み続けてしまうので、自分で読む。
     */
    static String readLine(Reader in, int max, long deadline) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (System.nanoTime() < deadline) {
            int c = in.read();
            if (c < 0) {
                return null;
            }
            if (c == '\n') {
                return sb.toString();
            }
            if (c == '\r') {
                continue;
            }
            if (sb.length() >= max) {
                return null;
            }
            sb.append((char) c);
        }
        return null;
    }

    /** 起動したときの引数から、開くファイルを取り出す（場所は、起動した所から見た絶対パスにする）。 */
    static List<Path> filesFrom(String[] args) {
        List<Path> files = new ArrayList<>();
        for (String arg : args) {
            if (arg.isBlank() || arg.startsWith("--")) {
                continue;
            }
            try {
                files.add(Path.of(arg).toAbsolutePath().normalize());
            } catch (RuntimeException ex) {
                // ファイルの場所として読めない引数は飛ばす
            }
        }
        return files;
    }
}
