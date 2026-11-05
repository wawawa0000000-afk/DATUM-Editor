package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.text.AttributedCharacterIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;

/**
 * 横書きの文書。JTextArea に、行番号・元に戻す・検索の色付けを足したもの。
 */
public class TextEditorPane extends EditorPane implements Searchable {

    /** 同じ文書を2つのタブで映すときに、共有するもの（文章と「元に戻す」の履歴）。 */
    private static final class Shared {
        final javax.swing.text.Document document;
        final UndoManager undoManager = new UndoManager();
        CompoundEdit compound;          // 複数の変更を「元に戻す1回ぶん」にまとめるときに使う
        boolean loading;                // ファイルを読み込んでいる最中か

        Shared(javax.swing.text.Document document) {
            this.document = document;
            document.addUndoableEditListener(e -> {
                if (loading) {
                    return;             // 読み込みは「元に戻す」の対象にしない
                }
                if (compound != null) {
                    compound.addEdit(e.getEdit());
                } else {
                    undoManager.addEdit(e.getEdit());
                }
            });
        }
    }

    private final StripedTextArea area = new StripedTextArea();
    private final Shared shared;
    private final List<Object> highlightTags = new ArrayList<>();
    private final DocumentListener documentListener = new DocumentListener() {
        public void insertUpdate(DocumentEvent e) {
            changed();
        }

        public void removeUpdate(DocumentEvent e) {
            changed();
        }

        public void changedUpdate(DocumentEvent e) {
        }
    };
    private final JScrollPane scrollPane;
    private final LineNumberGutter gutter;
    private boolean composing;          // IME で変換中か（入力補助は、変換中には規則を当てない）
    private boolean focusMode;          // 集中モード（文字の列を中央に寄せ、カーソルの行を画面の真ん中に保つ）
    private boolean wrapBeforeFocus;

    public TextEditorPane(Font font) {
        this(font, null);
    }

    private TextEditorPane(Font font, Shared from) {
        area.setFont(font);
        if (from != null) {
            area.setDocument(from.document);
            shared = from;
        } else {
            shared = new Shared(area.getDocument());
        }

        area.getDocument().addDocumentListener(documentListener);
        area.addCaretListener(e -> {
            area.repaint();             // カーソルのある行の強調を描き直す
            fireStatusChanged();
            if (focusMode) {
                SwingUtilities.invokeLater(this::centerCaret);
            }
        });
        // JTextArea は Ctrl+H に「1文字削除」が割り当てられている。
        // "none" を入れて無効にし、メニューの「置換」(Ctrl+H) が動くようにする。
        area.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "none");
        // 日本語入力（IME）の変換中かを、知らせから覚える。変換中の字は、入力補助の対象にしない
        area.addInputMethodListener(new InputMethodListener() {
            @Override
            public void inputMethodTextChanged(InputMethodEvent e) {
                AttributedCharacterIterator text = e.getText();
                // 確定していない字が残っていれば、変換中
                composing = text != null && text.getEndIndex() - text.getBeginIndex() > e.getCommittedCharacterCount();
            }

            @Override
            public void caretPositionChanged(InputMethodEvent e) {
            }
        });
        // 入力補助（段落頭の全角スペース・かっこの自動閉じ）。キーで打ったときだけ、規則を当てる
        area.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleEnter(e);
            }

            @Override
            public void keyTyped(KeyEvent e) {
                handleTyped(e);
            }
        });

        scrollPane = new JScrollPane(area);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(Theme.BG);
        gutter = new LineNumberGutter(area);
        scrollPane.setRowHeaderView(gutter);
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (focusMode) {
                    applyFocusLayout();
                }
            }
        });
        WheelScroll.install(scrollPane, false);
        add(scrollPane, BorderLayout.CENTER);
    }

    /** JSON から読み込んで作る。 */
    public static TextEditorPane fromJson(Map<String, Object> json) {
        Font font = new Font(Json.str(json, "fontName", "Monospaced"), Font.PLAIN,
                Theme.zoomed(Json.num(json, "fontSize", Theme.unzoomed(Theme.EDITOR_SIZE))));
        TextEditorPane pane = new TextEditorPane(font);
        pane.setWrap(Json.bool(json, "wrap", false));
        pane.loadText(Json.str(json, "text", ""));
        return pane;
    }

    @Override
    public EditorPane newView() {
        TextEditorPane view = new TextEditorPane(area.getFont(), shared);
        view.setWrap(isWrap());
        view.area.setCaretPosition(Math.min(area.getCaretPosition(), area.getDocument().getLength()));
        return linkView(view);
    }

    @Override
    protected void detachShared() {
        area.getDocument().removeDocumentListener(documentListener);
        gutter.detach();
        area.setDocument(new javax.swing.text.PlainDocument());    // JTextArea 自身が付けた見張りも、共有の文書から外れる
    }

    private void changed() {
        if (!shared.loading) {
            fireContentChanged();
        }
    }

    /** ファイルの中身を入れる。未保存マークは付けず、「元に戻す」の履歴も空にする。 */
    public void loadText(String text) {
        shared.loading = true;
        area.setText(text);
        area.setCaretPosition(0);
        shared.loading = false;
        shared.undoManager.discardAllEdits();
        setDirty(false);
    }

    /**
     * 集中モードの見た目にする／戻す。
     * 行番号を隠し、1行 40 字ほどの幅で中央に寄せて折り返し、カーソルの行がいつも画面の真ん中に来るようにする
     * （タイプライターのように、目線を動かさずに書ける）。
     */
    public void setFocusMode(boolean on) {
        if (on == focusMode) {
            return;
        }
        focusMode = on;
        if (on) {
            wrapBeforeFocus = area.getLineWrap();
            setWrap(true);
            scrollPane.setRowHeaderView(null);
            applyFocusLayout();
            SwingUtilities.invokeLater(this::centerCaret);
        } else {
            setWrap(wrapBeforeFocus);
            scrollPane.setRowHeaderView(gutter);
            area.setMargin(new Insets(4, 12, 4, 12));
            area.revalidate();
        }
    }

    public boolean isFocusMode() {
        return focusMode;
    }

    private void applyFocusLayout() {
        int width = scrollPane.getViewport().getWidth();
        int height = scrollPane.getViewport().getHeight();
        int column = area.getFontMetrics(area.getFont()).charWidth('あ') * 40;
        int side = Math.max(12, (width - column) / 2);
        // 上下にも、画面の半分ほどの余白を入れる（最初や最後の行でも、真ん中に来られるように）
        area.setMargin(new Insets(Math.max(4, height / 2 - area.getFontMetrics(area.getFont()).getHeight()), side,
                Math.max(4, height / 2), side));
        area.revalidate();
    }

    /** カーソルのある行が、画面の縦の真ん中に来るようにスクロールする。 */
    private void centerCaret() {
        try {
            Rectangle2D r = area.modelToView2D(area.getCaretPosition());
            if (r == null) {
                return;
            }
            int viewHeight = scrollPane.getViewport().getHeight();
            int y = (int) (r.getCenterY() - viewHeight / 2.0);
            y = Math.max(0, Math.min(y, area.getHeight() - viewHeight));
            Point position = scrollPane.getViewport().getViewPosition();
            if (position.y != y) {
                scrollPane.getViewport().setViewPosition(new Point(position.x, y));
            }
        } catch (BadLocationException ex) {
            // カーソル位置は必ず文書の中にあるので、通常は起きない
        }
    }

    /** カーソルの位置（前回の状態を復元するときに使う）。 */
    public int getCaretOffset() {
        return area.getCaretPosition();
    }

    public boolean isWrap() {
        return area.getLineWrap();
    }

    public void setWrap(boolean wrap) {
        area.setLineWrap(wrap);
        area.setWrapStyleWord(true);
    }

    @Override
    public Mode getMode() {
        return Mode.TEXT;
    }

    @Override
    public boolean isEmpty() {
        return area.getDocument().getLength() == 0;
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> json = baseJson();
        json.put("fontName", area.getFont().getFamily());
        json.put("fontSize", Theme.unzoomed(area.getFont().getSize()));   // 等倍のときの大きさで保存する
        json.put("wrap", focusMode ? wrapBeforeFocus : area.getLineWrap());     // 集中モードの折り返しは、一時的なものなので保存しない
        json.put("text", area.getText());
        return json;
    }

    @Override
    public String toPlainText() {
        return area.getText();
    }

    @Override
    public void exportPdf(PdfWriter writer) throws java.io.IOException {
        PagePrinter.printHorizontal(area.getText(), area.getFont().getFamily(), writer);
    }

    @Override
    public String statusText() {
        int caret = area.getCaretPosition();
        int line = 1;
        int column = 1;
        try {
            line = area.getLineOfOffset(caret) + 1;
            column = caret - area.getLineStartOffset(line - 1) + 1;
        } catch (BadLocationException ex) {
            // カーソル位置は必ず文書の中にあるので、通常は起きない
        }
        return "行 " + line + ", 列 " + column;
    }

    @Override
    public void focusEditor() {
        area.requestFocusInWindow();
    }

    @Override
    public Font getEditorFont() {
        return area.getFont();
    }

    @Override
    public void setEditorFont(Font font) {
        area.setFont(font);
    }

    @Override
    public void undo() {
        if (shared.undoManager.canUndo()) {
            shared.undoManager.undo();
        }
    }

    @Override
    public void redo() {
        if (shared.undoManager.canRedo()) {
            shared.undoManager.redo();
        }
    }

    @Override
    public void cut() {
        area.cut();
    }

    @Override
    public void copy() {
        area.copy();
    }

    @Override
    public void paste() {
        area.paste();
    }

    @Override
    public void selectAll() {
        area.requestFocusInWindow();
        area.selectAll();
    }

    // ---------- Searchable（検索バーから呼ばれる） ----------

    @Override
    public String getText() {
        return area.getText();
    }

    @Override
    public void setHighlights(List<Highlight> highlights) {
        // 自分で付けた色だけを消す（removeAllHighlights だと、範囲選択の色まで消えてしまう）
        for (Object tag : highlightTags) {
            area.getHighlighter().removeHighlight(tag);
        }
        highlightTags.clear();
        for (Highlight h : highlights) {
            try {
                highlightTags.add(area.getHighlighter().addHighlight(h.start(), h.end(),
                        new DefaultHighlighter.DefaultHighlightPainter(h.color())));
            } catch (BadLocationException ex) {
                // 範囲が文書からはみ出していたら、その1つだけ飛ばす
            }
        }
    }

    @Override
    public void reveal(int offset) {
        try {
            area.setCaretPosition(offset);
            Rectangle2D r = area.modelToView2D(offset);
            if (r != null) {
                area.scrollRectToVisible(r.getBounds());
            }
        } catch (BadLocationException | IllegalArgumentException ex) {
            // 位置が文書からはみ出していたら、何もしない
        }
    }

    @Override
    public void select(int start, int end) {
        reveal(end);
        int length = area.getDocument().getLength();
        area.select(Math.max(0, Math.min(start, length)), Math.max(0, Math.min(end, length)));
        area.getCaret().setSelectionVisible(true);
    }

    @Override
    public void replaceRange(String replacement, int start, int end) {
        shared.compound = new CompoundEdit();
        try {
            area.replaceRange(replacement, start, end);
        } finally {
            finishCompound();           // 途中で失敗しても、まとめるのをやめる（「元に戻す」が効かなくならないように）
        }
    }

    @Override
    public int getSelectionStart() {
        return area.getSelectionStart();
    }

    @Override
    public int getSelectionEnd() {
        return area.getSelectionEnd();
    }

    @Override
    public void replaceRanges(List<Replacement> replacements) {
        // 後ろにあるものから直す（前から直すと、文字数が変わって、後ろの位置がずれてしまうため）
        List<Replacement> sorted = new ArrayList<>(replacements);
        sorted.sort(Comparator.comparingInt(Replacement::start).reversed());
        shared.compound = new CompoundEdit();
        try {
            for (Replacement r : sorted) {
                area.replaceRange(r.text(), r.start(), r.end());
            }
        } finally {
            finishCompound();
        }
    }

    private void finishCompound() {
        shared.compound.end();
        shared.undoManager.addEdit(shared.compound);
        shared.compound = null;
    }

    /** 入力補助：Enter で改行したあと、段落頭の全角スペースを入れる（設定が入っているときだけ）。 */
    private void handleEnter(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.VK_ENTER || e.isControlDown() || e.isAltDown() || e.isMetaDown()
                || !InputAssist.indentOnBreak || isComposing()) {
            return;
        }
        typeAsOne("\n" + InputAssist.INDENT);     // 範囲を選んでいれば、その範囲を改行で置き換えてから入れる
        e.consume();
    }

    /**
     * 入力補助：開きかっこで閉じかっこも入れ、間にカーソルを置く。閉じかっこを打つとき、すぐ右が同じ閉じかっこなら、
     * 入れずに右へ進むだけ（設定が入っているときだけ）。範囲を選んでいるときは、ふつうに置き換える。
     */
    private void handleTyped(KeyEvent e) {
        char c = e.getKeyChar();
        if (!InputAssist.pairs || isComposing() || e.isControlDown() || e.isMetaDown()
                || (e.isAltDown() && !e.isAltGraphDown()) || area.getSelectionStart() != area.getSelectionEnd()) {
            return;
        }
        int caret = area.getCaretPosition();
        if (InputAssist.isClosing(c) && String.valueOf(c).equals(textAt(caret))) {
            area.setCaretPosition(caret + 1);
            e.consume();
            return;
        }
        String closing = InputAssist.closingFor(c);
        if (closing != null) {
            typeAsOne(c + closing);
            area.setCaretPosition(caret + 1);       // 2字のあいだに、カーソルを置く
            e.consume();
        }
    }

    /** 1回の打つ操作（かっこの2字など）を、「元に戻す」1回ぶんにまとめて入れる。 */
    private void typeAsOne(String s) {
        shared.compound = new CompoundEdit();
        try {
            area.replaceSelection(s);
        } finally {
            finishCompound();
        }
    }

    /** カーソルの位置の1字（文書の終わりなら空）。 */
    private String textAt(int position) {
        try {
            return position < area.getDocument().getLength() ? area.getText(position, 1) : "";
        } catch (BadLocationException ex) {
            return "";
        }
    }

    /** IME の変換中か（知らせの受け取りで覚えた状態。変換中は、入力補助の規則を当てない）。 */
    private boolean isComposing() {
        return composing;
    }
}
