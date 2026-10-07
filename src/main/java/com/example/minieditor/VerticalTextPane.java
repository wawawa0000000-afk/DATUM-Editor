package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;

/**
 * 縦書きの文書。
 *
 * 文字は右上から下へ並び、行が変わると左へ進む。
 * JTextArea は横書き専用なので、文字の配置・カーソル・キー操作をすべて自分で作っている。
 * 文章そのものは PlainDocument に入れてあり、「元に戻す」は横書きと同じ UndoManager が使える。
 */
public class VerticalTextPane extends EditorPane implements Searchable {

    // 縦書きでは 90 度回して描く記号（長音・かっこ など）
    private static final String ROTATED = "ー－—―…‥〜～（）〔〕［］｛｝〈〉《》「」『』【】＝：；";
    // 縦書きではマスの右上に寄せる句読点
    private static final String PUNCTUATION = "、。，．";
    // 少し右上に寄せる小さい仮名
    private static final String SMALL_KANA = "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ";

    private final PlainDocument doc = new PlainDocument();
    private final UndoManager undoManager = new UndoManager();
    private final View view = new View();
    private final JScrollPane scrollPane = new JScrollPane(view,
            JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

    private String text = "";           // 文書の中身の写し（描くたびに取り出すと遅いので持っておく）
    private int caret = 0;              // カーソルの位置（何文字目の前か）
    private int anchor = 0;             // 範囲選択の起点（選択していないときは caret と同じ）
    private boolean loading;
    private CompoundEdit compound;
    private List<Highlight> highlights = List.of();

    // 文字ごとの置き場所。colOf[i] が「右から何列目か」、rowOf[i] が「上から何文字目か」
    private int[] colOf = {0};
    private int[] rowOf = {0};
    private int totalCols = 1;
    private int layoutHeight = -1;
    private boolean layoutDirty = true;

    private boolean caretVisible = true;
    private final Timer blinkTimer = new Timer(530, e -> {
        caretVisible = !caretVisible;
        view.repaint();
    });

    public VerticalTextPane(Font font) {
        view.setFont(font);

        doc.addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                documentChanged(e.getOffset() + e.getLength());
            }

            public void removeUpdate(DocumentEvent e) {
                documentChanged(e.getOffset());
            }

            public void changedUpdate(DocumentEvent e) {
            }
        });
        doc.addUndoableEditListener(e -> {
            if (loading) {
                return;
            }
            if (compound != null) {
                compound.addEdit(e.getEdit());
            } else {
                undoManager.addEdit(e.getEdit());
            }
        });

        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(Theme.BG);
        // 縦書きは左へ読み進めるので、ホイールを下に回したら左へ動かす
        scrollPane.setWheelScrollingEnabled(false);
        scrollPane.addMouseWheelListener(e -> {
            JScrollBar bar = scrollPane.getHorizontalScrollBar();
            bar.setValue(bar.getValue() - e.getWheelRotation() * columnWidth());
        });
        // 枠の大きさが変わると、1列に入る文字数が変わるので、並べ直す
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutDirty = true;
                view.revalidate();
                view.repaint();
                SwingUtilities.invokeLater(VerticalTextPane.this::scrollToCaret);
            }
        });
        add(scrollPane, BorderLayout.CENTER);
    }

    public static VerticalTextPane fromJson(Map<String, Object> json) {
        Font font = new Font(Json.str(json, "fontName", "Monospaced"), Font.PLAIN,
                Json.num(json, "fontSize", Theme.EDITOR_SIZE));
        VerticalTextPane pane = new VerticalTextPane(font);
        pane.loadText(Json.str(json, "text", ""));
        return pane;
    }

    // ---------- 文書の操作 ----------

    private void documentChanged(int newCaret) {
        try {
            text = doc.getText(0, doc.getLength());
        } catch (BadLocationException ex) {
            text = "";
        }
        caret = anchor = Math.max(0, Math.min(newCaret, text.length()));
        layoutDirty = true;
        caretVisible = true;
        view.revalidate();
        view.repaint();
        if (!loading) {
            fireContentChanged();
        }
        SwingUtilities.invokeLater(this::scrollToCaret);
    }

    public void loadText(String newText) {
        loading = true;
        try {
            doc.remove(0, doc.getLength());
            doc.insertString(0, newText.replace("\r", ""), null);
        } catch (BadLocationException ex) {
            // 0 文字目から全体を入れ替えているので、通常は起きない
        }
        loading = false;
        caret = anchor = 0;
        undoManager.discardAllEdits();
        setDirty(false);
    }

    /** start から end までを replacement に置き換える（「元に戻す」1回ぶんにまとめる）。 */
    private void replace(int start, int end, String replacement) {
        compound = new CompoundEdit();
        try {
            if (end > start) {
                doc.remove(start, end - start);
            }
            if (!replacement.isEmpty()) {
                doc.insertString(start, replacement, null);
            }
        } catch (BadLocationException ex) {
            // 範囲は呼び出し側で文書の中に収めているので、通常は起きない
        }
        compound.end();
        undoManager.addEdit(compound);
        compound = null;
    }

    /** 選択範囲があればそれを消してから、カーソルの位置に文字を入れる。 */
    private void insert(String s) {
        replace(selectionStart(), selectionEnd(), s.replace("\r", ""));
    }

    private int selectionStart() {
        return Math.min(caret, anchor);
    }

    private int selectionEnd() {
        return Math.max(caret, anchor);
    }

    private void moveCaret(int position, boolean extendSelection) {
        int p = Math.max(0, Math.min(position, text.length()));
        // サロゲートペア（2つで1文字になる文字）の真ん中には止まらない
        if (p > 0 && p < text.length() && Character.isLowSurrogate(text.charAt(p))
                && Character.isHighSurrogate(text.charAt(p - 1))) {
            p += (p > caret) ? 1 : -1;
        }
        caret = p;
        if (!extendSelection) {
            anchor = p;
        }
        caretVisible = true;
        view.repaint();
        scrollToCaret();
        fireStatusChanged();
    }

    // ---------- 文字の配置 ----------

    private int fontSize() {
        return view.getFont().getSize();
    }

    /** 1文字ぶんの高さ（下へ進む量）。 */
    private int cellHeight() {
        return Math.round(fontSize() * 1.12f);
    }

    /** 1列ぶんの幅（左へ進む量）。行間を含む。 */
    private int columnWidth() {
        return Math.round(fontSize() * 1.75f);
    }

    private int margin() {
        return Math.max(16, fontSize() / 2);
    }

    private int viewportHeight() {
        int h = scrollPane.getViewport().getExtentSize().height;
        return h > 0 ? h : 600;
    }

    /** 全部の文字について、何列目・何文字目に置くかを計算する。 */
    private void ensureLayout() {
        int height = viewportHeight();
        if (!layoutDirty && height == layoutHeight) {
            return;
        }
        layoutHeight = height;
        layoutDirty = false;

        int n = text.length();
        colOf = new int[n + 1];
        rowOf = new int[n + 1];
        int rowsPerColumn = Math.max(1, (height - 2 * margin()) / cellHeight());
        int col = 0;
        int row = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (i > 0 && Character.isLowSurrogate(c) && Character.isHighSurrogate(text.charAt(i - 1))) {
                colOf[i] = colOf[i - 1];        // 前の文字と合わせて1文字なので、同じ場所
                rowOf[i] = rowOf[i - 1];
                continue;
            }
            colOf[i] = col;
            rowOf[i] = row;
            if (c == '\n') {
                col++;                          // 改行したら、左の列の一番上へ
                row = 0;
            } else {
                row++;
                if (row >= rowsPerColumn) {     // 下まで来たら、折り返して左の列へ
                    col++;
                    row = 0;
                }
            }
        }
        colOf[n] = col;                         // 文末のカーソル位置
        rowOf[n] = row;
        totalCols = col + 1;
    }

    /** i 文字目のマス（画面上の四角）。列は右から数えるので、右端から引き算する。 */
    private Rectangle cellRect(int i) {
        return new Rectangle(view.getWidth() - margin() - (colOf[i] + 1) * columnWidth(),
                margin() + rowOf[i] * cellHeight(), columnWidth(), cellHeight());
    }

    /** 指定した列・行にいちばん近い文字の位置を返す。 */
    private int indexAt(int col, int row) {
        ensureLayout();
        int lastInColumn = -1;
        for (int i = 0; i <= text.length(); i++) {
            if (colOf[i] == col) {
                if (rowOf[i] >= row) {
                    return i;
                }
                lastInColumn = i;
            }
        }
        return lastInColumn >= 0 ? lastInColumn : text.length();
    }

    private int indexAt(Point p) {
        ensureLayout();
        int col = (view.getWidth() - margin() - p.x) / columnWidth();
        col = Math.max(0, Math.min(col, totalCols - 1));
        int row = Math.max(0, (p.y - margin() + cellHeight() / 2) / cellHeight());
        return indexAt(col, row);
    }

    private void scrollToCaret() {
        ensureLayout();
        if (caret > text.length()) {
            return;
        }
        Rectangle r = cellRect(caret);
        r.grow(columnWidth(), 0);               // 左右の列も少し見えるようにする
        view.scrollRectToVisible(r);
    }

    // ---------- EditorPane ----------

    @Override
    public Mode getMode() {
        return Mode.VERTICAL;
    }

    @Override
    public boolean isEmpty() {
        return text.isEmpty();
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> json = baseJson();
        json.put("fontName", view.getFont().getFamily());
        json.put("fontSize", view.getFont().getSize());
        json.put("text", text);
        return json;
    }

    @Override
    public String toPlainText() {
        return text;
    }

    @Override
    public String statusText() {
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        int line = 1;
        for (int i = 0; i < lineStart; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        String status = "行 " + line + ", " + (caret - lineStart + 1) + " 文字目";
        if (selectionEnd() > selectionStart()) {
            status += "（" + (selectionEnd() - selectionStart()) + " 文字を選択）";
        }
        return status + "    " + text.length() + " 文字";
    }

    @Override
    public void focusEditor() {
        view.requestFocusInWindow();
    }

    @Override
    public Font getEditorFont() {
        return view.getFont();
    }

    @Override
    public void setEditorFont(Font font) {
        view.setFont(font);
        layoutDirty = true;
        view.revalidate();
        view.repaint();
        SwingUtilities.invokeLater(this::scrollToCaret);
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
        if (selectionEnd() > selectionStart()) {
            copy();
            insert("");
        }
    }

    @Override
    public void copy() {
        if (selectionEnd() > selectionStart()) {
            String selected = text.substring(selectionStart(), selectionEnd());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(selected), null);
        }
    }

    @Override
    public void paste() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (data instanceof String s && !s.isEmpty()) {
                insert(s);
            }
        } catch (Exception ex) {
            // クリップボードに文字が入っていないときは、何もしない
        }
    }

    @Override
    public void selectAll() {
        anchor = 0;
        caret = text.length();
        view.requestFocusInWindow();
        view.repaint();
        fireStatusChanged();
    }

    // ---------- Searchable ----------

    @Override
    public String getText() {
        return text;
    }

    @Override
    public void setHighlights(List<Highlight> newHighlights) {
        highlights = List.copyOf(newHighlights);
        view.repaint();
    }

    @Override
    public void reveal(int offset) {
        caret = anchor = Math.max(0, Math.min(offset, text.length()));
        view.repaint();
        scrollToCaret();
        fireStatusChanged();
    }

    @Override
    public void replaceRange(String replacement, int start, int end) {
        replace(start, end, replacement);
    }

    @Override
    public void replaceAllText(String newText) {
        replace(0, text.length(), newText);
    }

    // ---------- 画面（描画とキー・マウスの受け取り） ----------

    private final class View extends JComponent implements Scrollable {

        View() {
            setFocusable(true);
            setOpaque(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    moveCaret(indexAt(e.getPoint()), e.isShiftDown());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    moveCaret(indexAt(e.getPoint()), true);     // 押したままで動かすと範囲選択
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);

            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    handleKeyPressed(e);
                }

                @Override
                public void keyTyped(KeyEvent e) {
                    handleKeyTyped(e);
                }
            });

            addFocusListener(new FocusListener() {
                public void focusGained(FocusEvent e) {
                    caretVisible = true;
                    blinkTimer.start();
                    repaint();
                }

                public void focusLost(FocusEvent e) {
                    blinkTimer.stop();
                    repaint();
                }
            });
        }

        private void handleKeyPressed(KeyEvent e) {
            if (e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                return;                 // Ctrl+S などは、メニューのショートカットに任せる
            }
            boolean shift = e.isShiftDown();
            ensureLayout();
            switch (e.getKeyCode()) {
                // 縦書きなので、下が「次の文字」、左が「次の行」になる
                case KeyEvent.VK_DOWN -> moveCaret(caret + 1, shift);
                case KeyEvent.VK_UP -> moveCaret(caret - 1, shift);
                case KeyEvent.VK_LEFT -> moveColumn(1, shift);
                case KeyEvent.VK_RIGHT -> moveColumn(-1, shift);
                case KeyEvent.VK_HOME -> moveCaret(text.lastIndexOf('\n', caret - 1) + 1, shift);
                case KeyEvent.VK_END -> {
                    int end = text.indexOf('\n', caret);
                    moveCaret(end < 0 ? text.length() : end, shift);
                }
                case KeyEvent.VK_ENTER -> insert("\n");
                case KeyEvent.VK_BACK_SPACE -> {
                    if (selectionEnd() > selectionStart()) {
                        insert("");
                    } else if (caret > 0) {
                        replace(caret - charLengthBefore(caret), caret, "");
                    }
                }
                case KeyEvent.VK_DELETE -> {
                    if (selectionEnd() > selectionStart()) {
                        insert("");
                    } else if (caret < text.length()) {
                        replace(caret, caret + charLengthAt(caret), "");
                    }
                }
                default -> {
                    return;             // それ以外のキーは、ここでは扱わない
                }
            }
            e.consume();
        }

        private void handleKeyTyped(KeyEvent e) {
            char c = e.getKeyChar();
            // Enter・BackSpace などの制御文字は keyPressed で処理済みなので、ここでは文字だけを入れる。
            // 日本語入力（IME）で確定した文字も、ここに1文字ずつ届く。
            if (c == KeyEvent.CHAR_UNDEFINED || c < 0x20 || c == 0x7F
                    || e.isControlDown() || e.isMetaDown() || (e.isAltDown() && !e.isAltGraphDown())) {
                return;
            }
            insert(String.valueOf(c));
            e.consume();
        }

        /** 左右の列へ移る。同じ高さにいちばん近い文字へ動かす。 */
        private void moveColumn(int delta, boolean shift) {
            int target = colOf[caret] + delta;
            if (target < 0 || target >= totalCols) {
                return;
            }
            moveCaret(indexAt(target, rowOf[caret]), shift);
        }

        private int charLengthBefore(int position) {
            return position >= 2 && Character.isLowSurrogate(text.charAt(position - 1))
                    && Character.isHighSurrogate(text.charAt(position - 2)) ? 2 : 1;
        }

        private int charLengthAt(int position) {
            return position + 1 < text.length() && Character.isHighSurrogate(text.charAt(position))
                    && Character.isLowSurrogate(text.charAt(position + 1)) ? 2 : 1;
        }

        @Override
        public Dimension getPreferredSize() {
            ensureLayout();
            int needed = totalCols * columnWidth() + 2 * margin();
            int viewportWidth = scrollPane.getViewport().getExtentSize().width;
            return new Dimension(Math.max(needed, viewportWidth), viewportHeight());
        }

        @Override
        protected void paintComponent(Graphics g) {
            ensureLayout();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle clip = g2.getClipBounds();
            g2.setColor(Theme.BG);
            g2.fillRect(clip.x, clip.y, clip.width, clip.height);

            int columnWidth = columnWidth();
            int right = getWidth() - margin();

            // 1列おきの縞と、カーソルのある列の強調
            for (int col = 0; col < totalCols; col++) {
                int x = right - (col + 1) * columnWidth;
                if (x > clip.x + clip.width || x + columnWidth < clip.x) {
                    continue;
                }
                if (col == colOf[Math.min(caret, text.length())]) {
                    g2.setColor(Theme.CURRENT_LINE);
                    g2.fillRect(x, 0, columnWidth, getHeight());
                } else if (Theme.stripes && col % 2 == 1) {
                    g2.setColor(Theme.BG_STRIPE);
                    g2.fillRect(x, 0, columnWidth, getHeight());
                }
            }

            // 検索の色と、範囲選択の色
            for (Highlight h : highlights) {
                fillRange(g2, h.start(), h.end(), h.color());
            }
            fillRange(g2, selectionStart(), selectionEnd(), Theme.SELECTION);

            // 文字
            g2.setFont(getFont());
            g2.setColor(Theme.FG);
            FontMetrics fm = g2.getFontMetrics();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n' || Character.isLowSurrogate(c)) {
                    continue;
                }
                Rectangle cell = cellRect(i);
                if (!cell.intersects(clip)) {
                    continue;           // 見えていない文字は描かない
                }
                String s = Character.isHighSurrogate(c) && i + 1 < text.length()
                        ? text.substring(i, i + 2) : String.valueOf(c);
                drawCharacter(g2, fm, s, cell);
            }

            // カーソル（文字の上に引く横線）
            if (isFocusOwner() && caretVisible && caret <= text.length()) {
                Rectangle cell = cellRect(caret);
                g2.setColor(Color.WHITE);
                g2.fillRect(cell.x + 6, cell.y - 1, cell.width - 12, 3);
            }
            g2.dispose();
        }

        private void fillRange(Graphics2D g2, int start, int end, Color color) {
            g2.setColor(color);
            for (int i = Math.max(0, start); i < Math.min(end, text.length()); i++) {
                if (text.charAt(i) != '\n') {
                    Rectangle cell = cellRect(i);
                    g2.fillRect(cell.x + 4, cell.y, cell.width - 8, cell.height);
                }
            }
        }

        /** 1文字を、マスの中央に描く。記号や半角文字は、縦書き用に回したり寄せたりする。 */
        private void drawCharacter(Graphics2D g2, FontMetrics fm, String s, Rectangle cell) {
            char c = s.charAt(0);
            int centerX = cell.x + cell.width / 2;
            int centerY = cell.y + cell.height / 2;
            int x = centerX - fm.stringWidth(s) / 2;
            int baseline = centerY + (fm.getAscent() - fm.getDescent()) / 2;
            int size = getFont().getSize();

            boolean halfWidth = c <= 0x00FF || (c >= 0xFF61 && c <= 0xFF9F);
            if (halfWidth || ROTATED.indexOf(c) >= 0) {
                Graphics2D rotated = (Graphics2D) g2.create();
                rotated.rotate(Math.PI / 2, centerX, centerY);      // 時計回りに 90 度
                rotated.drawString(s, x, baseline);
                rotated.dispose();
            } else if (PUNCTUATION.indexOf(c) >= 0) {
                g2.drawString(s, x + Math.round(size * 0.55f), baseline - Math.round(size * 0.55f));
            } else if (SMALL_KANA.indexOf(c) >= 0) {
                g2.drawString(s, x + Math.round(size * 0.1f), baseline - Math.round(size * 0.1f));
            } else {
                g2.drawString(s, x, baseline);
            }
        }

        // --- Scrollable：スクロール枠に「高さは枠に合わせ、横だけスクロールする」と伝える ---

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return columnWidth();
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(columnWidth(), visibleRect.width - columnWidth());
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return false;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return true;
        }
    }
}
