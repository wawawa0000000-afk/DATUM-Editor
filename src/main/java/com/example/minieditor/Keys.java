package com.example.minieditor;

import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

/** 部品にキーを割り当てる小さな道具（検索バー・欄の一覧・ターミナルで同じ書き方をしていたもの）。 */
final class Keys {

    private Keys() {
    }

    /** component に焦点があるとき、key で action を動かす。name は、その割り当ての名前。 */
    static void bind(JComponent component, KeyStroke key, String name, Runnable action) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }
}
