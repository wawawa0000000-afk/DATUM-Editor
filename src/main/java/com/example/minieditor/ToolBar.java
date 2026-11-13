package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

/**
 * メニューバーの下の段に置く、よく使う操作のボタンの帯（ツールバー）。
 * メニューバー（文字だけ・全部の操作）と、ツールバー（よく使うものだけ・アイコンと名前）を、別の段に分ける。
 * 左に文書の操作（新規・開く・サイドバー・分割）、右に探す・欄・集中を並べる。
 * 見せ方は選ばせない。いつもアイコンと名前で、ウィンドウが狭くて名前まで入りきらないときだけ、自動でアイコンだけにする。
 */
final class ToolBar extends JPanel {

    private static final int GAP = 8;           // ボタンとボタンのあいだ（詰めすぎると、押し損じやすく、見た目も苦しい）
    private static final int GROUP_GAP = 6;     // まとまりの区切りの線の左右に、さらに足す空き（GAP と合わせて 14px ほど）

    private final JPanel left = row(FlowLayout.LEFT);
    private final JPanel right = row(FlowLayout.RIGHT);
    private final List<Button> buttons = new ArrayList<>();
    private boolean compact;                    // 名前まで入りきらないので、アイコンだけにしているか

    ToolBar() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(5, 10, 6, 10));
        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                fit();
            }
        });
    }

    private static JPanel row(int align) {
        JPanel panel = new JPanel(new FlowLayout(align, GAP, 0));
        panel.setOpaque(false);
        return panel;
    }

    /** 左のまとまりに足す。null は区切りの線。 */
    void addLeft(Component... parts) {
        add(left, parts);
    }

    /** 右のまとまりに足す。null は区切りの線。 */
    void addRight(Component... parts) {
        add(right, parts);
    }

    private void add(JPanel row, Component[] parts) {
        for (Component part : parts) {
            if (part == null) {
                row.add(separator());
                continue;
            }
            row.add(part);
            if (part instanceof Button button) {
                buttons.add(button);
                button.iconOnly = compact;
            }
        }
    }

    private void restyle() {
        for (Button button : buttons) {
            button.iconOnly = compact;
        }
        revalidate();
        repaint();
    }

    /** 名前まで並べたときの幅が、帯に入りきるかを調べ、入りきらなければアイコンだけにする。 */
    private void fit() {
        if (getWidth() <= 0) {
            return;
        }
        int need = getInsets().left + getInsets().right + GAP * 4;
        for (Component c : left.getComponents()) {
            need += width(c) + GAP;
        }
        for (Component c : right.getComponents()) {
            need += width(c) + GAP;
        }
        boolean next = need > getWidth();
        if (next != compact) {
            compact = next;
            SwingUtilities.invokeLater(this::restyle);      // 並べている最中に大きさを変えないよう、あとで
        }
    }

    private static int width(Component c) {
        return c instanceof Button button ? button.sizeFor(false).width : c.getPreferredSize().width;
    }

    @Override
    public void paint(Graphics g) {
        Fader.paint(this, g, super::paint);     // 集中モードに出入りするときは、薄くして描く
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Theme.MENU_BAR);
        g.fillRect(0, 0, getWidth(), getHeight());
        // メニューバーとは別の段だとわかるように、上と下に細い線を引く
        g.setColor(Theme.BORDER);
        g.fillRect(0, 0, getWidth(), 1);
        g.fillRect(0, getHeight() - 1, getWidth(), 1);
    }

    /** まとまりを分ける、細い縦の線。 */
    private JComponent separator() {
        return new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                int h = buttons.isEmpty() ? ToolIcons.size() : buttons.get(0).getPreferredSize().height;
                return new Dimension(1 + GROUP_GAP * 2, h);
            }

            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.BORDER);
                int h = getHeight();
                g.fillRect(getWidth() / 2, h / 5, 1, h - h * 2 / 5);
            }
        };
    }

    // ---------- ボタン ----------

    /** 押すとすぐ動くボタン（欄の出し入れなら、出ている間は淡い青の地になる）。 */
    static Button button(Icon icon, String name, String label, String tooltip, Runnable action) {
        return new Button(icon, name, label, tooltip, Kind.PLAIN, null, b -> action.run());
    }

    /** 押すと一覧が開くボタン。名前の右に「▾」を付けて、すぐ動くボタンと見分けられるようにする。 */
    static Button dropdown(Icon icon, String name, String label, String tooltip, Consumer<Button> open) {
        return new Button(icon, name, label, tooltip, Kind.DROPDOWN, null, open);
    }

    /** 入力欄の形をしたボタン（すべての操作を探す）。押すと、本当の入力欄（パレット）が開く。key は右端に出すキー。 */
    static Button field(Icon icon, String name, String label, String key, String tooltip, Runnable action) {
        return new Button(icon, name, label, tooltip, Kind.FIELD, key, b -> action.run());
    }

    private enum Kind { PLAIN, DROPDOWN, FIELD }

    /** ツールバーのボタン。見た目はすべて自分で描く（アイコン・名前・▾・キーの位置を、見せ方に合わせて決めるため）。 */
    static final class Button extends JToggleButton {
        private final Icon icon;
        private final String label;
        private final Kind kind;
        private final String key;
        private boolean iconOnly;          // 名前を省いて、アイコンだけを描くか（狭いとき）
        private final PlateFade fade = new PlateFade(this, false);     // 乗せた・オンの濃さを、100ms で移す

        private Button(Icon icon, String name, String label, String tooltip, Kind kind, String key, Consumer<Button> action) {
            this.icon = icon;
            this.label = label;
            this.kind = kind;
            this.key = key;
            setToolTipText(name + "：" + tooltip);
            getAccessibleContext().setAccessibleName(name);
            setFocusable(false);
            setRolloverEnabled(true);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setForeground(Theme.FG_STRONG);     // アイコンはこの色で描かれる（配色を変えると、一緒に入れ替わる）
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addActionListener(e -> {
                setSelected(!isSelected());     // 押した状態は、操作の結果に合わせて呼び出し側が決める
                action.accept(this);
            });
        }

        private Font font() {
            return Theme.small();
        }

        private int iconSize() {
            return icon.getIconWidth();
        }

        /** 左右の内側の余白（10px。表示の倍率に合わせて大きくする）。 */
        private static int padX() {
            return Math.round(10 * zoom());
        }

        private static float zoom() {
            return Theme.zoom() / 100f;
        }

        private int chevronWidth() {
            return Math.round(iconSize() * 0.45f);
        }

        Dimension sizeFor(boolean iconOnly) {
            FontMetrics fm = getFontMetrics(font());
            int s = iconSize();
            int gap = Math.round(s * 0.35f);
            int padX = padX();
            int padY = Math.round(5 * zoom());     // 上下の内側の余白（5px）
            int content = s;
            if (!iconOnly) {
                content += gap + fm.stringWidth(label);
            }
            if (kind == Kind.DROPDOWN) {
                content += gap + chevronWidth();
            }
            if (kind == Kind.FIELD && !iconOnly) {
                // 入力欄らしく見えるよう、名前のあとに空きをとり、右端にキーを置く
                content = Math.max(content, s * 9) + gap * 2 + fm.stringWidth(key);
            }
            return new Dimension(content + padX * 2, Math.max(s, fm.getAscent() + fm.getDescent()) + padY * 2);
        }

        @Override
        public Dimension getPreferredSize() {
            return sizeFor(iconOnly);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int arc = Theme.arc();              // 角の丸み（半径 6px）
            if (kind == Kind.FIELD && !iconOnly) {
                // 入力欄の形：押せる所だとわかるよう、外枠に淡い明るい線を引く（暗い配色では白の 15%、明るい配色では黒の 15%）
                g2.setColor(Theme.mix(Theme.INPUT, Theme.HOVER, fade.hot()));
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                g2.setColor(Theme.overlay(0.15f));
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            } else {
                // どこまでが押せる所か一目でわかるよう、ふだんから極薄いプレートと細い枠を描く。
                // 乗せると明るく浮き上がり、オン（欄を出している・分割中）は押し込んだように、さらに明るくする。
                // 地と枠の濃さ（白の割合）：ふだん 4%・10%、乗せると 12%・22%、オン 20%・35%。明るい配色では、白の代わりに黒で同じ濃さ
                float[] plate = fade.plate();
                java.awt.geom.RoundRectangle2D.Float shape = new java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc);
                g2.setColor(Theme.overlay(plate[0]));
                g2.fill(shape);
                g2.setColor(Theme.overlay(plate[1]));
                g2.draw(shape);
            }

            g2.setFont(font());
            FontMetrics fm = g2.getFontMetrics();
            int s = iconSize();
            int gap = Math.round(s * 0.35f);
            int padX = padX();
            int x = padX;
            if (kind != Kind.FIELD || iconOnly) {
                // ふつうのボタンは、中身を真ん中に寄せる
                x = (w - (sizeFor(iconOnly).width - padX * 2)) / 2;
            }
            int baseline = (h - fm.getAscent() - fm.getDescent()) / 2 + fm.getAscent();
            Color ink = isSelected() && kind != Kind.FIELD ? onColor() : Theme.FG_STRONG;
            putClientProperty(ToolIcons.ICON_COLOR, ink);      // 同じ値なら何も起きないので、描くたびに入れてよい
            icon.paintIcon(this, g2, x, (h - s) / 2);
            x += s + gap;
            if (!iconOnly) {
                g2.setColor(kind == Kind.FIELD ? placeholderColor() : ink);
                g2.drawString(label, x, baseline);
                x += fm.stringWidth(label) + gap;
            }
            if (kind == Kind.DROPDOWN) {
                drawChevron(g2, x, h / 2f);
            }
            if (kind == Kind.FIELD && !iconOnly) {
                g2.setColor(placeholderColor());
                g2.drawString(key, w - padX - fm.stringWidth(key), baseline);
            }
            g2.dispose();
        }

        /** オンのボタンの、アイコンと名前の色。暗い配色では、アクセントの青を少し明るくして読みやすくする。 */
        private static Color onColor() {
            return Theme.scheme().light ? Theme.ACCENT : Theme.mix(Theme.ACCENT, Color.WHITE, 0.35f);
        }

        /** 入力欄の形のボタンの、薄い文字（操作を探す…・Ctrl+P）。背景に沈まないよう、ふつうの文字の色から少しだけ下げる。 */
        static Color placeholderColor() {
            return Theme.mix(Theme.FG, Theme.INPUT, 0.30f);
        }

        /** 「▾」を、左端 x・縦の中心 cy に描く（線で描く下向きの山。アイコンと同じ線の太さ）。 */
        private void drawChevron(Graphics2D g2, float x, float cy) {
            float cw = chevronWidth();
            float ch = cw * 0.5f;
            g2.setColor(Theme.FG_DIM);
            g2.setStroke(new java.awt.BasicStroke(Math.max(1.4f, iconSize() / 12f),
                    java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
            p.moveTo(x, cy - ch / 2);
            p.lineTo(x + cw / 2, cy + ch / 2);
            p.lineTo(x + cw, cy - ch / 2);
            g2.draw(p);
        }
    }
}
