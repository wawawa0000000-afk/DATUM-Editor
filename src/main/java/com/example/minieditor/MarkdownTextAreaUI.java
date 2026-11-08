package com.example.minieditor;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

import javax.swing.JTextArea;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.PlainView;
import javax.swing.text.Segment;
import javax.swing.text.TabExpander;
import javax.swing.text.Utilities;
import javax.swing.text.View;
import javax.swing.text.WrappedPlainView;

/**
 * Markdown の装飾つきで文字を描く、JTextArea の見た目の部分。
 * 文字の幅も高さも変えず、色・線・かすかな二重打ち（太字）・傾け（斜体）だけで装飾する。
 * 幅が変わらないので、カーソル・選択・折り返し・行番号は、ふつうの文書とまったく同じに動く。
 * 見出しを大きくはできない（JTextArea は、行ごとに文字の大きさを変えられないため）。
 * Host が null の結果を返す間（.md でないとき）は、ふつうの JTextArea と同じ描き方。
 */
final class MarkdownTextAreaUI extends BasicTextAreaUI {

    /** 装飾の元になる結果を渡す側（TextEditorPane の中のテキストエリア）。 */
    interface Host {
        /** 今の文書を読み取った結果。装飾しないときは null。 */
        Markdown.Result markdown();
    }

    @Override
    public View create(Element elem) {
        JTextComponent c = getComponent();
        Object i18n = elem.getDocument().getProperty("i18n");
        if (c instanceof JTextArea area && !Boolean.TRUE.equals(i18n)) {
            return area.getLineWrap()
                    ? new Wrapped(elem, area.getWrapStyleWord())
                    : new Plain(elem);
        }
        return super.create(elem);
    }

    private static final class Plain extends PlainView {
        Plain(Element elem) {
            super(elem);
        }

        @Override
        protected int drawUnselectedText(Graphics g, int x, int y, int p0, int p1) throws BadLocationException {
            return paintText(this, this, g, x, y, p0, p1);
        }
    }

    private static final class Wrapped extends WrappedPlainView {
        Wrapped(Element elem, boolean wordWrap) {
            super(elem, wordWrap);
        }

        @Override
        protected int drawUnselectedText(Graphics g, int x, int y, int p0, int p1) throws BadLocationException {
            return paintText(this, this, g, x, y, p0, p1);
        }
    }

    // ---------- 文字 ----------

    /** p0 から p1（1行の中）の文字を、印ごとに区切って描く。描き終わった右端の x を返す。 */
    private static int paintText(View view, TabExpander tabs, Graphics g, int x, int y, int p0, int p1)
            throws BadLocationException {
        JTextComponent host = (JTextComponent) view.getContainer();
        Document doc = view.getDocument();
        Markdown.Result r = host instanceof Host h ? h.markdown() : null;
        Segment segment = new Segment();
        if (r == null || r.length != doc.getLength()) {
            g.setColor(host.getForeground());
            doc.getText(p0, p1 - p0, segment);
            return Utilities.drawTabbedText(segment, x, y, g, tabs, p0);
        }
        int line = r.lineOf(p0);
        Markdown.Kind kind = r.kind[line];
        boolean onCaret = r.lineOf(host.getCaretPosition()) == line;
        int i = p0;
        while (i < p1) {
            int flags = r.flags[i];
            int j = i + 1;
            while (j < p1 && r.flags[j] == flags) {
                j++;
            }
            doc.getText(i, j - i, segment);
            x = paintRun(host, tabs, g, x, y, segment, i, flags, kind, onCaret);
            i = j;
        }
        return x;
    }

    /** 同じ印が続く1区間を描く。 */
    private static int paintRun(JTextComponent host, TabExpander tabs, Graphics g, int x, int y, Segment s, int offset,
            int flags, Markdown.Kind kind, boolean onCaret) {
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
            color = mix(fg, host.getBackground(), onCaret ? 0.30 : 0.62);
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
                color = mix(fg, host.getBackground(), 0.5);
                strike = true;
            }
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
            int firstLine = r.lineOf(area.viewToModel2D(new Point(0, visible.y)));
            int lastLine = r.lineOf(area.viewToModel2D(new Point(0, visible.y + visible.height)));
            Color codeTint = mix(Theme.BG, Theme.FG, 0.10);
            Color bar = mix(Theme.BG, Theme.ACCENT, 0.65);
            Color rule = mix(Theme.FG, Theme.BG, 0.55);
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

    /** a と b を、b を ratio の割合で混ぜた色。 */
    static Color mix(Color a, Color b, double ratio) {
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * ratio),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * ratio),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * ratio));
    }
}
