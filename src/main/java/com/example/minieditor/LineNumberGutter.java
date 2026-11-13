package com.example.minieditor;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Rectangle2D;

import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;

/**
 * テキストエリアの左に出す、行番号の列。
 * スクロール枠の「行見出し」として置くので、本文と一緒に縦スクロールする。
 */
public class LineNumberGutter extends JComponent {

    private final JTextArea area;

    private final DocumentListener documentListener = new DocumentListener() {
        public void insertUpdate(DocumentEvent e) {
            refresh();
        }

        public void removeUpdate(DocumentEvent e) {
            refresh();
        }

        public void changedUpdate(DocumentEvent e) {
            refresh();
        }
    };

    public LineNumberGutter(JTextArea area) {
        this.area = area;
        setOpaque(true);

        // 行数・カーソル・大きさ・フォントが変わったら、描き直す
        area.getDocument().addDocumentListener(documentListener);
        area.addCaretListener(e -> repaint());
        area.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                refresh();
            }
        });
        area.addPropertyChangeListener("font", e -> refresh());
    }

    /** 文書の見張りを外す（同じ文書を映していたタブを閉じたとき）。 */
    public void detach() {
        area.getDocument().removeDocumentListener(documentListener);
    }

    private void refresh() {
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(area.getFont());
        int digits = Math.max(2, String.valueOf(area.getLineCount()).length());
        return new Dimension(fm.charWidth('0') * digits + 28, area.getHeight());
    }

    @Override
    protected void paintComponent(Graphics g) {
        Rectangle clip = g.getClipBounds();
        g.setColor(Theme.BG);
        g.fillRect(clip.x, clip.y, clip.width, clip.height);

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(area.getFont());
        FontMetrics fm = g2.getFontMetrics();
        int descent = area.getFontMetrics(area.getFont()).getDescent();     // 行を高くしたときは、字の下に空く高さを含む

        try {
            // 見えている範囲の、上の端と下の端の行のあいだだけを描く（行を下から上へ並べるときは、上の端の行のほうが後ろ）
            int top = area.getLineOfOffset(Math.max(area.viewToModel2D(new Point(0, clip.y)), 0));
            int bottom = area.getLineOfOffset(Math.max(area.viewToModel2D(new Point(0, clip.y + clip.height)), 0));
            int caretLine = area.getLineOfOffset(area.getCaretPosition());

            for (int line = Math.min(top, bottom); line <= Math.max(top, bottom); line++) {
                Rectangle2D r = area.modelToView2D(area.getLineStartOffset(line));
                if (r == null) {
                    break;
                }
                String number = String.valueOf(line + 1);
                g2.setColor(line == caretLine ? Theme.FG_STRONG : Theme.FG_DIM);
                int x = getWidth() - 14 - fm.stringWidth(number);    // 右にそろえる
                g2.drawString(number, x, (int) (r.getY() + r.getHeight()) - descent);   // 行の下にそろえる（見出しで行が高いときも、1段目の下に付く）
            }
        } catch (BadLocationException ex) {
            // 行の位置は文書の中から求めているので、通常は起きない
        }
    }
}
