package com.example.minieditor;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;

/**
 * 横書きで、カーソルが動いた・打ったあとに「描き直す」と頼んだ範囲だけを描き直せば、
 * 全部を描き直したときと同じ絵になることを確かめる（前の行と今の行だけを描き直す作りの確認）。
 * ふつう・折り返し・Markdown・ルビを上に表示・下から上へ書く、のそれぞれで、でたらめに動かす。
 */
public class CaretRepaintTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    /** 文字の部品が「ここを描き直して」と頼んだ範囲を、覚えておく。 */
    private static final class Recorder extends RepaintManager {
        JComponent watched;
        final List<Rectangle> dirty = new ArrayList<>();

        @Override
        public void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
            if (c == watched) {
                dirty.add(new Rectangle(x, y, w, h));
            }
        }
    }

    private static final Recorder RECORDER = new Recorder();

    private static final String PLAIN = """
            　彼は窓を開けた。朝の風が、机の上の原稿を一枚めくった。
            「今日は晴れだ」と彼は言った。長い長い一行は、折り返しのある画面では二段にも三段にもなって、次の行を下へ押していくことになる。

            　｜東京《とうきょう》の空は、思っていたよりも｜高《たか》かった。
            三行目。
            四行目は少し長めに書いておく。ここにもカーソルが来る。
            五行目。
            """.repeat(6);

    private static final String MARKDOWN = """
            # 大きい見出し

            ふつうの段落。**太字**と*傾けた字*と`コード`がある。

            ## 次の見出し

            - 一つ目
            - [ ] やること
            > 引用の行

            ---

            ```
            code block
            ```

            ### 小さい見出し
            終わりの行。長い長い一行は、折り返しのある画面では二段にも三段にもなって、次の行を下へ押していく。
            """.repeat(3);

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RepaintManager.setCurrentManager(RECORDER);
            boolean rubyBefore = TextEditorPane.rubyAbove;
            boolean upwardBefore = TypingPosition.upward();
            try {
                scenario("ふつう（折り返しなし）", PLAIN, false, null, false, false);
                scenario("折り返しあり", PLAIN, true, null, false, false);
                scenario("Markdown（折り返しなし）", MARKDOWN, false, Path.of("test.md"), false, false);
                scenario("Markdown（折り返しあり）", MARKDOWN, true, Path.of("test.md"), false, false);
                scenario("ルビを上に表示", PLAIN, true, null, true, false);
                scenario("下から上へ書く", PLAIN, false, null, false, true);
                scenario("下から上へ書く（折り返しあり）", PLAIN, true, null, false, true);
            } catch (Exception ex) {
                ex.printStackTrace();
                failures++;
            } finally {
                TextEditorPane.rubyAbove = rubyBefore;
                TypingPosition.setUpward(upwardBefore);
            }
        });
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void scenario(String name, String text, boolean wrap, Path path, boolean ruby, boolean upward)
            throws Exception {
        TextEditorPane.rubyAbove = ruby;
        TypingPosition.setUpward(upward);
        TextEditorPane pane = new TextEditorPane(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        if (path != null) {
            pane.setPath(path);
        }
        pane.loadText(text);
        pane.setWrap(wrap);
        pane.setSize(640, 420);
        layout(pane);
        layout(pane);
        JTextArea area = area(pane);
        JViewport viewport = (JViewport) area.getParent();
        RECORDER.watched = area;

        Random random = new Random(11);
        int partial = 0;
        int steps = 300;
        String firstBad = null;
        for (int i = 0; i < steps && firstBad == null; i++) {
            BufferedImage before = draw(area, null, null);
            Dimension size = area.getSize();
            Point position = viewport.getViewPosition();
            RECORDER.dirty.clear();
            Rectangle seenBefore = viewport.getViewRect();

            int length = area.getDocument().getLength();
            int kind = random.nextInt(10);
            String what;
            if (kind < 4) {
                area.setCaretPosition(Math.min(length, area.viewToModel2D(new Point(
                        seenBefore.x + random.nextInt(Math.max(1, seenBefore.width)),
                        seenBefore.y + random.nextInt(Math.max(1, seenBefore.height))))));
                what = "カーソルを移す";
            } else if (kind < 6) {
                area.replaceSelection(random.nextBoolean() ? "字" : "#");
                what = "1字打つ";
            } else if (kind == 6) {
                area.replaceSelection("\n");
                what = "改行する";
            } else if (kind == 7 && area.getCaretPosition() > 0) {
                area.replaceRange("", area.getCaretPosition() - 1, area.getCaretPosition());
                what = "1字消す";
            } else if (kind == 8) {
                area.moveCaretPosition(Math.max(0, Math.min(length, area.getCaretPosition() + random.nextInt(41) - 20)));
                what = "範囲を選ぶ";
            } else {
                int caret = area.getCaretPosition();
                area.setCaretPosition(Math.max(0, Math.min(length, caret + (random.nextBoolean() ? 1 : -1))));
                what = "カーソルを1つ動かす";
            }
            layout(pane);
            layout(pane);

            BufferedImage full = draw(area, null, null);
            // 大きさ・スクロールの位置が変わったときは、画面でも全部が描き直される
            boolean everything = !size.equals(area.getSize()) || !position.equals(viewport.getViewPosition());
            BufferedImage patched = before;
            if (everything) {
                patched = full;
            } else {
                partial++;
                for (Rectangle r : RECORDER.dirty) {
                    draw(area, r, patched);
                }
            }
            // 比べるのは、見えている範囲だけ（部品は、見えている範囲の地しか塗らない）
            Rectangle seen = viewport.getViewRect();
            if (!same(patched, full, seen)) {
                firstBad = (i + 1) + " 回目（" + what + "）" + diff(patched, full, seen) + " 頼んだ範囲 " + RECORDER.dirty
                        + " カーソル " + area.getCaretPosition() + " 選択 " + area.getSelectionStart() + "-" + area.getSelectionEnd();
            }
        }
        check(firstBad == null, name + "：頼んだ範囲だけ描き直しても、全部を描き直した絵と同じ"
                + (firstBad == null ? "" : " — 違ったのは " + firstBad));
        check(partial > steps / 3, name + "：全部の描き直しにならない操作が、十分にある（" + partial + " / " + steps + "）");

        // カーソルを1つ動かしただけなら、頼む範囲は画面の一部だけ
        area.setCaretPosition(0);
        layout(pane);
        RECORDER.dirty.clear();
        area.setCaretPosition(1);
        long asked = 0;
        for (Rectangle r : RECORDER.dirty) {
            asked += (long) r.intersection(new Rectangle(0, 0, area.getWidth(), 420)).width
                    * Math.max(0, r.intersection(new Rectangle(0, 0, area.getWidth(), 420)).height);
        }
        check(!RECORDER.dirty.isEmpty() && asked < (long) area.getWidth() * 420 / 2,
                name + "：同じ行の中でカーソルを動かしたとき、描き直すのは画面の半分より狭い");
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

    /** 文字の部品を画像に描く。clip があれば、その範囲だけを onto の上に描き直す。 */
    private static BufferedImage draw(JTextArea area, Rectangle clip, BufferedImage onto) {
        BufferedImage image = onto != null ? onto
                : new BufferedImage(Math.max(1, area.getWidth()), Math.max(1, Math.min(area.getHeight(), 700)),
                        BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        if (clip != null) {
            g.setClip(clip);
        }
        area.paint(g);
        g.dispose();
        return image;
    }

    /** 2つの絵が違っている範囲（調べるときの手がかり）。 */
    private static String diff(BufferedImage a, BufferedImage b, Rectangle seen) {
        Rectangle box = null;
        Rectangle in = seen.intersection(new Rectangle(0, 0, Math.min(a.getWidth(), b.getWidth()), Math.min(a.getHeight(), b.getHeight())));
        for (int y = in.y; y < in.y + in.height; y++) {
            for (int x = in.x; x < in.x + in.width; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    Rectangle point = new Rectangle(x, y, 1, 1);
                    box = box == null ? point : box.union(point);
                }
            }
        }
        return " 違う範囲 " + box;
    }

    private static boolean same(BufferedImage a, BufferedImage b, Rectangle seen) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        Rectangle in = seen.intersection(new Rectangle(0, 0, a.getWidth(), a.getHeight()));
        for (int y = in.y; y < in.y + in.height; y++) {
            for (int x = in.x; x < in.x + in.width; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }
}
