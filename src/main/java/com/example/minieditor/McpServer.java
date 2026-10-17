package com.example.minieditor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）の「会話の決まり」を受け持つ。
 *
 * MCP は、AI（Claude など）が外のプログラムの機能を「道具（ツール）」として使うための共通の決まり。
 * やり取りは JSON-RPC という形の JSON で、AI 側が
 *   {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{...}}
 * のように頼み、こちらが同じ id を付けて結果を返す。
 *
 * このクラスは、届いた JSON を読んで、どの頼みごとかを見分けて返事を作るところまでを行う。
 * 道具の中身（実際に文書を読む・書き換える）は Backend に任せる。こうしておくと、
 *   エディタ本体    … Backend は McpTools（画面を直接さわる）
 *   橋渡し役        … Backend は「エディタへ転送する」だけ
 * と、同じ決まりの処理を両方で使い回せる。画面の部品（Swing）は、このクラスでは使わない。
 */
public final class McpServer {

    /** 道具を実際に動かす役。結果は、MCP の「道具の結果」の形（content と isError）で返す。 */
    public interface Backend {
        Map<String, Object> callTool(String name, Map<String, Object> arguments);
    }

    public static final String SERVER_NAME = "datum-editor";
    public static final String SERVER_VERSION = "1.1.0";

    // 対応している決まりの版。相手が言ってきた版がこの中にあればそれで、なければ一番上の版で返事をする
    private static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    // AI が最初に読む、このサーバーの使い方の説明
    static final String INSTRUCTIONS = """
            DATUM-Editor（日本語の文章・小説を書くためのエディタ）につながっています。利用者がエディタで開いている文書と、利用者が選んだ「AI に任せるフォルダ」の中のファイルを扱えます。

            ・誤字・脱字の確認：read_document で本文を読み、直したい所を「どこを・どう直すか」の一覧で利用者に示してから、edit_document で書き換えます。利用者が「そのまま直して」と頼んだ場合は、確認なしで書き換えてかまいません。意味や文体を変える書き直しは、頼まれたときだけ行います。
            ・edit_document の old_text は、本文と1文字も違わないように写してください（全角・半角、空白、改行に注意）。同じ言葉が何度も出てくるときは、前後の文字も含めて1か所に決まるようにします。
            ・書き換えは、利用者が Ctrl+Z で元に戻せます。書き換えただけではファイルに保存されません。保存は save_document で行います（頼まれていないのに勝手に保存しないでください）。
            ・ファイルの場所は、「AI に任せるフォルダ」からの相対パス（例: 第一章.txt、下書き/メモ.txt）で書きます。フォルダの外は扱えません。
            ・縦書きの文書では、ルビ（ふりがな）は本文とは別に付いています。新しくルビを振るときは、new_text に「｜漢字《かんじ》」の形で書きます。
            ・ファイルを消す操作（trash_file）は、フォルダの中の .datum-trash へ移すだけで、利用者が取り戻せます。それでも、頼まれたときだけ行ってください。
            """;

    /** 決まりに沿った「エラーの返事」をするための例外。code は JSON-RPC で決められた番号。 */
    private static final class RpcException extends Exception {
        final int code;

        RpcException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final Backend backend;
    private final List<Map<String, Object>> tools = toolDefinitions();

    public McpServer(Backend backend) {
        this.backend = backend;
    }

    // ---------- メッセージの受け付け ----------

    /**
     * 届いたメッセージ（JSON の文字列）1つを処理して、返事の JSON を返す。
     * 返事がいらないメッセージ（「知らせ」）のときは null を返す。
     */
    public String handle(String message) {
        Object parsed;
        try {
            parsed = Json.parse(message);
        } catch (Json.JsonException ex) {
            return Json.writeCompact(error(null, -32700, "Parse error: " + ex.getMessage()));
        }
        if (parsed instanceof List<?> batch) {
            // 古い版の決まりでは、複数のメッセージを配列でまとめて送ってくることがある
            List<Object> responses = new ArrayList<>();
            for (Object item : batch) {
                Map<String, Object> response = handleOne(item);
                if (response != null) {
                    responses.add(response);
                }
            }
            return responses.isEmpty() ? null : Json.writeCompact(responses);
        }
        Map<String, Object> response = handleOne(parsed);
        return response == null ? null : Json.writeCompact(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleOne(Object parsed) {
        if (!(parsed instanceof Map<?, ?> raw)) {
            return error(null, -32600, "Invalid Request");
        }
        Map<String, Object> message = (Map<String, Object>) raw;
        if (!(message.get("method") instanceof String method)) {
            return null;                // 相手からの「返事」。こちらからは何も頼んでいないので、読み飛ばす
        }
        boolean notification = !message.containsKey("id");     // id がないものは「知らせ」で、返事をしてはいけない
        Object id = message.get("id");
        Map<String, Object> params = message.get("params") instanceof Map<?, ?> p
                ? (Map<String, Object>) p : new LinkedHashMap<>();
        try {
            Object result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> new LinkedHashMap<String, Object>();
                case "tools/list" -> map("tools", tools);
                case "tools/call" -> callTool(params);
                case "prompts/list" -> map("prompts", promptDefinitions());
                case "prompts/get" -> getPrompt(params);
                default -> {
                    if (notification) {
                        yield null;     // notifications/initialized など。知らないものは、そのまま聞き流す
                    }
                    throw new RpcException(-32601, "Method not found: " + method);
                }
            };
            return notification ? null : response(id, result);
        } catch (RpcException ex) {
            return notification ? null : error(id, ex.code, ex.getMessage());
        } catch (RuntimeException ex) {
            return notification ? null : error(id, -32603, "Internal error: " + ex);
        }
    }

    private static Map<String, Object> response(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", map("code", code, "message", message));
        return response;
    }

    // ---------- それぞれの頼みごと ----------

    /** 最初のあいさつ。お互いの版と、できること（道具と、定型の頼み方）を伝え合う。 */
    private Map<String, Object> initialize(Map<String, Object> params) {
        String requested = Json.str(params, "protocolVersion", "");
        String version = PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0);
        return map(
                "protocolVersion", version,
                "capabilities", map("tools", new LinkedHashMap<String, Object>(),
                        "prompts", new LinkedHashMap<String, Object>()),
                "serverInfo", map("name", SERVER_NAME, "title", "DATUM-Editor", "version", SERVER_VERSION),
                "instructions", INSTRUCTIONS);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Map<String, Object> params) throws RpcException {
        String name = Json.str(params, "name", null);
        boolean known = false;
        for (Map<String, Object> tool : tools) {
            known |= tool.get("name").equals(name);
        }
        if (!known) {
            throw new RpcException(-32602, "Unknown tool: " + name);
        }
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> a
                ? (Map<String, Object>) a : new LinkedHashMap<>();
        return backend.callTool(name, arguments);
    }

    /** 道具の結果を、MCP の形にする。isError が true のとき、AI は「うまくいかなかった」と受け取る。 */
    public static Map<String, Object> textResult(String text, boolean isError) {
        List<Object> content = new ArrayList<>();
        content.add(map("type", "text", "text", text));
        return map("content", content, "isError", isError);
    }

    // ---------- 定型の頼み方（プロンプト） ----------
    // AI のアプリのメニューから選べる、あらかじめ用意した頼み方。選ぶと、下の文章が AI への依頼として入る。

    private static List<Object> promptDefinitions() {
        List<Object> prompts = new ArrayList<>();
        prompts.add(map("name", "proofread", "title", "誤字・脱字のチェック",
                "description", "今開いている文書の誤字・脱字を探して、直す案を出します。",
                "arguments", List.of(map("name", "note", "description",
                        "とくに見てほしい点（例: 表記ゆれも見て）。なくてもかまいません。", "required", false))));
        prompts.add(map("name", "find", "title", "言葉を探す",
                "description", "開いている文書と、AI に任せるフォルダの中から、言葉を探します。",
                "arguments", List.of(map("name", "word", "description", "探す言葉", "required", true))));
        return prompts;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getPrompt(Map<String, Object> params) throws RpcException {
        String name = Json.str(params, "name", "");
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> a
                ? (Map<String, Object>) a : new LinkedHashMap<>();
        String text;
        switch (name) {
            case "proofread" -> {
                String note = Json.str(arguments, "note", "").strip();
                text = "DATUM-Editor で今開いている文書を read_document で読み、誤字・脱字（打ち間違い、変換の間違い、"
                        + "文字の抜け・重複、てにをはの明らかな間違い）を探してください。"
                        + "見つけたものを「行・今の書き方・直す案・理由」の一覧で示し、私が了承したら edit_document で直してください。"
                        + "文体や内容は変えないでください。直す所がなければ、そう伝えてください。"
                        + (note.isEmpty() ? "" : "\nとくに見てほしい点: " + note);
            }
            case "find" -> {
                String word = Json.str(arguments, "word", "").strip();
                if (word.isEmpty()) {
                    throw new RpcException(-32602, "Missing required argument: word");
                }
                text = "DATUM-Editor の search を使って、「" + word + "」を探してください。"
                        + "まず scope: open（開いている文書すべて）、次に scope: files（AI に任せるフォルダの中）で探し、"
                        + "どの文書・ファイルの何行目にあるかを、前後の文と一緒にまとめてください。";
            }
            default -> throw new RpcException(-32602, "Unknown prompt: " + name);
        }
        List<Object> messages = new ArrayList<>();
        messages.add(map("role", "user", "content", map("type", "text", "text", text)));
        return map("messages", messages);
    }

    // ---------- 道具の一覧 ----------
    // 名前・説明・受け取る値の形（JSON Schema）を並べる。AI はこの説明を読んで、どの道具をどう使うかを決める。

    private static final String ID_HELP = "文書の番号（list_documents で分かる）。省略すると、今いちばん手前に表示されている文書。";

    static List<Map<String, Object>> toolDefinitions() {
        List<Map<String, Object>> list = new ArrayList<>();

        list.add(tool("list_documents", "開いている文書の一覧",
                "エディタで開いている文書（タブ）の一覧を返す。番号・名前・種類（テキスト／縦書き／カードボード）・"
                        + "文字数・未保存かどうか・保存先が分かる。",
                true, false, map()));

        list.add(tool("read_document", "文書を読む",
                "開いている文書の本文を返す。誤字・脱字の確認や、内容についての質問に答えるときに使う。"
                        + "長い文書は start_line と end_line で分けて読める（1回に返すのは約10万文字まで）。",
                true, false, map(
                        "id", integer(ID_HELP),
                        "start_line", integer("読み始める行（1 から数える）。省略すると最初から。"),
                        "end_line", integer("読み終える行（この行も含む）。省略すると最後まで。"),
                        "include_ruby", bool("true にすると、縦書きの文書のルビ（ふりがな）を「｜親文字《ルビ》」の形で"
                                + "本文に含めて返す。ルビの間違いも見たいときに使う。"
                                + "ただし edit_document の old_text は、ルビの記号を含まない本文で指定すること。"))));

        list.add(tool("get_selection", "選択範囲を読む",
                "利用者がエディタで選んでいる範囲の文字と、カーソルのある行を返す。"
                        + "「ここを直して」「この部分」のように、場所を指して頼まれたときに使う。",
                true, false, map("id", integer(ID_HELP))));

        list.add(tool("search", "探す",
                "言葉を探して、見つかった行と前後の文を返す。"
                        + "scope で探す範囲を選ぶ：document（1つの文書）、open（開いている文書すべて）、"
                        + "files（AI に任せるフォルダの中の .txt / .json / .md ファイル）。",
                true, false, map(
                        "query", string("探す言葉。regex が true のときは正規表現。"),
                        "scope", choice("探す範囲。省略すると document。", "document", "open", "files"),
                        "id", integer("scope が document のときの対象。" + ID_HELP),
                        "regex", bool("true にすると、query を正規表現として扱う。省略すると false。"),
                        "case_sensitive", bool("true にすると、英字の大文字と小文字を区別する。省略すると false。"),
                        "max_results", integer("返す件数の上限。省略すると 50。")),
                "query"));

        Map<String, Object> edit = map(
                "type", "object",
                "properties", map(
                        "old_text", string("今の本文にある文字列。本文と1文字も違わないこと。"
                                + "本文の中で1か所に決まるように、必要なら前後の文字も含める。"),
                        "new_text", string("置き換えたあとの文字列。空にすると、old_text を消す。"),
                        "replace_all", bool("true にすると、old_text が何か所にあっても、すべて置き換える。")),
                "required", List.of("old_text", "new_text"),
                "additionalProperties", false);
        list.add(tool("edit_document", "文書を書き換える",
                "開いている文書の一部を書き換える（誤字・脱字の修正など）。edits に、置き換えを1つ以上並べる。"
                        + "1つでも見つからない・1か所に決まらないものがあれば、何も書き換えずにエラーを返す。"
                        + "書き換えた所はエディタで色が付き、利用者は Ctrl+Z の1回で、まとめて元に戻せる。"
                        + "ファイルへの保存は行わない。",
                false, true, map(
                        "id", integer(ID_HELP),
                        "edits", map("type", "array", "minItems", 1, "items", edit,
                                "description", "置き換えの一覧。位置は、書き換える前の本文で探す。")),
                "edits"));

        list.add(tool("new_document", "新しい文書を作る",
                "新しい文書（タブ）を作る。text を渡すと、その内容を入れる。まだファイルには保存されない。"
                        + "カードボードの場合は、text の1行が1枚のカードになる。",
                false, false, map(
                        "kind", choice("文書の種類。text（横書き）、vertical（縦書き・原稿用紙）、board（カードボード）。"
                                + "省略すると text。", "text", "vertical", "board"),
                        "text", string("最初に入れる内容。省略すると空。"))));

        list.add(tool("open_file", "ファイルを開く",
                "AI に任せるフォルダの中のファイルを、エディタのタブとして開く。すでに開いていれば、そのタブを手前に出す。"
                        + "読むだけでよいとき（多くのファイルを調べるなど）は、タブを増やさない read_file を使う。",
                false, false, map(
                        "path", string("開くファイル。AI に任せるフォルダからの相対パス。"),
                        "layout", choice(".txt を開くときの向き。省略すると horizontal（横書き）。", "horizontal", "vertical")),
                "path"));

        list.add(tool("save_document", "文書を保存する",
                "開いている文書を、ファイルに保存する。path を省略すると、今の保存先に上書きする。"
                        + "まだ保存先がない文書や、別の名前で保存したいときは path を指定する。"
                        + "拡張子がなければ .txt が付く。.json にすると、種類やルビ・カードの配置も保存される"
                        + "（カードボードは .json のみ）。縦書きの文書を .txt で保存すると、ルビは"
                        + "「｜親文字《ルビ》」の形（小説投稿サイト向け）で書き出される。",
                false, true, map(
                        "id", integer(ID_HELP),
                        "path", string("保存先。AI に任せるフォルダからの相対パス。省略すると、今の保存先。"),
                        "overwrite", bool("path に別のファイルがすでにあるとき、true なら上書きする。省略すると、上書きせずにエラーを返す。"))));

        list.add(tool("close_document", "文書を閉じる",
                "開いている文書（タブ）を閉じる。未保存の変更がある場合は、先に save_document で保存するか、"
                        + "discard_changes を true にする（変更は失われる）。",
                false, true, map(
                        "id", integer(ID_HELP),
                        "discard_changes", bool("true にすると、未保存の変更を捨てて閉じる。省略すると false。"))));

        list.add(tool("list_files", "ファイルの一覧",
                "AI に任せるフォルダの中のファイルとフォルダを一覧にする。大きさ・更新日時・エディタで開いているかも分かる。",
                true, false, map(
                        "path", string("一覧にするフォルダ。省略すると、AI に任せるフォルダそのもの。"),
                        "recursive", bool("true にすると、中のフォルダもたどる（4階層まで）。省略すると false。"))));

        list.add(tool("read_file", "ファイルを読む",
                "AI に任せるフォルダの中のテキストファイルを、エディタで開かずに読む（2MB まで）。"
                        + "エディタで開いていて未保存の変更がある文書は、read_document のほうが最新の内容になる。",
                true, false, map(
                        "path", string("読むファイル。AI に任せるフォルダからの相対パス。"),
                        "start_line", integer("読み始める行（1 から数える）。省略すると最初から。"),
                        "end_line", integer("読み終える行（この行も含む）。省略すると最後まで。")),
                "path"));

        list.add(tool("create_folder", "フォルダを作る",
                "AI に任せるフォルダの中に、新しいフォルダを作る。",
                false, false, map("path", string("作るフォルダ。AI に任せるフォルダからの相対パス。")),
                "path"));

        list.add(tool("move_file", "ファイルを移動・名前変更する",
                "AI に任せるフォルダの中で、ファイルやフォルダを移動する（名前の変更も、これで行う）。"
                        + "移動先に同じ名前のものがあるときは、上書きせずにエラーを返す。"
                        + "エディタで開いている文書は、保存先が新しい場所に付け替えられる。",
                false, true, map(
                        "from", string("移動するファイルやフォルダ。"),
                        "to", string("移動先のパス（新しい名前を含む）。すでにあるフォルダを指定すると、その中へ移す。")),
                "from", "to"));

        list.add(tool("trash_file", "ファイルをごみ箱へ",
                "ファイルやフォルダを、AI に任せるフォルダの中の .datum-trash へ移す（完全には消さないので、利用者が取り戻せる）。"
                        + "エディタで開いている文書は、先に閉じること。利用者に頼まれたときだけ使う。",
                false, true, map("path", string("ごみ箱へ入れるファイルやフォルダ。")),
                "path"));

        return list;
    }

    private static Map<String, Object> tool(String name, String title, String description,
            boolean readOnly, boolean destructive, Map<String, Object> properties, String... required) {
        Map<String, Object> schema = map("type", "object", "properties", properties);
        if (required.length > 0) {
            schema.put("required", List.of(required));
        }
        schema.put("additionalProperties", false);
        // annotations は、AI のアプリが「この道具は読むだけか、書き換えるか」を利用者に示すための手がかり
        return map("name", name, "title", title, "description", description, "inputSchema", schema,
                "annotations", map("title", title, "readOnlyHint", readOnly,
                        "destructiveHint", destructive, "openWorldHint", false));
    }

    private static Map<String, Object> string(String description) {
        return map("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return map("type", "integer", "description", description);
    }

    private static Map<String, Object> bool(String description) {
        return map("type", "boolean", "description", description);
    }

    private static Map<String, Object> choice(String description, String... values) {
        return map("type", "string", "enum", List.of(values), "description", description);
    }

    /** 「名前, 値, 名前, 値, …」の並びから、順番を保った Map を作る（JSON を組み立てるのを短く書くため）。 */
    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
