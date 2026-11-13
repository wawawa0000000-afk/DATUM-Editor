package com.example.minieditor;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;

import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.BoxView;
import javax.swing.text.Caret;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.LayeredHighlighter;
import javax.swing.text.PlainDocument;
import javax.swing.text.Position;
import javax.swing.text.Segment;
import javax.swing.text.TabExpander;
import javax.swing.text.Utilities;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;

/**
 * Markdown の装飾つきで文字を描く、JTextArea の見た目の部分（拡張子 .md の文書だけ。ほかの文書は標準の描き方のまま）。
 * 行ごとに文字の大きさを変えられるように、行の高さ・折り返し・カーソルの位置・描画を自前のビュー（DocView・Line）で受け持つ。
 * 見出し（# ～ ######）は、段に応じて文字を大きく・太くする。太字・斜体・取り消し線・リンクなどは、
 * 色・かすかな二重打ち・傾け・線で装飾する（文字の幅は変えない）。
 *
 * 「新しい行を上に表示」（TypingPosition.upward）のときも、.md でなくても自前のビューを使い、行を下から上へ並べる
 * （文書の最後の行がいちばん上。文書の中身の順番は変えず、見え方だけを逆にする。折り返した1行の中の段は、上から下のまま）。
 */
final class MarkdownTextAreaUI extends BasicTextAreaUI {

    /** 装飾の元になる結果を渡す側（TextEditorPane の中のテキストエリア）。 */
    interface Host {
        /** 拡張子 .md の文書か（見出しを大きくするか）。 */
        boolean markdownOn();

        /** 行を下から上へ並べるか（文書の最後の行を、いちばん上に）。 */
        boolean reversedOn();

        /** 今の文書を読み取った結果。装飾しないときは null。 */
        Markdown.Result markdown();
    }

    /** 見出しの段ごとの、文字の大きさの倍率（1 段 … 6 段）。 */
    private static final float[] HEADING_SCALE = {1.6f, 1.4f, 1.2f, 1.1f, 1.0f, 1.0f};

    @Override
    public View create(Element elem) {
        JTextComponent c = getComponent();
        Object i18n = elem.getDocument().getProperty("i18n");
        if (c instanceof JTextArea area && c instanceof Host h && (h.markdownOn() || h.reversedOn())
                && !Boolean.TRUE.equals(i18n)) {
            return new DocView(elem, area.getLineWrap(), area.getWrapStyleWord(), h.reversedOn());
        }
        return super.create(elem);
    }

    /** 見た目の組み立てをやり直す（.md かどうか・並べる向きが変わったとき、見出しの段が変わったとき）。 */
    void rebuild() {
        if (getComponent() != null) {
            modelChanged();
        }
    }

    /** 見出しの段を、読み取りの結果から決めた並びの印（変わったら、行の高さを組み直す）。 */
    static int headingSignature(Markdown.Result r) {
        int h = 1;
        if (r != null) {
            for (int i = 0; i < r.kind.length; i++) {
                if (r.kind[i] == Markdown.Kind.HEADING) {
                    h = h * 31 + i * 7 + r.level[i];
                }
            }
        }
        return h;
    }

    // ---------- ビュー（文書全体と、1行ずつ） ----------

    /** 文書全体。行（Line）を縦に並べる（reversed なら下から上へ）。折り返しの幅・タブ・選択の色を、行に渡す。 */
    private static final class DocView extends BoxView implements TabExpander {
        private final boolean wrap;
        private final boolean word;
        private final boolean reversed;
        private FontMetrics metrics;
        private float tabSize;
        private int tabBase;
        private int width;
        private int sel0;
        private int sel1;
        private Color unselected;
        private Color selected;

        DocView(Element elem, boolean wrap, boolean word, boolean reversed) {
            super(elem, Y_AXIS);
            this.wrap = wrap;
            this.word = word;
            this.reversed = reversed;
        }

        /** 行の縦の位置を決める。逆向きのときは、最後の行を上から順に積む。 */
        @Override
        protected void layoutMajorAxis(int targetSpan, int axis, int[] offsets, int[] spans) {
            super.layoutMajorAxis(targetSpan, axis, offsets, spans);
            if (reversed) {
                int y = 0;
                for (int i = spans.length - 1; i >= 0; i--) {
                    offsets[i] = y;
                    y += spans[i];
                }
            }
        }

        /** 点 y を含む行。並びの外なら、近いほうの端の行（逆向きでも、位置で探す）。 */
        @Override
        protected View getViewAtPoint(int x, int y, Rectangle alloc) {
            int n = getViewCount();
            if (!reversed || n == 0) {
                return super.getViewAtPoint(x, y, alloc);
            }
            int index = -1;
            for (int i = 0; i < n; i++) {
                int top = alloc.y + getOffset(Y_AXIS, i);
                if (y >= top && y < top + getSpan(Y_AXIS, i)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                index = y < alloc.y + getOffset(Y_AXIS, n - 1) ? n - 1 : 0;     // 上の外は最後の行、下の外は最初の行
            }
            childAllocation(index, alloc);
            return getView(index);
        }

        /** 押した位置の文字。逆向きのときは、上下の外も含めて、位置で行を探す（標準の作りは、上の外を文書の頭とみなすため）。 */
        @Override
        public int viewToModel(float x, float y, Shape a, Position.Bias[] bias) {
            if (!reversed) {
                return super.viewToModel(x, y, a, bias);
            }
            if (!isAllocationValid()) {
                Rectangle r = a.getBounds();
                setSize(r.width, r.height);
            }
            Rectangle alloc = getInsideAllocation(a);
            View v = getViewAtPoint((int) x, (int) y, alloc);
            if (v == null) {
                bias[0] = Position.Bias.Forward;
                return getStartOffset();
            }
            return v.viewToModel(x, y, alloc, bias);
        }

        /** ↑↓ の移動。逆向きのときは、文書の前後ではなく、画面の上下の位置で決める。 */
        @Override
        public int getNextVisualPositionFrom(int pos, Position.Bias b, Shape a, int direction, Position.Bias[] biasRet)
                throws BadLocationException {
            if (!reversed || pos < 0 || (direction != NORTH && direction != SOUTH)) {
                return super.getNextVisualPositionFrom(pos, b, a, direction, biasRet);
            }
            JTextComponent host = (JTextComponent) getContainer();
            Rectangle2D r = host.modelToView2D(pos);
            if (r == null) {
                return pos;
            }
            Point magic = host.getCaret().getMagicCaretPosition();     // ↑↓ を続けて押すあいだ、横の位置を保つ
            int x = magic != null ? magic.x : (int) r.getX();
            int y = direction == NORTH ? (int) r.getY() - 1 : (int) (r.getY() + r.getHeight());
            int top = host.getInsets().top;     // a は、まだ描いていないと null のことがあるので、部品の余白から求める
            int n = getViewCount();
            int bottom = n == 0 ? top : top + getOffset(Y_AXIS, 0) + getSpan(Y_AXIS, 0);
            if (y < top || y >= bottom) {
                return pos;                     // いちばん上・下の段からは、動かない
            }
            biasRet[0] = Position.Bias.Forward;
            return host.viewToModel2D(new Point(x, y));
        }

        private int tabCount() {
            Integer i = (Integer) getDocument().getProperty(PlainDocument.tabSizeAttribute);
            return i != null ? i : 8;
        }

        private void updateMetrics() {
            Component host = getContainer();
            if (host != null) {
                metrics = host.getFontMetrics(host.getFont());
                tabSize = tabCount() * metrics.charWidth('m');
            }
        }

        @Override
        public float nextTabStop(float x, int tabOffset) {
            if (tabSize == 0) {
                return x;
            }
            int n = (int) ((x - tabBase) / tabSize);
            return tabBase + (n + 1) * tabSize;
        }

        @Override
        protected void loadChildren(ViewFactory f) {
            Element e = getElement();
            int n = e.getElementCount();
            if (n > 0) {
                View[] added = new View[n];
                for (int i = 0; i < n; i++) {
                    added[i] = new Line(e.getElement(i));
                }
                replace(0, 0, added);
            }
        }

        private void updateChildren(DocumentEvent e, Shape a) {
            DocumentEvent.ElementChange ec = e.getChange(getElement());
            if (ec != null) {
                Element[] removed = ec.getChildrenRemoved();
                Element[] addedElems = ec.getChildrenAdded();
                View[] added = new View[addedElems.length];
                for (int i = 0; i < addedElems.length; i++) {
                    added[i] = new Line(addedElems[i]);
                }
                replace(ec.getIndex(), removed.length, added);
                if (a != null) {
                    preferenceChanged(null, true, true);
                    getContainer().repaint();
                }
            }
            updateMetrics();
        }

        @Override
        public void paint(Graphics g, Shape a) {
            Rectangle alloc = (Rectangle) a;
            tabBase = alloc.x;
            JTextComponent host = (JTextComponent) getContainer();
            sel0 = host.getSelectionStart();
            sel1 = host.getSelectionEnd();
            unselected = host.isEnabled() ? host.getForeground() : host.getDisabledTextColor();
            Caret c = host.getCaret();
            selected = c.isSelectionVisible() && host.getHighlighter() != null ? host.getSelectedTextColor() : unselected;
            g.setFont(host.getFont());
            super.paint(g, a);
        }

        @Override
        public void setSize(float w, float h) {
            updateMetrics();
            if ((int) w != width) {
                width = (int) w;
                preferenceChanged(null, true, true);    // 行の高さは、幅から決まる
            }
            super.setSize(w, h);
        }

        @Override
        public float getPreferredSpan(int axis) {
            updateMetrics();
            return super.getPreferredSpan(axis);
        }

        @Override
        public float getMinimumSpan(int axis) {
            updateMetrics();
            return super.getMinimumSpan(axis);
        }

        @Override
        public float getMaximumSpan(int axis) {
            updateMetrics();
            return super.getMaximumSpan(axis);
        }

        @Override
        public void insertUpdate(DocumentEvent e, Shape a, ViewFactory f) {
            updateChildren(e, a);
            Rectangle alloc = a != null && isAllocationValid() ? getInsideAllocation(a) : null;
            View v = getViewAtPosition(e.getOffset(), alloc);
            if (v != null) {
                v.insertUpdate(e, alloc, f);
            }
        }

        @Override
        public void removeUpdate(DocumentEvent e, Shape a, ViewFactory f) {
            updateChildren(e, a);
            Rectangle alloc = a != null && isAllocationValid() ? getInsideAllocation(a) : null;
            View v = getViewAtPosition(e.getOffset(), alloc);
            if (v != null) {
                v.removeUpdate(e, alloc, f);
            }
        }

        @Override
        public void changedUpdate(DocumentEvent e, Shape a, ViewFactory f) {
            updateChildren(e, a);
        }

        /** 1行（折り返せば複数の段）。見出しの行は、大きく太い文字で、高さも大きい。 */
        private final class Line extends View {
            private Font baseSeen;
            private Font font;
            private FontMetrics fm;
            private boolean dirty = true;       // 文字の大きさ（見出しの段）を決め直す
            private int rows = -1;              // 折り返した段の数（-1 なら、数え直す）
            private int[] ends;                 // 各段の終わり（行頭からの位置）。1段のときは null
            private int builtWidth = -1;
            private float textWidth = -1;       // 折り返さないときの、行の幅

            Line(Element elem) {
                super(elem);
            }

            private JTextComponent host() {
                return (JTextComponent) getContainer();
            }

            /** 文字の大きさと、折り返しを、今の状態に合わせる。 */
            private void ensure() {
                JTextComponent host = host();
                Font base = host.getFont();
                if (dirty || base != baseSeen || fm == null) {
                    updateMetrics();
                    baseSeen = base;
                    dirty = false;
                    int level = headingLevel(host);
                    if (level == 0) {
                        font = base;
                        fm = metrics;
                    } else {
                        font = base.deriveFont(Font.BOLD, base.getSize2D() * HEADING_SCALE[level - 1]);
                        fm = host.getFontMetrics(font);
                    }
                    rows = -1;
                    textWidth = -1;
                }
                if (rows < 0 || (wrap && builtWidth != width)) {
                    breakRows();
                }
            }

            /** この行の見出しの段（見出しでなければ 0）。読み取りの結果が今の文書と合っていれば結果から、合わなければ行の頭の字から。 */
            private int headingLevel(JTextComponent host) {
                if (!(host instanceof Host h) || !h.markdownOn()) {
                    return 0;                   // .md でない文書（逆向きに並べるだけのとき）は、見出しにしない
                }
                Markdown.Result r = h.markdown();
                int start = getStartOffset();
                if (r != null && r.length == getDocument().getLength()) {
                    int line = r.lineOf(start);
                    return r.kind[line] == Markdown.Kind.HEADING ? r.level[line] : 0;
                }
                int end = Math.min(getEndOffset() - 1, getDocument().getLength());
                Segment seg = new Segment();
                load(seg, start, Math.min(end, start + 12));
                int i = 0;
                while (i < seg.count && seg.array[seg.offset + i] == ' ' && i < 3) {
                    i++;
                }
                int run = 0;
                while (i + run < seg.count && seg.array[seg.offset + i + run] == '#') {
                    run++;
                }
                if (run < 1 || run > 6) {
                    return 0;
                }
                int after = i + run;
                if (after >= seg.count) {
                    return end - start <= after ? run : 0;      // 「#」だけの行
                }
                char c = seg.array[seg.offset + after];
                return c == ' ' || c == '\t' ? run : 0;
            }

            private void load(Segment seg, int p0, int p1) {
                try {
                    getDocument().getText(p0, Math.max(0, p1 - p0), seg);
                } catch (BadLocationException ex) {
                    throw new IllegalStateException("行の文字を取れません: " + p0 + "," + p1, ex);
                }
            }

            private void breakRows() {
                builtWidth = width;
                int start = getStartOffset();
                int end = getEndOffset();
                if (!wrap || width <= 0 || end - start <= 1) {
                    rows = 1;
                    ends = null;
                    return;
                }
                int count = 0;
                int[] tmp = new int[8];
                int p0 = start;
                while (p0 < end) {
                    int p = breakAt(p0, end);
                    p0 = p == p0 ? p0 + 1 : p;
                    if (count == 0 && p0 >= end) {
                        count = 1;
                        tmp = null;
                        break;
                    }
                    if (count >= tmp.length) {
                        tmp = Arrays.copyOf(tmp, tmp.length * 2);
                    }
                    tmp[count++] = p0 - start;
                }
                rows = Math.max(1, count);
                ends = rows > 1 ? Arrays.copyOf(tmp, rows) : null;
            }

            private int breakAt(int p0, int p1) {
                Segment seg = new Segment();
                load(seg, p0, p1);
                return word
                        ? p0 + Utilities.getBreakLocation(seg, fm, tabBase, tabBase + width, DocView.this, p0)
                        : p0 + Utilities.getTabbedTextOffset(seg, fm, tabBase, tabBase + width, DocView.this, p0, false);
            }

            private int rowStart(int row) {
                return row == 0 ? getStartOffset() : getStartOffset() + ends[row - 1];
            }

            private int rowEnd(int row) {
                return ends == null ? getEndOffset() : getStartOffset() + ends[row];
            }

            /** 位置（行頭からの相対）がある段。 */
            private int rowOf(int relative) {
                if (ends == null) {
                    return 0;
                }
                for (int i = 0; i < rows; i++) {
                    if (relative < ends[i]) {
                        return i;
                    }
                }
                return rows - 1;
            }

            @Override
            public float getPreferredSpan(int axis) {
                ensure();
                if (axis == X_AXIS) {
                    if (wrap) {
                        return width > 0 && width != Integer.MAX_VALUE ? width : 100f;
                    }
                    if (textWidth < 0) {
                        Segment seg = new Segment();
                        int start = getStartOffset();
                        load(seg, start, Math.min(getEndOffset() - 1, getDocument().getLength()));
                        textWidth = Utilities.getTabbedTextWidth(seg, fm, 0, DocView.this, start);
                    }
                    return textWidth;
                }
                if (axis == Y_AXIS) {
                    return rows * fm.getHeight();
                }
                throw new IllegalArgumentException("Invalid axis: " + axis);
            }

            @Override
            public float getMaximumSpan(int axis) {
                return axis == X_AXIS ? Integer.MAX_VALUE : getPreferredSpan(axis);
            }

            @Override
            public float getAlignment(int axis) {
                return 0f;
            }

            @Override
            public void paint(Graphics g, Shape a) {
                ensure();
                Rectangle alloc = (Rectangle) a;
                JTextComponent host = host();
                Highlighter h = host.getHighlighter();
                LayeredHighlighter dh = h instanceof LayeredHighlighter lh ? lh : null;
                int end = getEndOffset();
                g.setFont(font);
                int y = alloc.y + fm.getAscent();
                for (int i = 0; i < rows; i++) {
                    int p0 = rowStart(i);
                    int p1 = rowEnd(i);
                    if (dh != null) {
                        dh.paintLayeredHighlights(g, p0, p1 == end ? p1 - 1 : p1, a, host, this);
                    }
                    paintRow(g, alloc.x, y, p0, Math.min(p1 == end ? p1 - 1 : p1, getDocument().getLength()), host);
                    y += fm.getHeight();
                }
            }

            /** 1段の文字を、印ごとに区切って描く（選択の中は、選択の文字の色）。 */
            private void paintRow(Graphics g, int x, int y, int p0, int p1, JTextComponent host) {
                if (p1 <= p0) {
                    return;
                }
                Markdown.Result r = host instanceof Host h ? h.markdown() : null;
                boolean styled = r != null && r.length == getDocument().getLength();
                int line = styled ? r.lineOf(p0) : 0;
                Markdown.Kind kind = styled ? r.kind[line] : Markdown.Kind.NORMAL;
                boolean onCaret = styled && r.lineOf(host.getCaretPosition()) == line;
                Segment seg = new Segment();
                int i = p0;
                while (i < p1) {
                    int flags = styled ? r.flags[i] : 0;
                    boolean inSelection = i >= sel0 && i < sel1;
                    int j = i + 1;
                    while (j < p1 && (styled ? r.flags[j] : 0) == flags && (j >= sel0 && j < sel1) == inSelection) {
                        j++;
                    }
                    load(seg, i, j);
                    x = paintRun(host, DocView.this, g, x, y, seg, i, flags, kind, onCaret, inSelection && selected != unselected ? selected : null);
                    i = j;
                }
            }

            @Override
            public Shape modelToView(int pos, Shape a, Position.Bias b) throws BadLocationException {
                ensure();
                Rectangle alloc = a.getBounds();
                alloc.height = fm.getHeight();
                alloc.width = 1;
                int p0 = getStartOffset();
                if (pos < p0 || pos > getEndOffset()) {
                    throw new BadLocationException("Position out of range", pos);
                }
                int test = b == Position.Bias.Forward ? pos : Math.max(p0, pos - 1);
                int row = rowOf(test - p0);
                alloc.y += alloc.height * row;
                int rs = rowStart(row);
                if (pos > rs) {
                    Segment seg = new Segment();
                    load(seg, rs, pos);
                    float x = alloc.x + Utilities.getTabbedTextWidth(seg, fm, alloc.x, DocView.this, rs);
                    return new Rectangle2D.Float(x, alloc.y, alloc.width, alloc.height);
                }
                return alloc;
            }

            @Override
            public int viewToModel(float fx, float fy, Shape a, Position.Bias[] bias) {
                ensure();
                bias[0] = Position.Bias.Forward;
                Rectangle alloc = (Rectangle) a;
                int x = (int) fx;
                int y = (int) fy;
                if (y < alloc.y) {
                    return getStartOffset();
                }
                if (y > alloc.y + alloc.height) {
                    return getEndOffset() - 1;
                }
                int rowHeight = fm.getHeight();
                int row = rowHeight > 0 ? (y - alloc.y) / rowHeight : rows - 1;
                if (row >= rows) {
                    return getEndOffset() - 1;
                }
                int p0 = rowStart(row);
                int p1 = rowEnd(row);
                if (x < alloc.x) {
                    return p0;
                }
                if (x > alloc.x + alloc.width) {
                    return p1 - 1;
                }
                Segment seg = new Segment();
                load(seg, p0, p1);
                int n = Utilities.getTabbedTextOffset(seg, fm, alloc.x, x, DocView.this, p0, false);
                return Math.min(p0 + n, p1 - 1);
            }

            private void changed(DocumentEvent ev, Shape a) {
                int before = rows < 0 || fm == null ? -1 : rows * fm.getHeight();
                dirty = true;
                ensure();
                if (wrap) {
                    // 折り返しの有無・見出しの段が変わると高さが変わる
                    if (before != rows * fm.getHeight()) {
                        DocView.this.preferenceChanged(this, false, true);
                        getContainer().repaint();
                        return;
                    }
                } else {
                    DocView.this.preferenceChanged(this, true, before != rows * fm.getHeight());
                    getContainer().repaint();
                    return;
                }
                if (a != null) {
                    Rectangle alloc = (Rectangle) a;
                    getContainer().repaint(alloc.x, alloc.y, alloc.width, alloc.height);
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e, Shape a, ViewFactory f) {
                changed(e, a);
            }

            @Override
            public void removeUpdate(DocumentEvent e, Shape a, ViewFactory f) {
                changed(e, a);
            }

            @Override
            public void changedUpdate(DocumentEvent e, Shape a, ViewFactory f) {
                changed(e, a);
            }
        }
    }

    // ---------- 文字 ----------

    /** 同じ印が続く1区間を描く。 */
    private static int paintRun(JTextComponent host, TabExpander tabs, Graphics g, int x, int y, Segment s, int offset,
            int flags, Markdown.Kind kind, boolean onCaret, Color selectedColor) {
        Color fg = host.getForeground();
        Color color = fg;
        boolean bold = (flags & Markdown.BOLD) != 0;
        boolean italic = (flags & Markdown.ITALIC) != 0;
        boolean strike = (flags & Markdown.STRIKE) != 0;
        boolean underline = false;

        if ((flags & Markdown.MARK) != 0) {
            if (kind == Markdown.Kind.RULE && !onCaret) {
                // 区切り線の「---」は、見せずに線を引く（線は背景を描くときに引いている）
                return x + Utilities.getTabbedTextWidth(s, g.getFontMetrics(), x, tabs, offset);
            }
            color = Theme.mix(fg, host.getBackground(), onCaret ? 0.30 : 0.62);
            bold = false;
            italic = false;
        } else {
            if ((flags & Markdown.HEAD) != 0) {
                color = Theme.ACCENT;
                bold = true;
            }
            if (bold && color == fg) {
                color = Theme.FG_STRONG;
            }
            if ((flags & Markdown.LINK) != 0) {
                color = Theme.ACCENT;
                underline = true;
            }
            if ((flags & (Markdown.BULLET | Markdown.CHECK)) != 0) {
                color = Theme.ACCENT;
            }
            if ((flags & Markdown.DONE) != 0) {
                color = Theme.mix(fg, host.getBackground(), 0.5);
                strike = true;
            }
        }

        if (selectedColor != null) {
            color = selectedColor;      // 選んでいる字は、選択の文字の色（装飾の色は使わない）
        }
        if (g.getFont().isBold()) {
            bold = false;               // 見出しの行は、もともと太い字で描いている
        }
        FontMetrics fm = g.getFontMetrics();
        g.setColor(color);
        int end;
        if (italic) {
            Graphics2D shear = (Graphics2D) g.create();
            Graphics g2 = shear;                // int の版（Graphics）を選ばせるため、Graphics として持つ
            try {
                shear.translate(x, y);
                shear.shear(-0.2, 0);       // 文字の幅は変えず、上を右へ傾ける
                shear.translate(-x, -y);
                end = Utilities.drawTabbedText(s, x, y, g2, tabs, offset);
                if (bold) {
                    Utilities.drawTabbedText(s, x + 1, y, g2, tabs, offset);
                }
            } finally {
                shear.dispose();
            }
        } else {
            end = Utilities.drawTabbedText(s, x, y, g, tabs, offset);
            if (bold) {
                Utilities.drawTabbedText(s, x + 1, y, g, tabs, offset);     // 1px ずらして二重に打つ（幅は変わらない）
            }
        }
        if (underline) {
            g.fillRect(x, y + 2, Math.max(0, end - x), 1);
        }
        if (strike) {
            g.fillRect(x, y - fm.getAscent() / 3, Math.max(0, end - x), 1);
        }
        return end;
    }

    // ---------- 背景（文字より先に描く） ----------

    /**
     * 見えている範囲の、コードの地・引用の縦線・区切り線を描く（文字より先に）。
     * 位置は描くたびに画面の座標から求めるので、スクロールや折り返しに追従する。
     */
    static void paintBackdrop(Graphics g, JTextComponent area, Markdown.Result r) {
        if (r == null || r.length != area.getDocument().getLength()) {
            return;
        }
        Rectangle clip = g.getClipBounds();
        Rectangle visible = clip != null ? clip : area.getVisibleRect();
        if (visible.isEmpty()) {
            return;
        }
        try {
            int topLine = r.lineOf(area.viewToModel2D(new Point(0, visible.y)));
            int bottomLine = r.lineOf(area.viewToModel2D(new Point(0, visible.y + visible.height)));
            int firstLine = Math.min(topLine, bottomLine);      // 行を下から上へ並べるときは、上の行のほうが後ろ
            int lastLine = Math.max(topLine, bottomLine);
            Color codeTint = Theme.mix(Theme.BG, Theme.FG, 0.10);
            Color bar = Theme.mix(Theme.BG, Theme.ACCENT, 0.65);
            Color rule = Theme.mix(Theme.FG, Theme.BG, 0.55);
            int left = area.getInsets().left;
            int right = area.getWidth() - area.getInsets().right;
            for (int line = firstLine; line <= lastLine && line < r.kind.length; line++) {
                Markdown.Kind kind = r.kind[line];
                int start = r.lineStart[line];
                int end = r.lineEnd(line);
                switch (kind) {
                    case CODE, FENCE -> {
                        Rectangle2D a = area.modelToView2D(start);
                        Rectangle2D b = area.modelToView2D(end);
                        g.setColor(codeTint);
                        g.fillRect(visible.x, (int) a.getY(), visible.width, (int) (b.getY() + b.getHeight() - a.getY()));
                    }
                    case QUOTE -> {
                        Rectangle2D a = area.modelToView2D(start);
                        Rectangle2D b = area.modelToView2D(end);
                        g.setColor(bar);
                        g.fillRect(Math.max(0, left - 8), (int) a.getY(), 3, (int) (b.getY() + b.getHeight() - a.getY()));
                    }
                    case RULE -> {
                        Rectangle2D a = area.modelToView2D(start);
                        g.setColor(rule);
                        g.fillRect(left, (int) (a.getY() + a.getHeight() / 2), Math.max(0, right - left), 1);
                    }
                    default -> paintInlineCode(g, area, r, start, end, codeTint);
                }
            }
        } catch (BadLocationException ex) {
            // 位置は文書の中のものだけなので、通常は起きない
        }
    }

    /** 行の中のコード（`...`）の地。折り返しをまたぐものは、地を付けない（色は変わらない）。 */
    private static void paintInlineCode(Graphics g, JTextComponent area, Markdown.Result r, int start, int end,
            Color tint) throws BadLocationException {
        int i = start;
        while (i < end) {
            if ((r.flags[i] & Markdown.CODE) == 0) {
                i++;
                continue;
            }
            int j = i + 1;
            while (j < end && (r.flags[j] & Markdown.CODE) != 0 && (r.flags[j] & Markdown.MARK) == 0) {
                j++;
            }
            Rectangle2D a = area.modelToView2D(i);
            Rectangle2D b = area.modelToView2D(j);
            if ((int) a.getY() == (int) b.getY()) {
                g.setColor(tint);
                g.fillRoundRect((int) a.getX() - 1, (int) a.getY() + 1, (int) (b.getX() - a.getX()) + 2,
                        (int) a.getHeight() - 2, 5, 5);
            }
            i = j;
        }
    }
}
