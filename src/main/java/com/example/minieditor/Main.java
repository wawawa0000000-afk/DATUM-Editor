package com.example.minieditor;

import javax.swing.SwingUtilities;

/**
 * アプリの入口（起動するクラス）。
 * 実際の画面作りは EditorFrame が担当する。
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // 「--mcp-stdio」付きで起動されたときは、画面を出さずに、AI のアプリとエディタの橋渡し役として動く
        if (args.length > 0 && McpBridge.OPTION.equals(args[0])) {
            McpBridge.run();
            return;
        }

        // Swing の部品は必ず「イベントディスパッチスレッド（EDT）」で作る必要がある。
        // invokeLater でその手順を予約し、画面を作って表示する。
        SwingUtilities.invokeLater(() -> {
            // 色とフォントは、部品を作る前に決めておく（設定で選んだ配色にする）
            AppSettings settings = AppSettings.load();
            Theme.use(Theme.Scheme.fromId(settings.getString(AppSettings.THEME, "gray")));
            Theme.setZoom(settings.getInt(AppSettings.ZOOM, 100));
            Theme.apply();
            EditorFrame frame = new EditorFrame();
            frame.restoreSession();     // 前回閉じたときのタブ・分割・ウィンドウの大きさに戻す
            frame.setVisible(true);
            frame.startMcpIfEnabled();  // 前回、AI 連携を使っていたら、続きから使えるようにする
            SwingUtilities.invokeLater(frame::offerRecovery);   // 前回、保存されずに終わった文書があれば、復元するかを聞く
        });
    }
}
