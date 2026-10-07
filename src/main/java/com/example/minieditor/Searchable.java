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

    void replaceRange(String replacement, int start, int end);

    /** 全文を入れ替える（「元に戻す」1回で戻せるようにする）。 */
    void replaceAllText(String text);
}
