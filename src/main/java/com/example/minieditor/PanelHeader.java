package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 欄の上の見出し（左に題、あればその横に補足、右にボタン）。
 * サイドバー・校正・AI・ターミナル・見出し一覧・作品の集計で、同じ余白と並びにする。
 */
final class PanelHeader extends JPanel {

    private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

    PanelHeader(String title) {
        this(new JLabel(title), null);
    }

    /** note は、題の横に薄い字で出す補足（件数など）。なければ null。 */
    PanelHeader(JLabel title, JComponent note) {
        super(new BorderLayout(12, 0));
        setBackground(Theme.PANEL);
        setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 8));
        title.setFont(Theme.small());
        title.setForeground(Theme.FG);
        if (note == null) {
            add(title, BorderLayout.CENTER);    // 欄が狭いときは、題のほうを縮める
        } else {
            note.setFont(Theme.small());
            note.setForeground(Theme.FG_DIM);
            add(title, BorderLayout.WEST);
            add(note, BorderLayout.CENTER);
        }
        buttons.setOpaque(false);
        add(buttons, BorderLayout.EAST);
    }

    /** 右のボタンを、左から順に足す。 */
    PanelHeader with(JButton... added) {
        for (JButton button : added) {
            buttons.add(button);
        }
        return this;
    }

    /** 欄を隠す「×」のボタン。 */
    static JButton closeButton(String tooltip, Runnable onClose) {
        JButton close = Theme.flatButton("×");
        close.setToolTipText(tooltip);
        close.addActionListener(e -> onClose.run());
        return close;
    }
}
