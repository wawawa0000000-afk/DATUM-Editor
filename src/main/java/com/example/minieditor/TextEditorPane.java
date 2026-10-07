package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
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

    private final StripedTextArea area = new StripedTextArea();
    private final UndoManager undoManager = new UndoManager();
    private final List<Object> highlightTags = new ArrayList<>();
    private CompoundEdit compound;      // 複数の変更を「元に戻す1回ぶん」にまとめるときに使う
    private boolean loading;            // ファイルを読み込んでいる最中か

    public TextEditorPane(Font font) {
        area.setFont(font);

        area.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            public void changedUpdate(DocumentEvent e) {
            }
        });
        area.addCaretListener(e -> {
            area.repaint();             // カーソルのある行の強調を描き直す
            fireStatusChanged();
        });
        area.getDocument().addUndoableEditListener(e -> {
            if (loading) {
                return;                 // 読み込みは「元に戻す」の対象にしない
            }
            if (compound != null) {
                compound.addEdit(e.getEdit());
            } else {
                undoManager.addEdit(e.getEdit());
            }
        });

        // JTextArea は Ctrl+H に「1文字削除」が割り当てられている。
        // "none" を入れて無効にし、メニューの「置換」(Ctrl+H) が動くようにする。
        area.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "none");

        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(Theme.BG);
        scrollPane.setRowHeaderView(new LineNumberGutter(area));
        scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        add(scrollPane, BorderLayout.CENTER);
    }

    /** JSON から読み込んで作る。 */
    public static TextEditorPane fromJson(Map<String, Object> json) {
        Font font = new Font(Json.str(json, "fontName", "Monospaced"), Font.PLAIN,
                Json.num(json, "fontSize", Theme.EDITOR_SIZE));
        TextEditorPane pane = new TextEditorPane(font);
        pane.setWrap(Json.bool(json, "wrap", false));
        pane.loadText(Json.str(json, "text", ""));
        return pane;
    }

    private void changed() {
        if (!loading) {
            fireContentChanged();
        }
    }

    /** ファイルの中身を入れる。未保存マークは付けず、「元に戻す」の履歴も空にする。 */
    public void loadText(String text) {
        loading = true;
        area.setText(text);
        area.setCaretPosition(0);
        loading = false;
        undoManager.discardAllEdits();
        setDirty(false);
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
        json.put("fontSize", area.getFont().getSize());
        json.put("wrap", area.getLineWrap());
        json.put("text", area.getText());
        return json;
    }

    @Override
    public String toPlainText() {
        return area.getText();
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
        String status = "行 " + line + ", 列 " + column;
        int selected = area.getSelectionEnd() - area.getSelectionStart();
        if (selected > 0) {
            status += "（" + selected + " 文字を選択）";
        }
        return status + "    " + area.getDocument().getLength() + " 文字";
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
        if (undoManager.canUndo()) {
            undoManager.undo();
        }
    }

    @Override
    public void redo() {
        if (undoManager.canRedo()) {
            undoManager.redo();
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
    public void replaceRange(String replacement, int start, int end) {
        compound = new CompoundEdit();
        area.replaceRange(replacement, start, end);
        finishCompound();
    }

    @Override
    public void replaceAllText(String text) {
        compound = new CompoundEdit();
        area.setText(text);
        finishCompound();
    }

    private void finishCompound() {
        compound.end();
        undoManager.addEdit(compound);
        compound = null;
    }
}
