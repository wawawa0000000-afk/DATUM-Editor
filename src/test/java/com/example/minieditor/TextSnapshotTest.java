package com.example.minieditor;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.util.Random;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * 横書きの「全文の写し」を覚えておく仕組み（TextEditorPane.getText）のテスト。
 * 文書が変わらない間は同じ写しを返し、変わったら必ず新しい中身を返すことを確かめる。
 */
public class TextSnapshotTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(TextSnapshotTest::run);
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run() {
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 16);
        TextEditorPane pane = new TextEditorPane(font);
        JTextArea area = (JTextArea) viewOf(pane);

        check(pane.getText().isEmpty(), "新しい文書は空");
        pane.loadText("一行目\n二行目");
        check(pane.getText().equals("一行目\n二行目"), "読み込むと、その中身になる");
        check(pane.getText() == pane.getText(), "変わらない間は、同じ写しを返す（写し直さない）");

        area.insert("あ", 0);
        check(pane.getText().equals("あ一行目\n二行目"), "打つと、新しい中身になる");
        area.replaceRange("", 0, 1);
        check(pane.getText().equals("一行目\n二行目"), "消すと、新しい中身になる");
        area.replaceRange("壱", 0, 1);
        check(pane.getText().equals("壱行目\n二行目"), "同じ長さで置き換えても、新しい中身になる");
        pane.undo();
        check(pane.getText().equals(area.getText()), "元に戻すと、戻した中身になる");

        // 同じ文書を映す、もう1つのタブ
        TextEditorPane other = (TextEditorPane) pane.newView();
        JTextArea otherArea = (JTextArea) viewOf(other);
        check(other.getText() == pane.getText(), "同じ文書を映すタブは、写しも同じ1つ");
        otherArea.insert("別のタブから", 0);
        check(pane.getText().startsWith("別のタブから") && pane.getText().equals(other.getText()),
                "別のタブで打っても、どちらのタブからも新しい中身が読める");
        area.append("終わり");
        check(other.getText().endsWith("終わり"), "元のタブで打っても、別のタブから新しい中身が読める");

        // でたらめに打って・消して、いつも文書の中身と同じか
        Random random = new Random(7);
        boolean same = true;
        for (int i = 0; i < 2000 && same; i++) {
            JTextArea target = random.nextBoolean() ? area : otherArea;
            int length = target.getDocument().getLength();
            if (length > 0 && random.nextInt(3) == 0) {
                int start = random.nextInt(length);
                target.replaceRange(random.nextBoolean() ? "" : "替", start, Math.min(length, start + 1 + random.nextInt(3)));
            } else {
                target.insert(random.nextBoolean() ? "字" : "\n", random.nextInt(length + 1));
            }
            if (random.nextInt(4) == 0) {
                pane.undo();
            }
            same = pane.getText().equals(area.getText()) && other.getText().equals(area.getText());
        }
        check(same, "でたらめに 2000 回打って・消して・戻しても、いつも文書の中身と同じ");

        // 片方のタブを閉じる（文書から切り離す）
        String before = pane.getText();
        other.detachShared();
        check(other.getText().isEmpty(), "切り離したタブは、空の文書を返す");
        check(pane.getText().equals(before), "切り離したあとも、残ったタブの中身は変わらない");
        area.insert("続き", 0);
        check(pane.getText().equals("続き" + before), "切り離したあとも、残ったタブで打てば新しい中身になる");
    }

    private static Component viewOf(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JScrollPane scroll) {
                return scroll.getViewport().getView();
            }
            if (child instanceof Container inner) {
                Component found = viewOf(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
