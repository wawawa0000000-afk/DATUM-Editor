package com.example.minieditor;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;

/**
 * 画面の色とフォントをまとめたクラス（VS Code の Dark / Light テーマに近い配色）。
 * 色を変えたいときは、ここだけ直せばよい。
 */
public final class Theme {

    /** 配色。設定の「配色」と、表示 → 配色 で選ぶ。 */
    public enum Scheme {
        WHITE("white", "白", true),
        GRAY("gray", "灰", false),
        NAVY("navy", "紺", false),
        BLACK("black", "黒", false);

        public final String id;         // settings.json に書く名前
        public final String label;      // 画面に出す名前
        public final boolean light;     // 明るい配色か

        Scheme(String id, String label, boolean light) {
            this.id = id;
            this.label = label;
            this.light = light;
        }

        /** 設定の名前から配色を選ぶ。以前の版の "dark" は灰、"light" は白として読む。 */
        public static Scheme fromId(String id) {
            if ("light".equals(id)) {
                return WHITE;
            }
            for (Scheme scheme : values()) {
                if (scheme.id.equals(id)) {
                    return scheme;
                }
            }
            return GRAY;
        }
    }

    // 部品の色は、ここの値を使う。配色を変えると、use() で全部入れ替わる。
    // 文字の白（Color.WHITE）は「青い地の上の文字」専用にしているので、どの配色にも真っ白・真っ黒は使わない
    // （配色を切り替えるときに、色の値で入れ替え先を決めるため）。
    public static Color BG;                 // エディタの背景
    public static Color BG_STRIPE;          // 1行おきの縞
    public static Color CURRENT_LINE;       // カーソルのある行
    public static Color PANEL;              // タブの帯・ダイアログ
    public static Color TAB_INACTIVE;
    public static Color MENU_BAR;
    public static Color INPUT;              // 入力欄・ボタン
    public static Color BORDER;
    public static Color HOVER;
    public static Color FG;                 // ふつうの文字
    public static Color FG_STRONG;          // 目立たせたい文字（選んでいるタブの名前など）
    public static Color FG_DIM;             // 行番号などの薄い文字
    public static Color ACCENT;             // 青（ステータスバーなど）
    public static Color MENU_SELECTION;
    public static Color SELECTION;          // 選択範囲
    public static Color MATCH;              // 検索：一致
    public static Color MATCH_CURRENT;      // 検索：今いる場所
    public static Color REPLACED;           // 置換した場所（緑）
    public static Color WARNING;            // 校正で見つかった場所（黄土色）
    public static Color PAPER;              // 原稿用紙の紙の色
    public static Color PAPER_CURRENT;      // 原稿用紙の、カーソルのある行
    public static Color GRID;               // 原稿用紙のマス目の線（茶色）
    public static Color BOARD_DOT;          // カードボードの背景の点
    public static Color ERROR;              // 「見つかりません」などの文字

    private static Scheme scheme;

    static {
        use(Scheme.GRAY);
    }

    // 並び：BG, BG_STRIPE, CURRENT_LINE, PANEL, TAB_INACTIVE, MENU_BAR, INPUT, BORDER, HOVER,
    //       FG, FG_STRONG, FG_DIM, ACCENT, MENU_SELECTION, SELECTION, MATCH, MATCH_CURRENT, REPLACED, WARNING,
    //       PAPER, PAPER_CURRENT, GRID, BOARD_DOT, ERROR
    private static int[] palette(Scheme s) {
        return switch (s) {
            // 白：GitHub や Notion のような、白地にやわらかい灰色の配色。原稿用紙は最初の版のベージュ
            case WHITE -> new int[]{
                0xFEFEFE, 0xF6F8FA, 0xEEF4FC, 0xF6F7F9, 0xECEEF1, 0xF0F1F4, 0xEAECEF, 0xD8DCE1, 0xDDE1E6,
                0x24292F, 0x0E1116, 0x6E7781, 0x0969DA, 0xD6E6FB, 0xB6D7FF, 0xCDEBF8, 0x6CC4F0, 0xBDE8C9, 0xF8E3A1,
                0xFBF7EE, 0xF2EAD9, 0xC9A27E, 0xD9DDE2, 0xCF222E};
            // 灰：JetBrains の新しい画面のような、落ち着いた濃い灰色
            case GRAY -> new int[]{
                0x1F2023, 0x25262A, 0x2A2C30, 0x2B2D31, 0x26282C, 0x2F3136, 0x3A3D43, 0x43464D, 0x4B4F57,
                0xD5D8DD, 0xF4F5F7, 0x878C95, 0x3574F0, 0x2E436E, 0x2F4F80, 0x1F5266, 0x1590C0, 0x2A6B3C, 0x6B5A1A,
                0x26231F, 0x34302A, 0x7A6248, 0x3A3C41, 0xF28B82};
            // 紺：夜の空のような、青みのある紺色
            case NAVY -> new int[]{
                0x141B2D, 0x172036, 0x1B2540, 0x1A2238, 0x161E33, 0x101727, 0x243050, 0x2C3A5C, 0x33446B,
                0xC8D3F5, 0xEEF2FE, 0x7482A8, 0x3B82F6, 0x274272, 0x2D4A85, 0x1C4E6E, 0x0EA5E9, 0x1F6B4A, 0x6E5B1C,
                0x26231F, 0x34302A, 0x7A6248, 0x2A3552, 0xF7768E};
            // 黒：有機 EL の画面にも合う、ほぼ黒の配色
            case BLACK -> new int[]{
                0x0B0B0C, 0x121214, 0x18181B, 0x111113, 0x0E0E10, 0x151517, 0x1F1F23, 0x2A2A2F, 0x34343A,
                0xD4D4D8, 0xFAFAFA, 0x71717A, 0x2563EB, 0x1E3A8A, 0x1E3F7A, 0x0E4A63, 0x0284C7, 0x166534, 0x5C4D0F,
                0x26231F, 0x34302A, 0x7A6248, 0x26262B, 0xF87171};
        };
    }

    /** 配色を入れ替える（色の値だけ。すでにある部品に反映するには switchTo を使う）。 */
    public static void use(Scheme s) {
        scheme = s;
        int[] c = palette(s);
        int i = 0;
        BG = new Color(c[i++]);
        BG_STRIPE = new Color(c[i++]);
        CURRENT_LINE = new Color(c[i++]);
        PANEL = new Color(c[i++]);
        TAB_INACTIVE = new Color(c[i++]);
        MENU_BAR = new Color(c[i++]);
        INPUT = new Color(c[i++]);
        BORDER = new Color(c[i++]);
        HOVER = new Color(c[i++]);
        FG = new Color(c[i++]);
        FG_STRONG = new Color(c[i++]);
        FG_DIM = new Color(c[i++]);
        ACCENT = new Color(c[i++]);
        MENU_SELECTION = new Color(c[i++]);
        SELECTION = new Color(c[i++]);
        MATCH = new Color(c[i++]);
        MATCH_CURRENT = new Color(c[i++]);
        REPLACED = new Color(c[i++]);
        WARNING = new Color(c[i++]);
        PAPER = new Color(c[i++]);
        PAPER_CURRENT = new Color(c[i++]);
        GRID = new Color(c[i++]);
        BOARD_DOT = new Color(c[i++]);
        ERROR = new Color(c[i]);
    }

    public static Scheme scheme() {
        return scheme;
    }

    public static boolean isLight() {
        return scheme.light;
    }

    /**
     * 起動したまま配色を変える。roots から下の部品を全部たどり、前の配色の色を、新しい配色の同じ役目の色に置き換える。
     * 部品の多くは、作ったときの色を覚えているため（描くたびに Theme を読む部品は、描き直すだけで変わる）。
     */
    public static void switchTo(Scheme next, java.util.Collection<? extends java.awt.Component> roots) {
        if (next == scheme) {
            return;
        }
        int[] before = palette(scheme);
        int[] after = palette(next);
        java.util.Map<Integer, Color> map = new java.util.HashMap<>();
        for (int i = 0; i < before.length; i++) {
            map.putIfAbsent(before[i], new Color(after[i]));
        }
        use(next);
        apply();
        // 見た目（L&F）を設定し直すと、Swing がメニューを Esc や外のクリックで閉じるための仕組みが外れる。
        // ポップアップメニューを1つ作ると、その仕組みが付け直される
        new javax.swing.JPopupMenu();
        java.util.Set<java.awt.Component> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (java.awt.Component root : roots) {
            if (root != null) {
                recolor(root, map, seen);
            }
        }
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            window.repaint();
        }
    }

    private static Color swap(Color color, java.util.Map<Integer, Color> map) {
        if (color == null) {
            return null;
        }
        Color to = map.get(color.getRGB() & 0xFFFFFF);
        if (to == null || color.getAlpha() != 255) {
            return color;
        }
        // UIManager から来た色（UIResource）は、そのしるしを残す（部品が自分で付けた色と区別されるため）
        return color instanceof javax.swing.plaf.UIResource ? new ColorUIResource(to) : to;
    }

    private static void recolor(java.awt.Component c, java.util.Map<Integer, Color> map,
            java.util.Set<java.awt.Component> seen) {
        if (!seen.add(c)) {
            return;
        }
        if (c.isBackgroundSet()) {
            c.setBackground(swap(c.getBackground(), map));
        }
        if (c.isForegroundSet()) {
            c.setForeground(swap(c.getForeground(), map));
        }
        if (c instanceof JComponent jc) {
            jc.setBorder(swapBorder(jc.getBorder(), map));
        }
        if (c instanceof javax.swing.text.JTextComponent text) {
            text.setCaretColor(swap(text.getCaretColor(), map));
            text.setSelectionColor(swap(text.getSelectionColor(), map));
            text.setSelectedTextColor(swap(text.getSelectedTextColor(), map));
            text.setDisabledTextColor(swap(text.getDisabledTextColor(), map));
        }
        if (c instanceof javax.swing.JTextPane pane) {
            // 会話欄のように、文字ごとに色を付けた文章は、その色も入れ替える
            javax.swing.text.StyledDocument doc = pane.getStyledDocument();
            int pos = 0;
            while (pos < doc.getLength()) {
                javax.swing.text.Element run = doc.getCharacterElement(pos);
                int end = Math.max(run.getEndOffset(), pos + 1);
                Object fg = run.getAttributes().getAttribute(javax.swing.text.StyleConstants.Foreground);
                if (fg instanceof Color color && swap(color, map) != color) {
                    javax.swing.text.SimpleAttributeSet attr = new javax.swing.text.SimpleAttributeSet();
                    javax.swing.text.StyleConstants.setForeground(attr, swap(color, map));
                    doc.setCharacterAttributes(pos, Math.min(end, doc.getLength()) - pos, attr, false);
                }
                pos = end;
            }
        }
        if (c instanceof javax.swing.JTree tree &&tree.getCellRenderer() instanceof javax.swing.tree.DefaultTreeCellRenderer r) {
            r.setBackgroundNonSelectionColor(swap(r.getBackgroundNonSelectionColor(), map));
            r.setBackgroundSelectionColor(swap(r.getBackgroundSelectionColor(), map));
            r.setBorderSelectionColor(swap(r.getBorderSelectionColor(), map));
            r.setTextSelectionColor(swap(r.getTextSelectionColor(), map));
            r.setTextNonSelectionColor(swap(r.getTextNonSelectionColor(), map));
        }
        if (c instanceof javax.swing.JList<?> list) {
            list.setSelectionBackground(swap(list.getSelectionBackground(), map));
            list.setSelectionForeground(swap(list.getSelectionForeground(), map));
        }
        if (c instanceof javax.swing.JMenu menu) {
            recolor(menu.getPopupMenu(), map, seen);    // 閉じているメニューの中身は、部品の木に入っていない
        }
        if (c instanceof JComponent jc && jc.getComponentPopupMenu() != null) {
            recolor(jc.getComponentPopupMenu(), map, seen);
        }
        if (c instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                recolor(child, map, seen);
            }
        }
    }

    private static javax.swing.border.Border swapBorder(javax.swing.border.Border border, java.util.Map<Integer, Color> map) {
        if (border instanceof javax.swing.border.CompoundBorder compound) {
            javax.swing.border.Border outside = swapBorder(compound.getOutsideBorder(), map);
            javax.swing.border.Border inside = swapBorder(compound.getInsideBorder(), map);
            return outside == compound.getOutsideBorder() && inside == compound.getInsideBorder()
                    ? border : BorderFactory.createCompoundBorder(outside, inside);
        }
        if (border instanceof javax.swing.border.MatteBorder matte && matte.getMatteColor() != null) {
            Color to = swap(matte.getMatteColor(), map);
            if (to != matte.getMatteColor()) {
                java.awt.Insets in = matte.getBorderInsets();
                return BorderFactory.createMatteBorder(in.top, in.left, in.bottom, in.right, to);
            }
        } else if (border instanceof javax.swing.border.LineBorder line) {
            Color to = swap(line.getLineColor(), map);
            if (to != line.getLineColor()) {
                return new javax.swing.border.LineBorder(to, line.getThickness(), line.getRoundedCorners());
            }
        }
        return border;
    }

    // 文字の大きさ。Windows などで画面の拡大（125%・150% など）が効いているときは、
    // Java が自動で拡大して描くので、そのぶん小さい数字にして二重に大きくならないようにする。
    private static final double SCREEN_SCALE = screenScale();
    private static final int BASE_UI = scaled(22);
    private static final int BASE_SMALL = scaled(18);
    private static final int BASE_EDITOR = scaled(48);
    // 表示の倍率（ブラウザの拡大・縮小と同じ考え方）を掛けた大きさ。倍率を変えると、ここも変わる
    public static int UI_SIZE = BASE_UI;
    public static int SMALL_SIZE = BASE_SMALL;
    public static int EDITOR_SIZE = BASE_EDITOR;

    /** 選べる表示の倍率（ブラウザと同じような刻み）。 */
    public static final int[] ZOOM_STEPS = {50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200};
    private static int zoom = 100;

    /** 行の縞模様を表示するか（表示メニューで切り替える）。 */
    public static boolean stripes = true;

    private Theme() {
    }

    private static double screenScale() {
        try {
            return Math.max(1.0, GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .getDefaultConfiguration().getDefaultTransform().getScaleX());
        } catch (Exception | Error ex) {
            return 1.0;                 // 画面がない環境（テストなど）では、拡大なしとして扱う
        }
    }

    private static int scaled(int size) {
        return Math.max(12, (int) Math.round(size / SCREEN_SCALE));
    }

    /** 今の表示の倍率（100 が等倍）。 */
    public static int zoom() {
        return zoom;
    }

    /** 倍率を掛けた大きさにする（文書に保存されている文字の大きさを、画面の大きさに直すときに使う）。 */
    public static int zoomed(int size) {
        return Math.max(6, (int) Math.round(size * zoom / 100.0));
    }

    /** 倍率を外した大きさにする（文書には、等倍のときの大きさで保存する）。 */
    public static int unzoomed(int size) {
        return Math.max(6, (int) Math.round(size * 100.0 / zoom));
    }

    /** 刻みの中で、今の倍率の次（direction が 1 なら大きい方、-1 なら小さい方）の倍率。端ならそのまま。 */
    public static int nextZoom(int direction) {
        if (direction > 0) {
            for (int step : ZOOM_STEPS) {
                if (step > zoom) {
                    return step;
                }
            }
            return ZOOM_STEPS[ZOOM_STEPS.length - 1];
        }
        for (int i = ZOOM_STEPS.length - 1; i >= 0; i--) {
            if (ZOOM_STEPS[i] < zoom) {
                return ZOOM_STEPS[i];
            }
        }
        return ZOOM_STEPS[0];
    }

    /** 画面を作る前に、表示の倍率を決める（起動したときに、設定から読んで呼ぶ）。 */
    public static void setZoom(int percent) {
        zoom = Math.max(ZOOM_STEPS[0], Math.min(ZOOM_STEPS[ZOOM_STEPS.length - 1], percent));
        UI_SIZE = Math.max(6, (int) Math.round(BASE_UI * zoom / 100.0));
        SMALL_SIZE = Math.max(6, (int) Math.round(BASE_SMALL * zoom / 100.0));
        EDITOR_SIZE = Math.max(6, (int) Math.round(BASE_EDITOR * zoom / 100.0));
    }

    /**
     * 起動したまま表示の倍率を変える。roots から下の部品の文字を、全部同じ割合で大きく（小さく）する。
     * 文書の文字（エディタの本文・カード）は、呼ぶ側で setEditorFont し直す。
     */
    public static void zoomTo(int percent, java.util.Collection<? extends java.awt.Component> roots) {
        int before = zoom;
        setZoom(percent);
        if (zoom == before) {
            return;
        }
        apply();
        new javax.swing.JPopupMenu();       // switchTo と同じ理由（メニューを閉じる仕組みを付け直す）
        java.util.Set<java.awt.Component> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (java.awt.Component root : roots) {
            if (root != null) {
                resize(root, before, seen);
            }
        }
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            window.validate();
            window.repaint();
        }
    }

    /**
     * 倍率 percent のときの文字の大きさから、等倍のときの大きさを求める。
     * Theme の決まった大きさ（UI_SIZE など）と同じなら、四捨五入で狂わないように、その元の値を返す。
     */
    public static float baseSize(float size, int percent) {
        for (int base : new int[]{BASE_UI, BASE_SMALL, BASE_EDITOR, BASE_UI + 2}) {
            if (Math.round(size) == Math.max(6, (int) Math.round(base * percent / 100.0))) {
                return base;
            }
        }
        return size * 100f / percent;
    }

    // 部品ごとに「等倍のときの文字の大きさ」を覚えておき、そこから計算する（何度変えても、ずれが積もらないように）
    private static final String BASE_SIZE = "datum.baseFontSize";
    private static final String ZOOMED_FONT = "datum.zoomedFont";

    private static void resize(java.awt.Component c, int before, java.util.Set<java.awt.Component> seen) {
        if (!seen.add(c)) {
            return;
        }
        Font font = c.isFontSet() ? c.getFont() : null;
        if (font != null) {
            float base;
            if (c instanceof JComponent jc && jc.getClientProperty(ZOOMED_FONT) == font
                    && jc.getClientProperty(BASE_SIZE) instanceof Float saved) {
                base = saved;
            } else {
                base = baseSize(font.getSize2D(), before);   // 初めて変えるとき・部品が自分で文字を変えたとき
            }
            Font next = font.deriveFont(Math.max(6f, Math.round(base * zoom / 100f)));
            if (font instanceof javax.swing.plaf.UIResource) {
                next = new FontUIResource(next);
            }
            c.setFont(next);
            if (c instanceof JComponent jc) {
                jc.putClientProperty(BASE_SIZE, base);
                jc.putClientProperty(ZOOMED_FONT, next);
            }
        }
        if (c instanceof javax.swing.JMenu menu) {
            resize(menu.getPopupMenu(), before, seen);     // 閉じているメニューの中身は、部品の木に入っていない
        }
        if (c instanceof JComponent jc && jc.getComponentPopupMenu() != null) {
            resize(jc.getComponentPopupMenu(), before, seen);
        }
        if (c instanceof JComponent jc) {
            jc.revalidate();
        }
        if (c instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                resize(child, before, seen);
            }
        }
    }

    public static Font ui() {
        return new Font("Dialog", Font.PLAIN, UI_SIZE);
    }

    public static Font small() {
        return new Font("Dialog", Font.PLAIN, SMALL_SIZE);
    }

    public static Font editorFont() {
        return new Font("Monospaced", Font.PLAIN, EDITOR_SIZE);
    }

    /** 画面を作る前に呼ぶ（配色を変えたときにも呼び直す）。Swing 全体の色とフォントを設定する。 */
    public static void apply() {
        UIManager.put("swing.boldMetal", Boolean.FALSE);
        MetalLookAndFeel.setCurrentTheme(new DarkMetalTheme());
        try {
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (UnsupportedLookAndFeelException ex) {
            // Metal は Java に必ず入っているので、通常は起きない
        }

        // メニュー
        UIManager.put("MenuBar.background", MENU_BAR);
        UIManager.put("MenuBar.border", BorderFactory.createEmptyBorder(2, 4, 2, 4));
        UIManager.put("Menu.background", MENU_BAR);
        UIManager.put("Menu.foreground", FG);
        UIManager.put("Menu.selectionBackground", HOVER);
        UIManager.put("Menu.selectionForeground", FG_STRONG);
        UIManager.put("Menu.border", BorderFactory.createEmptyBorder(6, 12, 6, 12));
        UIManager.put("PopupMenu.background", PANEL);
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(BORDER));
        for (String item : new String[]{"MenuItem", "CheckBoxMenuItem", "RadioButtonMenuItem"}) {
            UIManager.put(item + ".background", PANEL);
            UIManager.put(item + ".foreground", FG);
            UIManager.put(item + ".selectionBackground", MENU_SELECTION);
            UIManager.put(item + ".selectionForeground", FG_STRONG);
            UIManager.put(item + ".acceleratorForeground", FG_DIM);
            UIManager.put(item + ".acceleratorSelectionForeground", FG_STRONG);
            UIManager.put(item + ".acceleratorFont", new FontUIResource(small()));
            UIManager.put(item + ".border", BorderFactory.createEmptyBorder(6, 12, 6, 12));
        }
        UIManager.put("MenuItem.acceleratorDelimiter", "+");     // 「Ctrl-N」ではなく「Ctrl+N」と表示する
        UIManager.put("Separator.foreground", BORDER);
        UIManager.put("Separator.background", PANEL);

        // ボタン・入力欄・ダイアログ
        UIManager.put("Button.background", INPUT);
        UIManager.put("Button.foreground", FG);
        UIManager.put("Button.select", MENU_SELECTION);
        UIManager.put("Button.focus", FG_DIM);
        UIManager.put("Button.disabledText", FG_DIM);       // 押せないボタンの文字が、地の色に溶けて消えないように
        UIManager.put("Button.border", BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        UIManager.put("TextField.background", INPUT);
        UIManager.put("TextField.foreground", FG);
        UIManager.put("TextField.caretForeground", FG_STRONG);
        UIManager.put("TextField.border", BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        UIManager.put("TextArea.caretForeground", FG_STRONG);
        UIManager.put("TextArea.background", INPUT);
        UIManager.put("TextArea.foreground", FG);
        UIManager.put("TextArea.selectionBackground", SELECTION);
        UIManager.put("TextArea.selectionForeground", FG_STRONG);
        UIManager.put("PasswordField.background", INPUT);
        UIManager.put("PasswordField.foreground", FG);
        UIManager.put("PasswordField.caretForeground", FG_STRONG);
        UIManager.put("List.background", BG);
        UIManager.put("List.foreground", FG);
        UIManager.put("List.selectionBackground", MENU_SELECTION);
        UIManager.put("List.selectionForeground", FG_STRONG);
        UIManager.put("Tree.background", PANEL);
        UIManager.put("Tree.foreground", FG);
        UIManager.put("Tree.textBackground", PANEL);
        UIManager.put("Tree.textForeground", FG);
        UIManager.put("Tree.selectionBackground", MENU_SELECTION);
        UIManager.put("Tree.selectionForeground", FG_STRONG);
        UIManager.put("Tree.selectionBorderColor", MENU_SELECTION);
        UIManager.put("CheckBox.background", PANEL);
        UIManager.put("CheckBox.foreground", FG);
        UIManager.put("RadioButton.background", PANEL);
        UIManager.put("RadioButton.foreground", FG);
        UIManager.put("Spinner.background", INPUT);
        UIManager.put("FormattedTextField.background", INPUT);
        UIManager.put("FormattedTextField.foreground", FG);
        UIManager.put("TitledBorder.titleColor", FG_STRONG);
        UIManager.put("ComboBox.background", INPUT);
        UIManager.put("ComboBox.foreground", FG);
        UIManager.put("OptionPane.background", PANEL);
        UIManager.put("OptionPane.messageForeground", FG);
        UIManager.put("Panel.background", PANEL);
        UIManager.put("Label.foreground", FG);
        UIManager.put("ToolTip.background", PANEL);
        UIManager.put("ToolTip.foreground", FG);
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(BORDER));

        // スクロールバー・分割
        UIManager.put("ScrollBarUI", FlatScrollBarUI.class.getName());
        UIManager.put(FlatScrollBarUI.class.getName(), FlatScrollBarUI.class);
        UIManager.put("ScrollBar.width", 14);
        UIManager.put("ScrollPane.border", BorderFactory.createEmptyBorder());
        UIManager.put("SplitPane.border", BorderFactory.createEmptyBorder());
        UIManager.put("SplitPaneDivider.border", BorderFactory.createEmptyBorder());
    }

    /** 枠のない、平らなボタンを作る（マウスを乗せると少し明るくなる）。 */
    public static JButton flatButton(String text) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean hot = getModel().isRollover() || getModel().isPressed() || isFocusOwner();
                g2.setColor(hot ? HOVER : INPUT);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setRolloverEnabled(true);
        button.setForeground(FG);
        button.setFont(small());
        button.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        // JButton は Space でしか押せないので、Enter でも押せるようにする
        button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pressButton");
        button.getActionMap().put("pressButton", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                button.doClick();
            }
        });
        return button;
    }

    /** 検索欄などの入力欄を、暗い配色にそろえる。 */
    public static void styleField(JTextField field) {
        field.setBackground(INPUT);
        field.setForeground(FG_STRONG);
        field.setCaretColor(FG_STRONG);
        field.setSelectionColor(SELECTION);
        field.setSelectedTextColor(FG_STRONG);
        field.setFont(small());
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
    }

    /**
     * Swing 標準の見た目（Metal）の基本色を、暗い色に入れ替える。
     * ファイル選択ダイアログなど、自分で色を付けていない部品もこれで暗くなる。
     */
    private static final class DarkMetalTheme extends DefaultMetalTheme {
        private final ColorUIResource primary1 = new ColorUIResource(ACCENT);
        private final ColorUIResource primary2 = new ColorUIResource(MENU_SELECTION);
        private final ColorUIResource primary3 = new ColorUIResource(SELECTION);
        private final ColorUIResource secondary1 = new ColorUIResource(BORDER);
        private final ColorUIResource secondary2 = new ColorUIResource(INPUT);
        private final ColorUIResource secondary3 = new ColorUIResource(PANEL);
        private final ColorUIResource white = new ColorUIResource(BG);
        private final ColorUIResource black = new ColorUIResource(FG);
        private final FontUIResource font = new FontUIResource(ui());
        private final FontUIResource smallFont = new FontUIResource(small());

        @Override
        public String getName() {
            return "DATUM " + scheme.id;
        }

        @Override
        protected ColorUIResource getPrimary1() {
            return primary1;
        }

        @Override
        protected ColorUIResource getPrimary2() {
            return primary2;
        }

        @Override
        protected ColorUIResource getPrimary3() {
            return primary3;
        }

        @Override
        protected ColorUIResource getSecondary1() {
            return secondary1;
        }

        @Override
        protected ColorUIResource getSecondary2() {
            return secondary2;
        }

        @Override
        protected ColorUIResource getSecondary3() {
            return secondary3;
        }

        @Override
        protected ColorUIResource getWhite() {
            return white;
        }

        @Override
        protected ColorUIResource getBlack() {
            return black;
        }

        @Override
        public FontUIResource getControlTextFont() {
            return font;
        }

        @Override
        public FontUIResource getSystemTextFont() {
            return font;
        }

        @Override
        public FontUIResource getUserTextFont() {
            return font;
        }

        @Override
        public FontUIResource getMenuTextFont() {
            return font;
        }

        @Override
        public FontUIResource getWindowTitleFont() {
            return font;
        }

        @Override
        public FontUIResource getSubTextFont() {
            return smallFont;
        }
    }
}
