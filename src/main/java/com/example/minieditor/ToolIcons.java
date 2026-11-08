package com.example.minieditor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
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
 * ツールバーとメニューの項目に使うアイコン。画像は使わず、図形で描く（外部のファイルやライブラリを使わないため）。
 * どれも線の太さと角の丸みをそろえ、ボタンの文字の色の線だけで描く（青くなったときは白で描かれる）。
 * 色を付けないのは、主役の本文より目立たせないためと、4つの配色のどれでも同じように見えるようにするため。
 * 色は「今オンになっている」（青い地）と、配色の見本のように色そのものを選ぶときだけに使う。
 */
final class ToolIcons {

    /** 部品にこの名前で色を持たせると、アイコンをその色で描く。 */
    static final String ICON_COLOR = "datum.iconColor";

    private ToolIcons() {
    }

    /** アイコンの大きさ。ボタンの文字と同じくらいにする。 */
    static int size() {
        return Math.round(Theme.SMALL_SIZE * 1.15f);
    }

    /** 見本（配色・行間）のアイコンの大きさ。形の違いを見分けられるよう、ほかより一回り大きくする。 */
    static int barSize() {
        return Math.round(Theme.SMALL_SIZE * 1.3f);
    }

    /** 共通の下ごしらえ：なめらかに描き、線の太さをそろえる。 */
    private abstract static class Base implements Icon {
        private final boolean bar;      // 見本用の、一回り大きい大きさで描くか

        Base() {
            this(false);
        }

        Base(boolean bar) {
            this.bar = bar;
        }

        private int px() {
            return bar ? barSize() : size();
        }

        @Override
        public int getIconWidth() {
            return px();
        }

        @Override
        public int getIconHeight() {
            return px();
        }

        @Override
        public final void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.translate(x, y);
            // ふつうは部品の文字の色で描く。部品が ICON_COLOR を持っていれば、その色で描く（ツールバーのオンのボタンなど）
            Object color = c instanceof javax.swing.JComponent jc ? jc.getClientProperty(ICON_COLOR) : null;
            g2.setColor(color instanceof Color given ? given : c.getForeground());
            float s = px();
            g2.setStroke(new BasicStroke(Math.max(1.4f, s / 12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            draw(g2, s);
            g2.dispose();
        }

        abstract void draw(Graphics2D g2, float s);
    }

    /** 虫眼鏡（文書の中を検索する）。 */
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

    /** 校正：紙と、チェックの印（文字を入れず、ほかの線のアイコンと重さをそろえる）。 */
    static Icon proofread() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                g2.draw(page(s, 0.16f, 0.08f, 0.76f, 0.92f));
                g2.draw(new Line2D.Float(s * 0.30f, s * 0.40f, s * 0.56f, s * 0.40f));
                Path2D.Float check = new Path2D.Float();
                check.moveTo(s * 0.44f, s * 0.70f);
                check.lineTo(s * 0.58f, s * 0.84f);
                check.lineTo(s * 0.92f, s * 0.50f);
                g2.draw(check);
            }
        };
    }

    /** フォルダのサイドバー：画面の枠と、左に縦の仕切り（欄の出し入れの印。「開く」のフォルダとは分ける）。 */
    static Icon sidebar() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float m = s * 0.08f;
                g2.draw(new RoundRectangle2D.Float(m, s * 0.14f, s - m * 2, s * 0.72f, s * 0.18f, s * 0.18f));
                g2.draw(new Line2D.Float(s * 0.38f, s * 0.14f, s * 0.38f, s * 0.86f));
                g2.draw(new Line2D.Float(s * 0.18f, s * 0.34f, s * 0.28f, s * 0.34f));
                g2.draw(new Line2D.Float(s * 0.18f, s * 0.48f, s * 0.28f, s * 0.48f));
            }
        };
    }

    /** すべての操作を探す：項目の並びと、右下の虫眼鏡（文章の「検索」の虫眼鏡とは分ける）。 */
    static Icon commands() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                g2.draw(new Line2D.Float(s * 0.10f, s * 0.18f, s * 0.80f, s * 0.18f));
                g2.draw(new Line2D.Float(s * 0.10f, s * 0.38f, s * 0.60f, s * 0.38f));
                g2.draw(new Line2D.Float(s * 0.10f, s * 0.58f, s * 0.34f, s * 0.58f));
                float r = s * 0.16f;
                float cx = s * 0.60f;
                float cy = s * 0.66f;
                g2.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
                float d = (float) (r / Math.sqrt(2));
                g2.draw(new Line2D.Float(cx + d, cy + d, s * 0.90f, s * 0.92f));
            }
        };
    }

    /** 横書き ⇔ 縦書き を切り替え：向きの違う2本の矢印（入れ替える印。「新規（縦書き）」とは分ける）。 */
    static Icon swapWriting() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                g2.draw(new Line2D.Float(s * 0.12f, s * 0.32f, s * 0.86f, s * 0.32f));
                Path2D.Float a = new Path2D.Float();
                a.moveTo(s * 0.70f, s * 0.16f);
                a.lineTo(s * 0.86f, s * 0.32f);
                a.lineTo(s * 0.70f, s * 0.48f);
                g2.draw(a);
                g2.draw(new Line2D.Float(s * 0.14f, s * 0.68f, s * 0.88f, s * 0.68f));
                Path2D.Float b = new Path2D.Float();
                b.moveTo(s * 0.30f, s * 0.52f);
                b.lineTo(s * 0.14f, s * 0.68f);
                b.lineTo(s * 0.30f, s * 0.84f);
                g2.draw(b);
            }
        };
    }

    // ---------- ツールバーと、メニューの中の項目のアイコン ----------





    /** ボード：3枚のカードと、つなぐ線。 */
    static Icon board() {
        return new Base(true) {
            @Override
            void draw(Graphics2D g2, float s) {
                float r = s * 0.10f;
                g2.draw(new RoundRectangle2D.Float(s * 0.08f, s * 0.12f, s * 0.36f, s * 0.28f, r, r));
                g2.draw(new RoundRectangle2D.Float(s * 0.56f, s * 0.12f, s * 0.36f, s * 0.28f, r, r));
                g2.draw(new RoundRectangle2D.Float(s * 0.32f, s * 0.62f, s * 0.36f, s * 0.28f, r, r));
                g2.draw(new Line2D.Float(s * 0.44f, s * 0.26f, s * 0.56f, s * 0.26f));
                g2.draw(new Line2D.Float(s * 0.26f, s * 0.40f, s * 0.40f, s * 0.62f));
            }
        };
    }



    // ---------- 右上のボタンのアイコン（続き） ----------

    /** AI の会話欄：吹き出しと3つの点（会話の印。きらめきは「AI 連携」のメニューだけに使い、2つを見分けられるようにする）。 */
    static Icon chat() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                Path2D.Float b = new Path2D.Float();
                float l = s * 0.08f;
                float r = s * 0.92f;
                float t = s * 0.14f;
                float bt = s * 0.70f;
                float c = s * 0.16f;
                b.moveTo(l + c, t);
                b.lineTo(r - c, t);
                b.quadTo(r, t, r, t + c);
                b.lineTo(r, bt - c);
                b.quadTo(r, bt, r - c, bt);
                b.lineTo(s * 0.44f, bt);
                b.lineTo(s * 0.24f, s * 0.90f);           // しっぽ
                b.lineTo(s * 0.26f, bt);
                b.lineTo(l + c, bt);
                b.quadTo(l, bt, l, bt - c);
                b.lineTo(l, t + c);
                b.quadTo(l, t, l + c, t);
                b.closePath();
                g2.draw(b);
                for (float x : new float[]{0.32f, 0.50f, 0.68f}) {
                    dot(g2, s * x, s * 0.42f, s * 0.055f);
                }
            }
        };
    }

    /** 集中モード：画面の四隅のかぎ（全画面の印）。 */
    static Icon focus() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float a = s * 0.12f;
                float b = s * 0.88f;
                float k = s * 0.26f;
                for (float[] c : new float[][]{{a, a, 1, 1}, {b, a, -1, 1}, {a, b, 1, -1}, {b, b, -1, -1}}) {
                    Path2D.Float p = new Path2D.Float();
                    p.moveTo(c[0] + k * c[2], c[1]);
                    p.lineTo(c[0], c[1]);
                    p.lineTo(c[0], c[1] + k * c[3]);
                    g2.draw(p);
                }
            }
        };
    }

    /** その他：横に並んだ3つの点（残りの操作の一覧が開く）。 */
    static Icon more() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float r = Math.max(1.2f, s * 0.08f);
                for (float cx : new float[]{s * 0.22f, s * 0.5f, s * 0.78f}) {
                    g2.fill(new Ellipse2D.Float(cx - r, s * 0.5f - r, r * 2, r * 2));
                }
            }
        };
    }

    // ---------- メニューの中の項目のアイコン（よく使う項目だけ。全部に付けると、かえって探しにくくなる） ----------

    /** 開く：フォルダと、中から出てくる上向きの矢印。 */
    static Icon open() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                Path2D.Float f = new Path2D.Float();
                f.moveTo(s * 0.34f, s * 0.80f);
                f.lineTo(s * 0.14f, s * 0.80f);
                f.quadTo(s * 0.08f, s * 0.80f, s * 0.08f, s * 0.74f);
                f.lineTo(s * 0.08f, s * 0.24f);
                f.quadTo(s * 0.08f, s * 0.18f, s * 0.14f, s * 0.18f);
                f.lineTo(s * 0.36f, s * 0.18f);
                f.lineTo(s * 0.44f, s * 0.28f);
                f.lineTo(s * 0.86f, s * 0.28f);
                f.quadTo(s * 0.92f, s * 0.28f, s * 0.92f, s * 0.34f);
                f.lineTo(s * 0.92f, s * 0.74f);
                f.quadTo(s * 0.92f, s * 0.80f, s * 0.86f, s * 0.80f);
                f.lineTo(s * 0.66f, s * 0.80f);
                g2.draw(f);
                g2.draw(new Line2D.Float(s * 0.50f, s * 0.44f, s * 0.50f, s * 0.94f));
                Path2D.Float a = new Path2D.Float();
                a.moveTo(s * 0.36f, s * 0.58f);
                a.lineTo(s * 0.50f, s * 0.44f);
                a.lineTo(s * 0.64f, s * 0.58f);
                g2.draw(a);
            }
        };
    }

    /** 新規：紙と「＋」。 */
    static Icon newPage() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                g2.draw(page(s, 0.18f, 0.08f, 0.78f, 0.92f));
                g2.draw(new Line2D.Float(s * 0.48f, s * 0.44f, s * 0.48f, s * 0.76f));
                g2.draw(new Line2D.Float(s * 0.32f, s * 0.60f, s * 0.64f, s * 0.60f));
            }
        };
    }

    /** 新規（縦書き）：紙の中に、縦に並んだ行（右から読む）。 */
    static Icon newVertical() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                g2.draw(page(s, 0.18f, 0.08f, 0.78f, 0.92f));
                g2.draw(new Line2D.Float(s * 0.58f, s * 0.40f, s * 0.58f, s * 0.80f));
                g2.draw(new Line2D.Float(s * 0.38f, s * 0.24f, s * 0.38f, s * 0.64f));
            }
        };
    }

    /** 保存：受け皿へ向かう下向きの矢印。 */
    static Icon save() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                tray(g2, s);
                g2.draw(new Line2D.Float(s * 0.50f, s * 0.10f, s * 0.50f, s * 0.58f));
                Path2D.Float p = new Path2D.Float();
                p.moveTo(s * 0.32f, s * 0.40f);
                p.lineTo(s * 0.50f, s * 0.58f);
                p.lineTo(s * 0.68f, s * 0.40f);
                g2.draw(p);
            }
        };
    }

    /** 書き出す：受け皿から出ていく上向きの矢印。 */
    static Icon export() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                tray(g2, s);
                g2.draw(new Line2D.Float(s * 0.50f, s * 0.10f, s * 0.50f, s * 0.58f));
                Path2D.Float p = new Path2D.Float();
                p.moveTo(s * 0.32f, s * 0.28f);
                p.lineTo(s * 0.50f, s * 0.10f);
                p.lineTo(s * 0.68f, s * 0.28f);
                g2.draw(p);
            }
        };
    }

    /** 版の履歴：時計。 */
    static Icon history() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float m = s * 0.10f;
                g2.draw(new Ellipse2D.Float(m, m, s - m * 2, s - m * 2));
                Path2D.Float p = new Path2D.Float();
                p.moveTo(s * 0.50f, s * 0.26f);
                p.lineTo(s * 0.50f, s * 0.50f);
                p.lineTo(s * 0.68f, s * 0.62f);
                g2.draw(p);
            }
        };
    }

    /** 設定：歯車。 */
    static Icon gear() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float c = s / 2f;
                Path2D.Float p = new Path2D.Float();
                int teeth = 8;
                for (int i = 0; i < teeth * 2; i++) {
                    double a0 = Math.PI * 2 * i / (teeth * 2);
                    double a1 = Math.PI * 2 * (i + 1) / (teeth * 2);
                    float r = (i % 2 == 0) ? s * 0.42f : s * 0.32f;
                    float x0 = c + (float) Math.cos(a0) * r;
                    float y0 = c + (float) Math.sin(a0) * r;
                    if (i == 0) {
                        p.moveTo(x0, y0);
                    } else {
                        p.lineTo(x0, y0);
                    }
                    p.lineTo(c + (float) Math.cos(a1) * r, c + (float) Math.sin(a1) * r);
                }
                p.closePath();
                g2.draw(p);
                float r = s * 0.13f;
                g2.draw(new Ellipse2D.Float(c - r, c - r, r * 2, r * 2));
            }
        };
    }

    /** 元に戻す（back が真）／やり直し：丸く曲がった矢印。 */
    static Icon undo(boolean back) {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                Graphics2D g = (Graphics2D) g2.create();
                if (!back) {
                    g.translate(s, 0);
                    g.scale(-1, 1);         // やり直しは、左右を裏返した形
                }
                Path2D.Float p = new Path2D.Float();
                p.moveTo(s * 0.20f, s * 0.40f);
                p.lineTo(s * 0.62f, s * 0.40f);
                p.curveTo(s * 0.92f, s * 0.40f, s * 0.92f, s * 0.84f, s * 0.62f, s * 0.84f);
                p.lineTo(s * 0.40f, s * 0.84f);
                g.draw(p);
                Path2D.Float h = new Path2D.Float();
                h.moveTo(s * 0.36f, s * 0.22f);
                h.lineTo(s * 0.18f, s * 0.40f);
                h.lineTo(s * 0.36f, s * 0.58f);
                g.draw(h);
                g.dispose();
            }
        };
    }

    /** 切り取り：はさみ。 */
    static Icon cut() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float r = s * 0.13f;
                g2.draw(new Ellipse2D.Float(s * 0.26f - r, s * 0.76f - r, r * 2, r * 2));
                g2.draw(new Ellipse2D.Float(s * 0.74f - r, s * 0.76f - r, r * 2, r * 2));
                g2.draw(new Line2D.Float(s * 0.34f, s * 0.66f, s * 0.72f, s * 0.10f));
                g2.draw(new Line2D.Float(s * 0.66f, s * 0.66f, s * 0.28f, s * 0.10f));
            }
        };
    }

    /** コピー：重なった2枚の紙。 */
    static Icon copy() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float r = s * 0.14f;
                g2.draw(new RoundRectangle2D.Float(s * 0.34f, s * 0.08f, s * 0.54f, s * 0.62f, r, r));
                Path2D.Float p = new Path2D.Float();
                p.moveTo(s * 0.22f, s * 0.30f);
                p.lineTo(s * 0.12f, s * 0.30f);
                p.lineTo(s * 0.12f, s * 0.92f);
                p.lineTo(s * 0.62f, s * 0.92f);
                p.lineTo(s * 0.62f, s * 0.82f);
                g2.draw(p);
            }
        };
    }

    /** 貼り付け：クリップボード。 */
    static Icon paste() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float r = s * 0.14f;
                g2.draw(new RoundRectangle2D.Float(s * 0.16f, s * 0.16f, s * 0.68f, s * 0.76f, r, r));
                g2.draw(new RoundRectangle2D.Float(s * 0.36f, s * 0.06f, s * 0.28f, s * 0.18f, s * 0.08f, s * 0.08f));
                g2.draw(new Line2D.Float(s * 0.32f, s * 0.48f, s * 0.68f, s * 0.48f));
                g2.draw(new Line2D.Float(s * 0.32f, s * 0.66f, s * 0.58f, s * 0.66f));
            }
        };
    }

    /** 本文の文字を大きく（bigger が真）／小さく：「A」と「＋」「−」。 */
    static Icon textSize(boolean bigger) {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                Path2D.Float a = new Path2D.Float();
                a.moveTo(s * 0.08f, s * 0.88f);
                a.lineTo(s * 0.34f, s * 0.20f);
                a.lineTo(s * 0.60f, s * 0.88f);
                g2.draw(a);
                g2.draw(new Line2D.Float(s * 0.18f, s * 0.62f, s * 0.50f, s * 0.62f));
                g2.draw(new Line2D.Float(s * 0.68f, s * 0.26f, s * 0.92f, s * 0.26f));
                if (bigger) {
                    g2.draw(new Line2D.Float(s * 0.80f, s * 0.14f, s * 0.80f, s * 0.38f));
                }
            }
        };
    }

    /** 拡大（in が真）／縮小：虫眼鏡の中に「＋」「−」。 */
    static Icon zoom(boolean in) {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                float r = s * 0.30f;
                float c = s * 0.42f;
                g2.draw(new Ellipse2D.Float(c - r, c - r, r * 2, r * 2));
                float d = (float) (r / Math.sqrt(2));
                g2.draw(new Line2D.Float(c + d, c + d, s * 0.88f, s * 0.88f));
                g2.draw(new Line2D.Float(c - r * 0.5f, c, c + r * 0.5f, c));
                if (in) {
                    g2.draw(new Line2D.Float(c, c - r * 0.5f, c, c + r * 0.5f));
                }
            }
        };
    }

    /** 見出し一覧：点と行の並び。 */
    static Icon outline() {
        return new Base() {
            @Override
            void draw(Graphics2D g2, float s) {
                for (int i = 0; i < 3; i++) {
                    float y = s * (0.24f + 0.26f * i);
                    float indent = i == 1 ? s * 0.14f : 0;
                    dot(g2, s * 0.14f + indent, y, s * 0.05f);
                    g2.draw(new Line2D.Float(s * 0.32f + indent, y, s * 0.88f, y));
                }
            }
        };
    }

    /** 配色の見本：その配色の地の色に、文字の色の線とアクセントの青の点。色そのものを選ぶので、ここだけは色で描く。 */
    static Icon swatch(Color background, Color text, Color accent) {
        return new Base(true) {         // 見本なので、ほかの項目のアイコンより一回り大きく
            @Override
            void draw(Graphics2D g2, float s) {
                RoundRectangle2D.Float box = new RoundRectangle2D.Float(s * 0.04f, s * 0.12f, s * 0.92f, s * 0.76f, s * 0.22f, s * 0.22f);
                Color line = g2.getColor();
                g2.setColor(background);
                g2.fill(box);
                g2.setColor(text);
                g2.draw(new Line2D.Float(s * 0.22f, s * 0.40f, s * 0.62f, s * 0.40f));
                g2.draw(new Line2D.Float(s * 0.22f, s * 0.60f, s * 0.50f, s * 0.60f));
                g2.setColor(accent);
                dot(g2, s * 0.76f, s * 0.60f, s * 0.08f);
                g2.setColor(line);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(box);                       // 地の色がメニューと同じでも、枠で見分けられるように
            }
        };
    }

    /**
     * 行間・字間・余白の段階の見本。kind は 0 縦の行間・1 縦の字間・2 余白、level は 0 狭い・1 標準・2 広い。
     * 文字で「狭い」と読むより先に、形で違いがわかるようにする。
     */
    static Icon spacing(int kind, int level) {
        return new Base(true) {         // 見本なので、ほかの項目のアイコンより一回り大きく
            @Override
            void draw(Graphics2D g2, float s) {
                float gap = 0.14f + 0.08f * level;
                if (kind == 0) {
                    // 縦の行：段階が広いほど、行と行のあいだが空く
                    float c = 0.5f;
                    for (float x : new float[]{c - gap, c, c + gap}) {
                        g2.draw(new Line2D.Float(s * x, s * 0.14f, s * x, s * 0.86f));
                    }
                } else if (kind == 1) {
                    // 縦に並んだ字（短い横線を1字とみなす）：段階が広いほど、字と字のあいだが空く。小さくても違いがわかるよう、差を大きくとる
                    float step = 0.16f + 0.13f * level;
                    float y = 0.5f - step;
                    for (int i = 0; i < 3; i++) {
                        g2.draw(new Line2D.Float(s * 0.32f, s * (y + step * i), s * 0.68f, s * (y + step * i)));
                    }
                } else {
                    // 紙の外枠と、中の文字の範囲：段階が広いほど、まわりが空く
                    float m = 0.06f;
                    g2.draw(new RoundRectangle2D.Float(s * m, s * m, s * (1 - m * 2), s * (1 - m * 2), s * 0.16f, s * 0.16f));
                    float in = m + 0.08f + 0.07f * level;
                    g2.fill(new RoundRectangle2D.Float(s * in, s * in, s * (1 - in * 2), s * (1 - in * 2), s * 0.06f, s * 0.06f));
                }
            }
        };
    }

    // ---------- 形の部品 ----------

    /** 右上の角を折った紙（範囲は、大きさ s に対する割合）。 */
    private static Path2D.Float page(float s, float left, float top, float right, float bottom) {
        float fold = s * 0.22f;
        float r = s * 0.06f;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(s * right - fold, s * top);
        p.lineTo(s * left + r, s * top);
        p.quadTo(s * left, s * top, s * left, s * top + r);
        p.lineTo(s * left, s * bottom - r);
        p.quadTo(s * left, s * bottom, s * left + r, s * bottom);
        p.lineTo(s * right - r, s * bottom);
        p.quadTo(s * right, s * bottom, s * right, s * bottom - r);
        p.lineTo(s * right, s * top + fold);
        p.closePath();
        p.moveTo(s * right - fold, s * top);
        p.lineTo(s * right - fold, s * top + fold);
        p.lineTo(s * right, s * top + fold);
        return p;
    }

    /** 右上から左下へ斜めに置いた鉛筆。 */
    private static Path2D.Float pencil(float s) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(s * 0.12f, s * 0.88f);           // 芯の先
        p.lineTo(s * 0.18f, s * 0.64f);
        p.lineTo(s * 0.64f, s * 0.18f);
        p.lineTo(s * 0.82f, s * 0.36f);
        p.lineTo(s * 0.36f, s * 0.82f);
        p.closePath();
        p.moveTo(s * 0.18f, s * 0.64f);
        p.lineTo(s * 0.36f, s * 0.82f);
        p.moveTo(s * 0.56f, s * 0.26f);           // 消しゴムとの境
        p.lineTo(s * 0.74f, s * 0.44f);
        return p;
    }

    /** 4つの先がとがった星（きらめき）。cx, cy は中心、r は先までの長さ。 */
    private static Path2D.Float sparkle(float cx, float cy, float r) {
        float k = r * 0.22f;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(cx, cy - r);
        p.quadTo(cx + k, cy - k, cx + r, cy);
        p.quadTo(cx + k, cy + k, cx, cy + r);
        p.quadTo(cx - k, cy + k, cx - r, cy);
        p.quadTo(cx - k, cy - k, cx, cy - r);
        p.closePath();
        return p;
    }

    /** 保存・書き出しの受け皿（下の、口の開いた箱）。 */
    private static void tray(Graphics2D g2, float s) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(s * 0.12f, s * 0.60f);
        p.lineTo(s * 0.12f, s * 0.80f);
        p.quadTo(s * 0.12f, s * 0.90f, s * 0.22f, s * 0.90f);
        p.lineTo(s * 0.78f, s * 0.90f);
        p.quadTo(s * 0.88f, s * 0.90f, s * 0.88f, s * 0.80f);
        p.lineTo(s * 0.88f, s * 0.60f);
        g2.draw(p);
    }

    /** 塗りつぶした小さな丸。 */
    private static void dot(Graphics2D g2, float cx, float cy, float r) {
        g2.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
    }
}
