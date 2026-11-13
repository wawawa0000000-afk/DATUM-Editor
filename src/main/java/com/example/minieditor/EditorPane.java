package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JPanel;

/**
 * タブ1枚ぶんの「文書」。横書き・縦書き・カードボードの3種類が、このクラスを継承する。
 * 保存先（path）と、未保存かどうか（dirty）は、どの種類でも共通なのでここで持つ。
 *
 * 分割して同じ文書を左右に出すときは、newView() で「同じ文書を映す、もう1つのタブ」を作る。
 * 中身・「元に戻す」の履歴・保存先・未保存の印は共有し、カーソルやスクロールの位置は別々に持つ。
 */
public abstract class EditorPane extends JPanel {

    /** 文書の種類。 */
    public enum Mode {
        TEXT("text", "テキスト", "文"),
        VERTICAL("vertical", "縦書き", "縦"),
        BOARD("board", "カードボード", "板");

        public final String id;      // JSON に書く名前
        public final String label;   // 画面に出す名前
        public final String badge;   // タブに出す短い印

        Mode(String id, String label, String badge) {
            this.id = id;
            this.label = label;
            this.badge = badge;
        }

        public static Mode fromId(String id) {
            if ("kj".equals(id)) {
                return BOARD;           // 以前の版で保存したファイルは、この名前で書かれている
            }
            for (Mode mode : values()) {
                if (mode.id.equals(id)) {
                    return mode;
                }
            }
            return TEXT;
        }
    }

    /** 拡張子が .md（.markdown）のファイルか。 */
    static boolean isMarkdownFile(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    /** 名前の前に出す印。横書きのテキストが Markdown のファイルなら、「文」の代わりに「MD」。 */
    static String badgeFor(Path path, Mode mode) {
        return mode == Mode.TEXT && isMarkdownFile(path) ? "MD" : mode.badge;
    }

    /** badgeFor の印の色。タブ・サイドバー・メニューで同じ色にする。 */
    static Color badgeColorFor(Path path, Mode mode) {
        return Theme.badge(mode == Mode.TEXT && isMarkdownFile(path) ? Theme.BADGE_MARKDOWN : mode.ordinal());
    }

    /** 色を付ける範囲（検索結果の表示に使う）。 */
    public record Highlight(int start, int end, Color color) {
    }

    /** 文書に変化があったことを、ウィンドウ側へ知らせるための窓口。 */
    public interface Listener {
        void contentChanged(EditorPane pane);   // 中身が変わった

        void titleChanged(EditorPane pane);     // タブの表示（名前・未保存マーク）が変わった

        void statusChanged(EditorPane pane);    // カーソル位置などが変わった

        /** 使う人への短いお知らせ（ステータスバーに出す）。 */
        default void message(EditorPane pane, String text) {
        }
    }

    public static final String FORMAT_NAME = "datum-editor";

    private static int nextDocumentId = 1;

    /** 開いている文書を見分けるための番号。AI 連携で「どの文書か」を指定するのに使う。 */
    // 分割で同じ文書を映すタブは、同じ番号にする（どちらのタブを閉じても、AI や控えから見た番号が変わらないように）
    private int documentId = nextDocumentId++;
    private Path path;
    private boolean dirty;
    private boolean rawFile;
    private Listener listener;
    // 同じ文書を映しているタブの一覧（自分も入る）。ふつうは自分だけ
    private List<EditorPane> views = new ArrayList<>(List.of(this));

    protected EditorPane() {
        super(new BorderLayout());
        setBackground(Theme.BG);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int getDocumentId() {
        return documentId;
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        for (EditorPane view : views) {
            view.path = path;
            view.pathChanged();
            if (view.listener != null) {
                view.listener.titleChanged(view);
            }
        }
    }

    /** 保存先が変わったときに呼ばれる（拡張子で見た目を変える子クラスが、上書きする）。 */
    protected void pathChanged() {
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        for (EditorPane view : views) {
            if (view.dirty != dirty) {
                view.dirty = dirty;
                if (view.listener != null) {
                    view.listener.titleChanged(view);
                }
            }
        }
    }

    /** true のとき、拡張子が .json でも中身をそのまま保存する（他のソフトの JSON を開いた場合）。 */
    public boolean isRawFile() {
        return rawFile;
    }

    public void setRawFile(boolean rawFile) {
        for (EditorPane view : views) {
            view.rawFile = rawFile;
        }
    }

    // ---------- 同じ文書を、2つのタブで映す ----------

    /** 同じ文書を映す、もう1つのタブを作る（分割で使う）。 */
    public abstract EditorPane newView();

    /** 子クラスの newView() から呼ぶ。view を、この文書を映すタブの仲間に入れる。 */
    protected <T extends EditorPane> T linkView(T view) {
        EditorPane added = view;
        views.add(added);
        added.views = views;
        added.path = path;
        added.pathChanged();
        added.dirty = dirty;
        added.rawFile = rawFile;
        added.documentId = documentId;
        return view;
    }

    /** タブを閉じたときに呼ぶ。ほかのタブとの共有をやめる（文書の中身は、残ったタブが持ち続ける）。 */
    public void unlinkView() {
        boolean shared = views.size() > 1;
        views.remove(this);
        views = new ArrayList<>(List.of(this));
        listener = null;                // 閉じたタブからは、もう知らせない
        if (shared) {
            detachShared();
        }
    }

    /**
     * 同じ文書を映していたタブを閉じたときに呼ばれる。共有している中身に付けた見張り（リスナー）を外す。
     * 外さないと、閉じたタブが残ったタブの中身につながったままになり、捨てられずに残り続ける。
     */
    protected void detachShared() {
    }

    /** 同じ文書を映している、ほかのタブ。 */
    public List<EditorPane> otherViews() {
        List<EditorPane> others = new ArrayList<>(views);
        others.remove(this);
        return others;
    }

    /** 同じ文書を映しているタブのうち、代表の1つ（文書ごとに1回だけ扱いたいときに使う）。 */
    public EditorPane firstView() {
        return views.get(0);
    }

    public String getTitle() {
        return path == null ? "無題" : path.getFileName().toString();
    }

    /** 中身が変わったときに、子クラスから呼ぶ。 */
    protected void fireContentChanged() {
        setDirty(true);
        if (listener != null) {
            listener.contentChanged(this);
            listener.statusChanged(this);
        }
    }

    protected void fireMessage(String text) {
        if (listener != null) {
            listener.message(this, text);
        }
    }

    protected void fireStatusChanged() {
        if (listener != null) {
            listener.statusChanged(this);
        }
    }

    /** JSON の先頭に入れる共通の項目。 */
    protected Map<String, Object> baseJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("format", FORMAT_NAME);
        map.put("version", 1);
        map.put("mode", getMode().id);
        return map;
    }

    public abstract Mode getMode();

    /** 何も書かれていないか。 */
    public abstract boolean isEmpty();

    /** .json で保存するときの中身。 */
    public abstract Map<String, Object> toJson();

    /** .txt で保存するときの中身。 */
    public abstract String toPlainText();

    /** PDF のページを作って、writer に追加する。 */
    public abstract void exportPdf(PdfWriter writer) throws java.io.IOException;

    /** ステータスバーに出す文字（行・列・文字数など）。 */
    public abstract String statusText();

    public abstract void focusEditor();

    public abstract Font getEditorFont();

    public abstract void setEditorFont(Font font);

    public abstract void undo();

    public abstract void redo();

    public abstract void cut();

    public abstract void copy();

    public abstract void paste();

    public abstract void selectAll();
}
