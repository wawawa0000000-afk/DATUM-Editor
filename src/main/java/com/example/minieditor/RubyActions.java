package com.example.minieditor;

import java.awt.Component;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.JOptionPane;

/**
 * 「書式」のルビの操作（ルビを振る・傍点を付ける・ルビと傍点を外す）。EditorFrame から切り出した。
 */
final class RubyActions {

    private final Component parent;                     // 入力の画面を出すときの親
    private final Supplier<EditorPane> currentPane;     // 今の文書
    private final Consumer<String> flash;               // ステータスバーに、数秒だけお知らせを出す

    RubyActions(Component parent, Supplier<EditorPane> currentPane, Consumer<String> flash) {
        this.parent = parent;
        this.currentPane = currentPane;
        this.flash = flash;
    }

    // 縦書きでは、ルビを文字の属性として持つ（VerticalTextPane に任せる）。
    // 横書きは文字だけを扱う画面なので、「小説家になろう」の記法を文章の中に書き込む。

    /** 今の文書が、文章（横書き・縦書き）のときだけ返す。カードボードなら、案内を出して null。 */
    private Searchable textTarget() {
        if (currentPane.get() instanceof Searchable s) {
            return s;
        }
        flash.accept("ルビは、テキストと縦書きの文書で使えます");
        return null;
    }

    /** position を含んでいるルビを探す（前後の端に接している場合も含む）。 */
    private static NarouRuby.Span rubyAt(String text, int position) {
        for (NarouRuby.Span span : NarouRuby.parse(text)) {
            if (span.start() <= position && position <= span.end()) {
                return span;
            }
        }
        return null;
    }

    /** 選んだ文字にルビを振る。何も選ばずにルビの上で実行したときは、そのルビを書き直す。 */
    void editRuby() {
        if (currentPane.get() instanceof VerticalTextPane vertical) {
            vertical.beginRubyEdit();   // 縦書きでは、文字のすぐ横に入力欄を出す
            return;
        }
        Searchable target = textTarget();
        if (target == null) {
            return;
        }
        String text = target.getText();
        int start = target.getSelectionStart();
        int end = target.getSelectionEnd();
        String current = "";
        if (start == end) {
            NarouRuby.Span span = rubyAt(text, start);
            if (span == null) {
                flash.accept("ルビを振る文字を選んでから、もう一度どうぞ");
                return;
            }
            start = span.start();
            end = span.end();
            current = text.substring(span.rubyStart(), span.rubyEnd());
        }
        String base = NarouRuby.strip(text.substring(start, end));     // 元から付いていたルビは、いったん外す
        if (base.isBlank() || base.contains("\n")) {
            flash.accept("ルビは、1行の中の文字に振れます");
            return;
        }
        if (base.length() > NarouRuby.MAX_LENGTH) {
            flash.accept("ルビを振れるのは " + NarouRuby.MAX_LENGTH + " 文字までです（小説家になろうの決まり）");
            return;
        }
        if (!NarouRuby.fits(base, "あ")) {
            flash.accept("｜ 《 》 （ ( を含む文字には、ルビを振れません");
            return;
        }
        Object answer = JOptionPane.showInputDialog(parent, "「" + base + "」のふりがな（空にすると、ルビを外します）",
                "ルビを振る", JOptionPane.PLAIN_MESSAGE, null, null, current);
        currentPane.get().focusEditor();
        if (answer == null) {
            return;                     // キャンセル
        }
        String ruby = answer.toString().strip();
        if (!ruby.isEmpty() && !NarouRuby.fits(base, ruby)) {
            flash.accept("ふりがなは " + NarouRuby.MAX_LENGTH + " 文字までで、｜ 《 》 （ ( は使えません");
            return;
        }
        target.replaceRange(ruby.isEmpty() ? base : NarouRuby.format(base, ruby), start, end);
    }

    /** 選んだ文字に、1文字ずつ傍点（・）を付ける。 */
    void addEmphasis() {
        if (currentPane.get() instanceof VerticalTextPane vertical) {
            vertical.emphasizeSelection();
            return;
        }
        Searchable target = textTarget();
        if (target == null) {
            return;
        }
        int start = target.getSelectionStart();
        int end = target.getSelectionEnd();
        if (start == end) {
            flash.accept("傍点を付ける文字を選んでから、もう一度どうぞ");
            return;
        }
        String base = NarouRuby.strip(target.getText().substring(start, end));
        target.replaceRange(NarouRuby.emphasize(base), start, end);
    }

    /** 選んだ範囲（選んでいなければ、カーソルのあるルビ）から、ルビと傍点を外す。 */
    void removeRuby() {
        if (currentPane.get() instanceof VerticalTextPane vertical) {
            vertical.removeRubyAtSelection();
            return;
        }
        Searchable target = textTarget();
        if (target == null) {
            return;
        }
        String text = target.getText();
        int start = target.getSelectionStart();
        int end = target.getSelectionEnd();
        if (start == end) {
            NarouRuby.Span span = rubyAt(text, start);
            if (span == null) {
                flash.accept("外すルビが、カーソルの位置にありません");
                return;
            }
            start = span.start();
            end = span.end();
        }
        String original = text.substring(start, end);
        String stripped = NarouRuby.strip(original);
        if (!stripped.equals(original)) {
            target.replaceRange(stripped, start, end);
        }
    }
}
