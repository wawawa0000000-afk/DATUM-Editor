package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.nio.file.Path;
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
import javax.swing.text.Highlighter;
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

    /** 横書きで、ルビを親文字の上に小さく出すか（表示メニューの「ルビを上に表示」。開いている横書きの文書すべてに効く）。 */
    static boolean rubyAbove = false;

    /** 文章の中のルビの場所。文章が変わったら空にして、次に描くときに探し直す。 */
    private List<RubySpans.Span> rubySpans;

    /** 文章の中の Markdown の読み取り結果。文章が変わったら空にして、次に描くときに読み直す。 */
    private Markdown.Result markdown;
    private boolean viewIsMarkdown;         // いまの見た目の組み立てが、Markdown 用（行ごとに高さが変わる）か
    private int headingSignature;           // 組み立てたときの、見出しの段の並び

    /** ふつうのテキストエリアに、ルビの表示と Markdown の装飾を足したもの。 */
    private final class MarkdownArea extends StripedTextArea implements MarkdownTextAreaUI.Host {
        @Override
        public void updateUI() {
            setUI(new MarkdownTextAreaUI());    // 文字を描く部分だけを差し替える（幅は変えない）
        }

        @Override
        protected void paintBackdrop(Graphics g) {
            MarkdownTextAreaUI.paintBackdrop(g, this, markdown());
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);            // 文字や選択を描いたあとに、ルビの記法を覆って、ふりがなを重ねる
            if (rubyAbove) {
                paintRuby(g);
            }
        }

        @Override
        public Markdown.Result markdown() {
            return TextEditorPane.this.markdown();
        }

        @Override
        public boolean markdownOn() {
            return isMarkdown();
        }

        /** 見出しだけ高い行を、行ごとに縞にする（行を2つおきに、地を少し変える）。 */
        @Override
        protected boolean paintLineStripes(Graphics g, Rectangle visible) {
            if (!isMarkdown()) {
                return false;
            }
            try {
                int first = getLineOfOffset(Math.max(0, viewToModel2D(new Point(0, visible.y))));
                int last = getLineOfOffset(Math.max(0, viewToModel2D(new Point(0, visible.y + visible.height))));
                g.setColor(Theme.BG_STRIPE);
                for (int line = first; line <= last; line++) {
                    if (line % 2 == 1) {
                        Rectangle2D a = modelToView2D(getLineStartOffset(line));
                        Rectangle2D b = modelToView2D(getLineEndOffset(line) - (line == getLineCount() - 1 ? 0 : 1));
                        g.fillRect(visible.x, (int) a.getY(), visible.width, (int) (b.getY() + b.getHeight() - a.getY()));
                    }
                }
            } catch (BadLocationException ex) {
                return false;
            }
            return true;
        }
    }

    private final StripedTextArea area = new MarkdownArea();
    private final Shared shared;
    private final List<Object> highlightTags = new ArrayList<>();
    private final DocumentListener documentListener = new DocumentListener() {
        public void insertUpdate(DocumentEvent e) {
            rubySpans = null;
            markdown = null;
            checkHeadings();
            changed();
        }

        public void removeUpdate(DocumentEvent e) {
            rubySpans = null;
            markdown = null;
            checkHeadings();
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
        area.setMargin(normalMargin());
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

    /** 拡張子が .md の文書か（Markdown の装飾をするか）。 */
    private boolean isMarkdown() {
        return isMarkdownFile(getPath());
    }

    /** 装飾に使う、今の文書の Markdown の読み取り結果（.md でないときは null）。 */
    private Markdown.Result markdown() {
        if (!isMarkdown()) {
            return null;
        }
        if (markdown == null) {
            markdown = Markdown.analyze(area.getText());
        }
        return markdown;
    }

    @Override
    protected void pathChanged() {
        markdown = null;                // 名前を付けて保存で、拡張子が変わったとき
        rebuildView();
    }

    /** .md かどうかが変わっていたら、見た目の組み立て（行の高さ・折り返し）をやり直す。 */
    private void rebuildView() {
        if (area == null) {
            return;                     // まだ作っている途中
        }
        boolean on = isMarkdown();
        if (on != viewIsMarkdown && area.getUI() instanceof MarkdownTextAreaUI ui) {
            viewIsMarkdown = on;
            headingSignature = on ? MarkdownTextAreaUI.headingSignature(markdown()) : 0;
            ui.rebuild();
        }
        area.repaint();
    }

    /** 文章が変わったあと、見出しの段の並びが変わっていたら（コードブロックの開け閉めなど）、行の高さを組み直す。 */
    private void checkHeadings() {
        if (!viewIsMarkdown) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!viewIsMarkdown || !(area.getUI() instanceof MarkdownTextAreaUI ui)) {
                return;
            }
            int signature = MarkdownTextAreaUI.headingSignature(markdown());
            if (signature != headingSignature) {
                headingSignature = signature;
                ui.rebuild();
            }
        });
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
            area.setMargin(normalMargin());
            area.revalidate();
        }
    }

    public boolean isFocusMode() {
        return focusMode;
    }

    /** ふつうの余白（上下 4、左右 12 に、設定の余白の倍率を掛ける）。 */
    private static Insets normalMargin() {
        float f = Spacing.marginFactor();
        return new Insets(Math.round(4 * f), Math.round(12 * f), Math.round(4 * f), Math.round(12 * f));
    }

    /** 余白の設定を、画面に反映する（集中モードのあいだは、集中モードの余白を使う）。 */
    public void applyMargin() {
        if (!focusMode) {
            area.setMargin(normalMargin());
            area.revalidate();
        }
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

    /**
     * ルビを上に表示するとき：ルビの記法（｜《》など）を行の背景色で覆い、親文字の上にふりがなを小さく描く。
     * 描くのは見えている範囲のルビだけ。位置は描くたびに画面の座標から求め直すので、スクロールや折り返しに追従する。
     * 覆うのは見た目だけで、文書の中身・カーソル・選択は変えない。
     * カーソルの点滅のたびにも描くので、画面の位置（modelToView2D）を求めるのは、1つのルビにつき記法の切れ目の数回だけにする。
     */
    private void paintRuby(Graphics g) {
        if (rubySpans == null) {
            rubySpans = RubySpans.find(area.getText());
        }
        if (rubySpans.isEmpty()) {
            return;
        }
        // 描き直す範囲（カーソルの点滅では、カーソルの周りだけ）に掛かるルビだけを描く。
        // ふりがなは親文字より左右にはみ出し、1つ上の行に描くので、範囲を左右に数字ぶん・下に1行ぶん広げて探す
        Rectangle clip = g.getClipBounds();
        Rectangle visible = clip != null ? clip : area.getVisibleRect();
        if (visible.isEmpty()) {
            return;
        }
        int rowHeight = area.getFontMetrics(area.getFont()).getHeight();
        int side = rowHeight * 4;
        int first = area.viewToModel2D(new Point(visible.x - side, visible.y));
        int last = area.viewToModel2D(new Point(visible.x + visible.width + side, visible.y + visible.height + rowHeight));
        Font rubyFont = area.getFont().deriveFont(Math.max(9f, area.getFont().getSize2D() / 2));
        FontMetrics metrics = g.getFontMetrics(rubyFont);
        int caret = area.getCaretPosition();
        boolean noSelection = area.getSelectionStart() == area.getSelectionEnd();
        int caretY = -1;                        // カーソルのある行の高さ（強調の色を決めるため）
        try {
            caretY = (int) area.modelToView2D(caret).getY();
        } catch (BadLocationException ex) {
            // カーソル位置は必ず文書の中にあるので、通常は起きない
        }
        boolean caretCovered = false;           // カーソルが覆った記法の中にあるか（覆ったあと、カーソルを描き直す）
        // 選択・検索の色は、覆った上から塗り直す（記法の上でも色が消えないように）。描く範囲に掛かるものだけを集めておく
        List<Highlighter.Highlight> marks = new ArrayList<>();
        for (Highlighter.Highlight h : area.getHighlighter().getHighlights()) {
            if (h.getEndOffset() > first && h.getStartOffset() <= last && h.getEndOffset() > h.getStartOffset()) {
                marks.add(h);
            }
        }
        for (int i = firstSpanEndingAtOrAfter(first); i < rubySpans.size(); i++) {
            RubySpans.Span span = rubySpans.get(i);
            if (span.markupStart() > last) {
                break;                          // ここから先は画面の外（ルビは先頭から順に並んでいる）
            }
            try {
                Rectangle2D markupStart = area.modelToView2D(span.markupStart());
                Rectangle2D head = span.baseStart() == span.markupStart() ? markupStart : area.modelToView2D(span.baseStart());
                Rectangle2D baseEnd = area.modelToView2D(span.baseEnd());
                Rectangle2D markupEnd = area.modelToView2D(span.markupEnd());
                // 記法（親文字の前の ｜ と、後ろの 《ルビ》）だけを覆う。親文字は見せたまま
                cover(g, span.markupStart(), markupStart, span.baseStart(), head, null, noSelection, caretY);
                cover(g, span.baseEnd(), baseEnd, span.markupEnd(), markupEnd, null, noSelection, caretY);
                for (Highlighter.Highlight h : marks) {
                    repaintMark(g, h, span.markupStart(), span.baseStart(), noSelection, caretY);
                    repaintMark(g, h, span.baseEnd(), span.markupEnd(), noSelection, caretY);
                }
                if (noSelection && caret >= span.markupStart() && caret <= span.markupEnd()) {
                    caretCovered = true;
                }
                // ふりがなは、親文字の1行目の左端と右端の真ん中にそろえ、その行の上に描く
                double right = baseEnd.getY() == head.getY() ? baseEnd.getX() : rightOfRow(span.baseStart(), span.baseEnd(), head);
                int x = (int) Math.round((head.getX() + right) / 2 - metrics.stringWidth(span.reading()) / 2.0);
                int y = (int) head.getY() - metrics.getDescent() - 1;
                g.setFont(rubyFont);
                g.setColor(area.getForeground());
                g.drawString(span.reading(), x, y);
            } catch (BadLocationException ex) {
                // 位置は文書の中にあるので、通常は起きない。この1つだけ飛ばす
            }
        }
        if (caretCovered) {
            area.getCaret().paint(g);           // 覆った上から、カーソルを描き直す（文字の上のカーソルが隠れないように）
        }
    }

    /** 記法の終わりが offset 以降にある、最初のルビの番号（ルビは先頭から順に並び、重ならないので、二分探索で探せる）。 */
    private int firstSpanEndingAtOrAfter(int offset) {
        int low = 0;
        int high = rubySpans.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (rubySpans.get(mid).markupEnd() < offset) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * start〜end の字を、color（null なら行の背景色）で覆う。startRect・endRect は start・end の画面の位置（字の左端）。
     * 同じ行に収まっていれば1つの四角で覆い、折り返しをまたぐときだけ、1字ずつ覆う。
     */
    private void cover(Graphics g, int start, Rectangle2D startRect, int end, Rectangle2D endRect, Color color,
            boolean noSelection, int caretY) throws BadLocationException {
        if (start >= end) {
            return;
        }
        if (startRect.getY() == endRect.getY()) {
            fillRow(g, startRect, endRect.getX() - startRect.getX(), color, noSelection, caretY);
            return;
        }
        for (int p = start; p < end; p++) {
            Rectangle2D r = area.modelToView2D(p);
            Rectangle2D next = area.modelToView2D(p + 1);
            fillRow(g, r, next.getY() == r.getY() ? next.getX() - r.getX() : charWidth(p), color, noSelection, caretY);
        }
    }

    /** 選択・検索の色 h のうち、覆った start〜end に重なる所を、その色で塗り直す（字は描かないので、記法は見えないまま）。 */
    private void repaintMark(Graphics g, Highlighter.Highlight h, int start, int end, boolean noSelection, int caretY)
            throws BadLocationException {
        int from = Math.max(start, h.getStartOffset());
        int to = Math.min(end, h.getEndOffset());
        if (from >= to) {
            return;
        }
        Color color = h.getPainter() instanceof DefaultHighlighter.DefaultHighlightPainter painter && painter.getColor() != null
                ? painter.getColor() : area.getSelectionColor();     // 色の無い塗り方は、範囲選択（選択の色で塗る）
        cover(g, from, area.modelToView2D(from), to, area.modelToView2D(to), color, noSelection, caretY);
    }

    /** color（null なら行の背景色。カーソルの行は強調の色）で、r の左端から width だけ覆う。 */
    private void fillRow(Graphics g, Rectangle2D r, double width, Color color, boolean noSelection, int caretY) {
        boolean current = noSelection && (int) r.getY() == caretY;
        g.setColor(color != null ? color : current ? Theme.CURRENT_LINE : rowBackground((int) r.getY()));
        g.fillRect((int) r.getX(), (int) r.getY(), (int) Math.ceil(width), (int) Math.ceil(r.getHeight()));
    }

    /** 親文字が折り返しをまたぐとき、1行目（head の行）の最後の字の右端。 */
    private double rightOfRow(int baseStart, int baseEnd, Rectangle2D head) throws BadLocationException {
        double right = head.getX() + charWidth(baseStart);
        for (int p = baseStart + 1; p < baseEnd; p++) {
            Rectangle2D r = area.modelToView2D(p);
            if (r.getY() == head.getY()) {
                right = Math.max(right, r.getX() + charWidth(p));
            }
        }
        return right;
    }

    /** p の字の幅（行の終わりの字のように、次の字の位置から求められないとき）。 */
    private int charWidth(int p) throws BadLocationException {
        return area.getFontMetrics(area.getFont()).stringWidth(area.getText(p, 1));
    }

    /** 画面の行の背景色（縞模様は StripedTextArea と同じ決め方）。 */
    private Color rowBackground(int y) {
        int rowHeight = area.getFontMetrics(area.getFont()).getHeight();     // JTextArea の1行の高さと同じ
        int row = rowHeight > 0 ? (y - area.getInsets().top) / rowHeight : 0;
        return Theme.stripes && row % 2 == 1 ? Theme.BG_STRIPE : Theme.BG;
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
