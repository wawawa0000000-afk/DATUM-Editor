package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JPanel;

/**
 * タブ1枚ぶんの「文書」。横書き・縦書き・カードボードの3種類が、このクラスを継承する。
 * 保存先（path）と、未保存かどうか（dirty）は、どの種類でも共通なのでここで持つ。
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
    private final int documentId = nextDocumentId++;
    private Path path;
    private boolean dirty;
    private boolean rawFile;
    private Listener listener;

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
        this.path = path;
        if (listener != null) {
            listener.titleChanged(this);
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        if (this.dirty != dirty) {
            this.dirty = dirty;
            if (listener != null) {
                listener.titleChanged(this);
            }
        }
    }

    /** true のとき、拡張子が .json でも中身をそのまま保存する（他のソフトの JSON を開いた場合）。 */
    public boolean isRawFile() {
        return rawFile;
    }

    public void setRawFile(boolean rawFile) {
        this.rawFile = rawFile;
    }

    public String getTitle() {
        return path == null ? "無題" : path.getFileName().toString();
    }

    /** 中身が変わったときに、子クラスから呼ぶ。 */
    protected void fireContentChanged() {
        boolean wasDirty = dirty;
        dirty = true;
        if (listener != null) {
            if (!wasDirty) {
                listener.titleChanged(this);
            }
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
