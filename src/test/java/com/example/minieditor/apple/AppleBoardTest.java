package com.example.minieditor.apple;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/** main で動かすテスト（JUnit は使わない）。1つでも失敗すると終了コード 1。 */
public final class AppleBoardTest {

    private static int failures = 0;

    private static void check(boolean ok, String name) {
        if (!ok) {
            failures++;
            System.out.println("NG  " + name);
        } else {
            System.out.println("OK  " + name);
        }
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        // 横に並んでいれば、左右の中央同士を結ぶ（始点は右端+6、終点は左端-8）
        Rectangle2D a = new Rectangle2D.Double(0, 0, 100, 50);
        Rectangle2D right = new Rectangle2D.Double(200, 10, 100, 50);
        CubicCurve2D.Double h = BoardGeometry.arrow(a, right);
        check(h.x1 == 106 && h.y1 == 25, "横: 始点は右端の中央から6px外");
        check(h.x2 == 192 && h.y2 == 35, "横: 終点は左端の中央の8px手前");
        check(h.ctrly2 == h.y2 && h.ctrlx2 < h.x2, "横: 終点には左から水平に入る（先端の向き）");
        CubicCurve2D.Double back = BoardGeometry.arrow(right, a);
        check(back.x1 == 194 && back.x2 == 108, "横(左向き): 左端-6 から 右端+8");

        // 重なっていれば、上下の中央同士を結ぶ
        Rectangle2D below = new Rectangle2D.Double(20, 200, 100, 50);
        CubicCurve2D.Double v = BoardGeometry.arrow(a, below);
        check(v.y1 == 56 && v.y2 == 192, "縦(下): 下端+6 から 上端-8");
        Rectangle2D above = new Rectangle2D.Double(20, -200, 100, 50);
        CubicCurve2D.Double u = BoardGeometry.arrow(a, above);
        check(u.y1 == -6 && u.y2 == -142, "縦(上): 上端-6 から 下端+8");

        // 大きさの倍率：字が 2 倍なら、辺からのすき間も 2 倍（始点は右端+12、終点は左端-16）
        CubicCurve2D.Double big = BoardGeometry.arrow(a, right, 2);
        check(big.x1 == 112 && big.x2 == 184, "倍率 2: すき間も 2 倍");
        check(BoardLook.arrowScale(30f) == 2.0 && BoardLook.arrowScale(AppleTheme.CARD_FONT) == 1.0, "倍率は字の大きさ ÷ 15");
        Rectangle2D far = new Rectangle2D.Double(140, 0, 100, 50);       // 隙間 40：ふつうは横並び、倍率 2 では縦扱い
        check(BoardGeometry.arrow(a, far).x1 == 106 && BoardGeometry.arrow(a, far, 2).x1 == 50, "横並びとみなす隙間も倍率に合わせる");

        // 近すぎる横並び(24px未満)は縦扱いにする
        Rectangle2D near = new Rectangle2D.Double(110, 0, 100, 50);
        check(BoardGeometry.arrow(a, near).x1 == 50, "近い横並びは上下の中央を使う");

        // 文字は背景に対して 4.5:1 以上（半透明の色は、下の地に重ねてから測る）
        for (AppleTheme t : new AppleTheme[] {AppleTheme.DARK, AppleTheme.LIGHT}) {
            String n = t.dark ? "ダーク" : "ライト";
            Color inner = AppleTheme.over(t.groupFill2, AppleTheme.over(t.groupFill, t.canvas));
            check(AppleTheme.contrast(AppleTheme.over(t.textSecondary, inner), inner) >= 4.5,
                n + ": 入れ子の枠の名前が 4.5:1 以上");
            check(AppleTheme.contrast(t.text, t.bg) >= 4.5, n + ": バーの文字が 4.5:1 以上");
            check(AppleTheme.contrast(AppleTheme.over(t.textSecondary, t.bg), t.bg) >= 4.5,
                n + ": 状態バーの薄い文字が 4.5:1 以上");
        }
        check(AppleTheme.contrast(AppleTheme.LIGHT.cardText, AppleTheme.LIGHT.card) >= 4.5, "カードの文字が 4.5:1 以上");
        check(AppleTheme.contrast(AppleTheme.over(AppleTheme.LIGHT.cardPlaceholder, AppleTheme.LIGHT.card),
            AppleTheme.LIGHT.card) >= 4.5, "カードの「無題」が 4.5:1 以上");

        // カードの6色：文字と「無題」が 4.5:1 以上。黄はちょうど #F8E5A0
        check(BoardLook.colors() == 6 && BoardLook.fill(0).getRGB() == AppleTheme.LIGHT.card.getRGB(), "6色で、黄は #F8E5A0");
        for (int i = 0; i < BoardLook.colors(); i++) {
            Color f = BoardLook.fill(i);
            check(AppleTheme.contrast(BoardLook.text(i), f) >= 4.5, "色" + i + ": 文字が 4.5:1 以上");
            check(AppleTheme.contrast(AppleTheme.over(BoardLook.placeholder(i), f), f) >= 4.5, "色" + i + ": 「無題」が 4.5:1 以上");
        }
        check(BoardLook.themeFor(new Color(0x1e1e1e)) == AppleTheme.DARK
            && BoardLook.themeFor(Color.WHITE) == AppleTheme.LIGHT, "暗い地はダーク、明るい地はライト");

        // 余白・角丸は 4 の倍数
        check(AppleTheme.RADIUS_CARD % 2 == 0 && AppleTheme.RADIUS_GROUP % 2 == 0
            && AppleTheme.RADIUS_GROUP_INNER % 4 == 0 && AppleTheme.SPACE % 4 == 0, "角丸と余白の値");

        // 折り返し：3行を超えたら末尾を「…」に、改行はそこで行を変える
        BufferedImage tiny = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D tg = tiny.createGraphics();
        FontMetrics fm = tg.getFontMetrics(AppleTheme.font(Font.BOLD, AppleTheme.CARD_FONT));
        int w = fm.stringWidth("あああ");
        List<String> lines = BoardPanel.wrap("ああああああああああああ", fm, w, 3);
        check(lines.size() == 3 && lines.get(2).endsWith("…"), "4行分の文は3行で「…」になる: " + lines);
        List<String> fit = BoardPanel.wrap("あああ\nいい", fm, w, 3);
        check(fit.size() == 2 && "あああ".equals(fit.get(0)) && "いい".equals(fit.get(1)), "改行で行を変え、「…」は付けない: " + fit);
        tg.dispose();

        // 操作：その場で書く（確定と取り消し）・Tab で次へ・ズームの段階
        BoardPanel b = AppleBoardDemo.sampleBoard(AppleTheme.DARK);
        BoardPanel.Card first = b.cards().get(0);
        b.beginEdit(first);
        b.typeForTest("着陸");
        b.commitEdit();
        check("着陸".equals(first.text) && !b.isEditing(), "Enter で確定すると文字が入る");
        b.beginEdit(first);
        b.typeForTest("消える");
        b.cancelEdit();
        check("着陸".equals(first.text) && !b.isEditing(), "Esc で取り消すと元に戻る");
        b.step(1);
        check(b.selected() == first, "Tab で最初のカードを選ぶ");
        for (int i = 1; i < b.cards().size(); i++) {
            b.step(1);
        }
        check(b.selected() == b.cards().get(b.cards().size() - 1), "Tab で最後のカードまで進む");
        b.step(1);
        check(b.selected() == null, "最後の次は選択を外す（外へフォーカスを渡す）");
        for (int i = 0; i < 5; i++) {
            b.zoomBy(1);
        }
        check(b.zoom() == 1.5, "拡大は 150% で止まる");
        for (int i = 0; i < 5; i++) {
            b.zoomBy(-1);
        }
        check(b.zoom() == 0.75, "縮小は 75% で止まる");

        // ボードが例外なく描けて、背景色で塗りつぶされていること（ダーク・ライト）
        for (AppleTheme t : new AppleTheme[] {AppleTheme.DARK, AppleTheme.LIGHT}) {
            BoardPanel p = AppleBoardDemo.sampleBoard(t);
            p.setSize(1020, 720);
            BufferedImage img = new BufferedImage(1020, 720, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                p.paint(g);
                for (int z = 0; z < 3; z++) {
                    p.zoomBy(1);
                    p.paint(g);
                }
                check(true, "描画: 例外なし");
            } catch (RuntimeException e) {
                check(false, "描画で例外: " + e);
            } finally {
                g.dispose();
            }
            check(img.getRGB(2, 2) != 0, "描画: 背景が塗られている");
        }

        if (failures > 0) {
            System.out.println(failures + " 件失敗");
            System.exit(1);
        }
        System.out.println("すべて成功");
    }
}
