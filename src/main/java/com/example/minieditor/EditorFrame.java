package com.example.minieditor;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.InputMap;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicSplitPaneUI;

/**
 * メインウィンドウ。
 *
 * 画面の構成（上から）：メニュー → 検索バー → タブ付きのエディタ（1つ、または分割して2つ） → ステータスバー
 * 左にフォルダのサイドバー、右に校正か AI の欄、下にターミナルを、必要なときだけ出す。
 * 文書そのものの処理は EditorPane の各クラスに任せ、ここでは
 * 「どの文書に対して、何をするか」（開く・保存・閉じる・分割など）を受け持つ。
 */
public class EditorFrame extends JFrame implements EditorPane.Listener, EditorGroup.Host {

    private static final String APP_NAME = "DATUM-Editor";
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;
    private static final int ALT = InputEvent.ALT_DOWN_MASK;

    private final FileService fileService = new FileService();

    private final EditorGroup group1 = new EditorGroup(this);
    private final EditorGroup group2 = new EditorGroup(this);
    private EditorGroup activeGroup = group1;
    private boolean split = false;
    private int splitOrientation = JSplitPane.HORIZONTAL_SPLIT;
    private final JPanel editorArea = new JPanel(new BorderLayout());
    private final JPanel center = new JPanel(new BorderLayout());     // 検索バー・エディタ・ターミナルを置く場所

    private TerminalPane terminal;              // 下に出すターミナル。初めて表示するときに作る
    private boolean terminalVisible = false;
    private FolderSearchPanel folderSearch;     // 下に出すフォルダ内の検索。初めて表示するときに作る
    private boolean folderSearchVisible = false;
    private boolean folderSearchBeforeFocus;
    private OutlinePanel outline;               // 下に出す見出し一覧の欄。初めて表示するときに作る
    private boolean outlineVisible = false;
    private boolean outlineBeforeFocus;
    private WorkPanel work;                     // 下に出す作品の集計の欄。初めて表示するときに作る
    private boolean workVisible = false;
    private boolean workBeforeFocus;
    private JSplitPane lowerSplit;              // エディタ（上）と下の欄（検索・見出し・集計）の分割。いったん作ったら使い回す（欄の高さを覚えておくため）
    private final JToggleButton terminalButton = createToolButton(">_", "ターミナルを表示する / 隠す（Ctrl+J）",
            () -> setTerminalVisible(!terminalVisible));
    private final JToggleButton folderButton = createIconButton(ToolIcons.folder(), "フォルダ",
            "フォルダのサイドバーを表示する / 隠す（Ctrl+Shift+E）", this::toggleSidebar);
    private final JToggleButton proofButton = createIconButton(ToolIcons.proofread(), "校正",
            "校正の欄を表示する / 隠す（F7）", () -> showRight("proof"));
    private final JToggleButton aiButton = createToolButton("AI", "AI の会話欄を表示する / 隠す（Ctrl+Shift+A）",
            () -> showRight("ai"));
    private final JToggleButton focusButton = createToolButton("集中", "集中モード（F11）", this::toggleFocusMode);
    private final JToggleButton paletteButton = createIconButton(ToolIcons.search(), "すべての操作を探す",
            "すべての操作を、名前で探して実行する（Ctrl+P）", this::showPalette);
    private JMenuItem paletteItem;
    private final JToggleButton splitSideButton = createIconButton(ToolIcons.split(() -> false), "左右に分割",
            "左右に分割する / 分割をやめる（Ctrl+\\）。タブが2枚以上なら今のタブを右へ、1枚なら同じ文書を右にも出します",
            () -> splitTo(JSplitPane.HORIZONTAL_SPLIT));
    private final JToggleButton splitDownButton = createIconButton(ToolIcons.split(() -> true), "上下に分割",
            "上下に分割する / 分割をやめる。タブが2枚以上なら今のタブを下へ、1枚なら同じ文書を下にも出します",
            () -> splitTo(JSplitPane.VERTICAL_SPLIT));
    private final JCheckBoxMenuItem terminalItem = new JCheckBoxMenuItem("ターミナル");

    private final FindBar findBar = new FindBar(this::currentPane);
    private final JLabel statusLeft = new JLabel(" ");
    private final JLabel statusRight = new JLabel(" ");
    private JLabel zoomLabel;       // ステータスバーの「100%」
    private final JCheckBoxMenuItem wrapItem = new JCheckBoxMenuItem("折り返し");
    private final JCheckBoxMenuItem manuscriptItem = new JCheckBoxMenuItem("原稿用紙（20字×20行）", true);
    private final AppSettings appSettings = AppSettings.load();  // 最後に保存したフォルダなど
    private final WritingStats stats = new WritingStats(appSettings);   // 1日に書いた字数
    private final GoalMeter goalMeter = new GoalMeter(stats, appSettings, this::updateStatus);  // 目標の進み具合（マス目）
    private final VersionHistory history = new VersionHistory();       // 保存した版の履歴
    private final Map<Path, Instant> lastHistory = new HashMap<>();     // 自動保存で履歴を残した時刻（残しすぎないように）
    private final Set<Path> changedOutsideWarned = new HashSet<>();     // ほかのアプリで変わったので自動保存を止めた、と伝えたファイル
    private final Recovery recovery = new Recovery();                   // 異常終了に備えた、未保存の文書の控え
    private final Timer autosaveTimer = new Timer(30_000, e -> autosaveTick());

    // 画面の左右に出す欄
    private final JPanel mainArea = new JPanel(new BorderLayout());
    private FolderSidebar sidebar;
    private boolean sidebarVisible;
    private ProofreadPanel proofPanel;
    private final Map<EditorPane, Integer> sheetCache = new java.util.WeakHashMap<>();  // 原稿用紙換算の枚数（中身が変わるまで覚えておく。カーソルが動くたびに全文を数えないため）
    private final Map<EditorPane, Object[]> zoomMemo = new java.util.WeakHashMap<>();   // 倍率を変えた文書：{そのとき付けた文字, 等倍の大きさ}
    private final Map<Theme.Scheme, javax.swing.JRadioButtonMenuItem> schemeItems = new java.util.EnumMap<>(Theme.Scheme.class);
    private AiChatPanel chatPanel;
    private McpTools chatTools;
    private String rightPanel;                  // 右に出している欄（"proof" / "ai"）。出していなければ null
    private int sidebarWidth = 300;
    private int rightWidth = 480;
    private JPanel statusBar;

    // 集中モード
    private boolean focusMode;
    private boolean terminalBeforeFocus;
    private int stateBeforeFocus;
    private final McpService mcp = new McpService(this);       // AI 連携（MCP）の窓口
    private final JCheckBoxMenuItem mcpItem = new JCheckBoxMenuItem("MCP サーバーを動かす");
    private String message;                     // ステータスバーに一時的に出す文
    private final Timer messageTimer = new Timer(4000, e -> {
        message = null;
        updateStatus();
    });

    public EditorFrame() {
        super(APP_NAME);
        messageTimer.setRepeats(false);
        // ウィンドウとタスクバーに出すアイコン（JAR の中に入れてある画像を読む）
        java.net.URL icon = EditorFrame.class.getResource("/icon.png");
        if (icon != null) {
            setIconImage(new ImageIcon(icon).getImage());
        }

        center.setBackground(Theme.BG);
        center.add(findBar, BorderLayout.NORTH);
        editorArea.setBackground(Theme.BG);
        center.add(editorArea, BorderLayout.CENTER);

        getContentPane().setBackground(Theme.BG);
        mainArea.setBackground(Theme.BG);
        add(mainArea, BorderLayout.CENTER);
        statusBar = createStatusBar();
        add(statusBar, BorderLayout.SOUTH);
        setJMenuBar(createMenuBar());
        installExtraShortcuts();
        installZoomKeys();
        installZoomWheel();
        layoutMain();

        // どちらのグループにカーソルがあるかを見張って、操作の対象を切り替える
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", e -> {
            if (e.getNewValue() instanceof Component c) {
                EditorGroup group = (EditorGroup) SwingUtilities.getAncestorOfClass(EditorGroup.class, c);
                if (group != null) {
                    setActiveGroup(group);
                }
            }
        });

        group1.setGroupActive(true);
        layoutGroups();
        newDocument(EditorPane.Mode.TEXT);

        setSize(1400, 850);
        setLocationByPlatform(true);
        autosaveTimer.start();
        // ×ボタンを押しても勝手には閉じず、未保存の確認をしてから終了する
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitApp();
            }
        });
    }

    // ---------- 画面の部品 ----------

    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.ACCENT);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 14));
        for (JLabel label : new JLabel[]{statusLeft, statusRight}) {
            label.setFont(Theme.small());
            label.setForeground(Color.WHITE);
        }
        bar.add(statusLeft, BorderLayout.WEST);
        JPanel right = new JPanel(new BorderLayout(24, 0));
        right.setOpaque(false);
        right.add(statusRight, BorderLayout.CENTER);
        JPanel east = new JPanel(new BorderLayout(24, 0));
        east.setOpaque(false);
        if (Edition.has(Edition.VERTICAL)) {
            east.add(goalMeter, BorderLayout.CENTER);       // 目標字数は、小説を書く機能（Writer から）
        }
        east.add(createZoomControl(), BorderLayout.EAST);
        right.add(east, BorderLayout.EAST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    /**
     * ステータスバーの右端の「－ 100% ＋」。タッチパッドしかないノートパソコンでも、クリックだけで倍率を変えられるように。
     * 真ん中の「100%」を押すと、100% に戻る。
     */
    private JPanel createZoomControl() {
        JPanel box = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        box.setOpaque(false);
        box.add(statusButton("－", "縮小（Ctrl+ホイールでも変えられます）", () -> setUiZoom(Theme.nextZoom(-1))));
        zoomLabel = statusButton(Theme.zoom() + "%", "表示の倍率（押すと 100% に戻ります）", () -> setUiZoom(100));
        box.add(zoomLabel);
        box.add(statusButton("＋", "拡大（Ctrl+ホイールでも変えられます）", () -> setUiZoom(Theme.nextZoom(1))));
        return box;
    }

    /** ステータスバーの上で押せる文字。マウスを乗せると、少し明るい地になる。 */
    private JLabel statusButton(String text, String tooltip, Runnable action) {
        JLabel label = new JLabel(text, SwingConstants.CENTER) {
            boolean hover;
            {
                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseEntered(java.awt.event.MouseEvent e) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(java.awt.event.MouseEvent e) {
                        hover = false;
                        repaint();
                    }

                    @Override
                    public void mouseClicked(java.awt.event.MouseEvent e) {
                        action.run();
                    }
                });
            }

            @Override
            public Dimension getPreferredSize() {
                // 「100%」の文字は、倍率が変わっても幅が変わらないように、いちばん長い「200%」の幅にそろえる
                Dimension size = super.getPreferredSize();
                if (getText().endsWith("%")) {
                    size.width = Math.max(size.width, getFontMetrics(getFont()).stringWidth("200%") + 16);
                }
                return size;
            }

            @Override
            protected void paintComponent(Graphics g) {
                if (hover) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(255, 255, 255, 50));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        label.setFont(Theme.small());
        label.setForeground(Color.WHITE);
        label.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        label.setToolTipText(tooltip);
        label.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        return label;
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu file = new JMenu("ファイル");
        file.add(item("新規（テキスト）", key(KeyEvent.VK_N, CTRL), () -> newDocument(EditorPane.Mode.TEXT)));
        // 種類（Edition）に入っていない機能は、メニューに出さない（コマンドパレットとショートカット一覧からも消える）
        if (Edition.has(Edition.VERTICAL)) {
            file.add(item("新規（縦書き）", key(KeyEvent.VK_N, CTRL | SHIFT), () -> newDocument(EditorPane.Mode.VERTICAL)));
        }
        if (Edition.has(Edition.BOARD)) {
            file.add(item("新規（カードボード）", key(KeyEvent.VK_K, CTRL | SHIFT), () -> newDocument(EditorPane.Mode.BOARD)));
        }
        file.addSeparator();
        file.add(item("開く…", key(KeyEvent.VK_O, CTRL), () -> openFile(false)));
        if (Edition.has(Edition.VERTICAL)) {
            file.add(item("縦書きで開く…", key(KeyEvent.VK_O, CTRL | SHIFT), () -> openFile(true)));
        }
        file.add(item("保存", key(KeyEvent.VK_S, CTRL), () -> save(currentPane())));
        file.add(item("名前を付けて保存…", key(KeyEvent.VK_S, CTRL | SHIFT), () -> saveAs(currentPane())));
        file.add(item("PDF として書き出す…", key(KeyEvent.VK_P, CTRL | SHIFT), this::exportPdf));
        file.add(item("版の履歴…", key(KeyEvent.VK_H, CTRL | SHIFT), this::showHistory));
        file.addSeparator();
        file.add(item("フォルダをサイドバーに開く…", null, this::openFolderInSidebar));
        file.add(item("設定…", key(KeyEvent.VK_COMMA, CTRL), this::openSettings));
        file.addSeparator();
        file.add(item("タブを閉じる", key(KeyEvent.VK_W, CTRL), () -> closePane(activeGroup, currentPane())));
        file.add(item("終了", key(KeyEvent.VK_Q, CTRL), this::exitApp));

        JMenu edit = new JMenu("編集");
        edit.add(item("元に戻す", key(KeyEvent.VK_Z, CTRL), () -> withPane(EditorPane::undo)));
        edit.add(item("やり直し", key(KeyEvent.VK_Y, CTRL), () -> withPane(EditorPane::redo)));
        edit.addSeparator();
        edit.add(item("切り取り", key(KeyEvent.VK_X, CTRL), () -> withPane(EditorPane::cut)));
        edit.add(item("コピー", key(KeyEvent.VK_C, CTRL), () -> withPane(EditorPane::copy)));
        edit.add(item("貼り付け", key(KeyEvent.VK_V, CTRL), () -> withPane(EditorPane::paste)));
        edit.add(item("すべて選択", key(KeyEvent.VK_A, CTRL), () -> withPane(EditorPane::selectAll)));
        edit.addSeparator();
        edit.add(item("検索", key(KeyEvent.VK_F, CTRL), () -> findBar.open(false)));
        edit.add(item("置換", key(KeyEvent.VK_H, CTRL), () -> findBar.open(true)));
        edit.add(item("フォルダ内を検索…", key(KeyEvent.VK_F, CTRL | SHIFT), this::showFolderSearch));

        JMenu format = new JMenu("書式");
        format.add(item("フォント…", null, this::chooseFont));
        format.add(item("本文の文字を大きく", key(KeyEvent.VK_SEMICOLON, CTRL | ALT), () -> zoom(1)));
        format.add(item("本文の文字を小さく", key(KeyEvent.VK_MINUS, CTRL | ALT), () -> zoom(-1)));
        format.addSeparator();
        wrapItem.addActionListener(e -> {
            if (currentPane() instanceof TextEditorPane text) {
                forEachView(text, view -> ((TextEditorPane) view).setWrap(wrapItem.isSelected()));
            } else {
                wrapItem.setSelected(false);
                flash("折り返しは、テキスト（横書き）の文書で使えます");
            }
        });
        format.add(wrapItem);
        if (Edition.has(Edition.VERTICAL)) {
            addVerticalFormatItems(format);
        }

        JMenu view = new JMenu("表示");
        view.add(item("画面を分割 / 分割をやめる", key(KeyEvent.VK_BACK_SLASH, CTRL), this::toggleSplit));
        view.add(item("分割の向きを切り替え（左右 ⇔ 上下）", null, this::toggleOrientation));
        view.add(item("タブを反対側へ移動", key(KeyEvent.VK_M, CTRL | SHIFT), this::moveTabToOtherGroup));
        view.addSeparator();
        // ブラウザと同じ、画面全体（メニュー・ボタン・本文）の拡大・縮小
        view.add(item("拡大", key(KeyEvent.VK_SEMICOLON, CTRL), () -> setUiZoom(Theme.nextZoom(1))));
        view.add(item("縮小", key(KeyEvent.VK_MINUS, CTRL), () -> setUiZoom(Theme.nextZoom(-1))));
        view.add(item("100% に戻す", key(KeyEvent.VK_0, CTRL), () -> setUiZoom(100)));
        view.addSeparator();
        view.add(item("次のタブ", key(KeyEvent.VK_PAGE_DOWN, CTRL), () -> activeGroup.selectNext(1)));
        view.add(item("前のタブ", key(KeyEvent.VK_PAGE_UP, CTRL), () -> activeGroup.selectNext(-1)));
        view.addSeparator();
        // 配色は、選ぶとすぐに変わる
        JMenu schemeMenu = new JMenu("配色");
        javax.swing.ButtonGroup schemeGroup = new javax.swing.ButtonGroup();
        for (Theme.Scheme scheme : Theme.Scheme.values()) {
            javax.swing.JRadioButtonMenuItem schemeItem = new javax.swing.JRadioButtonMenuItem(scheme.label, scheme == Theme.scheme());
            schemeItem.addActionListener(e -> {
                appSettings.put(AppSettings.THEME, scheme.id);
                appSettings.save();
                applyScheme(scheme);
            });
            schemeGroup.add(schemeItem);
            schemeItems.put(scheme, schemeItem);
            schemeMenu.add(schemeItem);
        }
        view.add(schemeMenu);
        // ステータスバーの字数の数え方（選ぶとすぐに変わる）
        JMenu countMenu = new JMenu("文字数の数え方");
        javax.swing.ButtonGroup countGroup = new javax.swing.ButtonGroup();
        WritingStats.CountMode currentCount = WritingStats.CountMode.fromId(appSettings.getString(AppSettings.COUNT_MODE, "all"));
        for (WritingStats.CountMode mode : WritingStats.CountMode.values()) {
            javax.swing.JRadioButtonMenuItem countItem = new javax.swing.JRadioButtonMenuItem(mode.label, mode == currentCount);
            countItem.addActionListener(e -> setCountMode(mode));
            countGroup.add(countItem);
            countMenu.add(countItem);
        }
        view.add(countMenu);
        JCheckBoxMenuItem stripeItem = new JCheckBoxMenuItem("行の縞模様", Theme.stripes);
        stripeItem.addActionListener(e -> {
            Theme.stripes = stripeItem.isSelected();
            repaint();
        });
        view.add(stripeItem);
        // 入力補助（段落頭の全角スペース・かっこの自動閉じ）。前回の設定を読んで、メニューと打つ処理に反映する。既定は切
        InputAssist.indentOnBreak = appSettings.getBool(AppSettings.INPUT_INDENT, false);
        InputAssist.pairs = appSettings.getBool(AppSettings.INPUT_PAIRS, false);
        JMenu assistMenu = new JMenu("入力補助");
        JCheckBoxMenuItem indentItem = new JCheckBoxMenuItem("段落頭に全角スペース", InputAssist.indentOnBreak);
        indentItem.addActionListener(e -> {
            InputAssist.indentOnBreak = indentItem.isSelected();
            appSettings.put(AppSettings.INPUT_INDENT, InputAssist.indentOnBreak);
            appSettings.save();
        });
        assistMenu.add(indentItem);
        JCheckBoxMenuItem pairsItem = new JCheckBoxMenuItem("かっこの自動閉じ", InputAssist.pairs);
        pairsItem.addActionListener(e -> {
            InputAssist.pairs = pairsItem.isSelected();
            appSettings.put(AppSettings.INPUT_PAIRS, InputAssist.pairs);
            appSettings.save();
        });
        assistMenu.add(pairsItem);
        view.add(assistMenu);
        view.addSeparator();
        view.add(item("フォルダのサイドバー", key(KeyEvent.VK_E, CTRL | SHIFT), this::toggleSidebar));
        view.add(item("見出し一覧", key(KeyEvent.VK_L, CTRL | ALT), this::toggleOutline));
        view.add(item("作品の集計", null, this::toggleWork));
        if (Edition.has(Edition.VERTICAL)) {
            view.add(item("校正の欄", key(KeyEvent.VK_F7, 0), () -> showRight("proof")));
        }
        if (Edition.has(Edition.AI)) {
            view.add(item("AI の会話欄", key(KeyEvent.VK_A, CTRL | SHIFT), () -> showRight("ai")));
        }
        view.add(item("集中モード", key(KeyEvent.VK_F11, 0), this::toggleFocusMode));
        if (Edition.has(Edition.VERTICAL)) {
            view.add(item("執筆の記録…", null, this::showStats));
        }
        if (Edition.has(Edition.AI)) {
            view.addSeparator();
            terminalItem.setAccelerator(key(KeyEvent.VK_J, CTRL));
            terminalItem.addActionListener(e -> setTerminalVisible(terminalItem.isSelected()));
            view.add(terminalItem);
        }

        // カードボード専用の操作。ほかの種類の文書を開いているときは、案内を出すだけにする
        JMenu boardMenu = new JMenu("ボード");
        boardMenu.add(item("カードを一括追加…", key(KeyEvent.VK_B, CTRL | SHIFT), this::bulkAddCards));
        boardMenu.add(item("複製", key(KeyEvent.VK_D, CTRL), () -> withBoard(BoardPane::duplicateSelected)));
        boardMenu.add(item("削除", null, () -> withBoard(BoardPane::deleteSelected)));
        boardMenu.addSeparator();
        boardMenu.add(item("選んだカードをグループ枠で囲む", key(KeyEvent.VK_G, CTRL), () -> withBoard(BoardPane::groupSelected)));
        boardMenu.add(item("整列", key(KeyEvent.VK_L, CTRL | SHIFT), () -> withBoard(BoardPane::tidy)));
        boardMenu.add(item("色ごとに並べる", null, () -> withBoard(BoardPane::arrangeByColor)));
        boardMenu.addSeparator();
        boardMenu.add(item("投票する（+1）", key(KeyEvent.VK_UP, CTRL | SHIFT), () -> withBoard(board -> board.vote(1))));
        boardMenu.add(item("投票を取り消す（−1）", key(KeyEvent.VK_DOWN, CTRL | SHIFT), () -> withBoard(board -> board.vote(-1))));
        boardMenu.addSeparator();
        boardMenu.add(item("拡大", null, () -> withBoard(board -> board.zoomBy(1.25))));
        boardMenu.add(item("縮小", null, () -> withBoard(board -> board.zoomBy(0.8))));
        boardMenu.add(item("全体を表示", key(KeyEvent.VK_0, CTRL | ALT), () -> withBoard(BoardPane::fitAll)));
        boardMenu.addSeparator();
        // Ctrl+Shift+E はサイドバーに使っているので、画像の書き出しは Ctrl+Alt+E にする
        boardMenu.add(item("画像として書き出す（PNG）…", key(KeyEvent.VK_E, CTRL | InputEvent.ALT_DOWN_MASK), this::exportBoardImage));

        // AI（Claude など）に、校正・検索・ファイルの整理を頼めるようにする
        JMenu aiMenu = new JMenu("AI 連携");
        mcpItem.addActionListener(e -> toggleMcp());
        aiMenu.add(mcpItem);
        aiMenu.add(item("AI に任せるフォルダを選ぶ…", null, this::chooseWorkspace));
        aiMenu.add(item("接続のしかた…", null, this::showMcpHelp));
        aiMenu.addSeparator();
        aiMenu.add(item("AI の会話欄を開く", null, () -> showRight("ai")));
        aiMenu.add(item("会話欄で使う AI の設定…", null, this::openSettings));

        menuBar.add(file);
        menuBar.add(edit);
        menuBar.add(format);
        menuBar.add(view);
        if (Edition.has(Edition.BOARD)) {
            menuBar.add(boardMenu);
        }
        if (Edition.has(Edition.AI)) {
            menuBar.add(aiMenu);
        }

        // 困ったときに引ける説明（ふつうのエディタと同じ操作が多いので、チュートリアルは出さない）
        JMenu help = new JMenu("ヘルプ");
        paletteItem = item("すべての操作を探す…", key(KeyEvent.VK_P, CTRL), this::showPalette);
        help.add(paletteItem);
        help.addSeparator();
        help.add(item("使い方", key(KeyEvent.VK_F1, 0), () -> HelpDialog.showGuide(this)));
        help.add(item("キーボードショートカット", null, () -> HelpDialog.showShortcuts(this, getJMenuBar())));
        help.addSeparator();
        help.add(item("DATUM-Editor について", null, () -> HelpDialog.showAbout(this)));
        menuBar.add(help);

        // 右端（ウィンドウの最小化・最大化・×のすぐ下）に、1回押すだけで使えるボタンを並べる
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();      // 表示の倍率を変えたときも、ボタンの大きさに合わせて広がるように
            }
        };
        tools.setOpaque(false);
        for (JToggleButton button : new JToggleButton[]{paletteButton, folderButton, splitSideButton, splitDownButton, proofButton, aiButton, terminalButton, focusButton}) {
            if ((button == proofButton && !Edition.has(Edition.VERTICAL))
                    || ((button == aiButton || button == terminalButton) && !Edition.has(Edition.AI))) {
                continue;           // 種類に入っていない機能のボタンは出さない
            }
            tools.add(button);
        }
        menuBar.add(Box.createHorizontalGlue());
        menuBar.add(tools);
        return menuBar;
    }

    /** 書式メニューの、縦書き（Writer から）の項目。 */
    private void addVerticalFormatItems(JMenu format) {
        format.add(item("横書き ⇔ 縦書き を切り替え", key(KeyEvent.VK_T, CTRL | SHIFT), this::switchWriting));
        manuscriptItem.setAccelerator(key(KeyEvent.VK_G, CTRL | SHIFT));
        manuscriptItem.addActionListener(e -> {
            if (currentPane() instanceof VerticalTextPane vertical) {
                forEachView(vertical, view -> ((VerticalTextPane) view).setManuscript(manuscriptItem.isSelected()));
            } else {
                manuscriptItem.setSelected(true);
                flash("原稿用紙の表示は、縦書きの文書で使えます");
            }
        });
        format.add(manuscriptItem);
        format.addSeparator();
        format.add(item("ルビ（ふりがな）を振る…", key(KeyEvent.VK_R, CTRL), this::editRuby));
        format.add(item("傍点を付ける", key(KeyEvent.VK_R, CTRL | SHIFT), this::addEmphasis));
        format.add(item("ルビ・傍点を外す", null, this::removeRuby));
    }

    /** メニューバーの右端に置く、1回押すだけで欄を出し入れするボタン。出ている間は青くなる。 */
    private JToggleButton createToolButton(String label, String tooltip, Runnable action) {
        JToggleButton button = new JToggleButton(label) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean hot = getModel().isRollover() || getModel().isPressed();
                g2.setColor(isSelected() ? Theme.ACCENT : hot ? Theme.HOVER : Theme.INPUT);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setRolloverEnabled(true);
        button.setForeground(Theme.FG_STRONG);
        // 青い地のときは白い文字にする（ライトの配色でも読めるように）
        button.addItemListener(e -> button.setForeground(button.isSelected() ? Color.WHITE : Theme.FG_STRONG));
        button.setFont(label.equals(">_") ? new Font(Font.MONOSPACED, Font.BOLD, Theme.SMALL_SIZE) : Theme.small());
        button.setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
        button.setToolTipText(tooltip);
        button.addActionListener(e -> {
            button.setSelected(!button.isSelected());   // 表示の切り替えは action 側で行い、その結果に合わせる
            action.run();
        });
        return button;
    }

    /** アイコンで表すボタン。name は、読み上げ（アクセシビリティ）用の名前。 */
    private JToggleButton createIconButton(javax.swing.Icon icon, String name, String tooltip, Runnable action) {
        JToggleButton button = createToolButton("", name + "：" + tooltip, action);
        button.setIcon(icon);
        button.getAccessibleContext().setAccessibleName(name);
        button.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        return button;
    }

    // ---------- 左右の欄（サイドバー・校正・AI） ----------

    /** 真ん中（検索バー・エディタ・ターミナル）の左右に、出すことになっている欄を並べ直す。 */
    private void layoutMain() {
        mainArea.removeAll();
        JComponent content = center;
        String right = focusMode ? null : rightPanel;
        if (right != null) {
            JComponent panel = right.equals("ai") ? chatPanel : proofPanel;
            JSplitPane splitPane = sideSplit(content, panel, 1.0);
            // 幅が決まってから、覚えている幅に合わせる。それより前の自動の配置では、覚えている幅を書き換えない
            boolean[] placed = {false};
            SwingUtilities.invokeLater(() -> {
                if (splitPane.getWidth() > 0) {
                    splitPane.setDividerLocation(Math.max(200, splitPane.getWidth() - rightWidth));
                }
                placed[0] = true;
            });
            splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                if (placed[0] && splitPane.getWidth() > 0 && splitPane.isShowing()) {
                    rightWidth = Math.max(260, splitPane.getWidth() - splitPane.getDividerLocation() - splitPane.getDividerSize());
                }
            });
            content = splitPane;
        }
        if (sidebarVisible && !focusMode) {
            JSplitPane splitPane = sideSplit(sidebar, content, 0.0);
            splitPane.setDividerLocation(sidebarWidth);
            splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                if (splitPane.isShowing()) {
                    sidebarWidth = Math.max(160, splitPane.getDividerLocation());
                }
            });
            content = splitPane;
        }
        mainArea.add(content, BorderLayout.CENTER);
        mainArea.revalidate();
        mainArea.repaint();
    }

    private static JSplitPane sideSplit(JComponent left, JComponent right, double weight) {
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        splitPane.setUI(new BasicSplitPaneUI());
        splitPane.setBorder(null);
        splitPane.setBackground(Theme.BORDER);
        splitPane.setDividerSize(4);
        splitPane.setResizeWeight(weight);      // ウィンドウの大きさを変えたときは、真ん中のエディタが伸び縮みする
        splitPane.setContinuousLayout(true);
        left.setMinimumSize(new Dimension(120, 0));
        right.setMinimumSize(new Dimension(120, 0));
        return splitPane;
    }

    private FolderSidebar sidebar() {
        if (sidebar == null) {
            sidebar = new FolderSidebar(new FolderSidebar.Host() {
                @Override
                public void openFromSidebar(Path file) {
                    openSidebarFile(file);
                }

                @Override
                public void fileMoved(Path from, Path to) {
                    for (EditorPane pane : allPanes()) {
                        Path open = pane.getPath();
                        if (open != null && open.toAbsolutePath().normalize().startsWith(from.toAbsolutePath().normalize())) {
                            pane.setPath(to.resolve(from.toAbsolutePath().normalize().relativize(open.toAbsolutePath().normalize())));
                        }
                    }
                    updateStatus();
                }

                @Override
                public void fileTrashed(Path file) {
                    for (EditorPane pane : allPanes()) {
                        Path open = pane.getPath();
                        if (open != null && open.toAbsolutePath().normalize().startsWith(file.toAbsolutePath().normalize())) {
                            // ファイルはなくなったが、タブの中身は残っているので、未保存の文書として扱う
                            pane.setPath(null);
                            pane.setDirty(true);
                        }
                    }
                    updateStatus();
                }

                @Override
                public void openCombined(String title, String text) {
                    TextEditorPane pane = new TextEditorPane(Theme.editorFont());
                    pane.loadText(text);
                    EditorPane previous = currentPane();
                    addPane(activeGroup, pane);
                    pane.setDirty(true);
                    removeIfPristine(previous);
                    flash(title + "を、新しいタブに開きました");
                }

                @Override
                public Path chooseFolder() {
                    JFileChooser chooser = newFileChooser();
                    chooser.setDialogTitle("サイドバーに表示するフォルダ");
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    chooser.setApproveButtonText("このフォルダを表示");
                    if (chooser.showOpenDialog(EditorFrame.this) != JFileChooser.APPROVE_OPTION) {
                        return null;
                    }
                    return chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
                }

                @Override
                public void folderChanged(Path folder) {
                    appSettings.put(AppSettings.SIDEBAR_FOLDER, folder.toString());
                    appSettings.save();
                }

                @Override
                public void message(String text) {
                    flash(text);
                }
            }, this::toggleSidebar);
            String saved = appSettings.getString(AppSettings.SIDEBAR_FOLDER, null);
            if (saved != null && Files.isDirectory(Path.of(saved))) {
                sidebar.setRoot(Path.of(saved));
            }
        }
        return sidebar;
    }

    private void setSidebarVisible(boolean visible) {
        if (visible) {
            sidebar();
        }
        sidebarVisible = visible;
        folderButton.setSelected(visible);
        layoutMain();
    }

    private void toggleSidebar() {
        setSidebarVisible(!sidebarVisible);
    }

    private void openFolderInSidebar() {
        setSidebarVisible(true);
        Path folder = null;
        JFileChooser chooser = newFileChooser();
        chooser.setDialogTitle("サイドバーに表示するフォルダ");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setApproveButtonText("このフォルダを表示");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            folder = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        }
        if (folder != null) {
            sidebar.setRoot(folder);
            appSettings.put(AppSettings.SIDEBAR_FOLDER, folder.toString());
            appSettings.save();
        }
    }

    /** 右の欄を切り替える。今出ている欄をもう一度選んだときは、隠す。 */
    private void showRight(String which) {
        if (("proof".equals(which) && !Edition.has(Edition.VERTICAL)) || ("ai".equals(which) && !Edition.has(Edition.AI))) {
            return;
        }
        String next = which != null && which.equals(rightPanel) ? null : which;
        if ("proof".equals(next) && proofPanel == null) {
            proofPanel = new ProofreadPanel(() -> currentPane() instanceof Searchable s ? s : null,
                    () -> withPane(EditorPane::focusEditor), appSettings, () -> showRight(null));
        }
        if ("ai".equals(next) && chatPanel == null) {
            chatPanel = new AiChatPanel(new AiChatPanel.Host() {
                @Override
                public AppSettings settings() {
                    return appSettings;
                }

                @Override
                public boolean ensureMcp() {
                    return ensureMcpRunning();
                }

                @Override
                public Path workspace() {
                    return mcp.settings().workspace;
                }

                @Override
                public McpServer.Backend tools() {
                    if (chatTools == null) {
                        chatTools = new McpTools(EditorFrame.this, mcp.settings());
                    }
                    return chatTools;
                }

                @Override
                public void openSettings() {
                    EditorFrame.this.openSettings();
                }
            }, () -> showRight(null));
        }
        if (focusMode && next != null) {
            toggleFocusMode();          // 集中モード中に欄を開くときは、集中モードを終える
        }
        rightPanel = next;
        proofButton.setSelected("proof".equals(next));
        aiButton.setSelected("ai".equals(next));
        layoutMain();
        if ("proof".equals(next)) {
            proofPanel.refresh();
        } else if ("ai".equals(next)) {
            SwingUtilities.invokeLater(chatPanel::focusInput);
        } else {
            withPane(pane -> SwingUtilities.invokeLater(pane::focusEditor));
        }
    }

    /** AI 連携（MCP）が動いていなければ、動かす（初めてなら、任せるフォルダを選んでもらう）。動けば true。 */
    private boolean ensureMcpRunning() {
        if (mcp.isRunning()) {
            return true;
        }
        if (!mcp.hasWorkspace() && !chooseWorkspace()) {
            return false;
        }
        try {
            mcp.start();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "AI 連携を始められませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        mcpItem.setSelected(true);
        updateStatus();
        flash("AI 連携（MCP）を始めました");
        return true;
    }

    // ---------- 集中モード ----------

    /**
     * 集中モード：メニュー・タブ・ステータスバー・左右の欄を隠し、ウィンドウを最大にして、文章だけにする。
     * テキストの文書では、文字の列を中央に寄せ、カーソルの行をいつも画面の真ん中に保つ。F11（または Esc）で戻る。
     */
    private void toggleFocusMode() {
        focusMode = !focusMode;
        InputMap windowKeys = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        if (focusMode) {
            windowKeys.put(key(KeyEvent.VK_ESCAPE, 0), "leaveFocus");
        } else {
            windowKeys.remove(key(KeyEvent.VK_ESCAPE, 0));
        }
        focusButton.setSelected(focusMode);
        JMenuBar menuBar = getJMenuBar();
        if (focusMode) {
            stateBeforeFocus = getExtendedState();
            terminalBeforeFocus = terminalVisible;
            folderSearchBeforeFocus = folderSearchVisible;
            if (folderSearchVisible) {
                folderSearch.cancelSearch();
                folderSearchVisible = false;
            }
            outlineBeforeFocus = outlineVisible;
            outlineVisible = false;
            workBeforeFocus = workVisible;
            if (workVisible) {
                work.cancel();
                workVisible = false;
            }
            if (terminalVisible) {
                setTerminalVisible(false);
            }
            layoutCenter();
            // メニューは隠すと Ctrl+S などのショートカットも効かなくなるので、高さを 0 にして見えなくする
            menuBar.setPreferredSize(new Dimension(0, 0));
            statusBar.setVisible(false);
            group1.setTabsVisible(false);
            group2.setTabsVisible(false);
            setExtendedState(MAXIMIZED_BOTH);
            if (currentPane() instanceof TextEditorPane text) {
                text.setFocusMode(true);
            }
            showHint("集中モード　F11 か Esc で元に戻ります");
        } else {
            menuBar.setPreferredSize(null);
            statusBar.setVisible(true);
            group1.setTabsVisible(true);
            group2.setTabsVisible(true);
            setExtendedState(stateBeforeFocus);
            for (EditorPane pane : allPanes()) {
                if (pane instanceof TextEditorPane text) {
                    text.setFocusMode(false);
                }
            }
            folderSearchVisible = folderSearchBeforeFocus;
            outlineVisible = outlineBeforeFocus;
            if (outlineVisible) {
                outline.refresh();              // 集中モードの間に文書が変わっているかもしれないので、探し直す
            }
            workVisible = workBeforeFocus;
            if (workVisible) {
                work.refresh();                 // 集中モードの間に文書が変わっているかもしれないので、数え直す
            }
            if (terminalBeforeFocus) {
                setTerminalVisible(true);       // 中で、検索の欄の分と一緒に並べ直す
            } else {
                layoutCenter();
            }
        }
        menuBar.revalidate();
        layoutMain();
        withPane(pane -> SwingUtilities.invokeLater(pane::focusEditor));
    }

    public boolean isFocusMode() {
        return focusMode;
    }

    /** 画面の上のほうに、数秒だけ案内を出す（ステータスバーが隠れているときに使う）。 */
    private void showHint(String text) {
        JLabel hint = new JLabel(text, JLabel.CENTER);
        hint.setOpaque(true);
        hint.setBackground(Theme.ACCENT);
        hint.setForeground(Color.WHITE);
        hint.setFont(Theme.small());
        hint.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        Dimension size = hint.getPreferredSize();
        JLayeredPane layers = getLayeredPane();
        layers.add(hint, JLayeredPane.POPUP_LAYER);
        // ウィンドウを最大にした直後は大きさが変わっていくので、少しのあいだ、真ん中に置き直す
        Timer place = new Timer(100, e -> hint.setBounds((layers.getWidth() - size.width) / 2, 24, size.width, size.height));
        place.start();
        Timer timer = new Timer(3000, e -> {
            place.stop();
            layers.remove(hint);
            layers.repaint();
        });
        timer.setRepeats(false);
        timer.start();
    }

    // ---------- 版の履歴・執筆の記録・設定 ----------

    private void showHistory() {
        EditorPane pane = currentPane();
        if (pane == null || pane.getPath() == null) {
            flash("版の履歴は、保存したことのある文書で使えます");
            return;
        }
        Path path = pane.getPath();
        List<VersionHistory.Version> versions = history.list(path);
        if (versions.isEmpty()) {
            flash("まだ履歴がありません（保存するたびに、版が残ります）");
            return;
        }
        String current = fileContent(pane, path);
        new HistoryDialog(this, pane.getTitle(), versions, history, current,
                content -> restoreVersion(pane, path, current, content),
                content -> {
                    EditorPane copy;
                    try {
                        copy = paneFromFile(path, content, pane instanceof VerticalTextPane);
                    } catch (IOException ex) {
                        flash(ex.getMessage());
                        return;
                    }
                    addPane(activeGroup, copy);
                    copy.setDirty(true);
                    flash("過去の版を、新しいタブ（無題）に開きました");
                }).setVisible(true);
    }

    /** 過去の版に戻す。今の内容は先に履歴へ残すので、戻したあとでも、元に戻れる。 */
    private void restoreVersion(EditorPane pane, Path path, String current, String content) {
        EditorPane fresh;
        try {
            fresh = paneFromFile(path, content, pane instanceof VerticalTextPane);
        } catch (IOException ex) {
            flash(ex.getMessage());
            return;
        }
        history.record(path, current, appSettings.getInt(AppSettings.HISTORY_LIMIT, 50) + 1);
        fresh.setListener(this);
        fresh.setRawFile(pane.isRawFile());
        fresh.setPath(path);
        fresh.setDirty(true);           // 戻しただけでは、ファイルはまだ書き換えていない
        stats.track(fresh);
        closeOtherViews(pane);
        EditorGroup group = groupOf(pane);
        group.replacePane(pane, fresh);
        setActiveGroup(group);
        tidyGroups();
        SwingUtilities.invokeLater(fresh::focusEditor);
        flash("過去の版に戻しました（保存すると、ファイルに書き込みます）");
    }

    private void showStats() {
        appSettings.save();
        stats.showDialog(this);
        updateStatus();
    }

    private void openSettings() {
        String themeBefore = appSettings.getString(AppSettings.THEME, "gray");
        if (new SettingsDialog(this, appSettings).open()) {
            if (chatPanel != null) {
                chatPanel.refreshBackendLabel();
            }
            updateStatus();
            if (!themeBefore.equals(appSettings.getString(AppSettings.THEME, "gray"))) {
                applyScheme(Theme.Scheme.fromId(appSettings.getString(AppSettings.THEME, "gray")));
            }
            flash("設定を保存しました");
            registerCommandIfNeeded();  // 「datum」コマンドを使う設定に変えたときは、すぐに登録する
        }
    }

    /** コマンドパレット（すべての操作を名前で探す）を開く。メニューの項目は自動で集める。 */
    private void showPalette() {
        paletteButton.setSelected(false);   // 押したままの見た目にしない（欄の出し入れのボタンではないため）
        List<CommandPalette.Command> extras = new ArrayList<>();
        // メニューにない操作（タブの右クリックなどでしかできないもの）
        extras.add(new CommandPalette.Command("extra/ほかのタブを閉じる", "ほかのタブを閉じる", "タブ", "", () -> {
            EditorPane keep = currentPane();
            for (EditorPane other : new ArrayList<>(activeGroup.getPanes())) {
                if (other != keep && activeGroup.getPanes().contains(other)) {
                    closePane(activeGroup, other);
                }
            }
        }));
        if (Edition.has(Edition.VERTICAL)) {
            extras.add(new CommandPalette.Command("extra/目標字数", "1日の目標字数を決める…", "執筆", "", () -> goalMeter.openEditor()));
        }
        CommandPalette.show(this, appSettings, CommandPalette.collect(getJMenuBar(), extras, paletteItem));
    }

    /** 配色や倍率を変えるときにたどる部品。画面に出ていない部品（裏のタブ・閉じている欄）も入れる。 */
    private List<Component> themeRoots() {
        List<Component> roots = new ArrayList<>(List.of(getRootPane(), group1, group2, findBar));
        roots.addAll(allPanes());
        for (Component c : new Component[]{sidebar, proofPanel, chatPanel, terminal, statusBar, goalMeter}) {
            if (c != null) {
                roots.add(c);
            }
        }
        return roots;
    }

    /** ブラウザのように、画面全体の倍率を変える（次に起動したときも同じ倍率）。 */
    private void setUiZoom(int percent) {
        int before = Theme.zoom();
        // 文書の文字は、各文書で選んだ大きさに、同じ割合を掛ける（Theme.zoomTo が部品の文字を変える前に覚えておく）
        Map<EditorPane, Font> fonts = new java.util.IdentityHashMap<>();
        for (EditorPane pane : allPanes()) {
            fonts.put(pane, pane.getEditorFont());
        }
        Theme.zoomTo(percent, themeRoots());
        if (Theme.zoom() != before) {
            fonts.forEach((pane, font) -> {
                // 等倍のときの大きさを覚えておき、そこから計算する（50% と 200% を行き来しても、ずれが積もらないように）
                Object[] memo = zoomMemo.get(pane);
                float base = memo != null && memo[0] == font ? (Float) memo[1] : Theme.baseSize(font.getSize2D(), before);
                Font next = font.deriveFont((float) Math.max(6, Math.round(base * Theme.zoom() / 100f)));
                pane.setEditorFont(next);
                zoomMemo.put(pane, new Object[]{next, base});
            });
            for (EditorGroup group : new EditorGroup[]{group1, group2}) {
                group.refreshTabs();
            }
            appSettings.put(AppSettings.ZOOM, Theme.zoom());
            appSettings.save();
        }
        zoomLabel.setText(Theme.zoom() + "%");
        flash("表示の倍率: " + Theme.zoom() + "%");
    }

    /** 起動したまま配色を変える。画面に出ていない部品（裏のタブ・閉じている欄）も一緒に塗り直す。 */
    private void applyScheme(Theme.Scheme scheme) {
        Theme.switchTo(scheme, themeRoots());
        JToggleButton[] buttons = {paletteButton, folderButton, splitSideButton, splitDownButton, proofButton, aiButton, terminalButton, focusButton};
        for (JToggleButton button : buttons) {
            button.setForeground(button.isSelected() ? Color.WHITE : Theme.FG_STRONG);
        }
        for (EditorGroup group : new EditorGroup[]{group1, group2}) {
            group.refreshTabs();
        }
        if (schemeItems.get(scheme) != null) {
            schemeItems.get(scheme).setSelected(true);
        }
        findBar.paneSwitched();             // 検索の色を、新しい配色で付け直す
        if (proofPanel != null) {
            proofPanel.refresh();           // 校正の印も付け直す
        }
        flash("配色を「" + scheme.label + "」にしました");
    }

    // ---------- 自動保存・異常終了からの復旧 ----------

    /** 30 秒ごとに呼ばれる。自動保存（設定で選んだときだけ）と、未保存の文書の控えを取る。 */
    private void autosaveTick() {
        if (appSettings.getBool(AppSettings.AUTO_SAVE, false)) {
            for (EditorPane pane : documentPanes()) {
                if (pane.isDirty() && pane.getPath() != null && Files.isRegularFile(pane.getPath())) {
                    write(pane, pane.getPath(), false);
                }
            }
        }
        recovery.save(documentPanes());
        appSettings.save();             // 今日の執筆量なども、ときどき書いておく
    }

    /** 前回、保存されずに終わった文書があれば、復元するかを聞く。起動して画面を出したあとに呼ぶ。 */
    public void offerRecovery() {
        List<Recovery.Entry> entries = recovery.leftovers();
        if (entries.isEmpty()) {
            return;
        }
        StringBuilder list = new StringBuilder();
        for (Recovery.Entry entry : entries) {
            list.append("・").append(entry.title()).append("（").append(entry.savedAt()).append(" の控え）\n");
        }
        Object[] options = {"復元する", "捨てる", "あとで決める"};
        int answer = JOptionPane.showOptionDialog(this,
                "前回、保存されずに終わった文書が " + entries.size() + " 件あります。復元しますか？\n\n" + list,
                "文書の復元", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (answer == 0) {
            List<Recovery.Entry> restored = new ArrayList<>();
            for (Recovery.Entry entry : entries) {
                if (restoreEntry(entry)) {
                    restored.add(entry);
                }
            }
            recovery.save(documentPanes());     // 復元した文書の控えを、すぐに取り直してから
            recovery.discard(restored);         // 前回の控えを消す（復元できなかったものは残す）
            if (restored.size() == entries.size()) {
                flash(restored.size() + " 件の文書を復元しました（まだ保存されていません）");
            } else {
                flash(restored.size() + " 件の文書を復元しました。復元できなかった " + (entries.size() - restored.size())
                        + " 件の控えは、消さずに残してあります");
            }
        } else if (answer == 1) {
            int sure = JOptionPane.showConfirmDialog(this, "控えを捨てると、もう復元できません。捨てますか？",
                    "確認", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (sure == JOptionPane.YES_OPTION) {
                recovery.discardLeftovers();
            }
        }
        // 「あとで決める」は、控えを残したまま。次に起動したときに、もう一度聞く
    }

    /** 控えを1つ、タブに戻す。戻せたら true。 */
    private boolean restoreEntry(Recovery.Entry entry) {
        EditorPane pane;
        try {
            pane = paneFromJson(entry.document());
        } catch (IOException | RuntimeException ex) {
            return false;
        }
        Path path = entry.path() == null ? null : Path.of(entry.path());
        pane.setRawFile(entry.raw());
        EditorPane open = path == null ? null : findOpenDocument(path);
        if (open != null) {
            // 同じファイルのタブがあれば（前回の状態から開いたもの）、控えの内容に入れ替える
            pane.setListener(this);
            pane.setPath(path);
            pane.setDirty(true);
            stats.track(pane);
            closeOtherViews(open);
            groupOf(open).replacePane(open, pane);
        } else {
            EditorPane previous = currentPane();
            if (path != null) {
                pane.setPath(path);
            }
            addPane(activeGroup, pane);
            pane.setDirty(true);
            removeIfPristine(previous);
        }
        currentPaneChanged();
        return true;
    }

    // ---------- 前回の状態（タブ・分割・ウィンドウ） ----------

    /** 開いているタブ・カーソルの位置・分割・ウィンドウの位置と大きさ・サイドバーを覚えておく。 */
    private void saveSession() {
        Map<String, Object> session = new java.util.LinkedHashMap<>();
        List<Object> groups = new ArrayList<>();
        List<Object> active = new ArrayList<>();
        for (EditorGroup group : new EditorGroup[]{group1, group2}) {
            List<Object> tabs = new ArrayList<>();
            int activeIndex = 0;
            for (EditorPane pane : group.getPanes()) {
                if (pane.getPath() == null || !Files.isRegularFile(pane.getPath())) {
                    continue;           // 保存していない文書は、開き直せないので覚えない
                }
                if (pane == group.getActive()) {
                    activeIndex = tabs.size();
                }
                int caret = pane instanceof Searchable s ? s.getSelectionStart() : 0;
                tabs.add(McpServer.map("path", pane.getPath().toAbsolutePath().toString(), "mode", pane.getMode().id,
                        "caret", caret));
            }
            groups.add(tabs);
            active.add(activeIndex);
        }
        session.put("groups", groups);
        session.put("active", active);
        session.put("activeGroup", activeGroup == group2 ? 1 : 0);
        session.put("split", split);
        session.put("vertical", splitOrientation == JSplitPane.VERTICAL_SPLIT);
        boolean maximized = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        int state = focusMode ? stateBeforeFocus : getExtendedState();
        session.put("window", McpServer.map("x", getX(), "y", getY(), "width", getWidth(), "height", getHeight(),
                "maximized", focusMode ? (state & MAXIMIZED_BOTH) == MAXIMIZED_BOTH : maximized));
        session.put("sidebar", sidebarVisible);
        session.put("sidebarWidth", sidebarWidth);
        session.put("right", rightPanel);
        session.put("rightWidth", rightWidth);
        appSettings.put(AppSettings.SESSION, session);
    }

    /** 前回の状態に戻す。起動したとき、画面を出す前に呼ぶ。うまくいかない所は飛ばす（起動を止めない）。 */
    @SuppressWarnings("unchecked")
    public void restoreSession() {
        Map<String, Object> session = appSettings.getMap(AppSettings.SESSION);
        if (session == null) {
            return;
        }
        try {
            if (session.get("window") instanceof Map<?, ?> w) {
                Map<String, Object> window = (Map<String, Object>) w;
                java.awt.Rectangle bounds = new java.awt.Rectangle(Json.num(window, "x", 0), Json.num(window, "y", 0),
                        Json.num(window, "width", 1400), Json.num(window, "height", 850));
                // 前回と画面の構成が違っても、ウィンドウが画面の外に出てしまわないように確かめる
                boolean onScreen = false;
                for (java.awt.GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                    onScreen |= device.getDefaultConfiguration().getBounds().intersects(bounds);
                }
                if (onScreen && bounds.width >= 400 && bounds.height >= 300) {
                    setBounds(bounds);
                }
                if (Json.bool(window, "maximized", false)) {
                    setExtendedState(MAXIMIZED_BOTH);
                }
            }
            sidebarWidth = Json.num(session, "sidebarWidth", sidebarWidth);
            rightWidth = Json.num(session, "rightWidth", rightWidth);
            if (Json.bool(session, "vertical", false)) {
                splitOrientation = JSplitPane.VERTICAL_SPLIT;
            }
            List<?> groups = session.get("groups") instanceof List<?> g ? g : List.of();
            List<?> active = session.get("active") instanceof List<?> a ? a : List.of();
            EditorGroup[] targets = {group1, group2};
            for (int index = 0; index < Math.min(2, groups.size()); index++) {
                if (!(groups.get(index) instanceof List<?> tabs) || tabs.isEmpty()) {
                    continue;
                }
                if (index == 1 && !split) {
                    split = true;
                    layoutGroups();
                }
                setActiveGroup(targets[index]);
                List<EditorPane> opened = new ArrayList<>();
                for (Object item : tabs) {
                    if (!(item instanceof Map<?, ?> raw)) {
                        continue;
                    }
                    Map<String, Object> tab = (Map<String, Object>) raw;
                    Path path = Path.of(Json.str(tab, "path", ""));
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    try {
                        boolean vertical = EditorPane.Mode.VERTICAL.id.equals(Json.str(tab, "mode", ""))
                                && Edition.has(Edition.VERTICAL);
                        EditorPane open = findOpenDocument(path);
                        EditorPane pane;
                        if (open != null && groupOf(open) != targets[index]) {
                            // 分割して同じ文書を左右に出していたときは、同じ文書を映すタブとして開き直す
                            pane = open.newView();
                            pane.setListener(this);
                            stats.track(pane);
                            targets[index].addPane(pane);
                        } else {
                            pane = openPath(path, vertical, false);
                        }
                        if (pane instanceof Searchable s) {
                            int caret = Math.min(Json.num(tab, "caret", 0), s.getText().length());
                            SwingUtilities.invokeLater(() -> s.reveal(caret));
                        }
                        opened.add(pane);
                    } catch (IOException | RuntimeException ex) {
                        // 開けなかったファイルは飛ばす
                    }
                }
                int activeIndex = index < active.size() && active.get(index) instanceof Number n ? n.intValue() : 0;
                if (!opened.isEmpty()) {
                    targets[index].select(opened.get(Math.max(0, Math.min(activeIndex, opened.size() - 1))));
                }
            }
            if (split && group2.isEmpty()) {
                split = false;
                layoutGroups();
            }
            setActiveGroup(Json.num(session, "activeGroup", 0) == 1 && split ? group2 : group1);
            if (Json.bool(session, "sidebar", false)) {
                setSidebarVisible(true);
            }
            String right = Json.str(session, "right", null);
            if ("proof".equals(right) || "ai".equals(right)) {
                showRight(right);
            }
            currentPaneChanged();
        } catch (RuntimeException ex) {
            flash("前回の状態を、一部戻せませんでした");
        }
    }

    // ---------- 見出し一覧 ----------

    /** 見出し一覧の欄を出す、または隠す（「表示」の「見出し一覧」・Ctrl+Alt+L）。 */
    private void toggleOutline() {
        if (outlineVisible) {
            hideOutline();
        } else {
            showOutline();
        }
    }

    /** 見出し一覧の欄を出す。今の文書の見出しを探して、欄へ移る。 */
    private void showOutline() {
        if (focusMode) {
            flash("集中モードの間は出せません（F11 で戻ります）");
            return;
        }
        if (outline == null) {
            outline = new OutlinePanel(() -> currentPane() instanceof Searchable s ? s : null,
                    () -> withPane(EditorPane::focusEditor), this::hideOutline);
        }
        outlineVisible = true;
        outline.refresh();
        layoutCenter();
        outline.focusList();
    }

    /** 見出し一覧の欄を隠して、エディタへ戻す。 */
    private void hideOutline() {
        if (!outlineVisible) {
            return;
        }
        outlineVisible = false;
        layoutCenter();
        withPane(pane -> SwingUtilities.invokeLater(pane::focusEditor));
    }

    // ---------- 作品の集計 ----------

    /** 作品の集計の欄を出す、または隠す（「表示」の「作品の集計」）。 */
    private void toggleWork() {
        if (workVisible) {
            hideWork();
        } else {
            showWork();
        }
    }

    /** 作品の集計の欄を出して、フォルダの中の文章を数える。欄へ移る。 */
    private void showWork() {
        if (focusMode) {
            flash("集中モードの間は出せません（F11 で戻ります）");
            return;
        }
        if (work == null) {
            work = new WorkPanel(new WorkPanel.Host() {
                @Override
                public Path root() {
                    return sidebar().getRoot();
                }

                @Override
                public Map<Path, String> openTexts() {
                    return openFileTexts();
                }

                @Override
                public WritingStats.CountMode countMode() {
                    return WritingStats.CountMode.fromId(appSettings.getString(AppSettings.COUNT_MODE, "all"));
                }

                @Override
                public boolean rubyNotation() {
                    return currentPane() instanceof TextEditorPane;     // ステータスバーの字数と同じ判定
                }

                @Override
                public void open(Path file) {
                    openSidebarFile(file);
                }
            }, this::hideWork);
        }
        workVisible = true;
        layoutCenter();
        work.refresh();
        work.focusList();
    }

    /** 作品の集計の欄を隠して、エディタへ戻す。数えている最中なら止める。 */
    private void hideWork() {
        if (!workVisible) {
            return;
        }
        work.cancel();
        workVisible = false;
        layoutCenter();
        withPane(pane -> SwingUtilities.invokeLater(pane::focusEditor));
    }

    /** 開いている文書の中身（絶対パス → 保存形式の文字）。未保存の変更も入る。フォルダ内の検索・作品の集計で使う。 */
    private Map<Path, String> openFileTexts() {
        Map<Path, String> texts = new HashMap<>();
        for (EditorPane pane : documentPanes()) {
            Path path = pane.getPath();
            if (path != null) {
                texts.put(path.toAbsolutePath().normalize(), fileContent(pane, path));
            }
        }
        return texts;
    }

    /** サイドバー・作品の集計の一覧からファイルを開く（開いていれば、そのタブを前に出す）。 */
    private void openSidebarFile(Path file) {
        try {
            openPath(file.toAbsolutePath(), currentPane() instanceof VerticalTextPane, false);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(EditorFrame.this, "開けませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------- ターミナル ----------

    /** エディタの下にターミナルを出す、または隠す。隠しても、中で動いているコマンドは止めない。 */
    private void setTerminalVisible(boolean visible) {
        if (visible && !Edition.has(Edition.AI)) {
            return;
        }
        terminalButton.setSelected(visible);
        terminalItem.setSelected(visible);
        if (visible == terminalVisible) {
            return;
        }
        terminalVisible = visible;
        if (terminal == null) {
            terminal = new TerminalPane(this::terminalFolder, () -> setTerminalVisible(false));
        }
        layoutCenter();
        if (visible) {
            terminal.start();
            SwingUtilities.invokeLater(terminal::focusInput);
        } else {
            withPane(pane -> SwingUtilities.invokeLater(pane::focusEditor));
        }
    }

    /**
     * 真ん中（検索バーの下）を並べ直す。上からエディタ、その下に下の欄（フォルダ内の検索・見出し一覧。出ていれば）、
     * いちばん下にターミナル（出ていれば）。下の欄の分割は、いったん作ったら使い回す（欄の高さを覚えておくため）。
     * ターミナルの分割は、出すたびに作り直す（高さは 0.65 にそろえる）。
     */
    private void layoutCenter() {
        center.removeAll();
        center.add(findBar, BorderLayout.NORTH);
        JComponent middle = editorArea;
        JComponent lower = lowerPanels();
        if (lower != null) {
            if (lowerSplit == null) {
                lowerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorArea, lower);
                styleSplit(lowerSplit);
                editorArea.setMinimumSize(new Dimension(0, 80));
                SwingUtilities.invokeLater(() -> lowerSplit.setDividerLocation(0.66));   // 下の欄は、下の 1/3 くらい
            } else {
                lowerSplit.setTopComponent(editorArea);
                lowerSplit.setBottomComponent(lower);
            }
            middle = lowerSplit;
        }
        if (terminalVisible) {
            JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, middle, terminal);
            styleSplit(splitPane);
            middle.setMinimumSize(new Dimension(0, 80));
            terminal.setMinimumSize(new Dimension(0, 80));
            SwingUtilities.invokeLater(() -> splitPane.setDividerLocation(0.65));
            middle = splitPane;
        }
        center.add(middle, BorderLayout.CENTER);
        center.revalidate();
        center.repaint();
    }

    /**
     * エディタの下に出す欄。出ている欄（フォルダ内の検索・見出し一覧・作品の集計）を、この順に左から並べる。
     * 1つだけならその欄を、どれも出ていなければ null を返す。
     */
    private JComponent lowerPanels() {
        List<JComponent> shown = new ArrayList<>();
        if (folderSearchVisible) {
            shown.add(folderSearch);
        }
        if (outlineVisible) {
            shown.add(outline);
        }
        if (workVisible) {
            shown.add(work);
        }
        if (shown.isEmpty()) {
            return null;
        }
        return shown.size() == 1 ? shown.get(0) : sideBySide(shown);
    }

    /**
     * 欄を左から右へ並べる。欄の間は分割の線（ドラッグで幅を変えられる）。最初の位置は、欄を同じ幅に分ける。
     * 欄を出し入れするたびに作り直すので、ドラッグで変えた左右の幅は覚えない（上下の高さは lowerSplit が覚える）。
     */
    private static JComponent sideBySide(List<JComponent> panels) {
        int count = panels.size();
        JComponent right = panels.get(count - 1);
        for (int i = count - 2; i >= 0; i--) {
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, panels.get(i), right);
            styleSplit(split);
            double share = 1.0 / (count - i);   // 左の欄が受け持つ幅（残りの欄と同じ割合）
            SwingUtilities.invokeLater(() -> split.setDividerLocation(share));
            right = split;
        }
        return right;
    }

    /** 真ん中の分割の見た目（ターミナル・下の欄で同じ）。上の欄が伸び縮みする。 */
    private static void styleSplit(JSplitPane splitPane) {
        splitPane.setUI(new BasicSplitPaneUI());
        splitPane.setBorder(null);
        splitPane.setBackground(Theme.BORDER);
        splitPane.setDividerSize(4);
        splitPane.setResizeWeight(1.0);     // ウィンドウの大きさを変えたときは、上の欄が伸び縮みする
        splitPane.setContinuousLayout(true);
    }

    // ---------- フォルダ内の検索 ----------

    /** フォルダ内の検索の欄を出す。出ていれば、欄へ移って文字を全部選ぶ。 */
    private void showFolderSearch() {
        if (focusMode) {
            flash("集中モードの間は出せません（F11 で戻ります）");
            return;
        }
        if (folderSearch == null) {
            folderSearch = new FolderSearchPanel(new FolderSearchPanel.Host() {
                @Override
                public Path searchRoot() {
                    return sidebar().getRoot();
                }

                @Override
                public Map<Path, String> openTexts() {
                    return openFileTexts();
                }

                @Override
                public void reveal(Path file, int start, int end, boolean jsonBody) {
                    revealSearchHit(file, start, end, jsonBody);
                }
            }, this::hideFolderSearch);
        }
        if (!folderSearchVisible) {
            folderSearchVisible = true;
            layoutCenter();
        }
        folderSearch.focusKeyword();
    }

    /** フォルダ内の検索の欄を隠して、エディタへ戻す。探している最中なら止める。 */
    private void hideFolderSearch() {
        if (!folderSearchVisible) {
            return;
        }
        folderSearch.cancelSearch();
        folderSearchVisible = false;
        layoutCenter();
        withPane(pane -> SwingUtilities.invokeLater(pane::focusEditor));
    }

    /**
     * 検索の結果の一致へ移る。開いていればそのタブを前に出し、開いていなければ開く（サイドバーからの開き方と同じ）。
     * jsonBody のときは、位置がファイルの中のものではないので、開くだけで選ばない。
     */
    private void revealSearchHit(Path file, int start, int end, boolean jsonBody) {
        try {
            Path path = file.toAbsolutePath().normalize();
            EditorPane pane = findOpenDocument(path);
            if (pane != null) {
                showDocument(pane);
            } else {
                pane = openPath(path, currentPane() instanceof VerticalTextPane, false);
            }
            if (!jsonBody && pane instanceof Searchable found) {
                found.select(start, end);
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "開けませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** ターミナルを始めるフォルダ。今の文書が保存されていればその場所、なければ「開く」の最初の場所と同じ。 */
    private Path terminalFolder() {
        EditorPane pane = currentPane();
        if (pane != null && pane.getPath() != null && pane.getPath().toAbsolutePath().getParent() != null) {
            return pane.getPath().toAbsolutePath().getParent();
        }
        return appSettings.startFolder();
    }

    private static KeyStroke key(int keyCode, int modifiers) {
        return KeyStroke.getKeyStroke(keyCode, modifiers);
    }

    private static JMenuItem item(String label, KeyStroke accelerator, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    /**
     * 倍率のキー（Ctrl+＋／Ctrl+－／Ctrl+0、本文だけは Ctrl+Alt）を、どの部品より先に受け取る。
     * Windows では、キーボードの配列や日本語入力によって、メニューのキーの取り決めまで届かないことがあるため、
     * キーの番号と、そのキーで出る文字の両方で判定する。
     */
    private void installZoomKeys() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED || e.isMetaDown()
                    || e.getComponent() == null || SwingUtilities.getWindowAncestor(e.getComponent()) != this) {
                return false;
            }
            // Alt+↑／Alt+↓／Alt+0：Ctrl+＋ などが、ほかのアプリに取られてしまう PC のための、もう1組のキー
            if (e.isAltDown() && !e.isControlDown() && !e.isShiftDown() && !e.isAltGraphDown()) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN || code == KeyEvent.VK_0) {
                    setUiZoom(code == KeyEvent.VK_0 ? 100 : Theme.nextZoom(code == KeyEvent.VK_UP ? 1 : -1));
                    e.consume();
                    return true;
                }
                return false;
            }
            if (!e.isControlDown()) {
                return false;
            }
            int direction = zoomDirection(e);
            if (direction == Integer.MIN_VALUE) {
                return false;
            }
            // Windows では、Ctrl+Alt が AltGr（右 Alt）として届くこともある
            boolean textOnly = e.isAltDown() || e.isAltGraphDown();
            if (textOnly && direction == 0) {
                withBoard(BoardPane::fitAll);
            } else if (textOnly) {
                zoom(direction);
            } else {
                setUiZoom(direction == 0 ? 100 : Theme.nextZoom(direction));
            }
            e.consume();
            return true;
        });
    }

    private double wheelSum;    // タッチパッドの細かい回転を、1目盛り分たまるまで足しておく

    /** ブラウザと同じく、Ctrl+ホイールで画面全体の倍率を変える（カードボードの上では、ボードの拡大・縮小のまま）。 */
    private void installZoomWheel() {
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof java.awt.event.MouseWheelEvent e) || e.getID() != java.awt.event.MouseEvent.MOUSE_WHEEL
                    || !e.isControlDown() || e.isAltDown() || e.isConsumed()
                    || SwingUtilities.getWindowAncestor(e.getComponent()) != this
                    || SwingUtilities.getAncestorOfClass(BoardPane.class, e.getComponent()) != null) {
                return;
            }
            e.consume();        // 本文がスクロールしないように
            wheelSum += e.getPreciseWheelRotation();
            if (Math.abs(wheelSum) >= 1) {
                setUiZoom(Theme.nextZoom(wheelSum < 0 ? 1 : -1));     // 奥へ回すと拡大
                wheelSum = 0;
            }
        }, AWTEvent.MOUSE_WHEEL_EVENT_MASK);
    }

    /** 倍率のキーなら 1（拡大）、-1（縮小）、0（元に戻す）。ほかのキーなら Integer.MIN_VALUE。 */
    private static int zoomDirection(KeyEvent e) {
        int code = e.getKeyCode();
        int extended = e.getExtendedKeyCode();
        for (int c : new int[]{code, extended}) {
            switch (c) {
                case KeyEvent.VK_SEMICOLON, KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD:
                    return 1;
                case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT:
                    return -1;
                case KeyEvent.VK_0, KeyEvent.VK_NUMPAD0:
                    return 0;
                default:
                    break;
            }
        }
        char ch = e.getKeyChar();
        if (ch == '+' || ch == ';' || ch == '=') {
            return 1;
        }
        if (ch == '-') {
            return -1;
        }
        return ch == '0' ? 0 : Integer.MIN_VALUE;
    }

    /** メニューには1つしかキーを付けられないので、別のキーでも動くようにする。 */
    private void installExtraShortcuts() {
        shortcut(key(KeyEvent.VK_Z, CTRL | SHIFT), "redo2", () -> withPane(EditorPane::redo));
        // 「+」は、JIS 配列では Shift+;、US 配列では Shift+= なので、Shift があってもなくても動くようにする
        Runnable uiIn = () -> setUiZoom(Theme.nextZoom(1));
        shortcut(key(KeyEvent.VK_SEMICOLON, CTRL | SHIFT), "uiZoomIn2", uiIn);
        shortcut(key(KeyEvent.VK_EQUALS, CTRL), "uiZoomIn3", uiIn);
        shortcut(key(KeyEvent.VK_EQUALS, CTRL | SHIFT), "uiZoomIn4", uiIn);
        shortcut(key(KeyEvent.VK_ADD, CTRL), "uiZoomIn5", uiIn);                  // テンキー
        shortcut(key(KeyEvent.VK_SUBTRACT, CTRL), "uiZoomOut2", () -> setUiZoom(Theme.nextZoom(-1)));
        shortcut(key(KeyEvent.VK_NUMPAD0, CTRL), "uiZoomReset2", () -> setUiZoom(100));
        // 本文の文字だけ：Ctrl+Alt（Ctrl+Shift+; は JIS 配列の Ctrl++ と同じキーになるので使えない）
        shortcut(key(KeyEvent.VK_SEMICOLON, CTRL | ALT | SHIFT), "zoomIn2", () -> zoom(1));
        shortcut(key(KeyEvent.VK_EQUALS, CTRL | ALT), "zoomIn3", () -> zoom(1));
        shortcut(key(KeyEvent.VK_EQUALS, CTRL | ALT | SHIFT), "zoomIn4", () -> zoom(1));
        shortcut(key(KeyEvent.VK_ADD, CTRL | ALT), "zoomIn5", () -> zoom(1));
        shortcut(key(KeyEvent.VK_SUBTRACT, CTRL | ALT), "zoomOut2", () -> zoom(-1));
        // 集中モード中に Esc でも戻れるようにする（検索バーなどが Esc を使うときは、そちらが先に受け取る）
        // 集中モード中は Esc でも戻れるようにする。取り決めは集中モードの間だけ登録する（setFocusEscape）。
        // いつも登録しておくと、Swing がメニューを閉じるための Esc より先に受け取ってしまい、
        // 開いたメニューを Esc で閉じられなくなるため。
        getRootPane().getActionMap().put("leaveFocus", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (MenuSelectionManager.defaultManager().getSelectedPath().length > 0) {
                    MenuSelectionManager.defaultManager().clearSelectedPath();   // 開いているメニューがあれば、先に閉じる
                } else if (focusMode) {
                    toggleFocusMode();
                }
            }
        });
    }

    private void shortcut(KeyStroke keyStroke, String name, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(keyStroke, name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    // ---------- 今の文書 ----------

    private EditorPane currentPane() {
        return activeGroup == null ? null : activeGroup.getActive();
    }

    private void withPane(java.util.function.Consumer<EditorPane> action) {
        EditorPane pane = currentPane();
        if (pane != null) {
            action.accept(pane);
        }
    }

    /** 今の文書が カードボードのときだけ、操作を実行する。 */
    private void withBoard(java.util.function.Consumer<BoardPane> action) {
        if (currentPane() instanceof BoardPane board) {
            action.accept(board);
            updateStatus();
        } else {
            flash("この操作は、カードボードで使えます（ファイル → 新規（カードボード））");
        }
    }

    /** 複数行をまとめて入力し、1行につき1枚のカードにする。 */
    private void bulkAddCards() {
        if (!(currentPane() instanceof BoardPane board)) {
            flash("この操作は、カードボードで使えます（ファイル → 新規（カードボード））");
            return;
        }
        JTextArea input = new JTextArea(10, 30);
        input.setFont(Theme.ui());
        input.setBackground(Theme.INPUT);
        input.setForeground(Theme.FG_STRONG);
        input.setCaretColor(Theme.FG_STRONG);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("1行につき、カードが1枚できます。"), BorderLayout.NORTH);
        panel.add(new JScrollPane(input), BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(this, panel, "カードを一括追加",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            board.addCardsFromLines(input.getText());
        }
        board.focusEditor();
    }

    /** カードボードを、PNG 画像のファイルに書き出す。 */
    private void exportBoardImage() {
        if (!(currentPane() instanceof BoardPane board)) {
            flash("この操作は、カードボードで使えます（ファイル → 新規（カードボード））");
            return;
        }
        BufferedImage image = board.renderImage();
        if (image == null) {
            flash("カードがないので、書き出すものがありません");
            return;
        }
        JFileChooser chooser = newFileChooser();
        FileNameExtensionFilter pngFilter = new FileNameExtensionFilter("PNG 画像 (*.png)", "png");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(pngFilter);
        if (board.getPath() != null) {
            String base = board.getPath().toString().replaceAll("\\.[^.\\\\/]+$", "");
            chooser.setSelectedFile(new java.io.File(base + ".png"));
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String name = chooser.getSelectedFile().toString();
        if (!name.toLowerCase().endsWith(".png")) {
            name = name + ".png";
        }
        Path path = Path.of(name);
        if (!confirmOverwrite(path)) {
            return;
        }
        try {
            if (!ImageIO.write(image, "png", path.toFile())) {
                throw new IOException("PNG として書き出せませんでした");
            }
            flash("画像を書き出しました: " + path);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "書き出せませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    private List<EditorPane> allPanes() {
        List<EditorPane> all = new ArrayList<>(group1.getPanes());
        all.addAll(group2.getPanes());
        return all;
    }

    private EditorGroup groupOf(EditorPane pane) {
        return group2.getPanes().contains(pane) ? group2 : group1;
    }

    private void setActiveGroup(EditorGroup group) {
        if (group == activeGroup) {
            return;
        }
        activeGroup = group;
        group1.setGroupActive(group == group1);
        group2.setGroupActive(group == group2);
        currentPaneChanged();
    }

    /** 表示している文書が変わったときに、タイトルやステータスバーを合わせる。 */
    private void currentPaneChanged() {
        updateTitle();
        updateStatus();
        wrapItem.setSelected(currentPane() instanceof TextEditorPane text && text.isWrap());
        manuscriptItem.setSelected(!(currentPane() instanceof VerticalTextPane vertical) || vertical.isManuscript());
        findBar.paneSwitched();
        if (proofPanel != null && "proof".equals(rightPanel)) {
            proofPanel.refresh();
        }
        if (outlineVisible) {
            outline.refresh();
        }
        if (focusMode && currentPane() instanceof TextEditorPane text) {
            text.setFocusMode(true);
        }
    }

    private void updateTitle() {
        EditorPane pane = currentPane();
        if (pane == null) {
            setTitle(APP_NAME + " " + Edition.title());
        } else {
            setTitle(pane.getTitle() + (pane.isDirty() ? " ●" : "") + " - " + APP_NAME);
        }
    }

    private void updateStatus() {
        EditorPane pane = currentPane();
        goalMeter.refresh();
        if (pane == null) {
            statusLeft.setText(message != null ? message : " ");
            statusRight.setText(" ");
            return;
        }
        statusLeft.setText(message != null ? message : pane.getMode().label);
        String format;
        if (pane.getPath() == null) {
            format = "未保存";
        } else if (isJsonPath(pane.getPath()) && !pane.isRawFile()) {
            format = "JSON";
        } else {
            format = "テキスト";
        }
        String sheets = pane instanceof TextEditorPane text && Edition.has(Edition.VERTICAL)
                ? "    原稿用紙換算 " + sheetCache.computeIfAbsent(text.firstView(), p -> WritingStats.manuscriptSheets(text.getText())) + " 枚"
                : "";
        // 字数は、設定の数え方で数える（横書きは記法を含むので、ルビを除くモードでは親文字だけを数える）
        String count = pane instanceof Searchable s
                ? "    " + WritingStats.charCountText(s, WritingStats.CountMode.fromId(appSettings.getString(AppSettings.COUNT_MODE, "all")),
                        pane instanceof TextEditorPane)
                : "";
        statusRight.setText(pane.statusText() + count + sheets + "    UTF-8    " + format
                + (mcp.isRunning() ? "    AI 連携中" : ""));
    }

    /** 字数の数え方を変える。ステータスバーにすぐ出し、設定にも残す。 */
    private void setCountMode(WritingStats.CountMode mode) {
        appSettings.put(AppSettings.COUNT_MODE, mode.id);
        appSettings.save();
        updateStatus();
        if (workVisible) {
            work.refresh();                     // 作品の集計の欄が出ていれば、新しい数え方で数え直す
        }
    }

    /** ステータスバーに、数秒だけお知らせを出す（ポップアップで作業を止めないため）。 */
    private void flash(String text) {
        message = text;
        updateStatus();
        messageTimer.restart();
    }

    // ---------- EditorPane.Listener（文書からの知らせ） ----------

    @Override
    public void contentChanged(EditorPane pane) {
        sheetCache.remove(pane.firstView());
        findBar.contentChanged(pane);
        stats.changed(pane);
        if (proofPanel != null && pane == currentPane()) {
            proofPanel.contentChanged();
        }
        if (outlineVisible && pane == currentPane()) {
            outline.refresh();
        }
    }

    @Override
    public void titleChanged(EditorPane pane) {
        groupOf(pane).refreshTabs();
        if (pane == currentPane()) {
            updateTitle();
        }
    }

    @Override
    public void message(EditorPane pane, String text) {
        flash(text);
    }

    @Override
    public void statusChanged(EditorPane pane) {
        if (pane == currentPane()) {
            updateStatus();
        }
    }

    // ---------- EditorGroup.Host（タブからの知らせ） ----------

    @Override
    public void groupActivated(EditorGroup group) {
        setActiveGroup(group);
    }

    @Override
    public void paneSelected(EditorGroup group, EditorPane pane) {
        if (group == activeGroup) {
            currentPaneChanged();
        }
    }

    @Override
    public void closeRequested(EditorGroup group, EditorPane pane) {
        closePane(group, pane);
    }

    /** 「+」を押したとき、どの種類の文書を作るかを選ぶメニューを出す。 */
    @Override
    public void newRequested(EditorGroup group, Component invoker) {
        setActiveGroup(group);
        if (!Edition.has(Edition.VERTICAL)) {
            newDocument(EditorPane.Mode.TEXT);      // 選べる種類が横書きだけなら、選ばせずに作る
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        String[][] choices = {
            {"横書きのテキスト", "Ctrl+N"},
            {"縦書き（原稿用紙）", "Ctrl+Shift+N"},
            {"カードボード（KJ法風のアイデア整理）", "Ctrl+Shift+K"},
        };
        for (EditorPane.Mode mode : EditorPane.Mode.values()) {
            if (!Edition.allows(mode)) {
                continue;
            }
            String[] choice = choices[mode.ordinal()];
            JMenuItem item = new JMenuItem("<html><font color='" + BADGE_HEX[mode.ordinal()] + "'><b>" + mode.badge
                    + "</b></font>&nbsp;&nbsp;" + choice[0] + "&nbsp;&nbsp;&nbsp;<font color='" + hex(Theme.FG_DIM) + "'>"
                    + choice[1] + "</font></html>");
            item.addActionListener(e -> {
                setActiveGroup(group);
                newDocument(mode);
            });
            menu.add(item);
        }
        menu.show(invoker, 0, invoker.getHeight());
    }

    private static String hex(Color color) {
        return String.format("#%06X", color.getRGB() & 0xFFFFFF);
    }

    private static final String[] BADGE_HEX = {"#4FC1FF", "#CE9178", "#4EC9B0"};     // タブの印と同じ色

    /** タブの右クリックメニュー。分割や移動を、メニューを探さずにその場でできるようにする。 */
    @Override
    public void tabMenuRequested(EditorGroup group, EditorPane pane, Component invoker, int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        boolean right = group == group2;
        String side = splitOrientation == JSplitPane.HORIZONTAL_SPLIT ? (right ? "左" : "右") : (right ? "上" : "下");
        JMenuItem move = new JMenuItem(split ? side + "側へ移す" : "画面を分割して、" + side + "側へ移す");
        move.setEnabled(split || group.getPanes().size() > 1);
        move.addActionListener(e -> moveTabToOtherGroup());
        menu.add(move);
        menu.add(menuItem(split ? "分割をやめる" : "画面を分割する", this::toggleSplit));
        menu.add(menuItem(splitOrientation == JSplitPane.HORIZONTAL_SPLIT ? "上下に分割する" : "左右に分割する",
                this::toggleOrientation));
        menu.addSeparator();
        menu.add(menuItem("閉じる", () -> closePane(group, pane)));
        JMenuItem others = new JMenuItem("ほかのタブを閉じる");
        others.setEnabled(group.getPanes().size() > 1);
        others.addActionListener(e -> {
            for (EditorPane other : new ArrayList<>(group.getPanes())) {
                if (other != pane && group.getPanes().contains(other)) {
                    closePane(group, other);
                }
            }
        });
        menu.add(others);
        menu.show(invoker, x, y);
    }

    private static JMenuItem menuItem(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }

    /** タブを離したとき、どこへ入るか。group が null なら、どこにも入らない。 */
    private record TabDrop(EditorGroup group, int index, boolean newSplit) {
    }

    private TabDrop tabDrop(EditorGroup from, Point screen) {
        for (EditorGroup group : split ? new EditorGroup[]{group1, group2} : new EditorGroup[]{group1}) {
            if (!group.containsScreen(screen)) {
                continue;
            }
            if (!split && !group.overStrip(screen) && from.getPanes().size() > 1) {
                // 分割していないときに、文書の右（下）3分の1で離したら、分割してそちらへ移す
                Point p = new Point(screen);
                SwingUtilities.convertPointFromScreen(p, group);
                boolean edge = splitOrientation == JSplitPane.HORIZONTAL_SPLIT
                        ? p.x > group.getWidth() * 2 / 3 : p.y > group.getHeight() * 2 / 3;
                if (edge) {
                    return new TabDrop(group2, 0, true);
                }
            }
            return new TabDrop(group, group.dropIndex(screen), false);
        }
        return new TabDrop(null, -1, false);
    }

    @Override
    public void tabDragged(EditorGroup from, EditorPane pane, Point screen) {
        TabDrop drop = tabDrop(from, screen);
        group1.setDropMarker(drop.group() == group1 && group1.overStrip(screen) ? drop.index() : -1);
        group2.setDropMarker(drop.group() == group2 && group2.overStrip(screen) ? drop.index() : -1);
        String hint = drop.newSplit() ? "ここで離すと、画面を分割して移します"
                : drop.group() != null && drop.group() != from ? "ここで離すと、反対側へ移します" : null;
        if (hint != null && !hint.equals(message)) {
            flash(hint);
        }
    }

    @Override
    public void tabDropped(EditorGroup from, EditorPane pane, Point screen) {
        group1.setDropMarker(-1);
        group2.setDropMarker(-1);
        TabDrop drop = tabDrop(from, screen);
        if (drop.group() == null) {
            return;
        }
        if (drop.group() == from) {
            from.movePane(pane, drop.index());      // 同じ側なら、並べ替え
        } else if (mergeInto(drop.group(), from, pane)) {
            return;
        } else {
            from.removePane(pane);
            if (drop.newSplit()) {
                split = true;
                layoutGroups();
            }
            drop.group().insertPane(pane, drop.index());
            setActiveGroup(drop.group());
            tidyGroups();
        }
        currentPaneChanged();
        SwingUtilities.invokeLater(pane::focusEditor);
    }

    // ---------- 新規・開く・保存・閉じる ----------

    private EditorPane createPane(EditorPane.Mode mode) {
        return switch (mode) {
            case TEXT -> new TextEditorPane(Theme.editorFont());
            case VERTICAL -> new VerticalTextPane(Theme.editorFont());
            case BOARD -> new BoardPane();
        };
    }

    private void addPane(EditorGroup group, EditorPane pane) {
        pane.setListener(this);
        stats.track(pane);
        group.addPane(pane);
        SwingUtilities.invokeLater(pane::focusEditor);
    }

    private void newDocument(EditorPane.Mode mode) {
        if (!Edition.allows(mode)) {
            flash(Edition.notice(mode == EditorPane.Mode.VERTICAL ? Edition.VERTICAL : Edition.BOARD));
            return;
        }
        addPane(activeGroup, createPane(mode));
    }

    /**
     * ファイルを選んで開く。
     * .txt には「縦書きか横書きか」の情報がないので、次のように決める：
     *   「縦書きで開く」から来たとき、または、今見ている文書が縦書きのとき … 縦書き（原稿用紙）
     *   それ以外 … 横書き
     * .json（このアプリの形式）は、保存したときの種類のまま開く。
     */
    private void openFile(boolean forceVertical) {
        boolean vertical = Edition.has(Edition.VERTICAL) && (forceVertical || currentPane() instanceof VerticalTextPane);
        JFileChooser chooser = newFileChooser();
        chooser.setDialogTitle(vertical ? "縦書きで開く" : "開く");
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("テキスト / JSON (*.txt, *.json)", "txt", "json"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (FolderSidebar.isGoogleFile(path)) {
            // Google ドキュメントなどは本文が PC にない（リンクだけ）ので、タブには開かずブラウザに渡す
            flash(FolderSidebar.openGoogleFile(path));
            return;
        }

        try {
            openPath(path, vertical, forceVertical);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "開けませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 指定したファイルを開いて、その文書を返す（メニューの「開く」と、AI 連携の両方から使う）。
     *
     * @param vertical           新しく開くとき、.txt を縦書きにするか
     * @param switchToVertical   すでに開いていたとき、そのタブを縦書きに切り替えるか
     */
    private EditorPane openPath(Path path, boolean vertical, boolean switchToVertical) throws IOException {
        // すでに開いているファイルは、もう1つ開かずに、そのタブを使う。
        // （同じファイルを2つのタブで別々に編集すると、後から保存したほうが、先の変更を消してしまうため）
        EditorPane open = findOpenDocument(path);
        if (open != null) {
            showAlreadyOpen(open, switchToVertical);
            return currentPane();
        }
        EditorPane pane = paneFromFile(path, fileService.read(path), vertical);
        EditorPane previous = currentPane();
        pane.setPath(path);
        addPane(activeGroup, pane);
        removeIfPristine(previous);
        return pane;
    }

    /** 何も書いていない「無題」のタブなら、片付ける（新しい文書を開いたあとに残さないため）。 */
    private void removeIfPristine(EditorPane pane) {
        if (pane != null && pane != currentPane() && pane.getPath() == null && !pane.isDirty() && pane.isEmpty()) {
            groupOf(pane).removePane(pane);
            pane.unlinkView();
        }
    }

    /**
     * すでに開いているタブを表示する。
     * 「縦書きで開く」から来たときは、そのタブをその場で縦書きに切り替えて、頼まれたことに応える。
     * タブは1つのままで、未保存の変更もそのまま引き継がれる（ファイルを読み直さない）。
     */
    private void showAlreadyOpen(EditorPane open, boolean wantVertical) {
        setActiveGroup(groupOf(open));
        activeGroup.select(open);
        if (!wantVertical) {
            // ふつうの「開く」は向きを指定していないので、今の表示のまま見せる
            flash("すでに開いているので、そのタブを表示しました");
        } else if (open instanceof VerticalTextPane) {
            flash("すでに縦書きで開いています");
        } else if (open instanceof TextEditorPane && !open.isRawFile()) {
            switchWriting();
            flash("すでに開いていたので、そのタブを縦書きに切り替えました");
        } else {
            flash("このタブは「" + open.getMode().label + "」として開いているので、縦書きにはできません");
        }
        withPane(EditorPane::focusEditor);
    }

    /** ファイルの中身から、合う種類の文書を作る。 */
    @SuppressWarnings("unchecked")
    private EditorPane paneFromFile(Path path, String content, boolean vertical) throws IOException {
        if (isJsonPath(path)) {
            try {
                if (Json.parse(content) instanceof Map<?, ?> raw
                        && EditorPane.FORMAT_NAME.equals(raw.get("format"))) {
                    return paneFromJson((Map<String, Object>) raw);
                }
            } catch (Json.JsonException ex) {
                flash("JSON として読めなかったので、テキストとして開きました");
            }
        }
        // このアプリの形式ではないファイルは、中身をそのまま文章として開く
        if (vertical && !isJsonPath(path) && Edition.has(Edition.VERTICAL)) {
            // 縦書き：「小説家になろう」の記法があれば、ルビとして読み取られる
            VerticalTextPane pane = new VerticalTextPane(Theme.editorFont());
            pane.loadText(content);
            return pane;
        }
        TextEditorPane pane = new TextEditorPane(Theme.editorFont());
        pane.loadText(content);
        pane.setRawFile(isJsonPath(path));
        return pane;
    }

    /** このアプリの JSON 形式から、文書を作る。 */
    private EditorPane paneFromJson(Map<String, Object> json) throws IOException {
        EditorPane.Mode mode = EditorPane.Mode.fromId(Json.str(json, "mode", "text"));
        if (mode == EditorPane.Mode.BOARD && !Edition.allows(mode)) {
            throw new IOException(Edition.notice(Edition.BOARD) + "（この種類では開けません）");
        }
        if (mode == EditorPane.Mode.VERTICAL && !Edition.allows(mode)) {
            // 縦書きの文書は、文章（ルビは「｜漢字《かんじ》」の形）を横書きで開く
            TextEditorPane text = new TextEditorPane(Theme.editorFont());
            text.loadText(VerticalTextPane.fromJson(json).toPlainText());
            flash("縦書きの文書を、横書きで開きました（" + Edition.notice(Edition.VERTICAL) + "）");
            return text;
        }
        return switch (mode) {
            case TEXT -> TextEditorPane.fromJson(json);
            case VERTICAL -> VerticalTextPane.fromJson(json);
            case BOARD -> BoardPane.fromJson(json);
        };
    }

    static boolean isJsonPath(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".json");
    }

    /**
     * 「開く」「保存」などで使う、ファイルを選ぶ画面を作る。
     * 最初に表示するのは、最後に保存したフォルダ。まだ一度も保存していなければ、デスクトップ。
     */
    private JFileChooser newFileChooser() {
        return new JFileChooser(appSettings.startFolder().toFile());
    }

    /** 保存する。成功したら true。保存先が決まっていなければ「名前を付けて保存」になる。 */
    private boolean save(EditorPane pane) {
        if (pane == null) {
            return false;
        }
        if (pane.getPath() == null) {
            return saveAs(pane);
        }
        return write(pane, pane.getPath());
    }

    private boolean saveAs(EditorPane pane) {
        if (pane == null) {
            return false;
        }
        JFileChooser chooser = newFileChooser();
        FileNameExtensionFilter txtFilter = new FileNameExtensionFilter("テキスト (*.txt)", "txt");
        FileNameExtensionFilter jsonFilter = new FileNameExtensionFilter("JSON：設定や配置も保存 (*.json)", "json");
        chooser.setAcceptAllFileFilterUsed(false);
        boolean cardBoard = pane.getMode() == EditorPane.Mode.BOARD;
        if (!cardBoard) {
            chooser.addChoosableFileFilter(txtFilter);     // カードボードはカードの位置があるので、JSON だけにする
        }
        chooser.addChoosableFileFilter(jsonFilter);
        boolean preferJson = cardBoard || (pane.getPath() != null && isJsonPath(pane.getPath()) && !pane.isRawFile());
        chooser.setFileFilter(preferJson ? jsonFilter : txtFilter);
        if (pane.getPath() != null) {
            chooser.setSelectedFile(pane.getPath().toFile());
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return false;
        }

        // 選んだ形式の拡張子が付いていなければ、付け足す
        String extension = ((FileNameExtensionFilter) chooser.getFileFilter()).getExtensions()[0];
        String name = chooser.getSelectedFile().toString();
        if (!name.toLowerCase().endsWith("." + extension)) {
            name = name + "." + extension;
        }
        Path path = Path.of(name).toAbsolutePath();
        EditorPane open = findOpenDocument(path);
        if (open != null && open.firstView() != pane.firstView()) {
            // 別のタブで開いているファイルに保存すると、あとでそのタブを保存したときに、こちらの中身が消えてしまう
            JOptionPane.showMessageDialog(this, "「" + path.getFileName() + "」は、別のタブで開いています。\n"
                    + "そのタブを閉じてから保存するか、別の名前を付けてください。", "保存できません", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (!path.equals(pane.getPath()) && !confirmOverwrite(path)) {
            return false;
        }

        boolean wasRaw = pane.isRawFile();
        pane.setRawFile(false);         // 自分で形式を選んだので、このアプリの形式で保存する
        if (write(pane, path)) {
            pane.setPath(path);
            updateStatus();
            if (sidebar != null) {
                sidebar.refresh();      // 新しいファイルができたかもしれないので、一覧を最新にする
            }
            return true;
        }
        pane.setRawFile(wasRaw);
        return false;
    }

    /** 今の文書を、PDF ファイルに書き出す。 */
    private void exportPdf() {
        EditorPane pane = currentPane();
        if (pane == null) {
            return;
        }
        if (pane.getMode() == EditorPane.Mode.BOARD && pane.isEmpty()) {
            flash("カードがないので、書き出すものがありません");
            return;
        }
        JFileChooser chooser = newFileChooser();
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("PDF (*.pdf)", "pdf"));
        if (pane.getPath() != null) {
            String base = pane.getPath().toString().replaceAll("\\.[^.\\\\/]+$", "");
            chooser.setSelectedFile(new java.io.File(base + ".pdf"));
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String name = chooser.getSelectedFile().toString();
        if (!name.toLowerCase().endsWith(".pdf")) {
            name = name + ".pdf";
        }
        Path path = Path.of(name);
        if (!confirmOverwrite(path)) {
            return;
        }

        // 長い文章では少し時間がかかるので、その間は「待ち」のカーソルにする
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        // 一時ファイルに書いてから入れ替える（途中で失敗しても、上書きしようとした元の PDF は残る）
        Path temp = null;
        try {
            Path folder = path.toAbsolutePath().getParent();
            temp = Files.createTempFile(folder, ".datum-", ".pdf.tmp");
            int pages;
            try (PdfWriter writer = new PdfWriter(temp)) {
                pane.exportPdf(writer);
                pages = writer.getPageCount();
            }
            try {
                Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
            flash("PDF を書き出しました（" + pages + " ページ）: " + path);
        } catch (IOException ex) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);     // 途中までしか書けていない一時ファイルは残さない
                } catch (IOException ignored) {
                    // 消せなくても、下のダイアログで失敗は伝わる
                }
            }
            JOptionPane.showMessageDialog(this, "書き出せませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        } finally {
            setCursor(java.awt.Cursor.getDefaultCursor());
        }
    }

    /** ファイルに書く中身。拡張子が .json なら JSON、それ以外は本文だけ。 */
    private static String fileContent(EditorPane pane, Path path) {
        return isJsonPath(path) && !pane.isRawFile() ? Json.write(pane.toJson()) : pane.toPlainText();
    }

    private boolean write(EditorPane pane, Path path) {
        return write(pane, path, true);
    }

    /**
     * 実際にファイルへ書き、版の履歴にも残す。
     *
     * @param manual 利用者が保存した（false なら自動保存。履歴は 10 分に1回だけ残し、失敗してもダイアログは出さない）
     */
    private boolean write(EditorPane pane, Path path, boolean manual) {
        String content = fileContent(pane, path);
        Path key = path.toAbsolutePath().normalize();
        if (fileService.changedOutside(path)) {
            // 同期のアプリやほかのエディタが書き換えたものを、黙って消さない
            if (!manual) {
                if (changedOutsideWarned.add(key)) {
                    flash("「" + path.getFileName() + "」はほかのアプリで変更されたので、自動保存を止めました（保存すると、上書きするか聞きます）");
                }
                return false;
            }
            int answer = JOptionPane.showConfirmDialog(this,
                    "「" + path.getFileName() + "」は、開いたあとで、ほかのアプリによって変更されています。\n"
                            + "上書きしますか？（今のファイルの中身は、版の履歴に残します）",
                    "上書きの確認", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return false;
            }
        }
        keepDiskVersion(path);
        try {
            fileService.save(content, path);
            changedOutsideWarned.remove(key);
            pane.setDirty(false);
            Instant previous = lastHistory.get(key);
            if (manual || previous == null || Duration.between(previous, Instant.now()).toMinutes() >= 10) {
                history.record(path, content, appSettings.getInt(AppSettings.HISTORY_LIMIT, 50));
                lastHistory.put(key, Instant.now());
            }
            if (manual) {
                appSettings.rememberFolder(path.toAbsolutePath().getParent());   // 次に「開く」「保存」を出したとき、ここから始める
                flash("保存しました: " + path);
            }
            return true;
        } catch (IOException ex) {
            if (!manual) {
                flash("自動保存できませんでした: " + saveFailure(ex));
                return false;
            }
            // 失敗は握りつぶさず、ダイアログで知らせる
            JOptionPane.showMessageDialog(this, "保存できませんでした: " + saveFailure(ex),
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /** 保存に失敗した理由を、利用者にわかる言葉にする（そのままだと、ファイルの場所しか出ないことがある）。 */
    private static String saveFailure(IOException ex) {
        if (ex instanceof java.nio.file.AccessDeniedException denied) {
            return "書き込みが許されていません（読み取り専用のファイルか、ほかのアプリが使っています）\n" + denied.getFile();
        }
        if (ex instanceof java.nio.charset.CharacterCodingException) {
            return "UTF-8 で書けない文字が入っています";
        }
        return ex.getMessage();
    }

    /**
     * 上書きする前のファイルの中身を、版の履歴に残す。
     * このアプリで初めて書くファイルや、ほかのアプリが書き換えたファイルは、まだ履歴に入っていないため。
     */
    void keepDiskVersion(Path path) {
        if (!Files.isRegularFile(path) || !(fileService.unknown(path) || fileService.changedOutside(path))) {
            return;
        }
        try {
            history.record(path, Files.readString(path, StandardCharsets.UTF_8), appSettings.getInt(AppSettings.HISTORY_LIMIT, 50) + 1);
        } catch (IOException ex) {
            // UTF-8 で読めないファイルなどは残せない（保存そのものは続ける）
        }
    }

    /** AI が文書を保存したときに呼ばれる（版の履歴に残し、サイドバーを最新にする）。 */
    void savedByAi(Path path, String content) {
        history.record(path, content, appSettings.getInt(AppSettings.HISTORY_LIMIT, 50));
        lastHistory.put(path.toAbsolutePath().normalize(), Instant.now());
        if (sidebar != null) {
            sidebar.refresh();
        }
    }

    private boolean confirmOverwrite(Path path) {
        if (!path.toFile().exists()) {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                path.getFileName() + " は既にあります。上書きしますか？",
                "上書きの確認", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return answer == JOptionPane.YES_OPTION;
    }

    /** 未保存の変更を捨ててよいか確認する。true なら、閉じてよい。 */
    private boolean confirmDiscard(EditorPane pane) {
        if (!pane.isDirty()) {
            return true;
        }
        // どの文書の話かわかるように、そのタブを表示してから聞く
        setActiveGroup(groupOf(pane));
        activeGroup.select(pane);
        int answer = JOptionPane.showConfirmDialog(this,
                "「" + pane.getTitle() + "」の変更を保存しますか？",
                "確認", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) {
            return save(pane);          // 保存に失敗、または保存をやめた場合は閉じない
        }
        return answer == JOptionPane.NO_OPTION;
    }

    private void closePane(EditorGroup group, EditorPane pane) {
        // 同じ文書をほかのタブでも映しているなら、そちらに中身が残るので、保存するかは聞かない
        if (pane == null || (pane.otherViews().isEmpty() && !confirmDiscard(pane))) {
            return;
        }
        group.removePane(pane);
        pane.unlinkView();
        tidyGroups();
    }

    /** 同じ文書を映しているほかのタブを閉じる（その文書を、別のタブに入れ替える前に呼ぶ）。 */
    private void closeOtherViews(EditorPane pane) {
        for (EditorPane other : pane.otherViews()) {
            groupOf(other).removePane(other);
            other.unlinkView();
        }
    }

    /**
     * 文書の設定（文字・折り返し・原稿用紙の表示）を、同じ文書を映しているタブ全部に同じように入れる。
     * ファイルに保存される設定なので、分割した片側だけ違うと、どちらで保存したかで中身が変わってしまう。
     */
    private void forEachView(EditorPane pane, java.util.function.Consumer<EditorPane> apply) {
        apply.accept(pane);
        for (EditorPane other : pane.otherViews()) {
            apply.accept(other);
        }
    }

    /** 開いている文書を1つずつ（同じ文書を2つのタブで映していても、1回だけ）。 */
    private List<EditorPane> documentPanes() {
        List<EditorPane> documents = new ArrayList<>();
        for (EditorPane pane : allPanes()) {
            if (!documents.contains(pane.firstView())) {
                documents.add(pane.firstView());
            }
        }
        return documents;
    }

    /** タブがなくなったグループを片付ける。 */
    private void tidyGroups() {
        if (group1.isEmpty() && !group2.isEmpty()) {
            for (EditorPane pane : new ArrayList<>(group2.getPanes())) {
                group2.removePane(pane);
                group1.addPane(pane);
            }
        }
        if (split && group2.isEmpty()) {
            split = false;
            layoutGroups();
        }
        if (!split) {
            setActiveGroup(group1);
        }
        if (group1.isEmpty()) {
            newDocument(EditorPane.Mode.TEXT);     // タブが1枚もなくなったら、新しい文書を出す
        }
        currentPaneChanged();
        withPane(pane -> SwingUtilities.invokeLater(pane::focusEditor));
    }

    private void exitApp() {
        for (EditorPane pane : documentPanes()) {
            if (!confirmDiscard(pane)) {
                return;                 // 1つでも「キャンセル」なら、終了をやめる
            }
        }
        saveSession();                  // 次に起動したとき、同じタブ・同じ位置から始められるように
        appSettings.save();
        recovery.clear();               // ふつうに終わったので、異常終了に備えた控えは要らない
        System.exit(0);
    }

    // ---------- AI 連携（MCP）から使う操作 ----------
    // AI から頼まれた操作（McpTools）が、ここを通してウィンドウを動かす。
    // どれも、確認のダイアログは出さない（AI が返事を待っている間、エディタが止まってしまうため）。
    // 必ず、画面のスレッド（EDT）から呼ぶこと。

    List<EditorPane> documents() {
        return documentPanes();
    }

    EditorPane activeDocument() {
        return currentPane();
    }

    /** そのファイルを開いているタブを探す。なければ null。 */
    EditorPane findOpenDocument(Path path) {
        for (EditorPane pane : allPanes()) {
            Path open = pane.getPath();
            if (open == null) {
                continue;
            }
            try {
                // 書き方が違っても同じファイルを指していることがあるので、文字の比較だけでなく、実体でも比べる
                if (open.equals(path) || (Files.exists(open) && Files.exists(path) && Files.isSameFile(open, path))) {
                    return pane;
                }
            } catch (IOException ex) {
                // 比べられないときは、別のファイルとして扱う
            }
        }
        return null;
    }

    /** その文書のタブを、手前に出す。 */
    void showDocument(EditorPane pane) {
        setActiveGroup(groupOf(pane));
        activeGroup.select(pane);
    }

    /** 起動するときに指定されたファイル（あとから起動されたぶんも）を開いて、ウィンドウを手前に出す。 */
    void openFiles(List<Path> files) {
        if (files.isEmpty()) {
            return;
        }
        List<Path> missing = new ArrayList<>();
        for (Path path : files) {
            if (!Files.isRegularFile(path)) {
                missing.add(path.toAbsolutePath().normalize());
                continue;
            }
            try {
                openPath(path, false, false);
            } catch (IOException ex) {
                flash(ex.getMessage());
            }
        }
        currentPaneChanged();
        if ((getExtendedState() & ICONIFIED) != 0) {
            setExtendedState(getExtendedState() & ~ICONIFIED);     // しまってあったら、元に戻す
        }
        toFront();
        requestFocus();
        withPane(pane -> SwingUtilities.invokeLater(pane::focusEditor));
        if (!missing.isEmpty()) {
            offerToCreate(missing);
        }
    }

    /**
     * 指定されたファイルがなかったとき、どこを探したのかを見せて、新しく作るかを聞く。
     * 下の欄の知らせだとすぐ消えて気づけないので、ダイアログにする。
     * 「はい」なら、その場所を保存先にした新しい文書を開く（保存したときに、ファイルができる）。
     */
    private void offerToCreate(List<Path> missing) {
        List<Path> creatable = new ArrayList<>();
        StringBuilder list = new StringBuilder();
        for (Path path : missing) {
            boolean canCreate = !Files.exists(path) && path.getParent() != null && Files.isDirectory(path.getParent());
            if (canCreate) {
                creatable.add(path);
            }
            list.append("・").append(path).append(canCreate ? "" : "（フォルダがないので作れません）").append("\n");
        }
        String message = "次のファイルが見つかりませんでした。\n\n" + list;
        if (creatable.isEmpty()) {
            JOptionPane.showMessageDialog(this, message + "\n名前と、今いるフォルダが合っているか確かめてください。",
                    "ファイルが見つかりません", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                message + "\n新しく作りますか？（保存したときに、この場所にファイルができます）",
                "ファイルが見つかりません", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        for (Path path : creatable) {
            createDocument(EditorPane.Mode.TEXT).setPath(path);
        }
        currentPaneChanged();
        withPane(pane -> SwingUtilities.invokeLater(pane::focusEditor));
    }

    EditorPane openDocument(Path path, boolean vertical) throws IOException {
        return openPath(path, vertical, vertical);
    }

    EditorPane createDocument(EditorPane.Mode mode) {
        EditorPane previous = currentPane();
        EditorPane pane = createPane(mode);
        addPane(activeGroup, pane);
        removeIfPristine(previous);
        return pane;
    }

    /** 確認をせずに、タブを閉じる（未保存かどうかは、呼ぶ側で確かめておくこと）。 */
    void closeDocumentSilently(EditorPane pane) {
        closeOtherViews(pane);
        groupOf(pane).removePane(pane);
        pane.unlinkView();
        tidyGroups();
    }

    void notifyUser(String text) {
        flash(text);
    }

    // ---------- AI 連携（MCP）の画面 ----------

    /** 前回、AI 連携を動かしたまま終了していたら、起動したときに自動で始める。 */
    /**
     * Windows の配布版で、ターミナルに「datum」と打って起動できるようにする。
     * 最初の1回だけ使う人に聞き、「はい」なら登録する。フォルダを移したときは、聞かずに登録し直す。
     */
    public void registerCommandIfNeeded() {
        Path dir = CommandRegistration.packageDir();
        if (dir == null || dir.toString().equals(appSettings.getString(AppSettings.COMMAND_DIR, null))) {
            return;                     // 配布版でない、または登録済み（Remove-Command.bat で消した場合も、足し直さない）
        }
        if (!appSettings.has(AppSettings.AUTO_COMMAND)) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "<html>ターミナル（コマンドプロンプトや PowerShell）で「datum」と打って、DATUM-Editor を起動できるようにしますか？<br>"
                            + "「datum 原稿.txt」と打つと、そのファイルを開けます。<br><br>"
                            + "このフォルダを、自分のユーザーの PATH に足します（管理者の権限は要りません）。<br>"
                            + "あとから「設定」で変えられます。足した分は Remove-Command.bat で消せます。</html>",
                    "datum コマンド", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            appSettings.put(AppSettings.AUTO_COMMAND, answer == JOptionPane.YES_OPTION);
            appSettings.save();
        }
        if (!appSettings.getBool(AppSettings.AUTO_COMMAND, false)) {
            return;
        }
        String previous = appSettings.getString(AppSettings.COMMAND_DIR, null);  // 前に登録したフォルダ（もう無ければ、PATH から外す）
        Thread thread = new Thread(() -> {
            if (CommandRegistration.register(dir, previous)) {
                SwingUtilities.invokeLater(() -> {
                    appSettings.put(AppSettings.COMMAND_DIR, dir.toString());
                    appSettings.save();
                    flash("「datum」コマンドを登録しました。新しく開いたターミナルで使えます");
                });
            }
        }, "datum-command");
        thread.setDaemon(true);
        thread.start();
    }

    public void startMcpIfEnabled() {
        if (!Edition.has(Edition.AI) || !mcp.settings().enabled) {
            return;
        }
        try {
            mcp.start();
            flash("AI 連携（MCP）を始めました");
        } catch (IOException ex) {
            flash("AI 連携を始められませんでした: " + ex.getMessage());
        }
        mcpItem.setSelected(mcp.isRunning());
        updateStatus();
    }

    private void toggleMcp() {
        if (!mcpItem.isSelected()) {
            mcp.stop();
            flash("AI 連携を止めました");
            return;
        }
        // 最初に、AI に触らせてよいフォルダを決めてもらう
        if (!mcp.hasWorkspace() && !chooseWorkspace()) {
            mcpItem.setSelected(false);
            return;
        }
        try {
            mcp.start();
        } catch (IOException ex) {
            mcpItem.setSelected(false);
            JOptionPane.showMessageDialog(this, "AI 連携を始められませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return;
        }
        updateStatus();
        showMcpHelp();
    }

    /** AI に読み書きを任せるフォルダを選ぶ。選ばれたら true。 */
    private boolean chooseWorkspace() {
        // 前に選んだフォルダがあればそこから、なければデスクトップから始める
        JFileChooser chooser = new JFileChooser(AppSettings.desktop().toFile());
        chooser.setDialogTitle("AI に任せるフォルダを選ぶ（AI は、この中のファイルだけを読み書きできます）");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setApproveButtonText("このフォルダにする");
        if (mcp.settings().workspace != null) {
            chooser.setCurrentDirectory(mcp.settings().workspace.toFile());
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return false;
        }
        Path folder = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        // ホームフォルダやドライブ全体のように広すぎる場所は、うっかり選んでいないかを確かめる
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        if (folder.equals(home) || folder.getNameCount() < 2) {
            int answer = JOptionPane.showConfirmDialog(this,
                    folder + "\nとても広い範囲です。この中のすべてのファイルを、AI が読み書きできるようになります。\n"
                            + "原稿を入れた専用のフォルダを選ぶことをおすすめします。このまま使いますか？",
                    "確認", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return false;
            }
        }
        try {
            mcp.setWorkspace(folder);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "フォルダを設定できませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        flash("AI に任せるフォルダ: " + mcp.settings().workspace);
        return true;
    }

    /** つなぎ方の案内を出す。登録に使うコマンドや設定を、ボタンでコピーできる。 */
    private void showMcpHelp() {
        JTextArea text = new JTextArea(mcp.describeConnection(), 22, 62);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setFont(Theme.small());
        text.setBackground(Theme.INPUT);
        text.setForeground(Theme.FG_STRONG);
        text.setCaretColor(Theme.FG_STRONG);
        text.setCaretPosition(0);
        Object[] options = {"Claude Code 用のコマンドをコピー", "Claude Desktop 用の設定をコピー", "閉じる"};
        int choice = JOptionPane.showOptionDialog(this, new JScrollPane(text), "AI 連携（MCP）の接続のしかた",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[2]);
        if (choice == 0 || choice == 1) {
            String copied = choice == 0 ? McpService.claudeCodeCommand() : McpService.claudeDesktopConfig();
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(copied), null);
            flash("コピーしました。" + (choice == 0 ? "ターミナルに貼り付けて実行してください" : "設定ファイルに貼り付けてください"));
        }
    }

    // ---------- 分割 ----------

    /** グループを画面に置き直す。分割中は2つを並べ、そうでなければ1つだけ置く。 */
    private void layoutGroups() {
        editorArea.removeAll();
        if (split) {
            JSplitPane splitPane = new JSplitPane(splitOrientation, group1, group2);
            splitPane.setUI(new BasicSplitPaneUI());
            splitPane.setBorder(null);
            splitPane.setBackground(Theme.BORDER);  // 境目の線の色になる
            splitPane.setDividerSize(4);
            splitPane.setResizeWeight(0.5);
            splitPane.setContinuousLayout(true);
            editorArea.add(splitPane, BorderLayout.CENTER);
            SwingUtilities.invokeLater(() -> splitPane.setDividerLocation(0.5));
        } else {
            editorArea.add(group1, BorderLayout.CENTER);
        }
        splitSideButton.setSelected(split && splitOrientation == JSplitPane.HORIZONTAL_SPLIT);
        splitDownButton.setSelected(split && splitOrientation == JSplitPane.VERTICAL_SPLIT);
        editorArea.revalidate();
        editorArea.repaint();
    }

    private void toggleSplit() {
        if (split) {
            // 分割をやめるときは、右（下）側のタブを左（上）側へ移す。
            // 左（上）にも同じ文書を映しているタブは、移さずに閉じる（同じ文書のタブが2枚並ばないように）
            for (EditorPane pane : new ArrayList<>(group2.getPanes())) {
                group2.removePane(pane);
                if (pane.otherViews().stream().anyMatch(group1.getPanes()::contains)) {
                    pane.unlinkView();
                } else {
                    group1.addPane(pane);
                }
            }
            tidyGroups();
            return;
        }
        // VSCode と同じように分ける：タブが2枚以上なら、今のタブを反対側へ移して2つの文書を並べる。
        // 1枚だけなら、同じ文書をもう1つのタブで映して並べる（どちらで書いても、両方に出る）
        EditorPane pane = currentPane();
        if (pane == null) {
            return;
        }
        if (activeGroup.getPanes().size() > 1) {
            moveTabToOtherGroup();
            return;
        }
        EditorPane view = pane.newView();
        view.setListener(this);
        stats.track(view);
        split = true;
        layoutGroups();
        setActiveGroup(group2);
        group2.addPane(view);
        currentPaneChanged();
        SwingUtilities.invokeLater(view::focusEditor);
    }

    /** ツールバーの「左右に分割」「上下に分割」。同じ向きで分割中なら、分割をやめる。 */
    private void splitTo(int orientation) {
        if (split && splitOrientation == orientation) {
            toggleSplit();
            return;
        }
        splitOrientation = orientation;
        if (split) {
            layoutGroups();
        } else {
            toggleSplit();
        }
        layoutGroups();                     // ボタンの押された状態を、今の分割に合わせる
    }

    private void toggleOrientation() {
        splitTo(splitOrientation == JSplitPane.HORIZONTAL_SPLIT ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT);
    }

    private void moveTabToOtherGroup() {
        EditorPane pane = currentPane();
        if (pane == null) {
            return;
        }
        EditorGroup from = activeGroup;
        EditorGroup to = (from == group1) ? group2 : group1;
        if (!split && from.getPanes().size() < 2) {
            flash("タブが1枚だけのときは移動できません（先に「画面を分割」をどうぞ）");
            return;
        }
        if (mergeInto(to, from, pane)) {
            return;
        }
        from.removePane(pane);
        if (!split) {
            split = true;
            layoutGroups();
        }
        to.addPane(pane);
        setActiveGroup(to);
        tidyGroups();
    }

    /**
     * 移す先のグループに、同じ文書を映すタブがもうあれば、移すタブは閉じて、そちらを表示する（同じ文書のタブが2枚並ばないように）。
     * そうしたら true。
     */
    private boolean mergeInto(EditorGroup to, EditorGroup from, EditorPane pane) {
        EditorPane existing = pane.otherViews().stream().filter(to.getPanes()::contains).findFirst().orElse(null);
        if (existing == null) {
            return false;
        }
        from.removePane(pane);
        pane.unlinkView();
        setActiveGroup(to);
        to.select(existing);
        tidyGroups();
        SwingUtilities.invokeLater(existing::focusEditor);
        return true;
    }

    // ---------- 書式 ----------

    /** 横書きと縦書きを入れ替える。文章・保存先・未保存の状態は引き継ぐ。 */
    private void switchWriting() {
        if (!Edition.has(Edition.VERTICAL)) {
            flash(Edition.notice(Edition.VERTICAL));
            return;
        }
        EditorPane old = currentPane();
        if (old == null || old.getMode() == EditorPane.Mode.BOARD) {
            flash("カードボードは、縦書き・横書きに切り替えられません");
            return;
        }
        EditorPane fresh;
        if (old.getMode() == EditorPane.Mode.TEXT) {
            VerticalTextPane vertical = new VerticalTextPane(old.getEditorFont());
            vertical.loadText(old.toPlainText());
            fresh = vertical;
        } else {
            TextEditorPane text = new TextEditorPane(old.getEditorFont());
            text.loadText(old.toPlainText());
            fresh = text;
        }
        fresh.setListener(this);
        fresh.setRawFile(old.isRawFile());
        fresh.setPath(old.getPath());
        fresh.setDirty(old.isDirty());
        stats.track(fresh);
        closeOtherViews(old);           // 分割して同じ文書を映していたら、もう片方は閉じる（縦と横を別々にしない）
        EditorGroup group = groupOf(old);
        group.replacePane(old, fresh);
        setActiveGroup(group);
        tidyGroups();
        SwingUtilities.invokeLater(fresh::focusEditor);
    }

    // ---------- ルビ ----------
    // 縦書きでは、ルビを文字の属性として持つ（VerticalTextPane に任せる）。
    // 横書きは文字だけを扱う画面なので、「小説家になろう」の記法を文章の中に書き込む。

    /** 今の文書が、文章（横書き・縦書き）のときだけ返す。カードボードなら、案内を出して null。 */
    private Searchable textTarget() {
        if (currentPane() instanceof Searchable s) {
            return s;
        }
        flash("ルビは、テキストと縦書きの文書で使えます");
        return null;
    }

    /** position を含んでいるルビを探す（前後の端に接している場合も含む）。 */
    private static NarouRuby.Span rubyAt(String text, int position) {
        for (NarouRuby.Span span : NarouRuby.parse(text)) {
            if (span.start() <= position && position <= span.end()) {
                return span;
            }
        }
        return null;
    }

    /** 選んだ文字にルビを振る。何も選ばずにルビの上で実行したときは、そのルビを書き直す。 */
    private void editRuby() {
        if (currentPane() instanceof VerticalTextPane vertical) {
            vertical.beginRubyEdit();   // 縦書きでは、文字のすぐ横に入力欄を出す
            return;
        }
        Searchable target = textTarget();
        if (target == null) {
            return;
        }
        String text = target.getText();
        int start = target.getSelectionStart();
        int end = target.getSelectionEnd();
        String current = "";
        if (start == end) {
            NarouRuby.Span span = rubyAt(text, start);
            if (span == null) {
                flash("ルビを振る文字を選んでから、もう一度どうぞ");
                return;
            }
            start = span.start();
            end = span.end();
            current = text.substring(span.rubyStart(), span.rubyEnd());
        }
        String base = NarouRuby.strip(text.substring(start, end));     // 元から付いていたルビは、いったん外す
        if (base.isBlank() || base.contains("\n")) {
            flash("ルビは、1行の中の文字に振れます");
            return;
        }
        if (base.length() > NarouRuby.MAX_LENGTH) {
            flash("ルビを振れるのは " + NarouRuby.MAX_LENGTH + " 文字までです（小説家になろうの決まり）");
            return;
        }
        if (!NarouRuby.fits(base, "あ")) {
            flash("｜ 《 》 （ ( を含む文字には、ルビを振れません");
            return;
        }
        Object answer = JOptionPane.showInputDialog(this, "「" + base + "」のふりがな（空にすると、ルビを外します）",
                "ルビを振る", JOptionPane.PLAIN_MESSAGE, null, null, current);
        currentPane().focusEditor();
        if (answer == null) {
            return;                     // キャンセル
        }
        String ruby = answer.toString().strip();
        if (!ruby.isEmpty() && !NarouRuby.fits(base, ruby)) {
            flash("ふりがなは " + NarouRuby.MAX_LENGTH + " 文字までで、｜ 《 》 （ ( は使えません");
            return;
        }
        target.replaceRange(ruby.isEmpty() ? base : NarouRuby.format(base, ruby), start, end);
    }

    /** 選んだ文字に、1文字ずつ傍点（・）を付ける。 */
    private void addEmphasis() {
        if (currentPane() instanceof VerticalTextPane vertical) {
            vertical.emphasizeSelection();
            return;
        }
        Searchable target = textTarget();
        if (target == null) {
            return;
        }
        int start = target.getSelectionStart();
        int end = target.getSelectionEnd();
        if (start == end) {
            flash("傍点を付ける文字を選んでから、もう一度どうぞ");
            return;
        }
        String base = NarouRuby.strip(target.getText().substring(start, end));
        target.replaceRange(NarouRuby.emphasize(base), start, end);
    }

    /** 選んだ範囲（選んでいなければ、カーソルのあるルビ）から、ルビと傍点を外す。 */
    private void removeRuby() {
        if (currentPane() instanceof VerticalTextPane vertical) {
            vertical.removeRubyAtSelection();
            return;
        }
        Searchable target = textTarget();
        if (target == null) {
            return;
        }
        String text = target.getText();
        int start = target.getSelectionStart();
        int end = target.getSelectionEnd();
        if (start == end) {
            NarouRuby.Span span = rubyAt(text, start);
            if (span == null) {
                flash("外すルビが、カーソルの位置にありません");
                return;
            }
            start = span.start();
            end = span.end();
        }
        String original = text.substring(start, end);
        String stripped = NarouRuby.strip(original);
        if (!stripped.equals(original)) {
            target.replaceRange(stripped, start, end);
        }
    }

    private void zoom(int direction) {
        EditorPane pane = currentPane();
        if (pane == null) {
            return;
        }
        if (pane instanceof VerticalTextPane vertical && vertical.isManuscript()) {
            flash("原稿用紙では、マスの大きさはウィンドウの高さに合わせて決まります（大きさを変えるには、原稿用紙の表示を切ってください）");
            return;
        }
        Font font = pane.getEditorFont();
        int step = Math.max(2, font.getSize() / 8);
        int size = Math.max(10, Math.min(160, font.getSize() + direction * step));
        forEachView(pane, view -> view.setEditorFont(font.deriveFont((float) size)));
        flash("文字の大きさ: " + size);
    }

    private void chooseFont() {
        EditorPane pane = currentPane();
        if (pane == null) {
            return;
        }
        Font current = pane.getEditorFont();

        // Java がどの環境でも持っている名前と、このパソコンに入っている日本語フォントを候補にする
        Set<String> names = new LinkedHashSet<>(List.of("Monospaced", "SansSerif", "Serif", "Dialog"));
        Set<String> installed = new LinkedHashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String candidate : new String[]{"Noto Sans CJK JP", "Noto Serif CJK JP", "Noto Sans Mono CJK JP",
            "IPAGothic", "IPAMincho", "Yu Gothic", "Yu Mincho", "MS Gothic", "MS Mincho", "Hiragino Sans"}) {
            if (installed.contains(candidate)) {
                names.add(candidate);
            }
        }
        names.add(current.getFamily());

        Set<Integer> sizes = new java.util.TreeSet<>(List.of(14, 18, 24, 32, 40, 48, 64, 80));
        sizes.add(current.getSize());

        JComboBox<String> nameBox = new JComboBox<>(names.toArray(new String[0]));
        nameBox.setSelectedItem(current.getFamily());
        JComboBox<Integer> sizeBox = new JComboBox<>(sizes.toArray(new Integer[0]));
        sizeBox.setSelectedItem(current.getSize());

        JPanel panel = new JPanel();
        panel.add(new JLabel("フォント:"));
        panel.add(nameBox);
        panel.add(new JLabel("サイズ:"));
        panel.add(sizeBox);

        int result = JOptionPane.showConfirmDialog(this, panel, "フォント",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            Font chosen = new Font((String) nameBox.getSelectedItem(), Font.PLAIN, (Integer) sizeBox.getSelectedItem());
            forEachView(pane, view -> view.setEditorFont(chosen));
        }
        pane.focusEditor();
    }
}
