package com.example.minieditor;

import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * 横書きの「打つ位置」（TypingPosition）のテスト。JUnit を使わず、main で動かす。
 * 高さの範囲、打つ行が画面の決まった高さに来ること（最初の行・途中の行・折り返し）、行を下から上へ並べて見せることを確かめる。
 * 設定ファイルには書かない（TypingPosition の値だけを変えて、最後に元に戻す）。
 */
public class TypingPositionTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  OK   " : "  失敗 ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        values();
        SwingUtilities.invokeAndWait(TypingPositionTest::holding);     // 画面の部品は、画面のスレッドで動かす
        SwingUtilities.invokeAndWait(TypingPositionTest::upward);
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void values() {
        int before = TypingPosition.position();
        try {
            TypingPosition.setPosition(0);
            check(TypingPosition.position() == TypingPosition.MIN, "上すぎる高さは、10% になる");
            TypingPosition.setPosition(100);
            check(TypingPosition.position() == TypingPosition.MAX, "下すぎる高さは、90% になる");
            TypingPosition.setPosition(35);
            check(TypingPosition.position() == 35, "範囲の中の高さは、そのまま");
        } finally {
            TypingPosition.setPosition(before);
        }
    }

    /** 打つ行が、画面の上から position ％の高さに来る。 */
    private static void holding() {
        boolean fixedBefore = TypingPosition.fixed();
        int positionBefore = TypingPosition.position();
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= 60; i++) {
                sb.append(i).append(" 行目の文章です。\n");
            }
            for (boolean wrap : new boolean[]{false, true}) {
                String what = wrap ? "（折り返しあり）" : "（折り返しなし）";
                TextEditorPane pane = new TextEditorPane(new Font(Font.SERIF, Font.PLAIN, 20));
                pane.loadText(sb.toString());
                pane.setWrap(wrap);
                pane.setSize(600, 400);
                layout(pane);
                JTextArea area = area(pane);
                JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, area);

                for (int position : new int[]{30, 50, 70}) {
                    TypingPosition.setFixed(true);
                    TypingPosition.setPosition(position);
                    pane.applyTypingPosition();
                    layout(pane);
                    int viewHeight = scroll.getViewport().getHeight();
                    int expected = viewHeight * position / 100;
                    for (int line : new int[]{0, 30, 59}) {
                        area.setCaretPosition(area.getLineStartOffset(line));
                        hold(pane);
                        Rectangle2D r = area.modelToView2D(area.getCaretPosition());
                        int onScreen = (int) Math.round(r.getCenterY()) - scroll.getViewport().getViewPosition().y;
                        check(Math.abs(onScreen - expected) <= 2,
                                (line + 1) + " 行目にいるとき、打つ行が上から " + position + "% に来る" + what + "（" + onScreen + "・" + expected + "）");
                    }
                }

                // 固定を切ると、上下の大きな余白が無くなる
                TypingPosition.setFixed(false);
                pane.applyTypingPosition();
                check(area.getMargin().top < 20, "固定を切ると、上の余白は元に戻る" + what);
            }
        } catch (Exception ex) {
            check(false, "確かめられませんでした: " + ex);
        } finally {
            TypingPosition.setFixed(fixedBefore);
            TypingPosition.setPosition(positionBefore);
        }
    }

    /** 行を下から上へ並べて見せる（文書の中身はそのまま）。 */
    private static void upward() {
        boolean before = TypingPosition.upward();
        try {
            for (boolean wrap : new boolean[]{false, true}) {
                String what = wrap ? "（折り返しあり）" : "（折り返しなし）";
                TypingPosition.setUpward(false);
                TextEditorPane pane = new TextEditorPane(new Font(Font.SERIF, Font.PLAIN, 20));
                pane.loadText("一行目\n二行目\n三行目");
                pane.setWrap(wrap);
                pane.setSize(600, 400);
                layout(pane);
                JTextArea area = area(pane);
                int two = area.getText().indexOf('二');
                int three = area.getText().indexOf('三');
                check(rect(area, 0).y < rect(area, three).y, "ふだんは、最初の行が上" + what);

                // 途中で切り替える：中身はそのまま、見え方だけ逆になる
                TypingPosition.setUpward(true);
                pane.applyTypingPosition();
                layout(pane);
                check(area.getText().equals("一行目\n二行目\n三行目"), "切り替えても、文書の中身の順番は変わらない" + what);
                Rectangle r1 = rect(area, 0);
                Rectangle r2 = rect(area, two);
                Rectangle r3 = rect(area, three);
                check(r3.y < r2.y && r2.y < r1.y, "最後の行がいちばん上、最初の行がいちばん下に並ぶ" + what);
                check(r2.y - r3.y == r3.height && r1.y - r2.y == r2.height, "行と行のあいだに、すき間も重なりもない" + what);

                // 書き足す：最後の行の終わりで改行すると、新しい行がいちばん上に来る（中身は後ろに足される）
                area.setCaretPosition(area.getDocument().getLength());
                area.replaceSelection("\n四行目");
                layout(pane);
                int four = area.getText().indexOf('四');
                check(area.getText().endsWith("三行目\n四行目"), "改行して書いた行は、文書の後ろに足される" + what);
                check(rect(area, four).y < rect(area, three).y && rect(area, three).y < rect(area, two).y,
                        "新しく書いた行が、いちばん上に来る（ほかの行は下へ送られる）" + what);

                // 押した位置：それぞれの行を押すと、その行に入る
                check(area.getLineOfOffset(area.viewToModel2D(new Point(r1.x + 30, rect(area, 0).y + 2))) == 0,
                        "下の行を押すと、最初の行に入る" + what);
                check(area.getLineOfOffset(area.viewToModel2D(new Point(r1.x + 30, rect(area, four).y + 2))) == 3,
                        "上の行を押すと、最後の行に入る" + what);
                int belowAll = rect(area, 0).y + rect(area, 0).height + 40;
                check(area.getLineOfOffset(area.viewToModel2D(new Point(r1.x + 30, belowAll))) == 0,
                        "行の無い下の所を押すと、いちばん下の行（最初の行）に入る" + what);

                // ↑↓ は、画面の上下に動く（JTextArea は、一度描くまで ↑↓ の位置を求めないので、先に描いておく）
                paint(area);
                area.setCaretPosition(two + 1);
                arrow(area, "caret-up");
                check(area.getLineOfOffset(area.getCaretPosition()) == 2, "↑ で、画面の上の行（文書では次の行）へ動く" + what);
                arrow(area, "caret-down");
                arrow(area, "caret-down");
                check(area.getLineOfOffset(area.getCaretPosition()) == 0, "↓ で、画面の下の行（文書では前の行）へ動く" + what);
                arrow(area, "caret-down");
                check(area.getLineOfOffset(area.getCaretPosition()) == 0, "いちばん下の行で ↓ を押しても、動かない" + what);

                // 元に戻すと、ふつうの並びに戻る
                TypingPosition.setUpward(false);
                pane.applyTypingPosition();
                layout(pane);
                check(rect(area, 0).y < rect(area, four).y, "切ると、ふつうの並びに戻る" + what);
            }

            // 折り返した1行の中の段は、上から下のまま
            TypingPosition.setUpward(true);
            TextEditorPane wrapped = new TextEditorPane(new Font(Font.SERIF, Font.PLAIN, 20));
            wrapped.loadText("短い行\n" + "あ".repeat(80));
            wrapped.setWrap(true);
            wrapped.setSize(400, 400);
            layout(wrapped);
            wrapped.applyTypingPosition();
            layout(wrapped);
            JTextArea w = area(wrapped);
            int longStart = w.getText().indexOf('あ');
            Rectangle head = rect(w, longStart);
            Rectangle tail = rect(w, w.getDocument().getLength());
            Rectangle shortLine = rect(w, 0);
            check(head.y < tail.y && tail.y < shortLine.y, "折り返した行は、頭が上・終わりが下で、その下に前の行が来る");
        } catch (Exception ex) {
            check(false, "確かめられませんでした: " + ex);
            ex.printStackTrace();
        } finally {
            TypingPosition.setUpward(before);
        }
    }

    private static void paint(JTextArea area) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(Math.max(1, area.getWidth()),
                Math.max(1, Math.min(area.getHeight(), 600)), java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        area.paint(g);
        g.dispose();
    }

    private static void arrow(JTextArea area, String action) {
        area.getActionMap().get(action).actionPerformed(new java.awt.event.ActionEvent(area, 0, action));
    }

    private static Rectangle rect(JTextArea area, int offset) throws javax.swing.text.BadLocationException {
        return area.modelToView2D(offset).getBounds();
    }

    private static void hold(TextEditorPane pane) throws ReflectiveOperationException {
        java.lang.reflect.Method method = TextEditorPane.class.getDeclaredMethod("holdCaret");
        method.setAccessible(true);
        method.invoke(pane);
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
}
