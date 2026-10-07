package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicSplitPaneUI;

/**
 * メインウィンドウ。
 *
 * 画面の構成（上から）：メニュー → 検索バー → タブ付きのエディタ（1つ、または分割して2つ） → ステータスバー
 * 文書そのものの処理は EditorPane の各クラスに任せ、ここでは
 * 「どの文書に対して、何をするか」（開く・保存・閉じる・分割など）を受け持つ。
 */
public class EditorFrame extends JFrame implements EditorPane.Listener, EditorGroup.Host {

    private static final String APP_NAME = "DATUM-Editor";
    private static final int CTRL = InputEvent.CTRL_DOWN_MASK;
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;

    private final FileService fileService = new FileService();

    private final EditorGroup group1 = new EditorGroup(this);
    private final EditorGroup group2 = new EditorGroup(this);
    private EditorGroup activeGroup = group1;
    private boolean split = false;
    private int splitOrientation = JSplitPane.HORIZONTAL_SPLIT;
    private final JPanel editorArea = new JPanel(new BorderLayout());

    private final FindBar findBar = new FindBar(this::currentPane);
    private final JLabel statusLeft = new JLabel(" ");
    private final JLabel statusRight = new JLabel(" ");
    private final JCheckBoxMenuItem wrapItem = new JCheckBoxMenuItem("折り返し");
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

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(Theme.BG);
        center.add(findBar, BorderLayout.NORTH);
        editorArea.setBackground(Theme.BG);
        center.add(editorArea, BorderLayout.CENTER);

        getContentPane().setBackground(Theme.BG);
        add(center, BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);
        setJMenuBar(createMenuBar());
        installExtraShortcuts();

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
        bar.add(statusRight, BorderLayout.EAST);
        return bar;
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu file = new JMenu("ファイル");
        file.add(item("新規（テキスト）", key(KeyEvent.VK_N, CTRL), () -> newDocument(EditorPane.Mode.TEXT)));
        file.add(item("新規（縦書き）", key(KeyEvent.VK_N, CTRL | SHIFT), () -> newDocument(EditorPane.Mode.VERTICAL)));
        file.add(item("新規（KJ法ボード）", key(KeyEvent.VK_K, CTRL | SHIFT), () -> newDocument(EditorPane.Mode.KJ)));
        file.addSeparator();
        file.add(item("開く…", key(KeyEvent.VK_O, CTRL), this::openFile));
        file.add(item("保存", key(KeyEvent.VK_S, CTRL), () -> save(currentPane())));
        file.add(item("名前を付けて保存…", key(KeyEvent.VK_S, CTRL | SHIFT), () -> saveAs(currentPane())));
        file.add(item("PDF として書き出す…", key(KeyEvent.VK_P, CTRL | SHIFT), this::exportPdf));
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

        JMenu format = new JMenu("書式");
        format.add(item("フォント…", null, this::chooseFont));
        format.add(item("文字を大きく", key(KeyEvent.VK_SEMICOLON, CTRL), () -> zoom(1)));
        format.add(item("文字を小さく", key(KeyEvent.VK_MINUS, CTRL), () -> zoom(-1)));
        format.addSeparator();
        wrapItem.addActionListener(e -> {
            if (currentPane() instanceof TextEditorPane text) {
                text.setWrap(wrapItem.isSelected());
            } else {
                wrapItem.setSelected(false);
                flash("折り返しは、テキスト（横書き）の文書で使えます");
            }
        });
        format.add(wrapItem);
        format.add(item("横書き ⇔ 縦書き を切り替え", key(KeyEvent.VK_T, CTRL | SHIFT), this::switchWriting));

        JMenu view = new JMenu("表示");
        view.add(item("画面を分割 / 分割をやめる", key(KeyEvent.VK_BACK_SLASH, CTRL), this::toggleSplit));
        view.add(item("分割の向きを切り替え（左右 ⇔ 上下）", null, this::toggleOrientation));
        view.add(item("タブを反対側へ移動", key(KeyEvent.VK_M, CTRL | SHIFT), this::moveTabToOtherGroup));
        view.addSeparator();
        view.add(item("次のタブ", key(KeyEvent.VK_PAGE_DOWN, CTRL), () -> activeGroup.selectNext(1)));
        view.add(item("前のタブ", key(KeyEvent.VK_PAGE_UP, CTRL), () -> activeGroup.selectNext(-1)));
        view.addSeparator();
        JCheckBoxMenuItem stripeItem = new JCheckBoxMenuItem("行の縞模様", Theme.stripes);
        stripeItem.addActionListener(e -> {
            Theme.stripes = stripeItem.isSelected();
            repaint();
        });
        view.add(stripeItem);

        // KJ法ボード専用の操作。ほかの種類の文書を開いているときは、案内を出すだけにする
        JMenu boardMenu = new JMenu("ボード");
        boardMenu.add(item("カードを一括追加…", key(KeyEvent.VK_B, CTRL | SHIFT), this::bulkAddCards));
        boardMenu.add(item("複製", key(KeyEvent.VK_D, CTRL), () -> withBoard(KjPane::duplicateSelected)));
        boardMenu.add(item("削除", null, () -> withBoard(KjPane::deleteSelected)));
        boardMenu.addSeparator();
        boardMenu.add(item("選んだカードをグループ枠で囲む", key(KeyEvent.VK_G, CTRL), () -> withBoard(KjPane::groupSelected)));
        boardMenu.add(item("整列", key(KeyEvent.VK_L, CTRL | SHIFT), () -> withBoard(KjPane::tidy)));
        boardMenu.add(item("色ごとに並べる", null, () -> withBoard(KjPane::arrangeByColor)));
        boardMenu.addSeparator();
        boardMenu.add(item("投票する（+1）", key(KeyEvent.VK_UP, CTRL | SHIFT), () -> withBoard(board -> board.vote(1))));
        boardMenu.add(item("投票を取り消す（−1）", key(KeyEvent.VK_DOWN, CTRL | SHIFT), () -> withBoard(board -> board.vote(-1))));
        boardMenu.addSeparator();
        boardMenu.add(item("拡大", null, () -> withBoard(board -> board.zoomBy(1.25))));
        boardMenu.add(item("縮小", null, () -> withBoard(board -> board.zoomBy(0.8))));
        boardMenu.add(item("全体を表示", key(KeyEvent.VK_0, CTRL), () -> withBoard(KjPane::fitAll)));
        boardMenu.addSeparator();
        boardMenu.add(item("画像として書き出す（PNG）…", key(KeyEvent.VK_E, CTRL | SHIFT), this::exportBoardImage));

        menuBar.add(file);
        menuBar.add(edit);
        menuBar.add(format);
        menuBar.add(view);
        menuBar.add(boardMenu);
        return menuBar;
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

    /** メニューには1つしかキーを付けられないので、別のキーでも動くようにする。 */
    private void installExtraShortcuts() {
        shortcut(key(KeyEvent.VK_Z, CTRL | SHIFT), "redo2", () -> withPane(EditorPane::redo));
        shortcut(key(KeyEvent.VK_SEMICOLON, CTRL | SHIFT), "zoomIn2", () -> zoom(1));   // JIS 配列の「+」
        shortcut(key(KeyEvent.VK_EQUALS, CTRL), "zoomIn3", () -> zoom(1));               // US 配列の「+」
        shortcut(key(KeyEvent.VK_ADD, CTRL), "zoomIn4", () -> zoom(1));                  // テンキー
        shortcut(key(KeyEvent.VK_SUBTRACT, CTRL), "zoomOut2", () -> zoom(-1));
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

    /** 今の文書が KJ法ボードのときだけ、操作を実行する。 */
    private void withBoard(java.util.function.Consumer<KjPane> action) {
        if (currentPane() instanceof KjPane board) {
            action.accept(board);
            updateStatus();
        } else {
            flash("この操作は、KJ法ボードで使えます（ファイル → 新規（KJ法ボード））");
        }
    }

    /** 複数行をまとめて入力し、1行につき1枚のカードにする。 */
    private void bulkAddCards() {
        if (!(currentPane() instanceof KjPane board)) {
            flash("この操作は、KJ法ボードで使えます（ファイル → 新規（KJ法ボード））");
            return;
        }
        JTextArea input = new JTextArea(10, 30);
        input.setFont(Theme.ui());
        input.setBackground(Theme.INPUT);
        input.setForeground(Color.WHITE);
        input.setCaretColor(Color.WHITE);
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

    /** KJ法ボードを、PNG 画像のファイルに書き出す。 */
    private void exportBoardImage() {
        if (!(currentPane() instanceof KjPane board)) {
            flash("この操作は、KJ法ボードで使えます（ファイル → 新規（KJ法ボード））");
            return;
        }
        BufferedImage image = board.renderImage();
        if (image == null) {
            flash("カードがないので、書き出すものがありません");
            return;
        }
        JFileChooser chooser = new JFileChooser();
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
        findBar.paneSwitched();
    }

    private void updateTitle() {
        EditorPane pane = currentPane();
        if (pane == null) {
            setTitle(APP_NAME);
        } else {
            setTitle(pane.getTitle() + (pane.isDirty() ? " ●" : "") + " - " + APP_NAME);
        }
    }

    private void updateStatus() {
        EditorPane pane = currentPane();
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
        statusRight.setText(pane.statusText() + "    UTF-8    " + format);
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
        findBar.contentChanged(pane);
    }

    @Override
    public void titleChanged(EditorPane pane) {
        groupOf(pane).refreshTabs();
        if (pane == currentPane()) {
            updateTitle();
        }
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

    @Override
    public void newRequested(EditorGroup group) {
        setActiveGroup(group);
        newDocument(EditorPane.Mode.TEXT);
    }

    // ---------- 新規・開く・保存・閉じる ----------

    private EditorPane createPane(EditorPane.Mode mode) {
        return switch (mode) {
            case TEXT -> new TextEditorPane(Theme.editorFont());
            case VERTICAL -> new VerticalTextPane(Theme.editorFont());
            case KJ -> new KjPane();
        };
    }

    private void addPane(EditorGroup group, EditorPane pane) {
        pane.setListener(this);
        group.addPane(pane);
        SwingUtilities.invokeLater(pane::focusEditor);
    }

    private void newDocument(EditorPane.Mode mode) {
        addPane(activeGroup, createPane(mode));
    }

    private void openFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("テキスト / JSON (*.txt, *.json)", "txt", "json"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath().toAbsolutePath();

        // すでに開いているファイルなら、そのタブを表示するだけにする
        for (EditorPane open : allPanes()) {
            if (path.equals(open.getPath())) {
                setActiveGroup(groupOf(open));
                activeGroup.select(open);
                open.focusEditor();
                return;
            }
        }

        try {
            EditorPane pane = paneFromFile(path, fileService.read(path));
            EditorPane previous = currentPane();
            pane.setPath(path);
            addPane(activeGroup, pane);
            // 何も書いていない「無題」のタブが残っていたら、片付ける
            if (previous != null && previous.getPath() == null && !previous.isDirty() && previous.isEmpty()) {
                activeGroup.removePane(previous);
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "開けませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** ファイルの中身から、合う種類の文書を作る。 */
    @SuppressWarnings("unchecked")
    private EditorPane paneFromFile(Path path, String content) {
        if (isJsonPath(path)) {
            try {
                if (Json.parse(content) instanceof Map<?, ?> raw
                        && EditorPane.FORMAT_NAME.equals(raw.get("format"))) {
                    Map<String, Object> json = (Map<String, Object>) raw;
                    return switch (EditorPane.Mode.fromId(Json.str(json, "mode", "text"))) {
                        case TEXT -> TextEditorPane.fromJson(json);
                        case VERTICAL -> VerticalTextPane.fromJson(json);
                        case KJ -> KjPane.fromJson(json);
                    };
                }
            } catch (Json.JsonException ex) {
                flash("JSON として読めなかったので、テキストとして開きました");
            }
        }
        // このアプリの形式ではないファイルは、中身をそのままテキストとして開く
        TextEditorPane pane = new TextEditorPane(Theme.editorFont());
        pane.loadText(content);
        pane.setRawFile(isJsonPath(path));
        return pane;
    }

    private static boolean isJsonPath(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".json");
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
        JFileChooser chooser = new JFileChooser();
        FileNameExtensionFilter txtFilter = new FileNameExtensionFilter("テキスト (*.txt)", "txt");
        FileNameExtensionFilter jsonFilter = new FileNameExtensionFilter("JSON：設定や配置も保存 (*.json)", "json");
        chooser.setAcceptAllFileFilterUsed(false);
        boolean kj = pane.getMode() == EditorPane.Mode.KJ;
        if (!kj) {
            chooser.addChoosableFileFilter(txtFilter);     // KJ法はカードの位置があるので、JSON だけにする
        }
        chooser.addChoosableFileFilter(jsonFilter);
        boolean preferJson = kj || (pane.getPath() != null && isJsonPath(pane.getPath()) && !pane.isRawFile());
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
        if (!path.equals(pane.getPath()) && !confirmOverwrite(path)) {
            return false;
        }

        boolean wasRaw = pane.isRawFile();
        pane.setRawFile(false);         // 自分で形式を選んだので、このアプリの形式で保存する
        if (write(pane, path)) {
            pane.setPath(path);
            updateStatus();
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
        if (pane.getMode() == EditorPane.Mode.KJ && pane.isEmpty()) {
            flash("カードがないので、書き出すものがありません");
            return;
        }
        JFileChooser chooser = new JFileChooser();
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
        try {
            int pages;
            try (PdfWriter writer = new PdfWriter(path)) {
                pane.exportPdf(writer);
                pages = writer.getPageCount();
            }
            flash("PDF を書き出しました（" + pages + " ページ）: " + path);
        } catch (IOException ex) {
            try {
                java.nio.file.Files.deleteIfExists(path);   // 途中までしか書けていない PDF は残さない
            } catch (IOException ignored) {
                // 消せなくても、下のダイアログで失敗は伝わる
            }
            JOptionPane.showMessageDialog(this, "書き出せませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        } finally {
            setCursor(java.awt.Cursor.getDefaultCursor());
        }
    }

    /** 実際にファイルへ書く。拡張子が .json なら JSON、それ以外は本文だけを書く。 */
    private boolean write(EditorPane pane, Path path) {
        String content = isJsonPath(path) && !pane.isRawFile()
                ? Json.write(pane.toJson())
                : pane.toPlainText();
        try {
            fileService.save(content, path);
            pane.setDirty(false);
            flash("保存しました: " + path);
            return true;
        } catch (IOException ex) {
            // 失敗は握りつぶさず、ダイアログで知らせる
            JOptionPane.showMessageDialog(this, "保存できませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
            return false;
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
        if (pane == null || !confirmDiscard(pane)) {
            return;
        }
        group.removePane(pane);
        tidyGroups();
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
        for (EditorPane pane : allPanes()) {
            if (!confirmDiscard(pane)) {
                return;                 // 1つでも「キャンセル」なら、終了をやめる
            }
        }
        System.exit(0);
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
        editorArea.revalidate();
        editorArea.repaint();
    }

    private void toggleSplit() {
        if (split) {
            // 分割をやめるときは、右（下）側のタブを左（上）側へ移す
            for (EditorPane pane : new ArrayList<>(group2.getPanes())) {
                group2.removePane(pane);
                group1.addPane(pane);
            }
            tidyGroups();
            return;
        }
        split = true;
        layoutGroups();
        setActiveGroup(group2);
        if (group2.isEmpty()) {
            newDocument(EditorPane.Mode.TEXT);
        }
    }

    private void toggleOrientation() {
        splitOrientation = (splitOrientation == JSplitPane.HORIZONTAL_SPLIT)
                ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT;
        if (split) {
            layoutGroups();
        } else {
            toggleSplit();
        }
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
        from.removePane(pane);
        if (!split) {
            split = true;
            layoutGroups();
        }
        to.addPane(pane);
        setActiveGroup(to);
        tidyGroups();
    }

    // ---------- 書式 ----------

    /** 横書きと縦書きを入れ替える。文章・保存先・未保存の状態は引き継ぐ。 */
    private void switchWriting() {
        EditorPane old = currentPane();
        if (old == null || old.getMode() == EditorPane.Mode.KJ) {
            flash("KJ法ボードは、縦書き・横書きに切り替えられません");
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
        activeGroup.replacePane(old, fresh);
        SwingUtilities.invokeLater(fresh::focusEditor);
    }

    private void zoom(int direction) {
        EditorPane pane = currentPane();
        if (pane == null) {
            return;
        }
        Font font = pane.getEditorFont();
        int step = Math.max(2, font.getSize() / 8);
        int size = Math.max(10, Math.min(160, font.getSize() + direction * step));
        pane.setEditorFont(font.deriveFont((float) size));
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
            pane.setEditorFont(new Font((String) nameBox.getSelectedItem(), Font.PLAIN,
                    (Integer) sizeBox.getSelectedItem()));
        }
        pane.focusEditor();
    }
}
