package com.example.minieditor;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.BooleanSupplier;

import javax.swing.Icon;

/**
 * 右上のボタンに使うアイコン。画像は使わず、図形で描く（外部のファイルやライブラリを使わないため）。
 * どれも線の太さと角の丸みをそろえ、ボタンの文字の色で描く（青くなったときは白で描かれる）。
 */
final class ToolIcons {

    private ToolIcons() {
    }

    /** アイコンの大きさ。ボタンの文字と同じくらいにする。 */
    static int size() {
        return Math.round(Theme.SMALL_SIZE * 1.15f);
    }

    /** 共通の下ごしらえ：なめらかに描き、線の太さをそろえる。 */
    private abstract static class Base implements Icon {
        @Override
        public int getIconWidth() {
            return size();
        }

        @Override
        public int getIconHeight() {
            return size();
        }

        @Override
        public final void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.translate(x, y);
            g2.setColor(c.getForeground());
            float s = size();
            g2.setStroke(new BasicStroke(Math.max(1.4f, s / 12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            draw(g2, s);
            g2.dispose();
        }

        abstract void draw(Graphics2D g2, float s);
    }

    /** 虫眼鏡（すべての操作を探す）。 */
    static Icon search() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float r = s * 0.30f;
                float cx = s * 0.42f;
                float cy = s * 0.42f;
                g2.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
                float d = (float) (r / Math.sqrt(2));
                g2.draw(new Line2D.Float(cx + d, cy + d, s * 0.86f, s * 0.86f));
            }
        };
    }

    /** フォルダ（線だけ。左上につまみ）。 */
    static Icon folder() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float left = s * 0.10f;
                float right = s * 0.90f;
                float top = s * 0.22f;
                float bottom = s * 0.80f;
                float tabEnd = s * 0.42f;
                float tabDrop = s * 0.12f;
                float round = s * 0.08f;
                Path2D.Float p = new Path2D.Float();
                p.moveTo(left, bottom - round);
                p.lineTo(left, top + round);
                p.quadTo(left, top, left + round, top);
                p.lineTo(tabEnd - round, top);
                p.lineTo(tabEnd, top + tabDrop);                    // つまみの右の斜め
                p.lineTo(right - round, top + tabDrop);
                p.quadTo(right, top + tabDrop, right, top + tabDrop + round);
                p.lineTo(right, bottom - round);
                p.quadTo(right, bottom, right - round, bottom);
                p.lineTo(left + round, bottom);
                p.quadTo(left, bottom, left, bottom - round);
                p.closePath();
                g2.draw(p);
            }
        };
    }

    /** 画面の分割：角の丸い四角を、真ん中の線で2つに分ける。vertical が true なら上下に分ける（横線）。 */
    static Icon split(BooleanSupplier vertical) {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float m = s * 0.12f;
                g2.draw(new RoundRectangle2D.Float(m, m + s * 0.04f, s - m * 2, s - m * 2 - s * 0.08f, s * 0.2f, s * 0.2f));
                if (vertical.getAsBoolean()) {
                    g2.draw(new Line2D.Float(m, s / 2f, s - m, s / 2f));
                } else {
                    g2.draw(new Line2D.Float(s / 2f, m + s * 0.04f, s / 2f, s - m - s * 0.04f));
                }
            }
        };
    }

    /** 校正：角の丸い枠の中に「校」（はんこのような形）。 */
    static Icon proofread() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float m = s * 0.06f;
                g2.draw(new RoundRectangle2D.Float(m, m, s - m * 2, s - m * 2, s * 0.28f, s * 0.28f));
                g2.setFont(new Font("Dialog", Font.BOLD, Math.round(s * 0.62f)));
                FontMetrics fm = g2.getFontMetrics();
                String text = "校";
                float x = (s - fm.stringWidth(text)) / 2f;
                float y = (s - fm.getAscent() - fm.getDescent()) / 2f + fm.getAscent();
                g2.drawString(text, x, y);
            }
        };
    }
}
