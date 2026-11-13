package com.example.minieditor;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * 横書きの行の高さ（Spacing.row）のテスト。JUnit を使わず、main で動かす。
 * 設定の値の範囲と表示、行の位置（折り返しあり・なし・Markdown）、字が行の真ん中に描かれること、クリックの位置を確かめる。
 * 設定ファイルには書かない（Spacing の値だけを変えて、最後に元に戻す）。
 */
public class RowHeightTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  OK   " : "  失敗 ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        values();
        SwingUtilities.invokeAndWait(RowHeightTest::layouts);      // 画面の部品は、画面のスレッドで動かす
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 値の範囲と、メニューに出す表示。 */
    private static void values() {
        int before = Spacing.row();
        try {
            Spacing.setRow(50);
            check(Spacing.row() == Spacing.ROW_MIN, "標準より低い値は、標準（1.0 倍）になる");
            Spacing.setRow(900);
            check(Spacing.row() == Spacing.ROW_MAX, "3.0 倍より高い値は、3.0 倍になる");
            Spacing.setRow(135);
            check(Spacing.row() == 135 && Math.abs(Spacing.rowFactor() - 1.35f) < 1e-6, "範囲の中の値は、そのまま");
        } finally {
            Spacing.setRow(before);
        }
        check(Spacing.rowLabel(100).equals("1.0 倍"), "100 の表示は「1.0 倍」");
        check(Spacing.rowLabel(120).equals("1.2 倍"), "120 の表示は「1.2 倍」");
        check(Spacing.rowLabel(135).equals("1.35 倍"), "135 の表示は「1.35 倍」");
        check(Spacing.rowLabel(205).equals("2.05 倍"), "205 の表示は「2.05 倍」");
        check(Spacing.rowLabel(300).equals("3.0 倍"), "300 の表示は「3.0 倍」");
    }

    private static void layouts() {
        int before = Spacing.row();
        try {
            String text = "あいうえお\nかきくけこ\nさしすせそ\nたちつてと\n";
            for (boolean wrap : new boolean[]{false, true}) {
                String what = wrap ? "（折り返しあり）" : "（折り返しなし）";
                Spacing.setRow(100);
                TextEditorPane pane = pane(text, wrap, null);
                JTextArea area = area(pane);
                int plain = rect(area, 0).height;
                int third = text.indexOf('さ');
                int y100 = rect(area, third).y - rect(area, 0).y;

                Spacing.setRow(150);
                pane.applyRowHeight();
                layout(pane);
                Rectangle first = rect(area, 0);
                int tall = first.height;
                check(Math.abs(tall - Math.round(plain * 1.5)) <= 1, "1.5 倍で、行の高さが 1.5 倍になる" + what + " " + plain + "→" + tall);
                check(rect(area, third).y - first.y == tall * 2 && tall * 2 > y100, "3行目の位置が、高くした行2つぶん下になる" + what);
                int unit = area.getScrollableUnitIncrement(area.getVisibleRect(), javax.swing.SwingConstants.VERTICAL, 1);
                check(unit == tall, "スクロールの1行ぶんも、高くした行に合う" + what);

                // 字は行の真ん中：行の上と下に、字の無い所がほぼ同じだけ空く
                BufferedImage image = draw(area);
                int[] ink = inkRows(image, first.x, first.x + area.getFontMetrics(area.getFont()).stringWidth("あいうえお"),
                        first.y, first.y + tall);
                check(ink != null, "高くした行にも、字が描かれる" + what);
                if (ink != null) {
                    int top = ink[0] - first.y;
                    int bottom = first.y + tall - 1 - ink[1];
                    check(Math.abs(top - bottom) <= plain / 3, "字は、行の真ん中あたりに描かれる" + what + "（上 " + top + "・下 " + bottom + "）");
                }

                // クリックの位置：3行目の、上の空いた所を押しても、3行目に入る
                int clicked = area.viewToModel2D(new Point(first.x + 2, rect(area, third).y + 2));
                check(area.getLineOfOffset(clicked) == 2, "高くした行の上の空いた所を押しても、その行に入る" + what);
                if (!wrap) {
                    save(image, "row-height-150.png");
                }

                // 標準に戻すと、元の高さに戻る
                Spacing.setRow(100);
                pane.applyRowHeight();
                layout(pane);
                check(rect(area, 0).height == plain, "1.0 倍に戻すと、元の高さに戻る" + what);
            }

            // Markdown：見出しの行も、ふつうの行も、高くなる
            String md = "# 見出し\n本文の行\n本文の行\n";
            Spacing.setRow(100);
            TextEditorPane mdPane = pane(md, false, Path.of("test.md"));
            JTextArea mdArea = area(mdPane);
            int head100 = rect(mdArea, 0).height;
            int body100 = rect(mdArea, md.indexOf('本')).height;
            Spacing.setRow(200);
            mdPane.applyRowHeight();
            layout(mdPane);
            int head200 = rect(mdArea, 0).height;
            int body200 = rect(mdArea, md.indexOf('本')).height;
            check(head100 > body100, "Markdown の見出しの行は、本文より高い（" + head100 + "・" + body100 + "）");
            check(Math.abs(body200 - body100 * 2) <= 1, "Markdown の本文の行が、2倍になる（" + body100 + "→" + body200 + "）");
            check(Math.abs(head200 - head100 * 2) <= 1, "Markdown の見出しの行も、2倍になる（" + head100 + "→" + head200 + "）");
            int second = md.indexOf('本');
            check(rect(mdArea, second).y - rect(mdArea, 0).y == head200, "見出しの次の行は、高くした見出しのすぐ下に来る");
            save(draw(mdArea), "row-height-md-200.png");
        } catch (Exception ex) {
            check(false, "画像に描いて確かめられませんでした: " + ex);
        } finally {
            Spacing.setRow(before);
        }
    }

    private static TextEditorPane pane(String text, boolean wrap, Path path) throws ReflectiveOperationException {
        TextEditorPane pane = new TextEditorPane(new Font(Font.SERIF, Font.PLAIN, 20));
        if (path != null) {
            pane.setPath(path);
        }
        pane.loadText(text);
        pane.setWrap(wrap);
        pane.setSize(600, 400);
        layout(pane);
        layout(pane);
        area(pane).setCaretPosition(0);
        return pane;
    }

    private static JTextArea area(TextEditorPane pane) throws ReflectiveOperationException {
        java.lang.reflect.Field field = TextEditorPane.class.getDeclaredField("area");
        field.setAccessible(true);
        return (JTextArea) field.get(pane);
    }

    /** 画面に出さずに、部品の並びと大きさを決める。 */
    private static void layout(java.awt.Component c) {
        if (c instanceof java.awt.Container container) {
            container.doLayout();
            for (java.awt.Component child : container.getComponents()) {
                layout(child);
            }
        }
    }

    private static Rectangle rect(JTextArea area, int offset) throws javax.swing.text.BadLocationException {
        return area.modelToView2D(offset).getBounds();
    }

    private static BufferedImage draw(JTextArea area) {
        BufferedImage image = new BufferedImage(Math.max(1, area.getWidth()), Math.max(1, Math.min(area.getHeight(), 600)),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        area.paint(g);
        g.dispose();
        return image;
    }

    /** x0〜x1・y0〜y1 の中で、字の色（地と違う色）がある一番上と一番下の行。無ければ null。 */
    private static int[] inkRows(BufferedImage image, int x0, int x1, int y0, int y1) {
        int background = image.getRGB(x0, y0);
        int top = -1;
        int bottom = -1;
        for (int y = Math.max(0, y0); y < Math.min(y1, image.getHeight()); y++) {
            for (int x = Math.max(0, x0); x < Math.min(x1, image.getWidth()); x++) {
                if (image.getRGB(x, y) != background) {
                    if (top < 0) {
                        top = y;
                    }
                    bottom = y;
                    break;
                }
            }
        }
        return top < 0 ? null : new int[]{top, bottom};
    }

    /** 見た目を目で確かめるための画像（システムプロパティ rowHeight.out にフォルダがあるときだけ書く）。 */
    private static void save(BufferedImage image, String name) {
        String dir = System.getProperty("rowHeight.out");
        if (dir == null) {
            return;
        }
        try {
            javax.imageio.ImageIO.write(image, "png", Path.of(dir, name).toFile());
        } catch (java.io.IOException ex) {
            System.out.println("     画像を書けませんでした: " + ex);
        }
    }
}
