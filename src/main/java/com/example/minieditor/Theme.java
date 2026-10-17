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

    // 色は、起動のときに一度だけ決める（設定で「ライト」を選んでいれば、useLight で明るい色に入れ替える）。
    // 部品は作られたときの色を覚えるので、配色の切り替えは、次に起動したときに反映される。
    public static Color BG = new Color(0x1E1E1E);            // エディタの背景
    public static Color BG_STRIPE = new Color(0x252525);     // 1行おきの縞
    public static Color CURRENT_LINE = new Color(0x2C2C2D);  // カーソルのある行
    public static Color PANEL = new Color(0x252526);         // タブの帯・ダイアログ
    public static Color TAB_INACTIVE = new Color(0x2D2D2D);
    public static Color MENU_BAR = new Color(0x323233);
    public static Color INPUT = new Color(0x3C3C3C);         // 入力欄・ボタン
    public static Color BORDER = new Color(0x454545);
    public static Color HOVER = new Color(0x505050);
    public static Color FG = new Color(0xD4D4D4);            // ふつうの文字
    public static Color FG_STRONG = Color.WHITE;             // 目立たせたい文字（選んでいるタブの名前など）
    public static Color FG_DIM = new Color(0x858585);        // 行番号などの薄い文字
    public static Color ACCENT = new Color(0x007ACC);        // 青（ステータスバーなど）
    public static Color MENU_SELECTION = new Color(0x094771);
    public static Color SELECTION = new Color(0x264F78);     // 選択範囲
    public static Color MATCH = new Color(0x00506E);         // 検索：一致（暗い水色）
    public static Color MATCH_CURRENT = new Color(0x0090C8); // 検索：今いる場所（明るい水色）
    public static Color REPLACED = new Color(0x1E7B34);      // 置換した場所（緑）
    public static Color WARNING = new Color(0x6B5A12);       // 校正で見つかった場所（黄土色）
    public static Color PAPER = new Color(0x26231F);         // 原稿用紙の紙の色
    public static Color PAPER_CURRENT = new Color(0x34302A); // 原稿用紙の、カーソルのある行
    public static Color GRID = new Color(0x7A6248);          // 原稿用紙のマス目の線（茶色）
    public static Color BOARD_DOT = new Color(0x3A3A3A);     // カードボードの背景の点
    public static Color ERROR = new Color(0xF48771);         // 「見つかりません」などの文字

    private static boolean light;

    /** 明るい配色（VS Code の Light に近い色）に入れ替える。apply() より前に呼ぶこと。 */
    public static void useLight() {
        light = true;
        BG = new Color(0xFFFFFF);
        BG_STRIPE = new Color(0xF6F6F6);
        CURRENT_LINE = new Color(0xEEF3FA);
        PANEL = new Color(0xF3F3F3);
        TAB_INACTIVE = new Color(0xE8E8E8);
        MENU_BAR = new Color(0xDDDDDD);
        INPUT = new Color(0xE4E4E4);
        BORDER = new Color(0xC8C8C8);
        HOVER = new Color(0xD0D0D0);
        FG = new Color(0x333333);
        FG_STRONG = new Color(0x111111);
        FG_DIM = new Color(0x777777);
        ACCENT = new Color(0x007ACC);
        MENU_SELECTION = new Color(0xC7DFF5);
        SELECTION = new Color(0xADD6FF);
        MATCH = new Color(0xC2E6F5);
        MATCH_CURRENT = new Color(0x62C4EC);
        REPLACED = new Color(0xB7E4C7);
        WARNING = new Color(0xF7E59A);
        PAPER = new Color(0xFBF7EE);
        PAPER_CURRENT = new Color(0xF2EAD9);
        GRID = new Color(0xC9A27E);
        BOARD_DOT = new Color(0xD8D8D8);
        ERROR = new Color(0xC0392B);
    }

    public static boolean isLight() {
        return light;
    }

    // 文字の大きさ。Windows などで画面の拡大（125%・150% など）が効いているときは、
    // Java が自動で拡大して描くので、そのぶん小さい数字にして二重に大きくならないようにする。
    private static final double SCREEN_SCALE = screenScale();
    public static final int UI_SIZE = scaled(22);
    public static final int SMALL_SIZE = scaled(18);
    public static final int EDITOR_SIZE = scaled(48);

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

    public static Font ui() {
        return new Font("Dialog", Font.PLAIN, UI_SIZE);
    }

    public static Font small() {
        return new Font("Dialog", Font.PLAIN, SMALL_SIZE);
    }

    public static Font editorFont() {
        return new Font("Monospaced", Font.PLAIN, EDITOR_SIZE);
    }

    /** 画面を作る前に1回だけ呼ぶ。Swing 全体の色とフォントを設定する。 */
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
            return light ? "DATUM Light" : "DATUM Dark";
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
