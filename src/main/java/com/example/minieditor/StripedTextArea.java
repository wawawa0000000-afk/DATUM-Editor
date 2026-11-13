package com.example.minieditor;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.Rectangle2D;
import java.text.CharacterIterator;
import java.util.HashMap;
import java.util.Map;

import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;

/**
 * 背景を自分で描くテキストエリア。
 * 1行おきの縞模様と、カーソルのある行の強調を、文字より先に描いている。
 * （JTextArea には「行ごとの背景色」という機能がないため、paintComponent を上書きする）
 *
 * 行の高さ（Spacing.row）も、ここで変える。JTextArea は行の高さを、文字の大きさの寸法（FontMetrics の getHeight）から決めるので、
 * getFontMetrics を上書きして、高さを足した寸法を返す。足したぶんは字の上と下に半分ずつ分けるので、字は行の真ん中に来る。
 * 縞・カーソルの行・選択・行番号・Markdown の見出しは、どれもこの寸法から高さを求めるので、そのまま追従する。
 */
public class StripedTextArea extends JTextArea {

    /**
     * 作った寸法（getFontMetrics は描くたびに何度も呼ばれ、PlainView は寸法が前と違うと最長の行を測り直すので、同じ物を返す）。
     * 字の大きさごとに覚える（Markdown の見出しは、段ごとに字が違う）。行の高さが変わったら作り直す。
     */
    private final Map<Font, FontMetrics> tallMetrics = new HashMap<>();
    private int tallRow;

    public StripedTextArea() {
        setOpaque(false);                       // 自動の塗りつぶしを止めて、自分で背景を描く
        setBackground(Theme.BG);
        setForeground(Theme.FG);
        setCaretColor(Theme.FG_STRONG);
        setSelectionColor(Theme.SELECTION);
        setSelectedTextColor(Theme.FG_STRONG);
        setMargin(new Insets(4, 12, 4, 12));
    }

    @Override
    public FontMetrics getFontMetrics(Font font) {
        FontMetrics plain = super.getFontMetrics(font);
        int row = Spacing.row();
        if (row <= Spacing.ROW_MIN || font == null) {
            return plain;                       // 標準の高さ（今までと同じ寸法）
        }
        if (row != tallRow || tallMetrics.size() > 32) {     // 字の大きさを何度も変えたときに、溜まりすぎないように
            tallMetrics.clear();
            tallRow = row;
        }
        return tallMetrics.computeIfAbsent(font,
                f -> new TallMetrics(plain, Math.round(plain.getHeight() * (row - 100) / 100f)));
    }

    /** 行の高さを足したぶんのうち、字の上に空く高さ（ルビを上に描くときの位置合わせに使う）。標準の高さでは 0。 */
    public int rowGapAbove() {
        return getFontMetrics(getFont()) instanceof TallMetrics tall ? tall.above : 0;
    }

    @Override
    protected void paintComponent(Graphics g) {
        paintBackground(g);
        paintBackdrop(g);
        super.paintComponent(g);                // 背景の上に、文字やハイライトを描かせる
    }

    /** 行の高さがそろわない文書（見出しだけ大きい Markdown）で、縞を自分で描くときに上書きする。描いたら true。 */
    protected boolean paintLineStripes(Graphics g, Rectangle visible) {
        return false;
    }

    /** 縞やカーソル行の上、文字の下に、何か描きたいときに上書きする（Markdown のコードの地など）。ふだんは何もしない。 */
    protected void paintBackdrop(Graphics g) {
    }

    private void paintBackground(Graphics g) {
        Rectangle visible = getVisibleRect();
        g.setColor(getBackground());
        g.fillRect(visible.x, visible.y, visible.width, visible.height);

        int rowHeight = getRowHeight();
        if (rowHeight <= 0) {
            return;
        }
        int top = getInsets().top;

        if (Theme.stripes && paintLineStripes(g, visible)) {
            // 子クラスが、行の高さに合わせて縞を描いた
        } else if (Theme.stripes) {
            g.setColor(Theme.BG_STRIPE);
            int first = Math.max((visible.y - top) / rowHeight, 0);
            int last = (visible.y + visible.height - top) / rowHeight;
            for (int row = first; row <= last; row++) {
                if (row % 2 == 1) {
                    g.fillRect(visible.x, top + row * rowHeight, visible.width, rowHeight);
                }
            }
        }

        // カーソルのある行を、少し明るくする（範囲選択中は、選択の色を優先する）
        if (getSelectionStart() == getSelectionEnd()) {
            try {
                Rectangle2D caret = modelToView2D(getCaretPosition());
                if (caret != null) {
                    g.setColor(Theme.CURRENT_LINE);
                    g.fillRect(visible.x, (int) caret.getY(), visible.width, (int) caret.getHeight());
                }
            } catch (BadLocationException ex) {
                // カーソル位置は必ず文書の中にあるので、通常は起きない
            }
        }
    }

    /** 元の寸法に、行の高さ extra を足した寸法。字の幅などは、元の寸法をそのまま返す。 */
    private static final class TallMetrics extends FontMetrics {
        private final FontMetrics plain;
        private final int above;                // 字の上に足す高さ
        private final int below;                // 字の下に足す高さ

        TallMetrics(FontMetrics plain, int extra) {
            super(plain.getFont());
            this.plain = plain;
            this.above = extra / 2;
            this.below = extra - extra / 2;
        }

        @Override
        public FontRenderContext getFontRenderContext() {
            return plain.getFontRenderContext();
        }

        @Override
        public int getLeading() {
            return plain.getLeading();
        }

        @Override
        public int getAscent() {
            return plain.getAscent() + above;
        }

        @Override
        public int getDescent() {
            return plain.getDescent() + below;
        }

        @Override
        public int getHeight() {
            return plain.getHeight() + above + below;
        }

        @Override
        public int getMaxAscent() {
            return plain.getMaxAscent() + above;
        }

        @Override
        public int getMaxDescent() {
            return plain.getMaxDescent() + below;
        }

        @Override
        public int getMaxAdvance() {
            return plain.getMaxAdvance();
        }

        @Override
        public int charWidth(int codePoint) {
            return plain.charWidth(codePoint);
        }

        @Override
        public int charWidth(char ch) {
            return plain.charWidth(ch);
        }

        @Override
        public int stringWidth(String str) {
            return plain.stringWidth(str);
        }

        @Override
        public int charsWidth(char[] data, int off, int len) {
            return plain.charsWidth(data, off, len);
        }

        @Override
        public int bytesWidth(byte[] data, int off, int len) {
            return plain.bytesWidth(data, off, len);
        }

        @Override
        public int[] getWidths() {
            return plain.getWidths();
        }

        @Override
        public boolean hasUniformLineMetrics() {
            return plain.hasUniformLineMetrics();
        }

        @Override
        public LineMetrics getLineMetrics(String str, Graphics context) {
            return plain.getLineMetrics(str, context);
        }

        @Override
        public LineMetrics getLineMetrics(String str, int beginIndex, int limit, Graphics context) {
            return plain.getLineMetrics(str, beginIndex, limit, context);
        }

        @Override
        public LineMetrics getLineMetrics(char[] chars, int beginIndex, int limit, Graphics context) {
            return plain.getLineMetrics(chars, beginIndex, limit, context);
        }

        @Override
        public LineMetrics getLineMetrics(CharacterIterator ci, int beginIndex, int limit, Graphics context) {
            return plain.getLineMetrics(ci, beginIndex, limit, context);
        }

        @Override
        public Rectangle2D getStringBounds(String str, Graphics context) {
            return plain.getStringBounds(str, context);
        }

        @Override
        public Rectangle2D getStringBounds(String str, int beginIndex, int limit, Graphics context) {
            return plain.getStringBounds(str, beginIndex, limit, context);
        }

        @Override
        public Rectangle2D getStringBounds(char[] chars, int beginIndex, int limit, Graphics context) {
            return plain.getStringBounds(chars, beginIndex, limit, context);
        }

        @Override
        public Rectangle2D getStringBounds(CharacterIterator ci, int beginIndex, int limit, Graphics context) {
            return plain.getStringBounds(ci, beginIndex, limit, context);
        }

        @Override
        public Rectangle2D getMaxCharBounds(Graphics context) {
            return plain.getMaxCharBounds(context);
        }
    }
}
