package com.example.minieditor;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;

import javax.swing.JTextArea;

/**
 * 1行ごとに、背景を基本色と縞の色で交互に塗るテキストエリア。
 * JTextArea 自体には「行ごとの背景色」という機能がないため、
 * paintComponent を上書きして、文字を描く前に縞模様を描いている。
 */
public class StripedTextArea extends JTextArea {

    private final Color stripeColor;

    public StripedTextArea(Color backgroundColor, Color stripeColor) {
        this.stripeColor = stripeColor;
        setOpaque(false);              // 自動の塗りつぶしを止めて、自分で背景を描く
        setBackground(backgroundColor);
    }

    @Override
    protected void paintComponent(Graphics g) {
        paintStripes(g);
        super.paintComponent(g);        // 縞の上に、文字やハイライトを描かせる
    }

    private void paintStripes(Graphics g) {
        Rectangle visible = getVisibleRect();

        // まず全体を基本色で塗る
        g.setColor(getBackground());
        g.fillRect(visible.x, visible.y, visible.width, visible.height);

        int lineHeight = getRowHeight();
        if (lineHeight <= 0) {
            return;
        }

        int lastLine = Math.max(getLineCount() - 1, 0);
        int startLine = visible.y / lineHeight;
        int endLine = Math.min((visible.y + visible.height) / lineHeight, lastLine);

        g.setColor(stripeColor);
        for (int line = startLine; line <= endLine; line++) {
            if (line % 2 == 1) {
                g.fillRect(visible.x, line * lineHeight, visible.width, lineHeight);
            }
        }
    }
}
