package com.example.minieditor;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

/**
 * ほかのプログラムとのつなぎ口のテスト。
 *   ・橋渡し役（McpBridge）が、合言葉を送る前に、相手が本物のエディタかを確かめること
 *   ・2つ目の起動からファイルを受け取る口（SingleInstance）が、長すぎる行や多すぎる量を受け付けないこと
 * どちらも、本物の設定フォルダには触らない（自分で小さな待ち受けを作って試す）。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.ConnectionTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class ConnectionTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        proof();
        bridge();
        lines();
        instance();
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static final String TOKEN = "a".repeat(64);
    private static final String CHALLENGE = "0123456789abcdef".repeat(4);

    private static void proof() {
        String proof = McpService.proof(TOKEN, CHALLENGE);
        check(proof != null && proof.matches("[0-9a-f]{64}"), "証明は 16 進数 64 文字");
        check(proof != null && !proof.contains(TOKEN), "証明に合言葉そのものは入らない");
        check(!proof.equals(McpService.proof("b".repeat(64), CHALLENGE)), "合言葉が違えば、証明も違う");
        check(McpService.proof(TOKEN, "短い") == null, "決まった形でない乱数には、証明を作らない");
    }

    /** 偽のエディタを立てて、橋渡し役が見分けられるかを試す。 */
    private static void bridge() throws Exception {
        String[] reply = {null};            // 偽のエディタが返す証明（null なら返さない）
        List<String> received = new ArrayList<>();
        HttpServer fake = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4);
        fake.createContext("/mcp", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                received.add(auth);         // 合言葉が送られてきた
            }
            String challenge = exchange.getRequestHeaders().getFirst(McpService.CHALLENGE_HEADER);
            if (challenge != null && reply[0] != null) {
                exchange.getResponseHeaders().set(McpService.PROOF_HEADER,
                        reply[0].equals("right") ? McpService.proof(TOKEN, challenge) : reply[0]);
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        fake.start();
        try {
            int port = fake.getAddress().getPort();
            reply[0] = "right";
            check(McpBridge.isEditor(port, TOKEN), "合言葉を知っている相手は、本物と認める");
            reply[0] = null;
            check(!McpBridge.isEditor(port, TOKEN), "証明を返さない相手は、本物と認めない");
            reply[0] = "0".repeat(64);
            check(!McpBridge.isEditor(port, TOKEN), "でたらめな証明を返す相手は、本物と認めない");
            check(received.isEmpty(), "確かめる間に、合言葉は一度も送っていない");
        } finally {
            fake.stop(0);
        }
    }

    private static void lines() throws IOException {
        long later = System.nanoTime() + 5_000_000_000L;
        check("abc".equals(SingleInstance.readLine(new StringReader("abc\r\nnext"), 10, later)), "1行読める（\\r は取り除く）");
        check(SingleInstance.readLine(new StringReader("x".repeat(11) + "\n"), 10, later) == null, "長すぎる行は読まない");
        check(SingleInstance.readLine(new StringReader("途中で切れる"), 100, later) == null, "改行の前に切れた行は読まない");
        check(SingleInstance.readLine(new StringReader("abc\n"), 10, System.nanoTime() - 1) == null, "締め切りを過ぎたら読まない");
    }

    /** 受け取り口に、いろいろなものを送ってみる。 */
    private static void instance() throws Exception {
        String token = "c".repeat(32);
        check(send(token, token + "\n/tmp/a.txt\n\n").equals(List.of(Path.of("/tmp/a.txt"))), "合言葉が合えば、ファイルを受け取る");
        check(send(token, "x".repeat(100_000) + "\n") == null, "とても長い1行目は、途中で読むのをやめる");
        check(send(token, "d".repeat(32) + "\n/tmp/a.txt\n\n") == null, "合言葉が違えば、受け取らない");
        StringBuilder many = new StringBuilder(token + "\n");
        for (int i = 0; i < 101; i++) {
            many.append("/tmp/").append(i).append(".txt\n");
        }
        check(send(token, many + "\n") == null, "多すぎるファイルは、受け取らない");
        check(send(token, token + "\n/tmp/" + "y".repeat(5000) + "\n\n") == null, "長すぎる場所は、受け取らない");
    }

    /** 受け取り口に data を送り、受け取られたファイルの一覧を返す（受け取られなければ null）。 */
    private static List<Path> send(String token, String data) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
            Thread writer = new Thread(() -> {
                try {
                    OutputStream out = client.getOutputStream();
                    out.write(data.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    client.shutdownOutput();
                } catch (IOException ex) {
                    // 受け取る側が先に閉じたときは、書けなくてもよい
                }
            });
            writer.start();
            List<List<Path>> opened = new ArrayList<>();
            try (Socket socket = server.accept()) {
                SingleInstance.receive(socket, token, opened::add);
            }
            writer.join(5000);
            javax.swing.SwingUtilities.invokeAndWait(() -> { });    // 受け取ったものは、画面のスレッドで渡される
            return opened.isEmpty() ? null : opened.get(0);
        }
    }
}
