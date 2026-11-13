package com.example.minieditor;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;

import javax.swing.border.AbstractBorder;

/**
 * 角の丸い 1px の枠（入力欄・ダイアログのボタン）。丸みは Theme.arc()、線の色は Theme.overlay で、描くたびに読む
 * （配色や表示の倍率を変えても、作り直さずに追従する）。
 * 部品は四角く地を塗るので、角の外側は、後ろの部品の地の色で塗って丸く見せる。
 */
final class RoundBorder extends AbstractBorder {

    private final int top;
    private final int left;
    private final int bottom;
    private final int right;

    /** 引数は、枠の線（1px）の内側に空ける余白。 */
    RoundBorder(int top, int left, int bottom, int right) {
        this.top = top;
        this.left = left;
        this.bottom = bottom;
        this.right = right;
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        insets.set(top + 1, left + 1, bottom + 1, right + 1);
        return insets;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = Theme.arc();
        Color behind = behind(c);
        if (behind != null) {
            Area corners = new Area(new Rectangle(x, y, width, height));
            corners.subtract(new Area(new RoundRectangle2D.Float(x, y, width, height, arc, arc)));
            g2.setColor(behind);
            g2.fill(corners);
        }
        g2.setColor(Theme.overlay(0.15f));
        g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, width - 1, height - 1, arc, arc));
        g2.dispose();
    }

    /** c の後ろに見えている地の色（地を塗っている、いちばん近い親の色）。 */
    private static Color behind(Component c) {
        for (Container p = c.getParent(); p != null; p = p.getParent()) {
            if (p.isOpaque()) {
                return p.getBackground();
            }
        }
        return null;
    }
}
