package com.example.minieditor;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * 矢印ボタンのない、細くて平らなスクロールバー。
 */
public class FlatScrollBarUI extends BasicScrollBarUI {

    /** Swing が、スクロールバーを作るたびに呼ぶ（Theme.apply で登録している）。 */
    public static ComponentUI createUI(JComponent c) {
        return new FlatScrollBarUI();
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        return zeroButton();
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return zeroButton();
    }

    private static JButton zeroButton() {
        JButton button = new JButton();
        Dimension zero = new Dimension(0, 0);
        button.setPreferredSize(zero);
        button.setMinimumSize(zero);
        button.setMaximumSize(zero);
        return button;
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
        g.setColor(Theme.BG);
        g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
        if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(isDragging || isThumbRollover() ? Theme.HOVER : Theme.BORDER);
        g2.fillRoundRect(thumbBounds.x + 3, thumbBounds.y + 3,
                thumbBounds.width - 6, thumbBounds.height - 6, 8, 8);
        g2.dispose();
    }
}
