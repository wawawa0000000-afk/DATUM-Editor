package com.example.minieditor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 会話欄の「つなぎ先」。利用者が使いたい AI に合わせて、3つの方法を用意した。
 *
 *   ・Claude Code … 利用者の PC に入っている Claude Code（claude コマンド）を、裏で1回ずつ動かす。
 *                   Claude の月額プランでログインしていれば、その範囲で使える。文書の読み書きは MCP を通す。
 *   ・Anthropic API … API キーで Claude を直接呼ぶ（使った分だけの課金）。
 *   ・OpenAI 互換 API … ChatGPT、Gemini、OpenRouter、Ollama（PC の中で動く AI）など、同じ形の窓口を持つ AI。
 *
 * API の2つは、エディタの「道具」（McpTools。AI 連携と同じもの）を AI に渡し、
 * 「AI が道具を使う → エディタが実行して結果を返す」を、AI が答え終わるまで繰り返す。
 * どれも画面のスレッドでは動かさない（返事を待つ間、画面が止まってしまうため）。
 */
final class AiBackends {

    private AiBackends() {
    }

    /** 会話の途中経過を、会話欄へ知らせる窓口。別のスレッドから呼ばれる。 */
    interface Listener {
        void text(String text);         // AI の返事（文章）

        void tool(String name);         // AI が道具を使った

        void info(String text);         // 使った量などのお知らせ

        void error(String text);
    }

    /** つなぎ先1つ。会話の履歴を持っていて、reset() で新しい会話に戻る。 */
    interface Backend {
        /** 1回分のやり取りをする（終わるまで戻らない）。 */
        void send(String userText, Listener listener) throws Exception;

        void stop();

        void reset();

        /** 会話の続きを保存するための中身（JSON にできる形）。まだ何も話していなければ null。 */
        Object state();

        /** state() で取り出した中身から、その会話の続きに戻す。 */
        void restore(Object state);
    }

    static final String SYSTEM = """
            あなたは、日本語の文章・小説を書くためのエディタ「DATUM-Editor」の中で動いているアシスタントです。
            利用者とは日本語で話します。答えは短く、分かりやすくします。
            文書を読んだり直したりするときは、用意された道具を使います（推測で文書の内容を語らないこと）。
            """;

    /** Anthropic API で、モデルを指定しなかったときに使うモデル。 */
    static final String ANTHROPIC_MODEL = "claude-opus-5-5";
    /** OpenAI 互換で、接続先を指定しなかったときの接続先。 */
    static final String OPENAI_URL = "https://api.openai.com/v1";

    private static final int MAX_STEPS = 25;        // 道具を使う繰り返しの上限（終わらなくなるのを防ぐ）

    // ---------- 共通：道具の一覧と実行 ----------

    private static String resultText(Map<String, Object> result) {
        StringBuilder sb = new StringBuilder();
        if (result.get("content") instanceof List<?> content) {
            for (Object block : content) {
                if (block instanceof Map<?, ?> m && m.get("text") instanceof String text) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private static String shortName(String name) {
        int at = name.lastIndexOf("__");
        return at >= 0 ? name.substring(at + 2) : name;
    }

    // ---------- Anthropic API ----------

    static final class Anthropic implements Backend {
        static final String API_URL = "https://api.anthropic.com/v1/messages";
        private final String url;
        private final String apiKey;
        private final String model;
        private final McpServer.Backend tools;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        private final List<Object> messages = new ArrayList<>();
        private volatile boolean stopped;

        Anthropic(String apiKey, String model, McpServer.Backend tools) {
            this(API_URL, apiKey, model, tools);
        }

        /** url を変えられるのは、テストで偽のサーバーにつなぐため。 */
        Anthropic(String url, String apiKey, String model, McpServer.Backend tools) {
            this.url = url;
            this.apiKey = apiKey;
            this.model = model;
            this.tools = tools;
        }

        @Override
        public void reset() {
            messages.clear();
        }

        @Override
        public Object state() {
            return messages.isEmpty() ? null : new ArrayList<>(messages);
        }

        @Override
        public void restore(Object state) {
            messages.clear();
            if (state instanceof List<?> saved) {
                messages.addAll(saved);
            }
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(String userText, Listener listener) throws Exception {
            stopped = false;
            // 前回、途中で止めた・失敗したときは、最後が利用者側のメッセージのまま残っている。
            // 利用者側が2つ続かないように、その中に今回の頼みごとを足す
            Object last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (last instanceof Map<?, ?> m && "user".equals(m.get("role"))) {
                List<Object> blocks = new ArrayList<>();
                if (m.get("content") instanceof List<?> old) {
                    blocks.addAll(old);
                } else {
                    blocks.add(McpServer.map("type", "text", "text", String.valueOf(m.get("content"))));
                }
                blocks.add(McpServer.map("type", "text", "text", userText));
                messages.set(messages.size() - 1, McpServer.map("role", "user", "content", blocks));
            } else {
                messages.add(McpServer.map("role", "user", "content", userText));
            }
            List<Object> toolList = new ArrayList<>();
            for (Map<String, Object> tool : McpServer.toolDefinitions()) {
                toolList.add(McpServer.map("name", tool.get("name"), "description", tool.get("description"),
                        "input_schema", tool.get("inputSchema")));
            }
            long inputTokens = 0;
            long outputTokens = 0;
            for (int step = 0; step < MAX_STEPS && !stopped; step++) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("max_tokens", 16000);
                body.put("system", SYSTEM + "\n" + McpServer.INSTRUCTIONS);
                body.put("tools", toolList);
                body.put("messages", messages);
                boolean fallback = supportsFallback(model);
                if (fallback) {
                    body.put("fallbacks", "default");   // 安全のための判定で断られたとき、別のモデルでやり直してもらう
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(10))
                        .header("content-type", "application/json")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01");
                if (fallback) {
                    request.header("anthropic-beta", "server-side-fallback-2026-07-01");
                }
                Map<String, Object> response = post(http, request, body);
                if (response.get("usage") instanceof Map<?, ?> usage) {
                    inputTokens += number(usage.get("input_tokens"));
                    outputTokens += number(usage.get("output_tokens"));
                }
                List<Object> content = response.get("content") instanceof List<?> c ? (List<Object>) c : List.of();
                String stopReason = Json.str(response, "stop_reason", "");
                if ("refusal".equals(stopReason)) {
                    listener.error("AI が、この依頼への応答を断りました。言い方を変えて、もう一度試してください。");
                    return;
                }
                // 返ってきた内容は、そのままの形で履歴に足す（考えた過程のブロックなども、手を加えずに返す決まり）
                messages.add(McpServer.map("role", "assistant", "content", content));
                List<Object> results = new ArrayList<>();
                for (Object item : content) {
                    if (!(item instanceof Map<?, ?> block)) {
                        continue;
                    }
                    if ("text".equals(block.get("type")) && block.get("text") instanceof String text && !text.isBlank()) {
                        listener.text(text);
                    } else if ("tool_use".equals(block.get("type"))) {
                        String name = String.valueOf(block.get("name"));
                        listener.tool(name);
                        Map<String, Object> input = block.get("input") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                        Map<String, Object> result = tools.callTool(name, new LinkedHashMap<>(input));
                        results.add(McpServer.map("type", "tool_result", "tool_use_id", block.get("id"),
                                "content", resultText(result), "is_error", Boolean.TRUE.equals(result.get("isError"))));
                    }
                }
                if (!"tool_use".equals(stopReason) || results.isEmpty()) {
                    if ("max_tokens".equals(stopReason)) {
                        listener.info("（返事が長すぎたため、途中で切れました）");
                    }
                    break;
                }
                messages.add(McpServer.map("role", "user", "content", results));   // 道具の結果は、1つのメッセージにまとめて返す
            }
            listener.info(String.format("使った量：入力 %,d ／ 出力 %,d トークン", inputTokens, outputTokens));
        }

        private static boolean supportsFallback(String model) {
            return model.equals("claude-opus-5-5") || model.equals("claude-opus-5")
                    || model.equals("claude-sonnet-5-5") || model.equals("claude-fable-5-1");
        }
    }

    // ---------- OpenAI 互換 API ----------

    /** 暗号化されない接続先のときに出す注意。 */
    static final String UNENCRYPTED_WARNING = "この接続先 URL は http なので、API キーと送る文章（原稿の中身も）が暗号化されずに送られます。"
            + "同じネットワークの人に読まれることがあります。インターネットの向こうの AI なら https にしてください。";

    /**
     * 暗号化されない接続先か（http で、しかも自分の PC の中ではない）。
     * http://localhost（Ollama など）は、PC の外へ出ないので、注意しなくてよい。
     */
    static boolean unencrypted(String url) {
        try {
            URI uri = URI.create(url.strip());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            boolean local = host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]") || host.equals("::1");
            return "http".equalsIgnoreCase(uri.getScheme()) && !local;
        } catch (IllegalArgumentException ex) {
            return false;               // URL として読めないものは、送るときにエラーになる
        }
    }

    static final class OpenAiCompatible implements Backend {
        private final String baseUrl;
        private final String apiKey;
        private final String model;
        private final McpServer.Backend tools;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        private final List<Object> messages = new ArrayList<>();
        private volatile boolean stopped;
        private boolean warned;                     // 暗号化されない接続先の注意を、もう出したか（1回だけ出す）

        OpenAiCompatible(String baseUrl, String apiKey, String model, McpServer.Backend tools) {
            this.baseUrl = baseUrl.replaceAll("/+$", "");
            this.apiKey = apiKey;
            this.model = model;
            this.tools = tools;
        }

        @Override
        public void reset() {
            messages.clear();
        }

        @Override
        public Object state() {
            return messages.isEmpty() ? null : new ArrayList<>(messages);
        }

        @Override
        public void restore(Object state) {
            messages.clear();
            if (state instanceof List<?> saved) {
                messages.addAll(saved);
            }
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(String userText, Listener listener) throws Exception {
            stopped = false;
            if (!warned && unencrypted(baseUrl)) {
                listener.info("※ " + UNENCRYPTED_WARNING);
                warned = true;
            }
            if (messages.isEmpty()) {
                messages.add(McpServer.map("role", "system", "content", SYSTEM + "\n" + McpServer.INSTRUCTIONS));
            }
            messages.add(McpServer.map("role", "user", "content", userText));
            List<Object> toolList = new ArrayList<>();
            for (Map<String, Object> tool : McpServer.toolDefinitions()) {
                toolList.add(McpServer.map("type", "function", "function", McpServer.map("name", tool.get("name"),
                        "description", tool.get("description"), "parameters", tool.get("inputSchema"))));
            }
            long inputTokens = 0;
            long outputTokens = 0;
            for (int step = 0; step < MAX_STEPS && !stopped; step++) {
                Map<String, Object> body = McpServer.map("model", model, "messages", messages, "tools", toolList,
                        "stream", false);
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                        .timeout(Duration.ofMinutes(10))
                        .header("content-type", "application/json");
                if (apiKey != null && !apiKey.isBlank()) {
                    request.header("authorization", "Bearer " + apiKey);
                }
                Map<String, Object> response = post(http, request, body);
                if (response.get("usage") instanceof Map<?, ?> usage) {
                    inputTokens += number(usage.get("prompt_tokens"));
                    outputTokens += number(usage.get("completion_tokens"));
                }
                if (!(response.get("choices") instanceof List<?> choices) || choices.isEmpty()
                        || !(choices.get(0) instanceof Map<?, ?> choice)
                        || !(choice.get("message") instanceof Map<?, ?> rawMessage)) {
                    throw new IOException("返事の形が予想と違いました");
                }
                Map<String, Object> message = new LinkedHashMap<>((Map<String, Object>) rawMessage);
                message.putIfAbsent("role", "assistant");
                messages.add(message);
                if (message.get("content") instanceof String text && !text.isBlank()) {
                    listener.text(text);
                }
                List<?> calls = message.get("tool_calls") instanceof List<?> l ? l : List.of();
                if (calls.isEmpty()) {
                    break;
                }
                for (Object item : calls) {
                    if (!(item instanceof Map<?, ?> call) || !(call.get("function") instanceof Map<?, ?> function)) {
                        continue;
                    }
                    String name = String.valueOf(function.get("name"));
                    listener.tool(name);
                    Map<String, Object> input = new LinkedHashMap<>();
                    Object arguments = function.get("arguments");
                    try {
                        if (arguments instanceof String s && !s.isBlank() && Json.parse(s) instanceof Map<?, ?> m) {
                            input.putAll((Map<String, Object>) m);
                        } else if (arguments instanceof Map<?, ?> m) {
                            input.putAll((Map<String, Object>) m);
                        }
                    } catch (Json.JsonException ex) {
                        messages.add(McpServer.map("role", "tool", "tool_call_id", call.get("id"),
                                "content", "引数を JSON として読めませんでした: " + ex.getMessage()));
                        continue;
                    }
                    Map<String, Object> result = tools.callTool(name, input);
                    messages.add(McpServer.map("role", "tool", "tool_call_id", call.get("id"), "content", resultText(result)));
                }
            }
            listener.info(String.format("使った量：入力 %,d ／ 出力 %,d トークン", inputTokens, outputTokens));
        }
    }

    /** JSON を送って、返ってきた JSON を受け取る。エラーの返事は、中の説明を添えて例外にする。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> post(HttpClient http, HttpRequest.Builder request, Map<String, Object> body)
            throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                request.POST(HttpRequest.BodyPublishers.ofString(Json.writeCompact(body), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Object parsed;
        try {
            parsed = Json.parse(response.body());
        } catch (Json.JsonException ex) {
            throw new IOException("HTTP " + response.statusCode() + "：返事を読めませんでした");
        }
        if (response.statusCode() / 100 != 2) {
            String detail = parsed instanceof Map<?, ?> m && m.get("error") instanceof Map<?, ?> error
                    ? String.valueOf(error.get("message")) : response.body();
            String hint = switch (response.statusCode()) {
                case 401, 403 -> "（API キーを確かめてください）";
                case 404 -> "（モデル名か接続先を確かめてください）";
                case 429 -> "（使える量の上限に達しました。少し待つか、残高を確かめてください）";
                default -> "";
            };
            throw new IOException("HTTP " + response.statusCode() + "：" + detail + hint);
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IOException("返事の形が予想と違いました");
        }
        return (Map<String, Object>) map;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    // ---------- Claude Code（利用者の PC に入っている claude コマンド） ----------

    private static volatile String loginPath;       // ふだんのターミナルの PATH（1回だけ調べる）

    static final class ClaudeCode implements Backend {
        private final String command;
        private final String model;
        private final Path workingFolder;
        private String sessionId;           // 2回目からは、同じ会話の続きとして頼む
        private volatile Process process;

        ClaudeCode(String command, String model, Path workingFolder) {
            this.command = command;
            this.model = model;
            this.workingFolder = workingFolder;
        }

        @Override
        public void reset() {
            sessionId = null;
        }

        @Override
        public Object state() {
            return sessionId;       // 会話の中身は Claude Code が覚えているので、続きの番号だけ持っておけばよい
        }

        @Override
        public void restore(Object state) {
            sessionId = state instanceof String id ? id : null;
        }

        @Override
        public void stop() {
            Process p = process;
            if (p != null) {
                p.descendants().forEach(ProcessHandle::destroy);
                p.destroy();
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public void send(String userText, Listener listener) throws Exception {
            Path config = McpSettings.directory().resolve("chat-mcp.json");
            List<String> bridge = McpService.bridgeCommand();
            Files.createDirectories(config.getParent());
            Files.writeString(config, Json.write(McpServer.map("mcpServers", McpServer.map(McpServer.SERVER_NAME,
                    McpServer.map("command", bridge.get(0), "args", bridge.subList(1, bridge.size()))))), StandardCharsets.UTF_8);

            List<String> args = new ArrayList<>(List.of(command, "-p",
                    "--output-format", "stream-json", "--verbose",
                    "--mcp-config", config.toString(), "--strict-mcp-config",
                    "--allowedTools", "mcp__" + McpServer.SERVER_NAME,
                    "--tools", "",                  // Claude Code 自身の道具（ファイル操作やコマンド実行）は使わせない
                    "--append-system-prompt", SYSTEM));
            if (model != null && !model.isBlank()) {
                args.add("--model");
                args.add(model);
            }
            if (sessionId != null) {
                args.add("--resume");
                args.add(sessionId);
            }
            ProcessBuilder builder = new ProcessBuilder(args);
            if (workingFolder != null && Files.isDirectory(workingFolder)) {
                builder.directory(workingFolder.toFile());
            }
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                if (loginPath == null) {
                    String found = TerminalPane.loginPath();
                    loginPath = found == null ? "" : found;
                }
                if (!loginPath.isEmpty()) {
                    builder.environment().put("PATH", loginPath);
                }
            }
            Process p;
            try {
                p = builder.start();
            } catch (IOException ex) {
                listener.error("Claude Code（" + command + "）を起動できませんでした。Claude Code が入っているか、"
                        + "設定の「Claude Code のコマンド」を確かめてください。\n" + ex.getMessage());
                return;
            }
            process = p;
            // 頼みごとは、標準入力で渡す（引数に入れると、長い文章や記号で問題が起きやすいため）
            try (OutputStream in = p.getOutputStream()) {
                in.write(userText.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder errors = new StringBuilder();
            Thread errorReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (errors) {
                            if (errors.length() < 4000) {
                                errors.append(line).append('\n');
                            }
                        }
                    }
                } catch (IOException ex) {
                    // 終わったら読めなくなる
                }
            }, "claude-stderr");
            errorReader.setDaemon(true);
            errorReader.start();

            boolean finished = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    Object parsed;
                    try {
                        parsed = Json.parse(line);
                    } catch (Json.JsonException ex) {
                        continue;
                    }
                    if (!(parsed instanceof Map<?, ?> raw)) {
                        continue;
                    }
                    Map<String, Object> event = (Map<String, Object>) raw;
                    String type = Json.str(event, "type", "");
                    if (event.get("session_id") instanceof String id) {
                        sessionId = id;
                    }
                    if ("assistant".equals(type) && event.get("message") instanceof Map<?, ?> message
                            && message.get("content") instanceof List<?> content) {
                        for (Object item : content) {
                            if (item instanceof Map<?, ?> block) {
                                if ("text".equals(block.get("type")) && block.get("text") instanceof String text && !text.isBlank()) {
                                    listener.text(text);
                                } else if ("tool_use".equals(block.get("type"))) {
                                    listener.tool(shortName(String.valueOf(block.get("name"))));
                                }
                            }
                        }
                    } else if ("result".equals(type)) {
                        finished = true;
                        if (Json.bool(event, "is_error", false)) {
                            listener.error("Claude Code がエラーを返しました: " + Json.str(event, "result", Json.str(event, "subtype", "")));
                        } else if (event.get("total_cost_usd") instanceof Number cost) {
                            listener.info(String.format("API 料金に換算した目安：$%.4f（月額プランでログインしている場合は、プランの利用枠から使われます）",
                                    cost.doubleValue()));
                        }
                    }
                }
            } finally {
                process = null;
            }
            int exit = p.waitFor();
            errorReader.join(1000);
            if (!finished) {
                String detail;
                synchronized (errors) {
                    detail = errors.toString().strip();
                }
                listener.error(exit == 143 || exit == 130 || exit == 137 ? "止めました。"
                        : "Claude Code が途中で終わりました（終了コード " + exit + "）。"
                        + (detail.isEmpty() ? "" : "\n" + detail)
                        + (detail.contains("login") || detail.contains("auth") ? "\nターミナルで claude を一度起動して、ログインしてください。" : ""));
            }
        }
    }
}
