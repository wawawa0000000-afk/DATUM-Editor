package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * ヘルプ メニューの3つの画面（使い方・ショートカット一覧・このソフトについて）。
 * ふつうのエディタと同じ操作が多いので、チュートリアルは置かず、困ったときに引ける説明だけを用意する。
 */
final class HelpDialog {

    private HelpDialog() {
    }

    /** 使い方。機能ごとに、短く説明する。 */
    static void showGuide(Frame owner) {
        String html = """
                <h1>使い方</h1>
                <p>ふつうのテキストエディタと同じように使えます。ここでは、このエディタならではの所だけを説明します。</p>
                <p><b>Ctrl+P</b>（右上の虫眼鏡）で、すべての操作を名前で探して実行できます。どこにあるかわからないときは、まずこれを使ってください。</p>

                <h2>文書の種類</h2>
                <ul>
                <li><b>横書きのテキスト</b>：ふつうのエディタです。</li>
                <li><b>縦書き（原稿用紙）</b>：20字×20行の原稿用紙に、縦書きで書けます。「書式 → 原稿用紙の表示」で、ふつうの縦書きにもできます。</li>
                <li><b>カードボード</b>：カードを並べて、アイデアを整理します（KJ法風）。何もない所をダブルクリックすると、カードができます。</li>
                </ul>
                <p>タブの帯の右の <b>＋</b> を押すと、どれを作るかを選べます。</p>

                <h2>ルビと傍点</h2>
                <p>文字を選んで <b>Ctrl+R</b> でルビ（ふりがな）、<b>Ctrl+Shift+R</b> で傍点を付けます。
                .txt で保存すると「｜漢字《かんじ》」の形で書き出すので、小説投稿サイトにそのまま貼り付けられます。</p>

                <h2>保存の形式</h2>
                <ul>
                <li><b>.txt</b>：文章だけを保存します。ほかのソフトでも開けます。</li>
                <li><b>.json</b>：文書の種類・ルビ・カードの配置まで保存します。カードボードは .json で保存します。</li>
                </ul>

                <h2>画面の分割とタブ</h2>
                <p>右上の分割のアイコン（四角を縦線で分けた形）で、画面を左右に分けます。タブは引きずって並べ替えたり、反対側へ移したりできます。
                タブを右クリックすると、移動・分割・閉じるの操作が選べます。</p>

                <h2>右上のボタン</h2>
                <ul>
                <li><b>虫眼鏡</b>：すべての操作を名前で探して実行します（Ctrl+P）。</li>
                <li><b>フォルダ</b>（フォルダの形）：原稿のフォルダを左に表示します。章ごとのファイルを並べて扱えます。</li>
                <li><b>分割</b>（四角を線で分けた形）：画面を2つに分けます。</li>
                <li><b>校正</b>（「校」の印）：ら抜き言葉・表記ゆれ・重複表現などを探します。AI は使わず、文章は外に送りません。</li>
                <li><b>AI</b>：AI に誤字の確認や相談を頼めます。先に「ファイル → 設定」で、使う AI を選んでください。</li>
                <li><b>&gt;_</b>：エディタの中にターミナルを出します。</li>
                <li><b>集中</b>：メニューやタブを隠して、本文だけにします。Esc で戻ります。</li>
                </ul>

                <h2>原稿を失わないために</h2>
                <ul>
                <li>保存するたびに版が残ります。「ファイル → 版の履歴」で、前の状態と比べたり、戻したりできます。</li>
                <li>未保存の文書は 30 秒ごとに控えを取るので、異常終了しても次の起動で復元できます。</li>
                <li>Google Drive に置きたいときは、Google の「Google ドライブ パソコン版」を入れると、Drive がふつうのフォルダ（G: など）として見えるので、そこへ保存してください。Google ドキュメントなど（名前の後ろに ↗）は、サイドバーでダブルクリックするとブラウザで開きます。</li>
                </ul>

                <h2>そのほか</h2>
                <ul>
                <li>ステータスバー右の「今日 … 字」を押すと、1日の目標字数を決められます。</li>
                <li>配色は「表示 → 配色」で、白・灰・紺・黒から選べます。すぐに変わります。</li>
                <li>ショートカットの一覧は「ヘルプ → キーボードショートカット」にあります。</li>
                </ul>
                """;
        show(owner, "使い方", html, new Dimension(900, 820));
    }

    /** ショートカットの一覧。メニューに登録されているものを集めるので、メニューを変えても一覧がずれない。 */
    static void showShortcuts(Frame owner, JMenuBar menuBar) {
        StringBuilder sb = new StringBuilder("<h1>キーボードショートカット</h1>");
        for (int i = 0; i < menuBar.getMenuCount(); i++) {
            JMenu menu = menuBar.getMenu(i);
            if (menu == null) {
                continue;
            }
            StringBuilder rows = new StringBuilder();
            collect(menu, "", rows);
            if (rows.length() > 0) {
                sb.append("<h2>").append(escape(menu.getText())).append("</h2><table width='100%'>").append(rows).append("</table>");
            }
        }
        sb.append("<h2>メニューにない操作</h2><table width='100%'>");
        String[][] extra = {
            {"集中モードをやめる", "Esc"},
            {"検索：次へ / 前へ", "Enter / Shift+Enter"},
            {"AI の会話欄：送る / 改行", "Enter / Shift+Enter"},
            {"タブを閉じる", "タブを中ボタンでクリック"},
            {"カードの文字を確定する", "Ctrl+Enter"},
            {"画面全体の拡大・縮小", "Ctrl+ホイール"},
            {"カードボードの拡大・縮小（ボードの上で）", "Ctrl+ホイール"},
        };
        for (String[] row : extra) {
            sb.append(row(row[0], row[1]));
        }
        sb.append("</table>");
        show(owner, "キーボードショートカット", sb.toString(), new Dimension(820, 860));
    }

    private static void collect(JMenu menu, String prefix, StringBuilder rows) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem item = menu.getItem(i);
            if (item == null) {
                continue;                   // 区切り線
            }
            if (item instanceof JMenu sub) {
                collect(sub, prefix + sub.getText() + " → ", rows);
            } else if (item.getAccelerator() != null) {
                rows.append(row(prefix + item.getText(), keyText(item.getAccelerator())));
            }
        }
    }

    private static String row(String action, String key) {
        return "<tr><td>" + escape(action) + "</td><td align='right'><span class='key'>" + escape(key) + "</span></td></tr>";
    }

    /** 「Ctrl+Shift+N」のような書き方にする（Java の標準の書き方は、環境によって言葉が変わるため自分で作る）。 */
    static String keyText(KeyStroke stroke) {
        StringBuilder sb = new StringBuilder();
        int mods = stroke.getModifiers();
        if ((mods & InputEvent.CTRL_DOWN_MASK) != 0) {
            sb.append("Ctrl+");
        }
        if ((mods & InputEvent.ALT_DOWN_MASK) != 0) {
            sb.append("Alt+");
        }
        if ((mods & InputEvent.SHIFT_DOWN_MASK) != 0) {
            sb.append("Shift+");
        }
        int code = stroke.getKeyCode();
        String key = switch (code) {
            case KeyEvent.VK_BACK_SLASH -> "\\";
            case KeyEvent.VK_COMMA -> ",";
            case KeyEvent.VK_PERIOD -> ".";
            case KeyEvent.VK_SLASH -> "/";
            case KeyEvent.VK_EQUALS -> "=";
            case KeyEvent.VK_MINUS -> "-";
            case KeyEvent.VK_PLUS -> "+";
            case KeyEvent.VK_SEMICOLON -> ";";
            case KeyEvent.VK_PAGE_UP -> "PageUp";
            case KeyEvent.VK_PAGE_DOWN -> "PageDown";
            case KeyEvent.VK_UP -> "↑";
            case KeyEvent.VK_DOWN -> "↓";
            case KeyEvent.VK_LEFT -> "←";
            case KeyEvent.VK_RIGHT -> "→";
            case KeyEvent.VK_DELETE -> "Delete";
            case KeyEvent.VK_ENTER -> "Enter";
            case KeyEvent.VK_ESCAPE -> "Esc";
            case KeyEvent.VK_TAB -> "Tab";
            default -> {
                if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) {
                    yield "F" + (code - KeyEvent.VK_F1 + 1);
                }
                if ((code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) || (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9)) {
                    yield String.valueOf((char) code);
                }
                yield KeyEvent.getKeyText(code);
            }
        };
        return sb.append(key).toString();
    }

    /** このソフトについて。版・ライセンス・連絡先。 */
    static void showAbout(Frame owner) {
        String html = "<h1>DATUM-Editor</h1>"
                + "<p>バージョン " + McpServer.SERVER_VERSION + "</p>"
                + "<p>小説や文章を書くためのテキストエディタです。</p>"
                + "<h2>ライセンス</h2><p>MIT ライセンスです（配布ファイルの中の LICENSE をご覧ください）。"
                + "個人が作った非公式のソフトで、無保証です。大切な文章は、こまめに保存して控えを取ってください。</p>"
                + "<h2>設定と控えの場所</h2><p>" + escape(McpSettings.directory().toString()) + "</p>"
                + "<p>設定（settings.json）、異常終了に備えた控え（recovery）、版の履歴（history）が入っています。</p>"
                + "<h2>連絡先</h2><p>不具合の報告やご意見は、wazaseisakujo@gmail.com までお願いします。</p>"
                + "<p class='dim'>Java " + escape(System.getProperty("java.version", "?")) + " / "
                + escape(System.getProperty("os.name", "?")) + "</p>";
        show(owner, "DATUM-Editor について", html, new Dimension(760, 620));
    }

    // ---------- 共通 ----------

    private static void show(Frame owner, String title, String body, Dimension size) {
        JEditorPane pane = new JEditorPane("text/html", "");
        pane.setEditable(false);
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.setFont(Theme.ui());
        pane.setBackground(Theme.BG);
        pane.setForeground(Theme.FG);
        pane.setBorder(BorderFactory.createEmptyBorder(8, 24, 16, 24));
        pane.setText("<html><head><style>" + css() + "</style></head><body>" + body + "</body></html>");
        pane.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(pane);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);

        JDialog dialog = new JDialog(owner, title, false);      // 開いたまま、エディタも使えるようにする
        JButton close = new JButton("閉じる");
        close.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(close);
        JPanel root = new JPanel(new BorderLayout());
        root.add(scroll, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setSize(size);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
    }

    private static String css() {
        int body = Theme.SMALL_SIZE;
        return "body { font-family: Dialog; font-size: " + body + "pt; color: " + hex(Theme.FG) + "; }"
                + "h1 { font-size: " + (body + 8) + "pt; color: " + hex(Theme.FG_STRONG) + "; margin-bottom: 6px; }"
                + "h2 { font-size: " + (body + 3) + "pt; color: " + hex(Theme.ACCENT) + "; margin-top: 16px; margin-bottom: 4px; }"
                + "b { color: " + hex(Theme.FG_STRONG) + "; }"
                + "li { margin-bottom: 4px; }"
                + "td { padding: 3px 0; }"
                + ".key { color: " + hex(Theme.FG_STRONG) + "; font-family: Monospaced; }"
                + ".dim { color: " + hex(Theme.FG_DIM) + "; }";
    }

    private static String hex(Color color) {
        return String.format("#%06X", color.getRGB() & 0xFFFFFF);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
