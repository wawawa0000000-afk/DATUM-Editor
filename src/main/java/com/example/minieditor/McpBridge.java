package com.example.minieditor;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * AI のアプリ（Claude Desktop や Claude Code）と、動いているエディタとの「橋渡し役」。
 *
 * AI のアプリの多くは、MCP のサーバーを「自分で起動した小さなプログラム」として扱い、
 * そのプログラムの標準入力に1行ずつ JSON を書き、標準出力から1行ずつ返事を読む（stdio という方式）。
 * 一方、エディタは別のプログラムとして、すでに動いている。
 *
 * そこで、同じ JAR を「--mcp-stdio」を付けて起動すると、画面を出さずにこの橋渡し役として動くようにした。
 *   ・あいさつや、道具の一覧のような「決まった返事」は、ここで直接答える（エディタが起動していなくても答えられる）。
 *   ・道具を実際に使う頼みごとだけを、エディタの中の HTTP サーバーへ転送する。
 * つなぐ先のポート番号と合言葉は、設定ファイル（McpSettings）から読む。
 */
public final class McpBridge {

    /** この引数を付けて起動すると、橋渡し役になる。 */
    public static final String OPTION = "--mcp-stdio";

    private static final String NOT_RUNNING = "DATUM-Editor が起動していないか、AI 連携が止まっています。"
            + "利用者に、DATUM-Editor を起動して、メニューの「AI 連携 → MCP サーバーを動かす」にチェックを入れてもらってから、"
            + "もう一度試してください。";

    private McpBridge() {
    }

    public static void run() throws IOException {
        // 標準出力は「返事の JSON だけ」を流す決まりなので、専用の出口を確保しておく。
        // ほかの所が System.out に何か書いても混ざらないように、System.out は標準エラーへ向け直す。
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        System.setOut(System.err);

        McpServer server = new McpServer(McpBridge::forward);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {        // 相手が入力を閉じたら（null）、終了する
            if (line.isBlank()) {
                continue;
            }
            String response = server.handle(line);
            if (response != null) {
                out.print(response);
                out.print('\n');                        // 1行が1つのメッセージ
                out.flush();
            }
        }
    }

    /** 道具を使う頼みごとを、動いているエディタへ転送して、結果を受け取る。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> forward(String name, Map<String, Object> arguments) {
        McpSettings settings = McpSettings.load();      // 毎回読み直す（エディタの再起動で、ポートが変わることがあるため）
        if (settings.token == null) {
            return McpServer.textResult(NOT_RUNNING, true);
        }
        Map<String, Object> request = McpServer.map("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", McpServer.map("name", name, "arguments", arguments));
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + settings.port + "/mcp")
                    .toURL().openConnection(Proxy.NO_PROXY);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5 * 60 * 1000);   // 利用者が画面を操作している間は待つことがあるので、長めにする
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            connection.setRequestProperty("Authorization", "Bearer " + settings.token);
            try (OutputStream body = connection.getOutputStream()) {
                body.write(Json.writeCompact(request).getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            if (status == 401) {
                return McpServer.textResult("エディタとの合言葉が合いませんでした。DATUM-Editor を再起動して、"
                        + "「AI 連携 → MCP サーバーを動かす」を入れ直してください。", true);
            }
            if (status != 200) {
                return McpServer.textResult("エディタから、予想していない返事が来ました（HTTP " + status + "）。", true);
            }
            String text;
            try (InputStream response = connection.getInputStream()) {
                text = new String(response.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (Json.parse(text) instanceof Map<?, ?> reply) {
                if (reply.get("result") instanceof Map<?, ?> result) {
                    return (Map<String, Object>) result;
                }
                if (reply.get("error") instanceof Map<?, ?> error) {
                    return McpServer.textResult("エディタがエラーを返しました: " + error.get("message"), true);
                }
            }
            return McpServer.textResult("エディタからの返事を読めませんでした。", true);
        } catch (ConnectException ex) {
            return McpServer.textResult(NOT_RUNNING, true);
        } catch (IOException | Json.JsonException ex) {
            return McpServer.textResult("エディタとの通信に失敗しました: " + ex, true);
        }
    }
}
