package com.example.minieditor.apple;

import java.awt.geom.CubicCurve2D;
import java.awt.geom.Rectangle2D;

/**
 * カードどうしを結ぶ矢印の形を決める。
 * 横に並んでいれば左右の辺の中央どうし、重なっている（または近すぎる）なら上下の辺の中央どうしを、
 * なめらかな曲線で結ぶ。描画には触らないので、テストでそのまま確かめられる。
 */
public final class BoardGeometry {

    /** 始点を辺から離す距離（線がカードに食い込まないように）。 */
    static final double START_GAP = 6;
    /** 終点を辺から離す距離（「く」の字の先端が辺に触れないように）。 */
    static final double END_GAP = 8;
    /** 横の隙間がこれより狭ければ、横並びとは見なさない。 */
    static final double MIN_SIDE_GAP = 24;

    private BoardGeometry() {
    }

    /** from から to への矢印の曲線（字が 15px のときの大きさ）。 */
    public static CubicCurve2D.Double arrow(Rectangle2D from, Rectangle2D to) {
        return arrow(from, to, 1);
    }

    /**
     * from から to への矢印の曲線。終点の向きは、2つ目の制御点から終点への向き。
     * k は大きさの倍率（カードの字の大きさ ÷ 15）。辺からのすき間・横並びとみなす隙間・曲がりの強さに掛ける。
     */
    public static CubicCurve2D.Double arrow(Rectangle2D from, Rectangle2D to, double k) {
        double gapRight = to.getMinX() - from.getMaxX();   // to が右にあるときの隙間
        double gapLeft = from.getMinX() - to.getMaxX();    // to が左にあるときの隙間
        double side = MIN_SIDE_GAP * k;
        double start = START_GAP * k;
        double end = END_GAP * k;
        if (gapRight >= side || gapLeft >= side) {
            int dir = gapRight >= side ? 1 : -1;
            double x1 = dir > 0 ? from.getMaxX() + start : from.getMinX() - start;
            double x2 = dir > 0 ? to.getMinX() - end : to.getMaxX() + end;
            double y1 = from.getCenterY();
            double y2 = to.getCenterY();
            double pull = bend(x2 - x1, k);
            return new CubicCurve2D.Double(x1, y1, x1 + dir * pull, y1, x2 - dir * pull, y2, x2, y2);
        }
        int dir = to.getCenterY() >= from.getCenterY() ? 1 : -1;
        double x1 = from.getCenterX();
        double x2 = to.getCenterX();
        double y1 = dir > 0 ? from.getMaxY() + start : from.getMinY() - start;
        double y2 = dir > 0 ? to.getMinY() - end : to.getMaxY() + end;
        double pull = bend(y2 - y1, k);
        return new CubicCurve2D.Double(x1, y1, x1, y1 + dir * pull, x2, y2 - dir * pull, x2, y2);
    }

    /** 曲がりの強さ。離れているほど大きく、近くても 24px（×倍率）は膨らませる。 */
    private static double bend(double distance, double k) {
        return Math.max(24 * k, Math.abs(distance) * 0.5);
    }
}
