package com.example.minieditor.apple;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * カードボード（BoardPane）の描き方を、AppleTheme の値でまとめたもの。
 * BoardPane は、位置と状態を渡してここを呼ぶだけにする（色や太さを BoardPane に書かない）。
 */
public final class BoardLook {

    /**
     * カードの6色（黄・緑・青・赤・紫・灰。並びは今までの色の番号と同じ）。
     * 黄は AppleTheme.card。ほかもそれに合わせた淡い色にした。
     */
    private static final Color[] FILLS = {
        new Color(0xF8E5A0), new Color(0xCFEAC2), new Color(0xC9DEF5),
        new Color(0xF6CDC6), new Color(0xDECFF0), new Color(0xE0E0E5)
    };
    /** それぞれの色の上の文字（同じ色みの、ずっと暗い色。どれも 4.5:1 を大きく超える）。 */
    private static final Color[] TEXTS = {
        new Color(0x3B2F00), new Color(0x1C3A10), new Color(0x0F2C4C),
        new Color(0x4A1610), new Color(0x2F1B4C), new Color(0x2A2A2E)
    };

    private BoardLook() {
    }

    public static int colors() {
        return FILLS.length;
    }

    public static Color fill(int color) {
        return FILLS[Math.floorMod(color, FILLS.length)];
    }

    public static Color text(int color) {
        return TEXTS[Math.floorMod(color, TEXTS.length)];
    }

    /** 文字の無いカードに出す「無題」の色（文字色を少し透かす）。 */
    public static Color placeholder(int color) {
        Color t = text(color);
        return new Color(t.getRed(), t.getGreen(), t.getBlue(), 190);
    }

    /** 配色の地が暗いか明るいかで、ダークかライトを選ぶ。 */
    public static AppleTheme themeFor(Color background) {
        double l = (0.299 * background.getRed() + 0.587 * background.getGreen() + 0.114 * background.getBlue()) / 255;
        return l < 0.5 ? AppleTheme.DARK : AppleTheme.LIGHT;
    }

    // ---- カード ----

    /**
     * カードの影と地を描く。lifted は持ち上げている（引きずっている・糸の元）とき。
     * 拡大は呼ぶ側で g に掛ける（文字も一緒に大きくするため）。
     */
    public static void paintCard(Graphics2D g, double x, double y, double w, double h, int color, boolean lifted) {
        double r = AppleTheme.RADIUS_CARD;
        AppleTheme.softShadow(g, x, y, w, h, r, lifted ? 18 : 6);
        g.setColor(fill(color));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, r, r));
    }

    /** 選択のリング（アクセント色、カードの外側に沿う）。色だけでなく形で選択を示す。 */
    public static void paintRing(Graphics2D g, AppleTheme t, double x, double y, double w, double h, double radius) {
        double half = AppleTheme.RING_WIDTH / 2;
        Stroke old = g.getStroke();
        g.setColor(t.accent);
        g.setStroke(new BasicStroke(AppleTheme.RING_WIDTH));
        g.draw(new RoundRectangle2D.Double(x - half, y - half, w + half * 2, h + half * 2,
            radius + half * 2, radius + half * 2));
        g.setStroke(old);
    }

    // ---- グループ枠 ----

    /** 枠の角丸。いちばん外の枠は大きく、入れ子の枠は少し小さく。 */
    public static double groupRadius(boolean nested) {
        return nested ? AppleTheme.RADIUS_GROUP_INNER : AppleTheme.RADIUS_GROUP;
    }

    /**
     * グループ枠の地と細い線を描く。色の違いは、地にごく薄く混ぜて残す（色ごとの意味を消さない）。
     * band は見出しの帯の高さ（つかむと枠ごと動く所を、少しだけ濃くする）。
     */
    public static void paintGroup(Graphics2D g, AppleTheme t, double x, double y, double w, double h,
                                  int color, boolean nested, double band) {
        double r = groupRadius(nested);
        RoundRectangle2D shape = new RoundRectangle2D.Double(x, y, w, h, r, r);
        g.setColor(nested ? t.groupFill2 : t.groupFill);
        g.fill(shape);
        Color tint = fill(color);
        g.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), t.dark ? 14 : 40));
        g.fill(shape);
        if (band > 0) {
            Graphics2D b = (Graphics2D) g.create();
            b.clip(new Rectangle2D.Double(x, y, w, band));
            b.setColor(t.groupFill);
            b.fill(shape);
            b.dispose();
        }
        Stroke old = g.getStroke();
        g.setColor(t.groupLine);
        g.setStroke(new BasicStroke(1f));
        g.draw(shape);
        g.setStroke(old);
    }

    /** 枠の名前の色（半透明の文字）。 */
    public static Color groupTitle(AppleTheme t) {
        return t.textSecondary;
    }

    // ---- 矢印 ----

    /**
     * 矢印の大きさの倍率。カードの字の大きさに比べて決める（字が 15px のとき 1）。
     * 字を大きくすると、カードと同じだけ、線の太さ・先端の「く」の字・辺からのすき間も大きくなる。
     */
    public static double arrowScale(float cardFontSize) {
        return cardFontSize / AppleTheme.CARD_FONT;
    }

    /** カードからカードへの矢印（なめらかな曲線と、先端の小さな「く」の字）。alpha は 0〜255、k は大きさの倍率。 */
    public static void paintArrow(Graphics2D g, AppleTheme t, Rectangle2D from, Rectangle2D to, int alpha, double k) {
        CubicCurve2D.Double c = BoardGeometry.arrow(from, to, k);
        Color color = withAlpha(t.arrow, alpha);
        Stroke old = g.getStroke();
        g.setColor(color);
        g.setStroke(new BasicStroke((float) (AppleTheme.ARROW_WIDTH * k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(c);
        chevron(g, c.x2, c.y2, Math.atan2(c.y2 - c.ctrly2, c.x2 - c.ctrlx2), k);
        g.setStroke(old);
    }

    /**
     * 矢印が描かれうる範囲（曲線の制御点まで含めた四角を、線の太さと先端の「く」の字のぶん広げたもの）。
     * カードが上下に重なっていたり近かったりすると、曲線は2枚を囲む四角の外へふくらむので、
     * 描くかどうか（描く範囲に入るか）は、こちらで判断する。
     */
    public static Rectangle2D arrowBounds(Rectangle2D from, Rectangle2D to, double k) {
        Rectangle2D r = BoardGeometry.arrow(from, to, k).getBounds2D();     // 制御点も含む（曲線は必ずこの中）
        double pad = (AppleTheme.ARROW_ARM + AppleTheme.ARROW_WIDTH) * k;
        return new Rectangle2D.Double(r.getX() - pad, r.getY() - pad, r.getWidth() + pad * 2, r.getHeight() + pad * 2);
    }

    /**
     * まっすぐな糸（引いている途中・縮んで消えるとき）。dashed なら点線、head なら先に「く」の字。
     * 太さと先端の形は、保存される矢印とそろえる。
     */
    public static void paintLine(Graphics2D g, Color color, double x1, double y1, double x2, double y2,
                                 boolean dashed, boolean head, double k) {
        if (Math.hypot(x2 - x1, y2 - y1) < 1) {
            return;
        }
        Stroke old = g.getStroke();
        float width = (float) (AppleTheme.ARROW_WIDTH * k);
        float dash = (float) (6 * k);
        g.setStroke(dashed
            ? new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{dash, dash}, 0f)
            : new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(color);
        g.draw(new Line2D.Double(x1, y1, x2, y2));
        if (head) {
            g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            chevron(g, x2, y2, Math.atan2(y2 - y1, x2 - x1), k);
        }
        g.setStroke(old);
    }

    /** (x, y) を先端に、angle の向きへ進む「く」の字を描く。 */
    private static void chevron(Graphics2D g, double x, double y, double angle, double k) {
        double arm = AppleTheme.ARROW_ARM * k;
        for (int s = -1; s <= 1; s += 2) {
            double a = angle + Math.PI + s * AppleTheme.ARROW_SPREAD;
            g.draw(new Line2D.Double(x, y, x + arm * Math.cos(a), y + arm * Math.sin(a)));
        }
    }

    public static Color withAlpha(Color c, int alpha) {
        int a = Math.max(0, Math.min(255, c.getAlpha() * alpha / 255));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }
}
