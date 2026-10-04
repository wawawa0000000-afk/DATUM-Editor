package com.example.minieditor;

import javax.swing.SwingUtilities;

/**
 * アプリの入口（起動するクラス）。
 * 実際の画面作りは EditorFrame が担当する。
 */
public class Main {

    public static void main(String[] args) {
        // Swing の部品は必ず「イベントディスパッチスレッド（EDT）」で作る必要がある。
        // invokeLater でその手順を予約し、画面を作って表示する。
        SwingUtilities.invokeLater(() -> {
            EditorFrame frame = new EditorFrame();
            frame.setVisible(true); 
        });
    }
}
