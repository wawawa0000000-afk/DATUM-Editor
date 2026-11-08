package com.example.minieditor.apple;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** 実行して見た目と操作を確かめるデモ。引数に --light を付けるとライト。 */
public final class AppleBoardDemo {

    private AppleBoardDemo() {
    }

    /** サンプルのボード（スクリーンショットと同じ配置）。 */
    static BoardPanel sampleBoard(AppleTheme theme) {
        BoardPanel b = new BoardPanel(theme);
        b.addGroup("冒頭", 72, 48, 600, 400, 0);
        b.addGroup("ミッション概要", 120, 120, 360, 232, 1);
        BoardPanel.Card fall = b.addCard("fall", "墜落", 240, 192, 164, 80);
        BoardPanel.Card start = b.addCard("start", "スタート", 496, 208, 160, 96);
        BoardPanel.Card c3 = b.addCard("c3", "", 736, 216, 164, 96);
        BoardPanel.Card c4 = b.addCard("c4", "", 744, 368, 164, 96);
        b.link(fall, start);
        b.link(start, c3);
        b.link(start, c4);
        return b;
    }

    /** 上部バー：タイトルだけ。 */
    private static JPanel topBar(AppleTheme t) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(t.hair);
                g.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        p.setBackground(t.bg);
        p.setPreferredSize(new Dimension(10, 52));
        JLabel title = new JLabel("時系列", JLabel.CENTER);
        title.setFont(AppleTheme.font(Font.BOLD, 13f));
        title.setForeground(t.text);
        p.add(title, BorderLayout.CENTER);
        return p;
    }

    /** 状態バー：AI連携・文字数・ズームだけ。 */
    private static JPanel statusBar(AppleTheme t, BoardPanel board) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(t.hair);
                g.fillRect(0, 0, getWidth(), 1);
            }
        };
        p.setBackground(t.bg);
        p.setPreferredSize(new Dimension(10, 28));
        p.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 8));

        p.add(label(t, "● AI 連携中"), BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        right.setOpaque(false);
        right.add(label(t, "今日 0 / 5,000 字"));
        JButton minus = flat(t, "−", "縮小");
        JLabel zoom = label(t, "100%");
        zoom.setForeground(t.text);
        JButton plus = flat(t, "+", "拡大");
        minus.addActionListener(e -> board.zoomBy(-1));
        plus.addActionListener(e -> board.zoomBy(1));
        board.addPropertyChangeListener("zoom", e -> zoom.setText(Math.round(board.zoom() * 100) + "%"));
        right.add(minus);
        right.add(zoom);
        right.add(plus);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private static JLabel label(AppleTheme t, String s) {
        JLabel l = new JLabel(s);
        l.setFont(AppleTheme.font(Font.PLAIN, 12f));
        l.setForeground(t.textSecondary);
        return l;
    }

    private static JButton flat(AppleTheme t, String s, String name) {
        JButton b = new JButton(s);
        b.setFont(AppleTheme.font(Font.BOLD, 14f));
        b.setForeground(t.textSecondary);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(true);
        b.setPreferredSize(new Dimension(28, 28));
        b.getAccessibleContext().setAccessibleName(name);
        return b;
    }

    public static void main(String[] args) {
        boolean light = args.length > 0 && "--light".equals(args[0]);
        AppleTheme theme = light ? AppleTheme.LIGHT : AppleTheme.DARK;
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("DATUM-Editor — カードボード");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.getContentPane().setBackground(theme.bg);
            BoardPanel board = sampleBoard(theme);
            f.add(topBar(theme), BorderLayout.NORTH);
            f.add(board, BorderLayout.CENTER);
            f.add(statusBar(theme, board), BorderLayout.SOUTH);
            f.setSize(1280, 800);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
