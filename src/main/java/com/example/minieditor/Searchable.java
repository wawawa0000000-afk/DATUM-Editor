package com.example.minieditor;

import java.util.List;

/**
 * 検索・置換ができる文書が実装する約束（interface）。
 * 検索バーは、相手が横書きか縦書きかを知らなくても、このメソッドだけで操作できる。
 */
public interface Searchable {

    String getText();

    /** 色を付ける範囲を、まとめて入れ替える。空のリストを渡すと、すべて消える。 */
    void setHighlights(List<EditorPane.Highlight> highlights);

    /** その位置が見えるようにスクロールし、カーソルを移す。 */
    void reveal(int offset);

    /** start〜end を選んだ状態にして、見えるようにスクロールする（校正の一覧から飛ぶときに使う）。 */
    void select(int start, int end);

    void replaceRange(String replacement, int start, int end);

    /** 選んでいる範囲の始まり。選んでいないときは、カーソルの位置。 */
    int getSelectionStart();

    /** 選んでいる範囲の終わり。選んでいないときは、カーソルの位置。 */
    int getSelectionEnd();

    /** 全文を入れ替える（「元に戻す」1回で戻せるようにする）。 */
    void replaceAllText(String text);

    /** 置き換え1つぶん：start〜end を text に置き換える（位置は、置き換える前の文章での位置）。 */
    record Replacement(int start, int end, String text) {
    }

    /**
     * 離れた場所にある複数の置き換えを、まとめて行う（「元に戻す」1回で、すべて戻せるようにする）。
     * 範囲は重なっていないこと。全文を入れ替えるのと違い、触っていない文字に付いたルビなどは、そのまま残る。
     */
    void replaceRanges(List<Replacement> replacements);
}
