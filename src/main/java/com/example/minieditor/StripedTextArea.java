package com.example.minieditor;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;

/**
 * 背景を自分で描くテキストエリア。
 * 1行おきの縞模様と、カーソルのある行の強調を、文字より先に描いている。
 * （JTextArea には「行ごとの背景色」という機能がないため、paintComponent を上書きする）
 */
public class StripedTextArea extends JTextArea {

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
}
