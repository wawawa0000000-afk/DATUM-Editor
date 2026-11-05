package com.example.minieditor;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.Font;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * 入力補助（InputAssist・横書きの TextEditorPane・縦書きの VerticalTextPane）のテスト。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.InputAssistTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 *
 * 日本語入力（IME）の変換中は、ここでは再現できないので対象外。
 * 画面は出さず、部品を作ってキーの知らせを送るだけで確かめる。
 */
public class InputAssistTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        rules();
        layout();
        // 窓を出さずに、キーの知らせを部品へ届けるため、フォーカスの管理を一時的に差し替える（テストの中だけ）。
        // 差し替えないと、フォーカスを持つ部品がないので、キーの知らせは部品に届かない
        KeyboardFocusManager original = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        KeyboardFocusManager.setCurrentKeyboardFocusManager(new DefaultKeyboardFocusManager() {
            @Override
            public boolean dispatchEvent(AWTEvent e) {
                return false;           // 振り分けはせず、部品の処理（キーの受け取り・打つ処理）に任せる
            }
        });
        try {
            SwingUtilities.invokeAndWait(InputAssistTest::horizontal);   // 画面の部品は、画面のスレッドで動かす
            SwingUtilities.invokeAndWait(InputAssistTest::vertical);
        } finally {
            KeyboardFocusManager.setCurrentKeyboardFocusManager(original);
        }
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void rules() {
        check("」".equals(InputAssist.closingFor('「')) && "』".equals(InputAssist.closingFor('『'))
                && "）".equals(InputAssist.closingFor('（')), "開きかっこ「『（に、対になる閉じかっこを返す");
        check(InputAssist.closingFor('a') == null && InputAssist.closingFor('・') == null,
                "かっこでない文字（a・）は null を返す");
        check(InputAssist.isClosing('」') && InputAssist.isClosing('』') && InputAssist.isClosing('）'),
                "閉じかっこ」』）は閉じかっことして扱う");
        check(!InputAssist.isClosing('「'), "開きかっこ「は閉じかっことして扱わない");
        check(InputAssist.INDENT.equals("　") && InputAssist.INDENT.length() == 1,
                "段落頭の字は全角スペース1字（\\u3000）");
        check(InputAssist.paragraphIndent().isEmpty() || !InputAssist.indentOnBreak,
                "初期状態（indentOnBreak が false）では、Enter の挿入は空");
        InputAssist.indentOnBreak = true;
        check(InputAssist.paragraphIndent().equals(InputAssist.INDENT),
                "indentOnBreak が true のとき、Enter の挿入は全角スペース");
        InputAssist.indentOnBreak = false;
        check(!InputAssist.pairs && !InputAssist.indentOnBreak, "既定はどちらもオフ");
    }

    /**
     * 段落頭に全角スペースを入れても、縦書きの配置（列・行）が、ふつうの全角の字を入れたときと同じになること。
     * 全角スペースは、禁則や行の計算の上では、ふつうの全角1字として扱われる。
     */
    private static void layout() {
        String[] samples = {
                "一二三\n　。四",
                "あいうえお\n　「かき」\n　く",
                "一二三四\n　　五",
                "あいうえお\n　、、。。",
                "あ\n　い\n　う\n　え",
        };
        boolean same = true;
        for (String sample : samples) {
            for (int rows = 2; rows <= 5; rows++) {
                VerticalLayout indented = new VerticalLayout(sample, rows, List.of());
                VerticalLayout plain = new VerticalLayout(sample.replace('　', 'あ'), rows, List.of());
                same &= Arrays.equals(indented.col, plain.col)
                        && Arrays.equals(indented.row, plain.row)
                        && indented.totalColumns == plain.totalColumns;
            }
        }
        check(same, "段落頭の全角スペースは、禁則を含めて、ふつうの全角1字と同じ位置に並ぶ");
    }

    // ---------- 横書き（TextEditorPane） ----------

    private static void horizontal() {
        try {
            TextEditorPane pane = new TextEditorPane(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            JTextArea area = (JTextArea) viewOf(pane);
            InputAssist.pairs = true;
            type(area, '「');
            check(area.getText().equals("「」") && area.getCaretPosition() == 1, "横書き：「 を打つと「」になり、カーソルが間に来る");
            type(area, '」');
            check(area.getText().equals("「」") && area.getCaretPosition() == 2, "横書き：閉じかっこを打つと、同じ字が右にあれば増えずに右へ進む");
            pane.undo();
            check(area.getText().isEmpty(), "横書き：取り消し1回で、かっこの2字が一緒に戻る");

            TextEditorPane sel = new TextEditorPane(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            JTextArea selArea = (JTextArea) viewOf(sel);
            sel.loadText("abc");
            selArea.select(0, 3);
            type(selArea, '『');
            check(selArea.getText().equals("『") && selArea.getCaretPosition() == 1,
                    "横書き：範囲を選んで『を打つと、範囲が『に置き換わり、かっこは付かない");

            TextEditorPane skip = new TextEditorPane(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            JTextArea skipArea = (JTextArea) viewOf(skip);
            skip.loadText("x");
            skipArea.setCaretPosition(0);
            type(skipArea, '」');
            check(skipArea.getText().equals("」x") && skipArea.getCaretPosition() == 1,
                    "横書き：右が同じ閉じかっこでなければ、ふつうに入る（飛び越さない）");

            InputAssist.indentOnBreak = true;
            TextEditorPane enter = new TextEditorPane(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            JTextArea enterArea = (JTextArea) viewOf(enter);
            enter.loadText("あ");
            enterArea.setCaretPosition(1);
            press(enterArea, KeyEvent.VK_ENTER);
            check(enterArea.getText().equals("あ\n　") && enterArea.getCaretPosition() == 3,
                    "横書き：indentOnBreak が true のとき、Enter で改行と全角スペース");
            enter.undo();
            check(enterArea.getText().equals("あ"), "横書き：取り消し1回で、改行と全角スペースが一緒に戻る");

            InputAssist.indentOnBreak = false;
            press(enterArea, KeyEvent.VK_ENTER);
            check(enterArea.getText().equals("あ\n"), "横書き：indentOnBreak が false のとき、Enter は改行だけ");

            InputAssist.pairs = false;
            TextEditorPane off = new TextEditorPane(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            JTextArea offArea = (JTextArea) viewOf(off);
            type(offArea, '「');
            check(offArea.getText().equals("「"), "横書き：pairs が false のときは、かっこを足さない");
            // 貼り付けや、直接の挿入（キーの知らせを通らない）では、かっこを足さない
            offArea.setText("");
            InputAssist.pairs = true;
            offArea.insert("「", 0);
            check(offArea.getText().equals("「"), "横書き：キーで打ったのではない挿入（貼り付けなど）では、かっこを足さない");
        } finally {
            InputAssist.pairs = false;
            InputAssist.indentOnBreak = false;
        }
    }

    // ---------- 縦書き（VerticalTextPane） ----------

    private static void vertical() {
        try {
            VerticalTextPane pane = new VerticalTextPane(new Font(Font.SERIF, Font.PLAIN, 20));
            Component view = viewOf(pane);
            InputAssist.pairs = true;
            type(view, '「');
            check(pane.toPlainText().equals("「」") && pane.getSelectionEnd() == 1 && pane.getSelectionStart() == 1,
                    "縦書き：「 を打つと「」になり、カーソルが間に来る");
            type(view, '」');
            check(pane.toPlainText().equals("「」") && pane.getSelectionEnd() == 2,
                    "縦書き：閉じかっこを打つと、同じ字が次にあれば増えずに進む");
            pane.undo();
            check(pane.toPlainText().isEmpty(), "縦書き：取り消し1回で、かっこの2字が一緒に戻る");

            VerticalTextPane sel = new VerticalTextPane(new Font(Font.SERIF, Font.PLAIN, 20));
            sel.loadText("abc");
            sel.select(0, 3);
            type(viewOf(sel), '『');
            check(sel.toPlainText().equals("『"), "縦書き：範囲を選んで『を打つと、範囲が『に置き換わり、かっこは付かない");

            VerticalTextPane skip = new VerticalTextPane(new Font(Font.SERIF, Font.PLAIN, 20));
            skip.loadText("x");
            skip.select(0, 0);
            type(viewOf(skip), '」');
            check(skip.toPlainText().equals("」x") && skip.getSelectionEnd() == 1,
                    "縦書き：右（次）が同じ閉じかっこでなければ、ふつうに入る（飛び越さない）");

            InputAssist.indentOnBreak = true;
            VerticalTextPane enter = new VerticalTextPane(new Font(Font.SERIF, Font.PLAIN, 20));
            enter.loadText("あ");
            enter.select(1, 1);
            press(viewOf(enter), KeyEvent.VK_ENTER);
            check(enter.toPlainText().equals("あ\n　") && enter.getSelectionEnd() == 3,
                    "縦書き：indentOnBreak が true のとき、Enter で改行と全角スペース");
            enter.undo();
            check(enter.toPlainText().equals("あ"), "縦書き：取り消し1回で、改行と全角スペースが一緒に戻る");

            InputAssist.indentOnBreak = false;
            press(viewOf(enter), KeyEvent.VK_ENTER);
            check(enter.toPlainText().equals("あ\n"), "縦書き：indentOnBreak が false のとき、Enter は改行だけ");

            // ルビの入力欄の中では、かっこの自動閉じを働かせない（入力欄は別の部品なので、打った字は本文に来ない。
            // ここでは、入力欄が開いている間に本文へ打っても、規則が当たらないことを確かめる）
            VerticalTextPane ruby = new VerticalTextPane(new Font(Font.SERIF, Font.PLAIN, 20));
            ruby.loadText("東京です");
            ruby.select(0, 2);
            ruby.beginRubyEdit();
            ruby.select(2, 2);
            type(viewOf(ruby), '「');
            check(ruby.toPlainText().equals("東京「です"), "縦書き：ルビの入力欄が開いている間は、かっこを足さない");
        } finally {
            InputAssist.pairs = false;
            InputAssist.indentOnBreak = false;
        }
    }

    // ---------- 打つ操作の再現 ----------

    /** 部品の中で、最初に出てくる JTextArea か、ScrollPane の中身（キーを受け取る部品）。 */
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

    /** 1字を、キーで打ったときの知らせ（KEY_TYPED）として送る。 */
    private static void type(Component target, char ch) {
        target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0,
                KeyEvent.VK_UNDEFINED, ch));
    }

    /** キーを押したときの知らせ（KEY_PRESSED）として送る。 */
    private static void press(Component target, int keyCode) {
        target.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                keyCode, KeyEvent.CHAR_UNDEFINED));
    }
}
