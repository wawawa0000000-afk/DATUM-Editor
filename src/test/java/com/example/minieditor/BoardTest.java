package com.example.minieditor;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * カードボード（BoardPane）の、グループ枠の入れ子と整列のテスト。
 * マウスとキーの操作は、ボードの部品へ直接イベントを送って確かめる（ウィンドウは出さない）。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.BoardTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class BoardTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            nestedClick();
            nestedDrag();
            emptyInsideGroup();
            tidyKeepsGroups();
            tidySelectedGroup();
            groupAroundGroup();
            alignKeys();
            unbounded();
        });
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------- 試すボードを作る ----------

    private static Map<String, Object> card(int id, String text, int x, int y, int w, int h, boolean group) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("x", x);
        m.put("y", y);
        m.put("w", w);
        m.put("h", h);
        m.put("color", 0);
        m.put("group", group);
        m.put("votes", 0);
        m.put("text", text);
        return m;
    }

    /**
     * 大きい枠「大」の中に小さい枠「小」があり、「小」の中にカード a・b、「大」の中に c、外に d・e がある。
     * 前の版は新しい枠を奥に入れていたので、あとから作った「小」を、一覧の先頭（いちばん奥）に置く。
     */
    private static BoardPane nestedBoard() {
        List<Object> cards = new ArrayList<>();
        cards.add(card(2, "小", 100, 200, 400, 300, true));
        cards.add(card(1, "大", 40, 40, 1000, 700, true));
        cards.add(card(3, "a", 130, 300, 120, 80, false));
        cards.add(card(4, "b", 300, 380, 120, 80, false));
        cards.add(card(5, "c", 700, 300, 120, 80, false));
        cards.add(card(6, "d", 1200, 100, 120, 80, false));
        cards.add(card(7, "e", 1300, 500, 120, 80, false));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("mode", "board");
        json.put("fontSize", 16);
        json.put("cards", cards);
        json.put("links", new ArrayList<>());
        BoardPane pane = BoardPane.fromJson(json);
        pane.setSize(1600, 1000);
        layoutAll(pane);
        return pane;
    }

    private static void layoutAll(Component c) {
        c.doLayout();
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                layoutAll(child);
            }
        }
    }

    /** 今のボードのカードを、文字 → 位置と大きさ の形で返す。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Rectangle> rects(BoardPane pane) {
        Map<String, Rectangle> result = new LinkedHashMap<>();
        for (Object o : (List<Object>) pane.toJson().get("cards")) {
            Map<String, Object> m = (Map<String, Object>) o;
            result.put((String) m.get("text"), new Rectangle(((Number) m.get("x")).intValue(), ((Number) m.get("y")).intValue(),
                    ((Number) m.get("w")).intValue(), ((Number) m.get("h")).intValue()));
        }
        return result;
    }

    private static BoardPane current;           // マウスの位置を、ボード上の座標から部品の上の座標へ直すため

    private static JComponent boardOf(BoardPane pane) {
        current = pane;
        for (Component c : pane.getComponents()) {
            if (c instanceof JScrollPane scroll) {
                return (JComponent) scroll.getViewport().getView();
            }
        }
        throw new IllegalStateException("ボードの部品が見つかりません");
    }

    /** x・y はボード上の座標。部品の上の位置に直して送る。 */
    private static void mouse(JComponent board, int id, int x, int y, int button, int modifiers) {
        java.awt.Point view = current.toView(x, y);
        x = view.x;
        y = view.y;
        int mask = switch (button) {
            case MouseEvent.BUTTON3 -> InputEvent.BUTTON3_DOWN_MASK;
            default -> InputEvent.BUTTON1_DOWN_MASK;
        };
        int mods = (id == MouseEvent.MOUSE_RELEASED ? 0 : mask) | modifiers;
        board.dispatchEvent(new MouseEvent(board, id, System.currentTimeMillis(), mods, x, y, 1, false, button));
    }

    private static void click(JComponent board, int x, int y, int modifiers) {
        mouse(board, MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1, modifiers);
        mouse(board, MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON1, modifiers);
    }

    private static void dragTo(JComponent board, int fromX, int fromY, int toX, int toY) {
        mouse(board, MouseEvent.MOUSE_PRESSED, fromX, fromY, MouseEvent.BUTTON1, 0);
        mouse(board, MouseEvent.MOUSE_DRAGGED, (fromX + toX) / 2, (fromY + toY) / 2, MouseEvent.BUTTON1, 0);
        mouse(board, MouseEvent.MOUSE_DRAGGED, toX, toY, MouseEvent.BUTTON1, 0);
        mouse(board, MouseEvent.MOUSE_RELEASED, toX, toY, MouseEvent.BUTTON1, 0);
    }

    /** キーは、フォーカスのない部品には届かないので、KeyListener を直接呼ぶ。 */
    private static void key(JComponent board, int code, int modifiers) {
        KeyEvent e = new KeyEvent(board, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED);
        for (KeyListener listener : board.getKeyListeners()) {
            listener.keyPressed(e);
        }
    }

    private static boolean centerInside(Rectangle item, Rectangle group) {
        return group.contains(item.getCenterX(), item.getCenterY());
    }

    // ---------- テスト ----------

    /** 大きい枠の中の小さい枠の見出しをクリックすると、小さい枠が選ばれる。 */
    private static void nestedClick() {
        BoardPane pane = nestedBoard();
        JComponent board = boardOf(pane);
        click(board, 150, 210, 0);              // 「小」の見出し（「大」の内側でもある）
        pane.deleteSelected();
        Map<String, Rectangle> r = rects(pane);
        check(!r.containsKey("小") && r.containsKey("大"), "入れ子の小さい枠の見出しをクリックすると、小さい枠が選ばれる");

        pane = nestedBoard();
        board = boardOf(pane);
        click(board, 600, 50, 0);               // 「大」の見出し（「小」の外）
        pane.deleteSelected();
        r = rects(pane);
        check(!r.containsKey("大") && r.containsKey("小"), "大きい枠の見出しをクリックすると、大きい枠が選ばれる");
    }

    /** 大きい枠を動かすと中の小さい枠ごと動き、小さい枠を動かしても大きい枠は動かない。 */
    private static void nestedDrag() {
        BoardPane pane = nestedBoard();
        JComponent board = boardOf(pane);
        Map<String, Rectangle> before = rects(pane);
        dragTo(board, 600, 50, 600 + 64, 50 + 32);
        Map<String, Rectangle> after = rects(pane);
        // 格子にそろうので、動く量はつかんだ枠の位置で決まる。全部が同じだけ動いていればよい
        int dx = after.get("大").x - before.get("大").x;
        int dy = after.get("大").y - before.get("大").y;
        boolean allMoved = dx != 0 && dy != 0;
        for (String name : List.of("大", "小", "a", "b", "c")) {
            allMoved &= after.get(name).x == before.get(name).x + dx && after.get(name).y == before.get(name).y + dy;
        }
        check(allMoved, "大きい枠の見出しをドラッグすると、中の小さい枠とカードが全部一緒に動く");
        check(after.get("d").equals(before.get("d")), "枠の外のカードは動かない");

        pane = nestedBoard();
        board = boardOf(pane);
        before = rects(pane);
        dragTo(board, 150, 210, 150 + 32, 210 + 16);
        after = rects(pane);
        dx = after.get("小").x - before.get("小").x;
        dy = after.get("小").y - before.get("小").y;
        check(dx != 0 && after.get("a").x == before.get("a").x + dx && after.get("b").y == before.get("b").y + dy,
                "小さい枠をドラッグすると、その中身が一緒に動く");
        check(after.get("大").equals(before.get("大")) && after.get("c").equals(before.get("c")),
                "小さい枠を動かしても、外側の大きい枠と、そこにだけ入っているカードは動かない");

        pane = nestedBoard();
        board = boardOf(pane);
        before = rects(pane);
        dragTo(board, 140, 310, 140 + 23, 310 + 5);   // 「a」を、半端な量だけ動かす
        after = rects(pane);
        check(after.get("a").x % 16 == 0 && after.get("a").y % 16 == 0, "ドラッグで動かすと、16 の格子にそろう");
    }

    /** 枠の中の空いた所からドラッグすると、枠は動かず、範囲選択になる。 */
    private static void emptyInsideGroup() {
        BoardPane pane = nestedBoard();
        JComponent board = boardOf(pane);
        Map<String, Rectangle> before = rects(pane);
        dragTo(board, 120, 280, 460, 470);      // 「小」の中の空いた所から、a と b を囲む
        Map<String, Rectangle> after = rects(pane);
        check(after.equals(before), "枠の中の空いた所からドラッグしても、何も動かない");
        pane.deleteSelected();
        Map<String, Rectangle> left = rects(pane);
        check(!left.containsKey("a") && !left.containsKey("b") && left.containsKey("小") && left.containsKey("大"),
                "枠の中で囲んだカードだけが選ばれる（枠は選ばれない）");
    }

    /** 全部を整列しても、カードは元の枠の中にとどまり、枠どうし・カードどうしが重ならない。 */
    private static void tidyKeepsGroups() {
        BoardPane pane = nestedBoard();
        pane.tidy();
        Map<String, Rectangle> r = rects(pane);
        Rectangle big = r.get("大");
        Rectangle small = r.get("小");
        check(centerInside(r.get("a"), small) && centerInside(r.get("b"), small), "整列のあとも a・b は小さい枠の中");
        check(big.contains(small), "整列のあとも、小さい枠は大きい枠の中");
        check(centerInside(r.get("c"), big) && !centerInside(r.get("c"), small), "整列のあとも c は大きい枠の中で、小さい枠の外");
        check(!centerInside(r.get("d"), big) && !centerInside(r.get("e"), big), "整列のあとも d・e は枠の外");
        check(!r.get("c").intersects(small), "大きい枠の中で、c と小さい枠が重ならない");
        check(!r.get("d").intersects(big) && !r.get("e").intersects(big), "外のカードと大きい枠が重ならない");
        check(!r.get("a").intersects(r.get("b")) && !r.get("d").intersects(r.get("e")), "カードどうしが重ならない");
        check(small.contains(r.get("a")) && small.contains(r.get("b")) && big.contains(r.get("c")),
                "枠は中のカードがすっぽり入る大きさになる");

        // もう一度整列しても、位置は変わらない（並びが入れ替わらない）
        pane.tidy();
        check(rects(pane).equals(r), "続けて整列しても、並びが変わらない");
    }

    /** 枠を1つ選んで整列すると、その枠の中だけが並ぶ。 */
    private static void tidySelectedGroup() {
        BoardPane pane = nestedBoard();
        JComponent board = boardOf(pane);
        click(board, 150, 210, 0);              // 「小」を選ぶ
        Map<String, Rectangle> before = rects(pane);
        pane.tidy();
        Map<String, Rectangle> after = rects(pane);
        check(after.get("c").equals(before.get("c")) && after.get("d").equals(before.get("d"))
                && after.get("大").equals(before.get("大")), "枠を選んで整列すると、枠の外は動かない");
        check(centerInside(after.get("a"), after.get("小")) && centerInside(after.get("b"), after.get("小")),
                "選んだ枠の中のカードは、その枠の中に並ぶ");
    }

    /** 枠を範囲選択で選び、C で囲むと、その枠を中に入れた大きい枠ができる。 */
    private static void groupAroundGroup() {
        BoardPane pane = nestedBoard();
        JComponent board = boardOf(pane);
        dragTo(board, 1150, 20, 1500, 650);     // d・e を囲む
        key(board, KeyEvent.VK_C, 0);
        pane.focusEditor();                     // 名前の入力欄を閉じる
        Map<String, Rectangle> r = rects(pane);
        Rectangle made = r.get("グループ");
        check(made != null && made.contains(r.get("d")) && made.contains(r.get("e")), "C で、選んだカードを囲む枠ができる");

        pane = nestedBoard();
        board = boardOf(pane);
        dragTo(board, 90, 190, 510, 510);       // 「小」をすっぽり囲む
        key(board, KeyEvent.VK_C, 0);
        pane.focusEditor();
        r = rects(pane);
        made = r.get("グループ");
        check(made != null && made.contains(r.get("小")), "範囲選択で選んだ枠を、さらに大きい枠で囲める");
        check(made != null && r.get("大").contains(made), "大きい枠の中で作った枠は、その中に収まる");

        // 新しい枠の見出しをクリックすると、外の「大」ではなく新しい枠が選ばれる
        if (made != null) {
            click(board, made.x + 40, made.y + 5, 0);
            pane.deleteSelected();
            r = rects(pane);
            check(!r.containsKey("グループ") && r.containsKey("大") && r.containsKey("小"),
                    "あとから作った外側の枠も、見出しのクリックで選べる");
        }
    }

    /** ボードに端はない：カードを左上（マイナスの位置）へも動かせ、中ボタンのドラッグで表示もそこへ動く。 */
    private static void unbounded() {
        BoardPane pane = nestedBoard();
        JComponent board = boardOf(pane);
        dragTo(board, 1210, 110, 1210 - 1400, 110 - 500);        // d を、左上の外へ
        Map<String, Rectangle> r = rects(pane);
        check(r.get("d").x < 0 && r.get("d").y < 0, "カードをマイナスの位置へ動かせる（" + r.get("d").x + ", " + r.get("d").y + "）");

        // 中ボタン（ホイールを押す）でドラッグすると、表示が動き、押した所のボード上の位置がマウスについてくる
        Map<String, Object> saved = pane.toJson();
        java.awt.Point before = current.toView(0, 0);
        java.awt.Point screenBefore = javax.swing.SwingUtilities.convertPoint(board, before, board.getParent());
        int sx = 300;
        int sy = 300;
        java.awt.Point v = current.toView(sx, sy);
        java.awt.Component port = board.getParent();
        java.awt.Point cursor = javax.swing.SwingUtilities.convertPoint(board, v, port);   // マウスの、スクロール枠から見た位置
        board.dispatchEvent(new MouseEvent(board, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON2_DOWN_MASK, v.x, v.y, 1, false, MouseEvent.BUTTON2));
        for (int step = 0; step < 10; step++) {
            // 表示が動くと部品の上の座標も変わるので、送るたびに、今の表示の位置で直す
            cursor.translate(200, 100);
            v = javax.swing.SwingUtilities.convertPoint(port, cursor, board);
            board.dispatchEvent(new MouseEvent(board, MouseEvent.MOUSE_DRAGGED, 0, InputEvent.BUTTON2_DOWN_MASK,
                    v.x, v.y, 1, false, MouseEvent.BUTTON2));
        }
        v = javax.swing.SwingUtilities.convertPoint(port, cursor, board);
        board.dispatchEvent(new MouseEvent(board, MouseEvent.MOUSE_RELEASED, 0, 0, v.x, v.y, 1, false, MouseEvent.BUTTON2));
        java.awt.Point screenAfter = javax.swing.SwingUtilities.convertPoint(board, current.toView(0, 0), board.getParent());
        check(screenAfter.x - screenBefore.x == 2000 && screenAfter.y - screenBefore.y == 1000,
                "中ボタンのドラッグで、ボードが端で止まらずに動く（" + (screenAfter.x - screenBefore.x) + ", " + (screenAfter.y - screenBefore.y) + "）");
        check(pane.toJson().get("cards").equals(saved.get("cards")), "表示を動かしても、カードの位置は変わらない");

        // マイナスの位置も保存して、読み戻せる
        BoardPane copy = BoardPane.fromJson(pane.toJson());
        check(rects(copy).get("d").equals(r.get("d")), "マイナスの位置も保存して、読み戻せる");
    }

    /** Shift+クリックで2枚選び、Shift+W で上にそろえる。 */
    private static void alignKeys() {
        BoardPane pane = nestedBoard();
        JComponent board = boardOf(pane);
        click(board, 1210, 110, 0);             // d
        click(board, 1310, 510, InputEvent.SHIFT_DOWN_MASK);     // e を足す
        key(board, KeyEvent.VK_W, InputEvent.SHIFT_DOWN_MASK);
        Map<String, Rectangle> r = rects(pane);
        check(r.get("d").y == 100 && r.get("e").y == 100, "Shift+W で、選んだカードの上のふちがそろう");
        key(board, KeyEvent.VK_D, InputEvent.SHIFT_DOWN_MASK);
        r = rects(pane);
        check(r.get("d").x == 1300 && r.get("e").x == 1300, "Shift+D で、右のふちがそろう");

        // Ctrl+クリックで、選択から外せる（重ならない位置で確かめる）
        pane = nestedBoard();
        board = boardOf(pane);
        click(board, 1210, 110, 0);
        click(board, 1310, 510, InputEvent.SHIFT_DOWN_MASK);
        click(board, 1210, 110, InputEvent.CTRL_DOWN_MASK);
        pane.deleteSelected();
        r = rects(pane);
        check(r.containsKey("d") && !r.containsKey("e"), "Ctrl+クリックで、選んだカードを選択から外せる");
    }
}
