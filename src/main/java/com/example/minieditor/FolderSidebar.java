package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/**
 * 画面の左に出す、フォルダのサイドバー。原稿のフォルダの中身を木の形で表示し、クリックでファイルを開く。
 *
 * 長編を章ごとのファイルに分けて書くときに使う。並びは名前の順だが、数字は数として比べるので
 * 「第2章」は「第10章」より前に来る（ファイル名の先頭に 01_、02_ と付けると、好きな順に並べられる）。
 */
public final class FolderSidebar extends JPanel {

    /** サイドバーの操作を、ウィンドウ側へ伝える窓口。 */
    public interface Host {
        void openFromSidebar(Path file);

        /** 名前を変えた・移したとき（開いているタブの保存先を合わせるため）。 */
        void fileMoved(Path from, Path to);

        /** ごみ箱へ移したとき。 */
        void fileTrashed(Path file);

        /** フォルダの文章をまとめた結果を、新しいタブで開く。 */
        void openCombined(String title, String text);

        /** フォルダを選ぶ画面を出す。選ばれなければ null。 */
        Path chooseFolder();

        void folderChanged(Path folder);

        void message(String text);

        /** いま開いているタブの種類（縦書きなど）。開いていなければ null。 */
        EditorPane.Mode openMode(Path file);
    }

    private static final String TRASH = ".datum-trash";
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "json", "md", "text");

    private final Host host;
    private final JLabel title = new JLabel("フォルダ");
    private final DefaultTreeModel model = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final JTree tree = new JTree(model);
    private Path root;

    public FolderSidebar(Host host, Runnable onClose) {
        super(new BorderLayout());
        this.host = host;
        setBackground(Theme.PANEL);

        title.setFont(Theme.small());
        title.setForeground(Theme.FG);
        JButton open = Theme.flatButton("開く…");
        open.setToolTipText("表示するフォルダを選ぶ");
        open.addActionListener(e -> {
            Path folder = host.chooseFolder();
            if (folder != null) {
                setRoot(folder);
                host.folderChanged(folder);
            }
        });
        JButton reload = Theme.flatButton("↻");
        reload.setToolTipText("表示を最新にする");
        reload.addActionListener(e -> refresh());
        JButton close = Theme.flatButton("×");
        close.setToolTipText("サイドバーを隠す");
        close.addActionListener(e -> onClose.run());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        for (JButton button : new JButton[]{open, reload, close}) {
            button.setFocusable(false);
            buttons.add(button);
        }
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.PANEL);
        header.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 6));
        header.add(title, BorderLayout.CENTER);
        header.add(buttons, BorderLayout.EAST);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setBackground(Theme.PANEL);
        tree.setForeground(Theme.FG_DIM);   // 開閉の印（＞ ∨）の色
        tree.setFont(Theme.small());
        tree.setRowHeight(0);
        tree.setToggleClickCount(1);
        tree.setCellRenderer(new Renderer());
        // 開閉の印を、見やすい太めの ＞（閉じている）と ∨（開いている）にする
        if (tree.getUI() instanceof javax.swing.plaf.basic.BasicTreeUI ui) {
            ui.setCollapsedIcon(new Chevron(false));
            ui.setExpandedIcon(new Chevron(true));
        }
        // フォルダを開いたときに、はじめて中身を読む（大きなフォルダでも待たされないように）
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                loadChildren(node);
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    tree.setSelectionPath(path);
                }
                if (SwingUtilities.isRightMouseButton(e)) {
                    showMenu(e.getX(), e.getY(), path == null ? null : fileOf(path));
                } else if (path != null && SwingUtilities.isLeftMouseButton(e)) {
                    Path file = fileOf(path);
                    if (file != null && Files.isRegularFile(file)) {
                        if (isText(file)) {
                            host.openFromSidebar(file);
                        } else if (isGoogleFile(file)) {
                            // Google ドキュメントなどは、本文が PC になくリンクだけなので、ショートカットのようにブラウザで開く。
                            // うっかり開かないように、ダブルクリックのときだけにする
                            if (e.getClickCount() == 2) {
                                host.message(openGoogleFile(file));
                            } else {
                                host.message("Google のファイルです。ダブルクリックでブラウザで開きます");
                            }
                        } else {
                            host.message("このエディタで開けるのは、テキスト（.txt など）と JSON です");
                        }
                    }
                }
            }
        });
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        scroll.getViewport().setBackground(Theme.PANEL);

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        showEmpty();
    }

    public Path getRoot() {
        return root;
    }

    /** 表示するフォルダを決める。 */
    public void setRoot(Path folder) {
        root = folder == null ? null : folder.toAbsolutePath().normalize();
        refresh();
    }

    /** 中身を読み直す（開いていたフォルダは、開いたままにする）。 */
    public void refresh() {
        if (root == null || !Files.isDirectory(root)) {
            root = null;
            showEmpty();
            return;
        }
        // フォルダの名前は、木の最上段（根）に出す。上の見出しは「フォルダ」のまま
        tree.setRootVisible(true);
        Set<Path> expanded = new HashSet<>();
        Enumeration<TreePath> open = tree.getExpandedDescendants(new TreePath(model.getRoot()));
        while (open != null && open.hasMoreElements()) {
            Path file = fileOf(open.nextElement());
            if (file != null) {
                expanded.add(file);
            }
        }
        DefaultMutableTreeNode top = new DefaultMutableTreeNode(root);
        loadChildren(top);
        model.setRoot(top);
        tree.expandPath(new TreePath(top));
        reopen(top, expanded);
    }

    private void reopen(DefaultMutableTreeNode node, Set<Path> expanded) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            if (child.getUserObject() instanceof Path p && expanded.contains(p)) {
                loadChildren(child);
                tree.expandPath(new TreePath(child.getPath()));
                reopen(child, expanded);
            }
        }
    }

    private void showEmpty() {
        tree.setRootVisible(false);
        DefaultMutableTreeNode top = new DefaultMutableTreeNode();
        top.add(new DefaultMutableTreeNode("（「開く…」でフォルダを選んでください）"));
        model.setRoot(top);
    }

    /** フォルダの中身を読んで、子として並べる。まだ読んでいないフォルダには、仮の子を1つ入れておく（開く印を出すため）。 */
    private void loadChildren(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof Path folder) || !Files.isDirectory(folder)) {
            return;
        }
        if (node.getChildCount() > 0 && !(((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject() instanceof String)) {
            return;                     // もう読んである
        }
        node.removeAllChildren();
        for (Path child : listSorted(folder)) {
            DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child);
            if (Files.isDirectory(child)) {
                childNode.add(new DefaultMutableTreeNode("…"));
            }
            node.add(childNode);
        }
        model.nodeStructureChanged(node);
    }

    /** フォルダの中身を、フォルダが先・数字は数として比べた名前の順に並べて返す。隠しファイルとごみ箱は除く。 */
    static List<Path> listSorted(Path folder) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (!name.startsWith(".")) {
                    children.add(child);
                }
            }
        } catch (IOException ex) {
            return children;
        }
        children.sort(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString(), FolderSidebar::naturalCompare));
        return children;
    }

    /** 名前を比べる。数字（全角も）の部分は数として比べるので、「2」は「10」より前になる。 */
    static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                long na = parseDigits(a.substring(si, i));
                long nb = parseDigits(b.substring(sj, j));
                if (na != nb) {
                    return Long.compare(na, nb);
                }
            } else {
                if (ca != cb) {
                    return Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                }
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static long parseDigits(String digits) {
        long value = 0;
        for (int k = 0; k < digits.length() && k < 18; k++) {
            value = value * 10 + Character.digit(digits.charAt(k), 10);
        }
        return value;
    }

    static boolean isText(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    // ---------- Google ドキュメントなど（ショートカットとして扱う） ----------

    /** 拡張子 → Google のサービスの URL の一部。 */
    private static final java.util.Map<String, String> GOOGLE_KINDS = java.util.Map.of(
            "gdoc", "document",
            "gsheet", "spreadsheets",
            "gslides", "presentation",
            "gform", "forms",
            "gdraw", "drawings");

    /** Google ドライブ パソコン版が作る、Google ドキュメントなどへのリンクのファイルか。 */
    static boolean isGoogleFile(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && GOOGLE_KINDS.containsKey(name.substring(dot + 1).toLowerCase());
    }

    /**
     * リンクのファイルから、ブラウザで開く URL を作る。読めなければ null。
     * 中身は小さな JSON で、古いアプリは "url"、今のアプリは "doc_id"（文書の番号）を書いている。
     */
    static String googleUrl(Path file) {
        try {
            if (Files.size(file) > 100_000
                    || !(Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof java.util.Map<?, ?> json)) {
                return null;
            }
            if (json.get("url") instanceof String url && url.startsWith("https://")) {
                return url;
            }
            if (json.get("doc_id") instanceof String id && id.matches("[A-Za-z0-9_-]+")) {
                String name = file.getFileName().toString();
                String kind = GOOGLE_KINDS.get(name.substring(name.lastIndexOf('.') + 1).toLowerCase());
                String url = "https://docs.google.com/" + kind + "/d/" + id + "/edit";
                if (json.get("email") instanceof String email && !email.isBlank()) {
                    // Google に複数のアカウントでログインしていても、この文書の持ち主のアカウントで開く
                    url += "?authuser=" + java.net.URLEncoder.encode(email, StandardCharsets.UTF_8);
                }
                return url;
            }
        } catch (IOException | Json.JsonException | RuntimeException ex) {
            // 下で null を返す
        }
        return null;
    }

    /** ブラウザで開く（サイドバーと「開く」の両方から使う）。結果を知らせる文を返す。 */
    static String openGoogleFile(Path file) {
        String url = googleUrl(file);
        try {
            java.awt.Desktop desktop = java.awt.Desktop.isDesktopSupported() ? java.awt.Desktop.getDesktop() : null;
            if (url != null && desktop != null && desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                desktop.browse(java.net.URI.create(url));
            } else if (url != null && !System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("xdg-open", url).start();
            } else if (desktop != null && desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                desktop.open(file.toFile());    // 中身が読めなくても、Google ドライブのアプリに任せれば開ける
            } else {
                return "このファイルを開くための URL が見つかりませんでした";
            }
            return "ブラウザで開きました（編集は Google の画面で行います）";
        } catch (IOException | RuntimeException ex) {
            return "ブラウザで開けませんでした: " + ex.getMessage();
        }
    }

    private static Path fileOf(TreePath path) {
        Object last = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return last instanceof Path p ? p : null;
    }

    // ---------- 右クリックのメニュー ----------

    private void showMenu(int x, int y, Path target) {
        if (root == null) {
            return;
        }
        Path folder = target == null ? root : (Files.isDirectory(target) ? target : target.getParent());
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("新しいテキストファイル…", () -> createFile(folder)));
        if (Edition.has(Edition.BOARD)) {
            menu.add(menuItem("新しいカードボード（KJ法風のアイデア整理）…", () -> createBoard(folder)));
        }
        menu.add(menuItem("新しいフォルダ…", () -> createFolder(folder)));
        if (target != null && !target.equals(root)) {      // 最上段のフォルダ自身は、名前を変えない・捨てない
            menu.addSeparator();
            menu.add(menuItem("名前を変える…", () -> rename(target)));
            menu.add(menuItem("ごみ箱へ移す", () -> trash(target)));
        }
        menu.addSeparator();
        menu.add(menuItem("このフォルダの文章を1つにまとめる", () -> combine(folder)));
        menu.add(menuItem("表示を最新にする", this::refresh));
        menu.show(tree, x, y);
    }

    private static JMenuItem menuItem(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }

    private String ask(String message, String initial) {
        Object answer = JOptionPane.showInputDialog(this, message, "フォルダ", JOptionPane.PLAIN_MESSAGE, null, null, initial);
        if (answer == null || answer.toString().isBlank()) {
            return null;
        }
        String name = answer.toString().strip();
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            host.message("名前に / や \\ は使えません");
            return null;
        }
        return name;
    }

    private void createFile(Path folder) {
        String name = ask("新しいファイルの名前（.txt は自動で付きます）", "新しい章.txt");
        if (name == null) {
            return;
        }
        if (!name.contains(".")) {
            name = name + ".txt";
        }
        Path file = folder.resolve(name);
        try {
            Files.writeString(file, "", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
            refresh();
            host.openFromSidebar(file);
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            host.message("同じ名前のファイルがあります: " + name);
        } catch (IOException ex) {
            host.message("作れませんでした: " + ex.getMessage());
        }
    }

    /** 空のカードボードを、このアプリの .json として作って開く。 */
    private void createBoard(Path folder) {
        String name = ask("新しいカードボードの名前（.json は自動で付きます）", "アイデア.json");
        if (name == null) {
            return;
        }
        if (!name.toLowerCase().endsWith(".json")) {
            name = name + ".json";      // カードの配置を残すため、カードボードは .json でしか保存できない
        }
        Path file = folder.resolve(name);
        try {
            Files.writeString(file, Json.write(new BoardPane().toJson()), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            refresh();
            host.openFromSidebar(file);
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            host.message("同じ名前のファイルがあります: " + name);
        } catch (IOException ex) {
            host.message("作れませんでした: " + ex.getMessage());
        }
    }

    private void createFolder(Path folder) {
        String name = ask("新しいフォルダの名前", "新しいフォルダ");
        if (name == null) {
            return;
        }
        try {
            Files.createDirectory(folder.resolve(name));
            refresh();
        } catch (IOException ex) {
            host.message("作れませんでした: " + ex.getMessage());
        }
    }

    private void rename(Path target) {
        String name = ask("新しい名前", target.getFileName().toString());
        if (name == null || name.equals(target.getFileName().toString())) {
            return;
        }
        Path to = target.resolveSibling(name);
        if (Files.exists(to)) {
            host.message("同じ名前のものがあります: " + name);
            return;
        }
        try {
            Files.move(target, to);
            host.fileMoved(target, to);
            refresh();
        } catch (IOException ex) {
            host.message("名前を変えられませんでした: " + ex.getMessage());
        }
    }

    /** 完全には消さず、表示しているフォルダの中の .datum-trash へ移す（AI 連携のごみ箱と同じ場所）。 */
    private void trash(Path target) {
        int answer = JOptionPane.showConfirmDialog(this,
                "「" + target.getFileName() + "」をごみ箱（" + TRASH + "）へ移しますか？\n必要になったら、そこから取り出せます。",
                "ごみ箱へ移す", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            Path trash = root.resolve(TRASH);
            Files.createDirectories(trash);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path to = trash.resolve(stamp + "_" + target.getFileName());
            Files.move(target, to, StandardCopyOption.ATOMIC_MOVE);
            host.fileTrashed(target);
            refresh();
            host.message("ごみ箱へ移しました: " + target.getFileName());
        } catch (IOException ex) {
            host.message("移せませんでした: " + ex.getMessage());
        }
    }

    /** フォルダの中のテキストファイルを名前の順につなげて、新しいタブで開く（全体を読み返す・書き出すときに使う）。 */
    private void combine(Path folder) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Path file : listSorted(folder)) {
            String name = file.getFileName().toString().toLowerCase();
            if (!Files.isRegularFile(file) || !(name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".text"))) {
                continue;
            }
            try {
                String text = new FileService().readText(file).replace("\r\n", "\n").stripTrailing();
                if (count > 0) {
                    sb.append("\n\n");
                }
                sb.append(text);
                count++;
            } catch (IOException ex) {
                host.message("読めなかったので飛ばしました: " + file.getFileName());
            }
        }
        if (count == 0) {
            host.message("このフォルダには、まとめるテキストファイル（.txt）がありません");
            return;
        }
        host.openCombined(folder.getFileName() + "（" + count + " ファイルをまとめたもの）", sb.toString());
    }

    /** タブの種類が変わったとき、木の印を描き直す。 */
    public void repaintTree() {
        tree.repaint();
    }

    private static final String[] BADGE_HEX = {"#4FC1FF", "#CE9178", "#4EC9B0"};     // タブの印と同じ色
    private static final java.util.Map<Path, Object[]> KINDS = new java.util.HashMap<>();   // ファイル → {更新時刻, 種類}

    /** このアプリの形式の .json なら、その種類を返す。それ以外は null。更新時刻が同じあいだは、読み直さない。 */
    static EditorPane.Mode kindOf(Path file) {
        if (!file.getFileName().toString().toLowerCase().endsWith(".json")) {
            return null;
        }
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            Object[] cached = KINDS.get(file);
            if (cached != null && (long) cached[0] == modified) {
                return (EditorPane.Mode) cached[1];
            }
            EditorPane.Mode mode = null;
            if (Files.size(file) < 5_000_000
                    && Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof java.util.Map<?, ?> raw
                    && EditorPane.FORMAT_NAME.equals(raw.get("format"))) {
                mode = EditorPane.Mode.fromId(String.valueOf(raw.get("mode")));
            }
            KINDS.put(file, new Object[]{modified, mode});
            return mode;
        } catch (IOException | Json.JsonException | RuntimeException ex) {
            return null;                // 読めない・JSON でないものは、ふつうのファイルとして出す
        }
    }

    /** 木の1行の見た目：フォルダは ▸、テキストはそのまま、開けないファイルは薄く。 */
    private final class Renderer extends DefaultTreeCellRenderer {
        Renderer() {
            setBackgroundNonSelectionColor(Theme.PANEL);
            setBackgroundSelectionColor(Theme.MENU_SELECTION);
            setBorderSelectionColor(Theme.MENU_SELECTION);
            setTextSelectionColor(Theme.FG_STRONG);
            setTextNonSelectionColor(Theme.FG);
            setLeafIcon(null);
            setOpenIcon(null);
            setClosedIcon(null);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean focused) {
            Object user = ((DefaultMutableTreeNode) value).getUserObject();
            String text = user instanceof Path p ? (p.getFileName() == null ? p.toString() : p.getFileName().toString())
                    : String.valueOf(user);
            EditorPane.Mode mode = user instanceof Path p && Files.isRegularFile(p) ? kindOf(p) : null;
            if (mode == null && user instanceof Path p && Files.isRegularFile(p) && isText(p)) {
                // ふつうのテキストファイル（.txt など）は「文」。縦書きで開いているときは、タブと同じ「縦」
                EditorPane.Mode open = host.openMode(p);
                mode = open != null && open != EditorPane.Mode.BOARD ? open : EditorPane.Mode.TEXT;
            }
            if (mode != null) {
                // タブと同じ印（文・縦・板）を名前の前に付けて、種類がわかるようにする
                Path file = (Path) user;
                text = "<html><font color='" + EditorPane.badgeHexFor(file, mode, BADGE_HEX) + "'><b>"
                        + EditorPane.badgeFor(file, mode) + "</b></font>&nbsp;&nbsp;"
                        + text.replace("&", "&amp;").replace("<", "&lt;") + "</html>";
            } else if (user instanceof Path p && isGoogleFile(p)) {
                text = text + "  ↗";         // ブラウザで開くリンクだとわかるように
            }
            super.getTreeCellRendererComponent(tree, text, selected, expanded, leaf, row, focused);
            boolean folder = user instanceof Path p && Files.isDirectory(p);
            setFont(tree.getFont().deriveFont(folder ? java.awt.Font.BOLD : java.awt.Font.PLAIN));   // フォルダは太字
            setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
            if (!selected && user instanceof Path p && Files.isRegularFile(p) && !isText(p)) {
                setForeground(Theme.FG_DIM);
            }
            return this;
        }
    }

    /** 木の開閉の印：＞ を閉じている時、それを下へ回した ∨ を開いている時に描く。色は木の文字色に合わせる。 */
    private static final class Chevron implements javax.swing.Icon {
        private final boolean open;

        Chevron(boolean open) {
            this.open = open;
        }

        @Override
        public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
                g2.setColor(c.getForeground());
                g2.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                if (open) {
                    g2.drawPolyline(new int[]{x + 3, x + 8, x + 13}, new int[]{y + 6, y + 11, y + 6}, 3);
                } else {
                    g2.drawPolyline(new int[]{x + 6, x + 11, x + 6}, new int[]{y + 3, y + 8, y + 13}, 3);
                }
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }
}
