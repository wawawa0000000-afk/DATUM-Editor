package com.example.minieditor;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * AI 連携（MCP）の「道具」の中身。AI から頼まれたことを、実際にエディタやファイルに対して行う。
 *
 * 気をつけていること：
 *   ・ファイルを扱うのは、利用者が選んだ「AI に任せるフォルダ」の中だけ（inWorkspace で必ず確かめる）。
 *   ・画面の部品は、決められたスレッド（EDT）からしか触れない。通信は別のスレッドで届くので、
 *     画面に触る所は ui(...) で包んで、EDT に実行を頼む。
 *   ・確認のダイアログは、ふだんは出さない（AI が返事を待っている間、エディタが止まってしまうため）。
 *     かわりに、危ない操作は「何もせずにエラーを返す」ようにして、AI に理由を伝える。
 *     ただし、取り戻せない操作（未保存の変更を捨てる・別のファイルを上書きする）だけは、
 *     AI が「利用者に確かめた」と言ってきても、本当に利用者に聞く（原稿の中の偽の指示に従ってしまうことがあるため）。
 *   ・名前が「.」で始まるファイルやフォルダ（.git など）は、読むことも書くこともできない。
 *   ・文書の id は、同じ文書を分割で両側に出していても1つ（代表のタブの id）にそろえて伝える。
 */
final class McpTools implements McpServer.Backend {

    private static final int MAX_TEXT = 100_000;                    // 1回に返す本文の上限（文字数）
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;    // 読むファイルの大きさの上限
    private static final int MAX_LIST = 300;                        // 一覧に出す数の上限
    private static final String TRASH = ".datum-trash";             // 「ごみ箱」にするフォルダの名前
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final long SEARCH_MILLIS = 3000;                 // 探すのにかける時間の上限（正規表現が重すぎるとき）

    // 道具は1つずつ実行する。MCP サーバー用と AI 会話欄用で McpTools は別々に作られるので、鍵は全体で1つにする
    private static final Object LOCK = new Object();

    /** 頼まれたことができないときに投げる。メッセージは、そのまま AI に伝わる。 */
    static final class ToolException extends Exception {
        ToolException(String message) {
            super(message);
        }
    }

    /** 画面のスレッド（EDT）で行いたい処理。 */
    private interface UiCall<T> {
        T run() throws ToolException, IOException;
    }

    private final EditorFrame frame;
    private final McpSettings settings;
    private final FileService fileService = new FileService();

    McpTools(EditorFrame frame, McpSettings settings) {
        this.frame = frame;
        this.settings = settings;
    }

    /** 道具を1つ実行する。同時に2つは実行しない（MCP サーバーと AI 会話欄の両方から来ても）。 */
    @Override
    public Map<String, Object> callTool(String name, Map<String, Object> a) {
        synchronized (LOCK) {
            return run(name, a);
        }
    }

    private Map<String, Object> run(String name, Map<String, Object> a) {
        try {
            String text = switch (name) {
                case "list_documents" -> listDocuments();
                case "read_document" -> readDocument(a);
                case "get_selection" -> getSelection(a);
                case "search" -> search(a);
                case "edit_document" -> editDocument(a);
                case "new_document" -> newDocument(a);
                case "open_file" -> openFile(a);
                case "save_document" -> saveDocument(a);
                case "close_document" -> closeDocument(a);
                case "list_files" -> listFiles(a);
                case "read_file" -> readFile(a);
                case "create_folder" -> createFolder(a);
                case "move_file" -> moveFile(a);
                case "trash_file" -> trashFile(a);
                default -> throw new ToolException("この名前の道具はありません: " + name);
            };
            return McpServer.textResult(text, false);
        } catch (ToolException ex) {
            return McpServer.textResult(ex.getMessage(), true);
        } catch (IOException ex) {
            return McpServer.textResult("ファイルの操作に失敗しました: " + ex, true);
        } catch (RuntimeException ex) {
            return McpServer.textResult("エディタの中でエラーが起きました: " + ex, true);
        }
    }

    // ---------- 開いている文書 ----------

    private String listDocuments() throws ToolException, IOException {
        return ui(() -> {
            List<EditorPane> documents = frame.documents();
            EditorPane active = frame.activeDocument() == null ? null : frame.activeDocument().firstView();
            StringBuilder sb = new StringBuilder("開いている文書: " + documents.size()
                    + " 個（★ は、今いちばん手前に表示されている文書）\n");
            for (EditorPane pane : documents) {
                sb.append(pane == active ? "★ " : "   ").append(describe(pane)).append('\n');
            }
            sb.append("\nAI に任せるフォルダ: ").append(settings.workspace);
            return sb.toString();
        });
    }

    private String readDocument(Map<String, Object> a) throws ToolException, IOException {
        return ui(() -> {
            EditorPane pane = pane(a);
            boolean hasRuby = pane instanceof VerticalTextPane vertical && !vertical.toPlainText().equals(vertical.getText());
            String text = (flag(a, "include_ruby") && pane instanceof VerticalTextPane)
                    ? pane.toPlainText() : textOf(pane);

            StringBuilder sb = new StringBuilder("文書: " + describe(pane) + "\n");
            if (pane instanceof Searchable s) {
                String all = s.getText();
                int caret = Math.min(s.getSelectionEnd(), all.length());
                sb.append("カーソル: ").append(lineOf(all, caret)).append(" 行目");
                int selected = s.getSelectionEnd() - s.getSelectionStart();
                if (selected > 0) {
                    sb.append("（").append(selected).append(" 文字を選択中。get_selection で読める）");
                }
                sb.append('\n');
            } else {
                sb.append("※ カードボードです。下は、カードの文字を並べたものです（■ はグループ枠）。"
                        + "edit_document での書き換えはできません。\n");
            }
            if (hasRuby) {
                sb.append("※ この文書にはルビ（ふりがな）が付いています。ルビも読むには include_ruby: true。"
                        + "edit_document の old_text は、ルビの記号を含まない本文で指定してください。\n");
            }
            sb.append(slice(text, integer(a, "start_line"), integer(a, "end_line")));
            return sb.toString();
        });
    }

    private String getSelection(Map<String, Object> a) throws ToolException, IOException {
        return ui(() -> {
            EditorPane pane = pane(a);
            if (!(pane instanceof Searchable s)) {
                throw new ToolException("この文書（" + pane.getMode().label + "）では、選択範囲を読めません。");
            }
            String text = s.getText();
            int start = Math.min(s.getSelectionStart(), text.length());
            int end = Math.min(s.getSelectionEnd(), text.length());
            StringBuilder sb = new StringBuilder("文書: " + describe(pane) + "\n");
            if (end > start) {
                String selected = text.substring(start, end);
                sb.append("選択範囲: ").append(lineOf(text, start)).append(" 行目から ")
                        .append(lineOf(text, end)).append(" 行目（").append(selected.length()).append(" 文字）\n");
                sb.append("----- 選択されている文字 -----\n").append(limit(selected));
            } else {
                int lineStart = text.lastIndexOf('\n', start - 1) + 1;
                int lineEnd = text.indexOf('\n', start);
                sb.append("何も選択されていません。カーソルは ").append(lineOf(text, start)).append(" 行目の ")
                        .append(start - lineStart + 1).append(" 文字目にあります。\n");
                sb.append("----- カーソルのある行 -----\n")
                        .append(limit(text.substring(lineStart, lineEnd < 0 ? text.length() : lineEnd)));
            }
            return sb.toString();
        });
    }

    /** 開いている文書の一部を書き換える。全部の置き換えの場所が決まってから、まとめて行う。 */
    @SuppressWarnings("unchecked")
    private String editDocument(Map<String, Object> a) throws ToolException, IOException {
        List<Object> edits = Json.list(a, "edits");
        if (edits.isEmpty()) {
            throw new ToolException("edits に、置き換えを1つ以上入れてください（例: [{\"old_text\": \"誤字\", \"new_text\": \"正しい字\"}]）。");
        }
        return ui(() -> {
            EditorPane pane = pane(a);
            if (!(pane instanceof Searchable s)) {
                throw new ToolException("この文書（" + pane.getMode().label + "）は、文章の書き換えに対応していません。");
            }
            String text = s.getText();
            List<Searchable.Replacement> replacements = new ArrayList<>();
            for (int i = 0; i < edits.size(); i++) {
                String label = "edits の " + (i + 1) + " 番目";
                if (!(edits.get(i) instanceof Map<?, ?> raw)) {
                    throw new ToolException(label + ": old_text と new_text を持つ形にしてください。");
                }
                Map<String, Object> edit = (Map<String, Object>) raw;
                String oldText = string(edit, "old_text");
                String newText = string(edit, "new_text");
                if (oldText == null || oldText.isEmpty()) {
                    throw new ToolException(label + ": old_text が空です。書き換える元の文字を入れてください"
                            + "（文字を足したいときは、その前後の文字を old_text にして、足したあとの形を new_text にします）。");
                }
                if (newText == null) {
                    throw new ToolException(label + ": new_text がありません（消したいときは、空の文字列 \"\" を入れます）。");
                }
                oldText = oldText.replace("\r", "");
                newText = newText.replace("\r", "");
                if (oldText.equals(newText)) {
                    throw new ToolException(label + ": old_text と new_text が同じです。");
                }
                List<Integer> found = occurrences(text, oldText);
                if (found.isEmpty()) {
                    throw new ToolException(label + ": old_text が本文に見つかりません: 「" + shorten(oldText) + "」。"
                            + "空白・改行・全角と半角の違い・ルビの記号が混ざっていないかを確かめ、"
                            + "read_document で本文を読み直してください。何も書き換えていません。");
                }
                if (found.size() > 1 && !flag(edit, "replace_all")) {
                    throw new ToolException(label + ": 「" + shorten(oldText) + "」は " + found.size() + " か所にあります（"
                            + lines(text, found) + "）。前後の文字も含めて1か所に決まるようにするか、"
                            + "すべて直してよければ replace_all: true を付けてください。何も書き換えていません。");
                }
                for (int start : found) {
                    replacements.add(new Searchable.Replacement(start, start + oldText.length(), newText));
                }
            }

            // 範囲が重なっていると、どちらを先に直すかで結果が変わってしまうので、受け付けない
            replacements.sort(Comparator.comparingInt(Searchable.Replacement::start));
            for (int i = 1; i < replacements.size(); i++) {
                if (replacements.get(i).start() < replacements.get(i - 1).end()) {
                    throw new ToolException("置き換える範囲が重なっています（" + lineOf(text, replacements.get(i).start())
                            + " 行目のあたり）。1つの置き換えにまとめてください。何も書き換えていません。");
                }
            }

            s.replaceRanges(replacements);

            // 書き換えた所に色を付けて、最初の場所を見せる。位置は、前の置き換えで増減した文字数だけずらす
            StringBuilder report = new StringBuilder("「" + pane.getTitle() + "」の " + replacements.size() + " か所を書き換えました。\n");
            List<EditorPane.Highlight> highlights = new ArrayList<>();
            int shift = 0;
            for (Searchable.Replacement r : replacements) {
                // 縦書きでは、new_text の中のルビの記号は消えてルビになるので、その分を除いた長さで数える
                int length = pane instanceof VerticalTextPane ? NarouRuby.read(r.text()).text().length() : r.text().length();
                int start = r.start() + shift;
                if (length > 0) {
                    highlights.add(new EditorPane.Highlight(start, start + length, Theme.REPLACED));
                }
                report.append("  ").append(lineOf(text, r.start())).append(" 行目: 「")
                        .append(shorten(text.substring(r.start(), r.end()))).append("」→「")
                        .append(shorten(r.text())).append("」\n");
                shift += length - (r.end() - r.start());
            }
            frame.showDocument(pane);
            s.setHighlights(highlights);
            s.reveal(Math.min(replacements.get(0).start(), s.getText().length()));
            frame.notifyUser("AI が " + replacements.size() + " か所を書き換えました（Ctrl+Z で元に戻せます）");
            report.append("利用者は Ctrl+Z で元に戻せます。まだファイルには保存していません（保存は save_document）。");
            return report.toString();
        });
    }

    private String newDocument(Map<String, Object> a) throws ToolException, IOException {
        String kind = string(a, "kind");
        EditorPane.Mode mode = switch (kind == null ? "text" : kind) {
            case "text" -> EditorPane.Mode.TEXT;
            case "vertical" -> EditorPane.Mode.VERTICAL;
            case "board" -> EditorPane.Mode.BOARD;
            default -> throw new ToolException("kind は text・vertical・board のどれかにしてください。");
        };
        String text = string(a, "text");
        return ui(() -> {
            EditorPane pane = frame.createDocument(mode);
            if (text != null && !text.isEmpty()) {
                if (pane instanceof TextEditorPane t) {
                    t.loadText(text.replace("\r", ""));
                    t.setDirty(true);       // 中身はあるが、まだファイルには保存されていない
                } else if (pane instanceof VerticalTextPane v) {
                    v.loadText(text);
                    v.setDirty(true);
                } else if (pane instanceof BoardPane board) {
                    board.addCardsFromLines(text);
                }
            }
            frame.notifyUser("AI が新しい文書を作りました");
            return "新しい文書を作りました: " + describe(pane) + "\nまだ保存されていません（保存は save_document で、path を指定します）。";
        });
    }

    private String closeDocument(Map<String, Object> a) throws ToolException, IOException {
        return ui(() -> {
            EditorPane pane = pane(a);
            if (pane.isDirty() && !flag(a, "discard_changes")) {
                throw new ToolException("「" + pane.getTitle() + "」には未保存の変更があります。先に save_document で保存するか、"
                        + "変更を捨ててよいと利用者に確かめたうえで discard_changes: true を付けてください。閉じていません。");
            }
            if (pane.isDirty()) {
                confirm("AI が「" + pane.getTitle() + "」を、保存していない変更を捨てて閉じようとしています。\n"
                        + "閉じると、その変更は取り戻せません。閉じてよいですか？", "閉じていません。");
            }
            String title = pane.getTitle();
            frame.closeDocumentSilently(pane);
            return "「" + title + "」を閉じました。";
        });
    }

    // ---------- 探す ----------

    private String search(Map<String, Object> a) throws ToolException, IOException {
        String query = string(a, "query");
        if (query == null || query.isEmpty()) {
            throw new ToolException("query に、探す言葉を入れてください。");
        }
        String scope = string(a, "scope");
        scope = scope == null ? "document" : scope;
        Integer requested = integer(a, "max_results");
        int max = Math.max(1, Math.min(requested == null ? 50 : requested, 500));

        Pattern pattern;
        try {
            int flags = flag(a, "case_sensitive") ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            pattern = Pattern.compile(flag(a, "regex") ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException ex) {
            throw new ToolException("正規表現として読めません: " + ex.getDescription());
        }

        StringBuilder out = new StringBuilder();
        int[] count = {0};
        long deadline = System.nanoTime() + SEARCH_MILLIS * 1_000_000;
        String where;
        try {
            switch (scope) {
                case "document" -> {
                    where = "指定した文書";
                    // 画面のスレッドでは本文の写しを取るだけにして、探すのはこのスレッドで行う（重い正規表現で画面が固まらないように）
                    String[] found = ui(() -> {
                        EditorPane pane = pane(a);
                        return new String[]{"文書 " + describe(pane), textOf(pane)};
                    });
                    searchText(found[0], found[1], pattern, max, count, out, deadline);
                }
                case "open" -> {
                    where = "開いている文書すべて";
                    List<String[]> documents = ui(() -> {
                        List<String[]> list = new ArrayList<>();
                        for (EditorPane pane : frame.documents()) {
                            list.add(new String[]{"文書 " + describe(pane), textOf(pane)});
                        }
                        return list;
                    });
                    for (String[] document : documents) {
                        searchText(document[0], document[1], pattern, max, count, out, deadline);
                    }
                }
                case "files" -> where = searchFiles(pattern, max, count, out, deadline);
                default -> throw new ToolException("scope は document・open・files のどれかにしてください。");
            }
        } catch (TooSlow ex) {
            throw new ToolException("探すのに時間がかかりすぎたので、途中でやめました（" + SEARCH_MILLIS / 1000 + " 秒まで）。"
                    + "正規表現を簡単にするか、scope を狭めてください。");
        }

        String header = "「" + query + "」を " + where + " から探しました: ";
        if (count[0] == 0) {
            return header + "見つかりませんでした。";
        }
        return header + (count[0] > max ? max + " 件を表示（" + max + " 件を超えて見つかったので、途中までです）" : count[0] + " 件")
                + "\n（【 】は見つかった所の印で、本文には入っていません）\n" + out;
    }

    /** 1つの文章の中を探して、見つかった行を out に足す。deadline を過ぎたら TooSlow を投げてやめる。 */
    private static void searchText(String title, String text, Pattern pattern, int max, int[] count, StringBuilder out,
            long deadline) {
        Matcher matcher = pattern.matcher(new Timed(text, deadline));
        boolean first = true;
        while (matcher.find()) {
            if (matcher.end() == matcher.start()) {
                continue;               // 長さ 0 の一致（正規表現の ^ など）は数えない
            }
            if (++count[0] > max) {
                return;
            }
            if (first) {
                out.append('\n').append(title).append('\n');
                first = false;
            }
            int start = matcher.start();
            int end = matcher.end();
            int lineStart = text.lastIndexOf('\n', start - 1) + 1;
            int lineEnd = text.indexOf('\n', start);
            lineEnd = lineEnd < 0 ? text.length() : lineEnd;
            int from = Math.max(lineStart, start - 30);
            int to = Math.max(end, Math.min(lineEnd, end + 30));
            out.append("  ").append(lineOf(text, start)).append(" 行 ").append(start - lineStart + 1).append(" 文字目: ")
                    .append(from > lineStart ? "…" : "")
                    .append(text, from, start).append("【").append(text.substring(start, end).replace("\n", "⏎")).append("】")
                    .append(text.substring(end, Math.min(to, text.length())).replace("\n", "⏎"))
                    .append(to < lineEnd ? "…" : "").append('\n');
        }
    }

    /** 探すのにかけてよい時間を過ぎた。 */
    private static final class TooSlow extends RuntimeException {
        TooSlow() {
            super(null, null, false, false);
        }
    }

    /**
     * 締め切りのある文字列。正規表現の照合は、文字を1つ読むたびに charAt を呼ぶので、
     * そこで時刻を確かめて、締め切りを過ぎていたら止める（Java の正規表現には、時間切れの仕組みがないため）。
     */
    private static final class Timed implements CharSequence {
        private final CharSequence text;
        private final long deadline;
        private int reads;

        Timed(CharSequence text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFFF) == 0 && System.nanoTime() > deadline) {   // 毎回は確かめない（時刻を読むのも手間なので）
                throw new TooSlow();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Timed(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    /** AI に任せるフォルダの中のファイルを探す。戻り値は、探した範囲の説明。 */
    private String searchFiles(Pattern pattern, int max, int[] count, StringBuilder out, long deadline) throws ToolException, IOException {
        Path root = root();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 8)) {
            // リンク（シンボリックリンク）は、フォルダの外のファイルを指していることがあるので、たどらない
            stream.filter(p -> !Files.isSymbolicLink(p)).filter(Files::isRegularFile)
                    .filter(p -> !isHidden(root, p)).filter(McpTools::isTextFile)
                    .sorted().forEach(files::add);
        }
        int searched = 0;
        for (Path file : files) {
            if (count[0] > max) {
                break;
            }
            try {
                if (Files.size(file) > MAX_FILE_BYTES) {
                    continue;
                }
                searchText("ファイル " + display(file), searchableContent(file), pattern, max, count, out, deadline);
                searched++;
            } catch (IOException ex) {
                // 読めないファイル（文字コードが違う、など）は飛ばして、ほかを探し続ける
            }
        }
        boolean unsaved = ui(() -> frame.documents().stream().anyMatch(EditorPane::isDirty));
        if (unsaved) {
            out.append("\n※ エディタに未保存の変更がある文書があります。ファイルの中身は保存した時点のものなので、"
                    + "最新の内容を探すには scope: open も使ってください。\n");
        }
        return "AI に任せるフォルダの中（" + searched + " 個のファイル）";
    }

    /** ファイルの中の、探す対象にする文章。このアプリの .json は、本文やカードの文字だけを取り出す。 */
    static String searchableContent(Path file) throws IOException {
        return searchableText(file, new FileService().readText(file));
    }

    /**
     * 中身（ファイルの中身、または開いている文書の保存形式の文字）から、探す対象にする文章を取り出す。
     * 形式は nameForKind の拡張子で決める（ファイルでなくてもよい）。
     */
    @SuppressWarnings("unchecked")
    static String searchableText(Path nameForKind, String content) {
        if (EditorFrame.isJsonPath(nameForKind)) {
            try {
                if (Json.parse(content) instanceof Map<?, ?> raw && EditorPane.FORMAT_NAME.equals(raw.get("format"))) {
                    Map<String, Object> json = (Map<String, Object>) raw;
                    if (json.get("text") instanceof String text) {
                        return text;
                    }
                    StringBuilder cards = new StringBuilder();
                    for (Object card : Json.list(json, "cards")) {
                        if (card instanceof Map<?, ?> c && c.get("text") instanceof String text) {
                            cards.append(text.replace("\n", " ")).append('\n');
                        }
                    }
                    return cards.toString();
                }
            } catch (Json.JsonException ex) {
                // JSON として読めなければ、ふつうの文章として探す
            }
        }
        return content;
    }

    // ---------- ファイル ----------

    private String openFile(Map<String, Object> a) throws ToolException, IOException {
        Path path = inWorkspace(required(a, "path"), true);
        if (!Files.isRegularFile(path)) {
            throw new ToolException(display(path) + " はファイルではありません（フォルダの中は list_files で見られます）。");
        }
        if (Files.size(path) > MAX_FILE_BYTES) {
            throw new ToolException(display(path) + " は大きすぎるので、AI からは開けません（2MB まで）。");
        }
        String layout = string(a, "layout");
        if (layout != null && !layout.equals("horizontal") && !layout.equals("vertical")) {
            throw new ToolException("layout は horizontal か vertical にしてください。");
        }
        return ui(() -> {
            try {
                EditorPane pane = frame.openDocument(path, "vertical".equals(layout));
                return "開きました: " + describe(pane) + "\n本文は read_document（id: " + idOf(pane) + "）で読めます。";
            } catch (CharacterCodingException ex) {
                throw new ToolException(display(path) + " は、UTF-8 の文章として読めませんでした（別の文字コードか、文章ではないファイルです）。");
            }
        });
    }

    private String saveDocument(Map<String, Object> a) throws ToolException, IOException {
        String rawPath = string(a, "path");
        // フォルダの中かどうかの確認は、画面のスレッドの外で先に済ませる（ディスクを見るので）
        Path requested;
        if (rawPath != null) {
            requested = inWorkspace(rawPath, false);
        } else {
            // path を省いたときは、文書の今の保存先に書く。利用者がメニューから開いた、フォルダの外のファイルかもしれないので、確かめる
            Path own = ui(() -> pane(a).getPath());
            if (own == null) {
                requested = null;
            } else {
                try {
                    requested = inWorkspace(own.toString(), false);
                } catch (ToolException ex) {
                    throw new ToolException(own + " は「AI に任せるフォルダ」の外（または隠しフォルダの中）にあるので、"
                            + "AI からは保存できません。書き換えた内容は画面に残っているので、利用者に保存してもらってください。");
                }
            }
        }
        return ui(() -> {
            EditorPane pane = pane(a);
            Path target = requested;
            if (target == null) {
                throw new ToolException("「" + pane.getTitle() + "」には、まだ保存先がありません。path に保存先（例: 第一章.txt）を指定してください。");
            }
            if (rawPath != null) {
                target = withExtension(target, pane);
                if (!isTextFile(target)) {
                    throw new ToolException("AI から保存できるのは .txt・.json・.md だけです: " + display(target) + "　保存していません。");
                }
            }
            boolean cardBoard = pane.getMode() == EditorPane.Mode.BOARD;
            if (cardBoard && !EditorFrame.isJsonPath(target)) {
                throw new ToolException("カードボードは、カードの配置を保存するため .json で保存します。path の拡張子を .json にしてください。");
            }
            boolean changing = !samePath(target, pane.getPath());
            if (changing) {
                EditorPane other = frame.findOpenDocument(target);
                if (other != null && other != pane) {
                    throw new ToolException(display(target) + " は、別のタブ（id " + idOf(other)
                            + "）で開いています。同じファイルを2つの文書から保存することはできません。別の名前にしてください。");
                }
                if (Files.isDirectory(target)) {
                    throw new ToolException(display(target) + " はフォルダです。ファイルの名前まで指定してください。");
                }
                if (Files.exists(target) && !flag(a, "overwrite")) {
                    throw new ToolException(display(target) + " は既にあります。上書きしてよいと利用者に確かめたうえで "
                            + "overwrite: true を付けるか、別の名前にしてください。保存していません。");
                }
                if (Files.exists(target)) {
                    confirm("AI が「" + pane.getTitle() + "」の内容で、" + display(target) + " を上書きしようとしています。\n"
                            + "上書きしてよいですか？", "保存していません。");
                }
                Files.createDirectories(target.getParent());
            }

            if (!changing && fileService.changedOutside(target)) {
                if (!flag(a, "overwrite")) {
                    throw new ToolException(display(target) + " は、開いたあとで、ほかのアプリによって変更されています。"
                            + "上書きしてよいと利用者に確かめたうえで overwrite: true を付けてください。保存していません。");
                }
                confirm(display(target) + " は、開いたあとで、ほかのアプリによって変更されています。\n"
                        + "AI がそれを上書きしようとしています。上書きしてよいですか？（今の中身は、版の履歴に残します）", "保存していません。");
            }
            frame.keepDiskVersion(target);      // 上書きする前の中身を、版の履歴に残す

            boolean wasRaw = pane.isRawFile();
            if (changing) {
                pane.setRawFile(false);     // 新しい名前で保存するときは、このアプリの形式で書く
            }
            String content = EditorFrame.isJsonPath(target) && !pane.isRawFile()
                    ? Json.write(pane.toJson()) : pane.toPlainText();
            try {
                fileService.save(content, target);
            } catch (IOException ex) {
                pane.setRawFile(wasRaw);
                throw ex;
            }
            if (changing) {
                pane.setPath(target);
            }
            pane.setDirty(false);
            frame.savedByAi(target, content);
            frame.notifyUser("AI が保存しました: " + target);
            return "保存しました: " + display(target) + "（" + describe(pane) + "）";
        });
    }

    /** 拡張子がない名前には、.txt（カードボードなら .json）を付ける。 */
    private static Path withExtension(Path path, EditorPane pane) {
        String name = path.getFileName().toString();
        if (name.contains(".") && !name.endsWith(".")) {
            return path;
        }
        return path.resolveSibling(name + (pane.getMode() == EditorPane.Mode.BOARD ? ".json" : ".txt"));
    }

    private String listFiles(Map<String, Object> a) throws ToolException, IOException {
        Path root = root();
        String rawPath = string(a, "path");
        Path folder = (rawPath == null || rawPath.isBlank()) ? root : inWorkspace(rawPath, true);
        if (!Files.isDirectory(folder)) {
            throw new ToolException(display(folder) + " はフォルダではありません。");
        }
        // エディタで開いているファイルに印を付けるため、開いている文書の場所を先に集める
        Map<Path, EditorPane> open = openDocumentsByRealPath();

        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(folder, flag(a, "recursive") ? 4 : 1)) {
            // リンク（シンボリックリンク）は、フォルダの外を指していることがあるので、一覧に出さない
            stream.filter(p -> !p.equals(folder)).filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> !isHidden(root, p)).sorted().forEach(entries::add);
        }
        StringBuilder sb = new StringBuilder("フォルダ: " + (folder.equals(root) ? "（AI に任せるフォルダ）" + root : display(folder))
                + "　" + entries.size() + " 個\n");
        int shown = 0;
        for (Path entry : entries) {
            if (++shown > MAX_LIST) {
                sb.append("…（多いので、ここまで。path でフォルダを絞ってください）\n");
                break;
            }
            if (Files.isDirectory(entry)) {
                sb.append("  ").append(display(entry)).append("/\n");
                continue;
            }
            String modified = LocalDateTime.ofInstant(Files.getLastModifiedTime(entry).toInstant(), ZoneId.systemDefault()).format(TIME);
            sb.append("  ").append(display(entry)).append("　").append(size(Files.size(entry))).append("　").append(modified);
            EditorPane pane = open.get(entry);
            if (pane != null) {
                sb.append("　（エディタで開いています: id ").append(idOf(pane))
                        .append(pane.isDirty() ? "・未保存の変更あり" : "").append("）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String readFile(Map<String, Object> a) throws ToolException, IOException {
        Path path = inWorkspace(required(a, "path"), true);
        if (!Files.isRegularFile(path)) {
            throw new ToolException(display(path) + " はファイルではありません（フォルダの中は list_files で見られます）。");
        }
        if (Files.size(path) > MAX_FILE_BYTES) {
            throw new ToolException(display(path) + " は大きすぎるので、読めません（2MB まで）。");
        }
        String content;
        try {
            content = fileService.readText(path).replace("\r", "");
        } catch (FileService.UnreadableException ex) {
            throw new ToolException(display(path) + " は、UTF-8 の文章として読めませんでした（別の文字コードか、文章ではないファイルです）。");
        }
        StringBuilder sb = new StringBuilder("ファイル: " + display(path) + "（" + size(Files.size(path)) + "）\n");
        EditorPane pane = openDocumentsByRealPath().get(path);
        if (pane != null) {
            sb.append("※ エディタで開いています（id ").append(idOf(pane)).append("）。")
                    .append(pane.isDirty() ? "未保存の変更があるので、最新の内容は read_document で読んでください。" : "")
                    .append("書き換えるときは edit_document を使います。\n");
        }
        sb.append(slice(content, integer(a, "start_line"), integer(a, "end_line")));
        return sb.toString();
    }

    private String createFolder(Map<String, Object> a) throws ToolException, IOException {
        Path path = inWorkspace(required(a, "path"), false);
        if (Files.isDirectory(path)) {
            return "フォルダは既にあります: " + display(path);
        }
        if (Files.exists(path)) {
            throw new ToolException(display(path) + " という名前のファイルが既にあります。");
        }
        Files.createDirectories(path);
        return "フォルダを作りました: " + display(path);
    }

    private String moveFile(Map<String, Object> a) throws ToolException, IOException {
        Path root = root();
        Path from = inWorkspace(required(a, "from"), true);
        Path to = inWorkspace(required(a, "to"), false);
        if (from.equals(root)) {
            throw new ToolException("AI に任せるフォルダそのものは、移動できません。");
        }
        if (Files.isDirectory(to)) {
            to = to.resolve(from.getFileName());        // すでにあるフォルダを指定されたら、その中へ移す
        }
        if (to.startsWith(from)) {
            throw new ToolException("フォルダを、そのフォルダ自身の中へ移すことはできません。");
        }
        if (Files.exists(to)) {
            throw new ToolException(display(to) + " は既にあります。上書きはしません。別の名前にしてください。移動していません。");
        }
        // 移動するものの中に、エディタで開いている文書があれば、移動のあとで保存先を付け替える
        Map<Path, EditorPane> open = openDocumentsByRealPath();
        Files.createDirectories(to.getParent());
        Files.move(from, to);

        Path destination = to;
        int relinked = ui(() -> {
            int n = 0;
            for (Map.Entry<Path, EditorPane> entry : open.entrySet()) {
                if (entry.getKey().startsWith(from)) {
                    entry.getValue().setPath(destination.resolve(from.relativize(entry.getKey())));
                    n++;
                }
            }
            if (n > 0) {
                frame.notifyUser("AI がファイルを移動しました: " + destination);
            }
            return n;
        });
        return "移動しました: " + display(from) + " → " + display(to)
                + (relinked > 0 ? "\nエディタで開いていた文書 " + relinked + " 個の保存先を、新しい場所に付け替えました。" : "");
    }

    private String trashFile(Map<String, Object> a) throws ToolException, IOException {
        Path root = root();
        Path path = inWorkspace(required(a, "path"), true);
        if (path.equals(root)) {
            throw new ToolException("AI に任せるフォルダそのものは、ごみ箱へ入れられません。");
        }
        Path trash = root.resolve(TRASH);
        if (path.startsWith(trash)) {
            throw new ToolException("ごみ箱（" + TRASH + "）の中は、AI からは操作できません。利用者に、手で片付けてもらってください。");
        }
        for (Map.Entry<Path, EditorPane> entry : openDocumentsByRealPath().entrySet()) {
            if (entry.getKey().startsWith(path)) {
                throw new ToolException(display(entry.getKey()) + " は、エディタで開いています（id " + idOf(entry.getValue())
                        + "）。先に close_document で閉じてください。ごみ箱へは入れていません。");
            }
        }
        Files.createDirectories(trash);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path destination = trash.resolve(stamp + "-" + path.getFileName());
        for (int n = 2; Files.exists(destination); n++) {
            destination = trash.resolve(stamp + "-" + n + "-" + path.getFileName());
        }
        Files.move(path, destination);
        Path moved = destination;
        ui(() -> {
            frame.notifyUser("AI がごみ箱へ入れました: " + path.getFileName() + "（" + TRASH + " にあります）");
            return null;
        });
        return "ごみ箱へ入れました: " + display(path) + "\n完全には消していません。" + root.relativize(moved)
                + " にあるので、利用者が元の場所へ戻せます。";
    }

    // ---------- フォルダの中かどうかの確認 ----------

    /** AI に任せるフォルダ（リンクをたどった、実際の場所）。 */
    private Path root() throws ToolException, IOException {
        Path workspace = settings.workspace;
        if (workspace == null || !Files.isDirectory(workspace)) {
            throw new ToolException("「AI に任せるフォルダ」が見つかりません。利用者に、エディタのメニュー"
                    + "「AI 連携 → AI に任せるフォルダを選ぶ」で選び直してもらってください。");
        }
        return workspace.toRealPath();
    }

    /**
     * AI から渡されたパスを、実際の場所に直す。AI に任せるフォルダの外を指していたら、受け付けない。
     *
     * 「../」で上へ出る書き方や、フォルダの外を指すリンク（シンボリックリンク）を使っても、
     * 外のファイルに届かないように、「実際にある所までを、リンクをたどった本当の場所に直してから」比べている。
     */
    private Path inWorkspace(String raw, boolean mustExist) throws ToolException, IOException {
        Path root = root();
        Path path;
        try {
            path = Path.of(raw.strip());
        } catch (InvalidPathException ex) {
            throw new ToolException("パスとして読めません: " + raw);
        }
        path = (path.isAbsolute() ? path : root.resolve(path)).normalize();

        // 実際にある一番深い所まで戻る（これから作るファイルは、まだないため）
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            if (Files.isSymbolicLink(existing)) {
                throw new ToolException("行き先のないリンクは扱えません: " + raw);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new ToolException("パスとして読めません: " + raw);
        }
        Path real = existing.toRealPath().resolve(existing.relativize(path)).normalize();
        if (!real.startsWith(root)) {
            throw new ToolException("「AI に任せるフォルダ」の外は扱えません: " + raw + "\nフォルダ: " + root
                    + "\nこのフォルダからの相対パス（例: 第一章.txt）で指定してください。");
        }
        if (isHidden(root, real)) {
            // .git や .bashrc などを書き換えられると、コマンドを仕込まれることがあるので、読み書きともに断る
            throw new ToolException("名前が「.」で始まるファイルやフォルダ（隠しファイル。ごみ箱の " + TRASH + " も）は、"
                    + "AI からは扱えません: " + raw);
        }
        if (mustExist && !Files.exists(real)) {
            throw new ToolException("見つかりません: " + raw + "（list_files で、あるファイルを確かめられます）");
        }
        return real;
    }

    /** 名前が「.」で始まるもの（ごみ箱や、隠しフォルダ）と、その中にあるものかどうか。 */
    private static boolean isHidden(Path root, Path path) {
        for (Path part : root.relativize(path)) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTextFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".json") || name.endsWith(".md");
    }

    /** 画面に出すパス。AI に任せるフォルダの中なら、そこからの相対パスにする。 */
    private String display(Path path) {
        try {
            Path root = root();
            Path real = Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
            if (real.startsWith(root) && !real.equals(root)) {
                return root.relativize(real).toString().replace('\\', '/');
            }
            return real.toString();
        } catch (IOException | ToolException ex) {
            return path.toString();
        }
    }

    private static boolean samePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.equals(b) || (Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b));
        } catch (IOException ex) {
            return false;
        }
    }

    /** エディタで開いている文書を、「実際の場所 → 文書」の表にする。 */
    private Map<Path, EditorPane> openDocumentsByRealPath() throws ToolException, IOException {
        return ui(() -> {
            Map<Path, EditorPane> open = new LinkedHashMap<>();
            for (EditorPane pane : frame.documents()) {
                if (pane.getPath() != null) {
                    try {
                        open.put(pane.getPath().toRealPath(), pane);
                    } catch (IOException ex) {
                        open.put(pane.getPath().toAbsolutePath().normalize(), pane);   // ファイルが消されている場合など
                    }
                }
            }
            return open;
        });
    }

    // ---------- 画面のスレッドで実行する ----------

    /**
     * 画面のスレッド（EDT）で call を実行して、終わるまで待つ。
     * Swing の部品は EDT 以外から触ると壊れることがあるので、文書やウィンドウに触る処理は必ずここを通す。
     */
    @SuppressWarnings("unchecked")
    private <T> T ui(UiCall<T> call) throws ToolException, IOException {
        if (SwingUtilities.isEventDispatchThread()) {
            return call.run();
        }
        Object[] box = new Object[2];       // [0] 結果、[1] 起きた例外
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    box[0] = call.run();
                } catch (ToolException | IOException | RuntimeException ex) {
                    box[1] = ex;
                }
            });
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ToolException("処理が中断されました。");
        } catch (InvocationTargetException ex) {
            throw new ToolException("エディタの中でエラーが起きました: " + ex.getCause());
        }
        if (box[1] instanceof ToolException ex) {
            throw ex;
        }
        if (box[1] instanceof IOException ex) {
            throw ex;
        }
        if (box[1] instanceof RuntimeException ex) {
            throw ex;
        }
        return (T) box[0];
    }

    // ---------- こまかい道具 ----------

    /**
     * id で指定された文書（省略されていれば、今いちばん手前の文書）。画面のスレッドで呼ぶこと。
     *
     * 同じ文書を分割で両側に出しているときは、タブごとに id が違うので、どちらのタブの id でも受け付ける。
     * 返すのは、利用者が今見ているタブがその文書ならそのタブ（選択範囲やカーソルが、見ているとおりになるように）、
     * そうでなければ代表のタブ。AI に伝える id は、いつも代表のタブの id にそろえる（idOf）。
     */
    private EditorPane pane(Map<String, Object> a) throws ToolException {
        Integer id = integer(a, "id");
        EditorPane active = frame.activeDocument();
        if (id == null) {
            if (active == null) {
                throw new ToolException("開いている文書がありません。");
            }
            return active;
        }
        for (EditorPane pane : frame.documents()) {
            boolean match = pane.getDocumentId() == id
                    || pane.otherViews().stream().anyMatch(view -> view.getDocumentId() == id);
            if (match) {
                return active != null && active.firstView() == pane ? active : pane;
            }
        }
        throw new ToolException("id " + id + " の文書は開いていません（閉じられたか、番号が違います）。list_documents で確かめてください。");
    }

    private String describe(EditorPane pane) {
        String amount = pane instanceof Searchable s ? String.format("%,d 文字", s.getText().length()) : pane.statusText().strip();
        return "id " + idOf(pane) + "「" + pane.getTitle() + "」" + pane.getMode().label + "・" + amount
                + (pane.isDirty() ? "・未保存の変更あり" : "")
                + (pane.getPath() == null ? "　（まだ保存されていません）" : "　" + display(pane.getPath()));
    }

    /** AI に伝える文書の id（分割で両側に出していても、代表のタブの id にそろえる）。 */
    private static int idOf(EditorPane pane) {
        return pane.firstView().getDocumentId();
    }

    /**
     * 取り戻せない操作の前に、利用者に聞く。断られたら、AI にそう伝えて、何もしない。画面のスレッドで呼ぶこと。
     *
     * @param notDone 断られたときに AI へ伝える、「何をしなかったか」
     */
    private void confirm(String question, String notDone) throws ToolException {
        int answer = JOptionPane.showConfirmDialog(frame, question, "AI からの確認",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            throw new ToolException("利用者が断りました。" + notDone);
        }
    }

    private static String textOf(EditorPane pane) {
        return pane instanceof Searchable s ? s.getText() : pane.toPlainText();
    }

    /** start〜end 行（1 から数え、end も含む）を取り出して、見出しを付ける。長すぎるときは途中で切る。 */
    private static String slice(String text, Integer startLine, Integer endLine) throws ToolException {
        String[] lines = text.split("\n", -1);
        int first = startLine == null ? 1 : startLine;
        int last = endLine == null ? lines.length : Math.min(endLine, lines.length);
        if (first < 1 || first > Math.max(lines.length, 1) || last < first) {
            throw new ToolException("行の指定が範囲の外です（全部で " + lines.length + " 行。start_line は 1 以上、end_line は start_line 以上）。");
        }
        StringBuilder body = new StringBuilder();
        int line = first;
        for (; line <= last; line++) {
            if (body.length() + lines[line - 1].length() > MAX_TEXT && body.length() > 0) {
                break;
            }
            body.append(lines[line - 1]);
            if (line < last) {
                body.append('\n');
            }
        }
        String header = String.format("全体: %,d 文字・%,d 行", text.length(), lines.length);
        if (first != 1 || line - 1 != lines.length) {
            header += "（" + first + "〜" + (line - 1) + " 行目を表示";
            if (line <= last) {
                header += "。長いので途中までです。続きは start_line: " + line + " で読めます";
            }
            header += "）";
        }
        return header + "\n----- 本文 -----\n" + body;
    }

    private static String limit(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "\n…（長いので、ここまで）";
    }

    private static String shorten(String text) {
        String oneLine = text.replace("\n", "⏎");
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
    }

    private static List<Integer> occurrences(String text, String word) {
        List<Integer> found = new ArrayList<>();
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + word.length())) {
            found.add(i);
        }
        return found;
    }

    /** offset が、上から何行目にあるか（1 から数える）。 */
    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String lines(String text, List<Integer> offsets) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < offsets.size() && i < 10; i++) {
            sb.append(i == 0 ? "" : "、").append(lineOf(text, offsets.get(i))).append(" 行目");
        }
        return sb + (offsets.size() > 10 ? " など" : "");
    }

    private static String size(long bytes) {
        return bytes < 1024 ? bytes + " B" : bytes < 1024 * 1024 ? String.format("%.1f KB", bytes / 1024.0)
                : String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ---------- AI から渡された値の取り出し ----------

    private static String string(Map<String, Object> a, String key) throws ToolException {
        Object value = a.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        throw new ToolException(key + " は文字列で指定してください。");
    }

    private static String required(Map<String, Object> a, String key) throws ToolException {
        String value = string(a, key);
        if (value == null || value.isBlank()) {
            throw new ToolException(key + " を指定してください。");
        }
        return value;
    }

    private static boolean flag(Map<String, Object> a, String key) {
        return a.get(key) instanceof Boolean b && b;
    }

    private static Integer integer(Map<String, Object> a, String key) throws ToolException {
        Object value = a.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && s.strip().matches("-?\\d{1,9}")) {
            return Integer.valueOf(s.strip());     // 数を文字列で送ってくる AI もあるので、受け付ける
        }
        throw new ToolException(key + " は整数で指定してください。");
    }
}
