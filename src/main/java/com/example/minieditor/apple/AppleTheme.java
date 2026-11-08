package com.example.minieditor.apple;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Apple的デザインの色・形・書体をまとめたもの。
 * 見た目の値はすべてここに置き、描画側は値を直接書かない。
 */
public final class AppleTheme {

    /** 余白・角丸は 8 の倍数（と 4）に揃える。 */
    public static final int SPACE = 8;
    public static final int RADIUS_CARD = 14;
    public static final int RADIUS_GROUP = 22;
    public static final int RADIUS_GROUP_INNER = 16;

    /** カードの文字の大きさと、内側の余白（左右・上）。 */
    public static final float CARD_FONT = 15f;
    public static final int CARD_PAD_X = 14;
    public static final int CARD_PAD_Y = 12;
    /** カードに出す行の数。これを超えたら末尾を「…」にする。 */
    public static final int CARD_LINES = 3;
    /** グループの名前の文字の大きさ。 */
    public static final float GROUP_FONT = 13f;
    /** 矢印の太さと、先端の「く」の字の腕の長さ・開き（ラジアン）。 */
    public static final float ARROW_WIDTH = 1.6f;
    public static final double ARROW_ARM = 7;
    public static final double ARROW_SPREAD = 0.62;
    /** 選択のリングの太さ。 */
    public static final float RING_WIDTH = 3f;
    /** 背景の点の間隔。 */
    public static final int DOT_STEP = 24;
    /** 持ち上げたカードの拡大率。 */
    public static final double LIFT_SCALE = 1.03;

    public final boolean dark;
    public final Color bg;
    public final Color canvas;
    public final Color text;
    public final Color textSecondary;
    public final Color hair;
    public final Color groupFill;
    public final Color groupFill2;
    public final Color groupLine;
    public final Color arrow;
    public final Color dot;
    public final Color accent;
    public final Color card;
    public final Color cardText;
    public final Color cardPlaceholder;

    public static final AppleTheme DARK = new AppleTheme(true);
    public static final AppleTheme LIGHT = new AppleTheme(false);

    private AppleTheme(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = new Color(0x1c1c1e);
            canvas = new Color(0x161618);
            text = new Color(0xf5f5f7);
            textSecondary = new Color(235, 235, 245, 158);
            hair = new Color(255, 255, 255, 31);
            groupFill = new Color(255, 255, 255, 12);
            groupFill2 = new Color(255, 255, 255, 17);
            groupLine = new Color(255, 255, 255, 38);
            arrow = new Color(138, 198, 255, 230);
            dot = new Color(255, 255, 255, 20);
            accent = new Color(0x0a84ff);
        } else {
            bg = new Color(0xf5f5f7);
            canvas = new Color(0xfbfbfd);
            text = new Color(0x1d1d1f);
            // 添付の値（179）は、入れ子の枠の地の上で 4.5:1 に届かなかったので濃くした
            textSecondary = new Color(60, 60, 67, 205);
            hair = new Color(0, 0, 0, 26);
            groupFill = new Color(0, 0, 0, 8);
            groupFill2 = new Color(0, 0, 0, 12);
            groupLine = new Color(0, 0, 0, 36);
            arrow = new Color(0, 113, 227, 217);
            dot = new Color(0, 0, 0, 23);
            accent = new Color(0x0a84ff);
        }
        card = new Color(0xf8e5a0);
        cardText = new Color(0x3b2f00);
        // 添付の値（153）は 4.5:1 に届かなかったので濃くした
        cardPlaceholder = new Color(59, 47, 0, 190);
    }

    // ---- 書体：入っている日本語フォントの中から、Apple に近い順に選ぶ ----

    private static final String[] FAMILIES = {
        "SF Pro Text", "Hiragino Sans", "Hiragino Kaku Gothic ProN",
        "Yu Gothic UI", "Meiryo UI", "Noto Sans JP", "Noto Sans CJK JP", "SansSerif"
    };
    private static String family;

    public static synchronized Font font(int style, float size) {
        if (family == null) {
            Set<String> have = new HashSet<>();
            for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames()) {
                have.add(f);
            }
            family = "SansSerif";
            for (String f : FAMILIES) {
                if (have.contains(f)) {
                    family = f;
                    break;
                }
            }
        }
        Map<TextAttribute, Object> a = new HashMap<>();
        a.put(TextAttribute.FAMILY, family);
        a.put(TextAttribute.SIZE, size);
        // DEMIBOLD は、多くの日本語フォントでふつうの太さに落ちるので、太字は BOLD にする
        a.put(TextAttribute.WEIGHT,
            (style & Font.BOLD) != 0 ? TextAttribute.WEIGHT_BOLD : TextAttribute.WEIGHT_REGULAR);
        a.put(TextAttribute.TRACKING, 0.02f);
        return new Font(a);
    }

    /** なめらかな描画に切り替える。 */
    public static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    /**
     * ぼかしの代わりに、少しずつ広がる半透明の角丸を重ねて、やわらかい影を作る。
     * lift が大きいほど（持ち上げたほど）影は下へ遠く、広くなる。
     */
    public static void softShadow(Graphics2D g, double x, double y, double w, double h,
                                  double radius, double lift) {
        int layers = 14;
        double spread = 6 + lift * 1.2;
        double dy = 2 + lift * 0.5;
        for (int i = 0; i < layers; i++) {
            double t = (double) i / layers;
            // カードより少し内側から広げ始め、下の縁に硬い帯が出ないようにする
            double grow = -3 + (spread + 3) * t;
            float alpha = (float) (0.028 * (1 - t) * (1 - t) + 0.002);
            g.setColor(new Color(0f, 0f, 0f, (float) Math.min(1.0, alpha * (1 + lift / 24.0))));
            g.fill(new RoundRectangle2D.Double(
                x - grow, y - grow + dy, w + grow * 2, h + grow * 2, radius + grow, radius + grow));
        }
        // 接地の影（すぐ下の細い影）
        g.setColor(new Color(0f, 0f, 0f, 0.14f));
        g.fill(new RoundRectangle2D.Double(x, y + 1, w, h, radius, radius));
    }

    // ---- コントラスト（WCAG の式）----

    /** 半透明の色 top を、不透明な色 under の上に重ねた色。 */
    public static Color over(Color top, Color under) {
        double a = top.getAlpha() / 255.0;
        return new Color(
            (int) Math.round(top.getRed() * a + under.getRed() * (1 - a)),
            (int) Math.round(top.getGreen() * a + under.getGreen() * (1 - a)),
            (int) Math.round(top.getBlue() * a + under.getBlue() * (1 - a)));
    }

    /** 2つの不透明な色のコントラスト比（1〜21）。本文は 4.5 以上にする。 */
    public static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
