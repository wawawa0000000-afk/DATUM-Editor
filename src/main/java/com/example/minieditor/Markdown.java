package com.example.minieditor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Markdown の記法を読み取る（拡張子 .md の文書の、横書きの表示に使う）。
 * 文書の中身は変えない。「どの文字が、どんな役目か」を、1文字ずつの印（flags）と、1行ずつの種類（kind）で返すだけ。
 *
 * 読み取る記法：見出し（# ～ ######）、太字（** と __）、斜体（* と _）、取り消し線（~~）、
 * インラインコード（`）、コードブロック（``` と ~~~）、引用（>）、箇条書き（- + * と 1.）、
 * チェックリスト（- [ ] と - [x]）、区切り線（--- *** ___）、表（| と |---|）、リンク（[字](場所)）、
 * 記号を字として使う（\* など）。画像（![]()）は、リンクと同じ扱い（字だけ）。
 * 日本語の文章に合わせて、強調の前後は「空白でないこと」だけを見る（「**「強調」**」が効く）。
 */
final class Markdown {

    /** 字の印（組み合わせて使う）。 */
    static final int MARK = 1;      // 記法の記号（#、**、`、> など）。薄く描く
    static final int BOLD = 2;
    static final int ITALIC = 4;
    static final int STRIKE = 8;
    static final int CODE = 16;     // インラインコードの中身、またはコードブロックの行
    static final int LINK = 32;     // リンクの字
    static final int HEAD = 64;     // 見出しの字
    static final int BULLET = 128;  // 箇条書きの「- 」「1.」
    static final int CHECK = 256;   // チェックリストの「[ ]」「[x]」
    static final int DONE = 512;    // 済みのチェックの字

    /** 行の種類。 */
    enum Kind { NORMAL, HEADING, QUOTE, FENCE, CODE, RULE, LIST, TABLE, TABLE_RULE }

    /** これより長い文書は読み取らない（描くたびの重さを抑える）。 */
    static final int LIMIT = 1_000_000;

    /** 読み取った結果。length は、読み取ったときの文書の長さ（今の文書と違えば、古い結果）。 */
    static final class Result {
        final int length;
        final int[] flags;          // 1文字ずつの印
        final int[] lineStart;      // 各行の先頭の位置
        final Kind[] kind;          // 各行の種類
        final byte[] level;         // 見出しの段（1～6）、引用の深さ

        Result(int length, int[] flags, int[] lineStart, Kind[] kind, byte[] level) {
            this.length = length;
            this.flags = flags;
            this.lineStart = lineStart;
            this.kind = kind;
            this.level = level;
        }

        /** 位置がある行（0 から）。 */
        int lineOf(int offset) {
            int index = Arrays.binarySearch(lineStart, offset);
            return Math.max(0, index >= 0 ? index : -index - 2);
        }

        /** 行の終わりの位置（改行は含まない）。 */
        int lineEnd(int line) {
            int end = line + 1 < lineStart.length ? lineStart[line + 1] - 1 : length;
            return Math.max(lineStart[line], end);
        }
    }

    private Markdown() {
    }

    /** 文章を読み取る。LIMIT より長いときは null（装飾しない）。 */
    static Result analyze(String text) {
        int n = text.length();
        if (n > LIMIT) {
            return null;
        }
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int lines = starts.size();
        int[] lineStart = new int[lines];
        for (int i = 0; i < lines; i++) {
            lineStart[i] = starts.get(i);
        }
        int[] flags = new int[n];
        Kind[] kind = new Kind[lines];
        byte[] level = new byte[lines];
        Arrays.fill(kind, Kind.NORMAL);

        char fence = 0;                 // コードブロックの中なら、その区切りの字（` か ~）
        int fenceLength = 0;
        boolean inTable = false;
        boolean delimiterNext = false;  // 次の行は、表の見出しの下の線のはず
        for (int line = 0; line < lines; line++) {
            int s = lineStart[line];
            int e = s + lengthOf(text, lineStart, line, n);
            int pAll = skipBlanks(text, s, e);
            int indent = pAll - s;

            if (fence != 0) {
                if (indent <= 3 && isFence(text, pAll, e, fence, fenceLength, true)) {
                    kind[line] = Kind.FENCE;
                    mark(flags, s, e);
                    fence = 0;
                } else {
                    kind[line] = Kind.CODE;
                    add(flags, s, e, CODE);
                }
                continue;
            }
            if (indent <= 3 && pAll < e && (text.charAt(pAll) == '`' || text.charAt(pAll) == '~')) {
                char c = text.charAt(pAll);
                int run = run(text, pAll, e, c);
                if (run >= 3 && isFence(text, pAll, e, c, 3, false)) {
                    fence = c;
                    fenceLength = run;
                    kind[line] = Kind.FENCE;
                    mark(flags, s, e);
                    continue;
                }
            }

            // 表：見出しの行（次の行が |---| の線）、その線、そのあとの行
            if (delimiterNext) {
                delimiterNext = false;
                if (isDelimiterRow(text, s, e)) {
                    kind[line] = Kind.TABLE_RULE;
                    mark(flags, s, e);
                    inTable = true;
                    continue;
                }
            }
            if (inTable) {
                if (pAll < e && indexOf(text, s, e, '|') >= 0) {
                    kind[line] = Kind.TABLE;
                    inline(text, flags, s, e);
                    markPipes(text, flags, s, e);
                    continue;
                }
                inTable = false;
            } else if (indexOf(text, s, e, '|') >= 0 && line + 1 < lines
                    && isDelimiterRow(text, lineStart[line + 1], lineStart[line + 1] + lengthOf(text, lineStart, line + 1, n))) {
                kind[line] = Kind.TABLE;
                add(flags, s, e, BOLD);
                inline(text, flags, s, e);
                markPipes(text, flags, s, e);
                delimiterNext = true;
                continue;
            }

            if (pAll >= e) {
                continue;               // 空行
            }
            char first = text.charAt(pAll);

            if (indent <= 3 && first == '#') {
                int run = run(text, pAll, e, '#');
                if (run <= 6 && (pAll + run == e || isBlank(text.charAt(pAll + run)))) {
                    kind[line] = Kind.HEADING;
                    level[line] = (byte) run;
                    mark(flags, pAll, pAll + run);
                    int cs = skipBlanks(text, pAll + run, e);
                    int ce = e;
                    while (ce > cs && isBlank(text.charAt(ce - 1))) {
                        ce--;
                    }
                    int closing = ce;       // 終わりの「 ##」も記号にする
                    while (closing > cs && text.charAt(closing - 1) == '#') {
                        closing--;
                    }
                    if (closing < ce && closing > cs && isBlank(text.charAt(closing - 1))) {
                        mark(flags, closing, ce);
                        ce = closing;
                        while (ce > cs && isBlank(text.charAt(ce - 1))) {
                            ce--;
                        }
                    }
                    add(flags, cs, ce, HEAD);
                    inline(text, flags, cs, ce);
                    continue;
                }
            }
            if (indent <= 3 && isRule(text, pAll, e)) {
                kind[line] = Kind.RULE;
                mark(flags, s, e);
                continue;
            }
            if (indent <= 3 && first == '>') {
                kind[line] = Kind.QUOTE;
                int q = pAll;
                int depth = 0;
                while (q < e && text.charAt(q) == '>') {
                    mark(flags, q, q + 1);
                    depth++;
                    q++;
                    if (q < e && text.charAt(q) == ' ') {
                        q++;
                    }
                }
                level[line] = (byte) Math.min(depth, 100);
                inline(text, flags, q, e);
                continue;
            }
            int bulletEnd = bulletEnd(text, pAll, e);
            if (bulletEnd > 0) {
                kind[line] = Kind.LIST;
                add(flags, pAll, bulletEnd, BULLET);
                int q = skipBlanks(text, bulletEnd, e);
                boolean done = false;
                if (q + 2 < e && text.charAt(q) == '[' && text.charAt(q + 2) == ']'
                        && " xX".indexOf(text.charAt(q + 1)) >= 0 && (q + 3 == e || isBlank(text.charAt(q + 3)))) {
                    add(flags, q, q + 3, CHECK);
                    done = text.charAt(q + 1) != ' ';
                    q += 3;
                }
                inline(text, flags, q, e);
                if (done) {
                    add(flags, q, e, DONE);
                }
                continue;
            }
            inline(text, flags, s, e);
        }
        return new Result(n, flags, lineStart, kind, level);
    }

    // ---------- 行の中（インライン） ----------

    /** s から e の間の、強調・コード・リンクを読み取って、印を足す（入れ子は中身を読み直す）。 */
    private static void inline(CharSequence t, int[] f, int s, int e) {
        int i = s;
        while (i < e) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < e && isPunctuation(t.charAt(i + 1))) {
                f[i] |= MARK;           // 「\*」の \ は記号、* は字
                i += 2;
                continue;
            }
            if (c == '`') {
                int n = run(t, i, e, '`');
                int j = findRun(t, i + n, e, '`', n);
                if (j >= 0) {
                    mark(f, i, i + n);
                    add(f, i + n, j, CODE);
                    mark(f, j, j + n);
                    i = j + n;
                } else {
                    i += n;
                }
                continue;
            }
            if (c == '*' || c == '_' || c == '~') {
                int n = run(t, i, e, c);
                boolean usable = c == '~' ? n == 2 : n <= 3;
                if (usable && i + n < e && !isBlank(t.charAt(i + n))
                        && (c != '_' || i == s || !Character.isLetterOrDigit(t.charAt(i - 1)))) {
                    int j = findCloser(t, i + n, e, c, n);
                    if (j > 0) {
                        int style = c == '~' ? STRIKE : n == 1 ? ITALIC : n == 2 ? BOLD : BOLD | ITALIC;
                        mark(f, i, i + n);
                        add(f, i + n, j, style);
                        mark(f, j, j + n);
                        inline(t, f, i + n, j);
                        i = j + n;
                        continue;
                    }
                }
                i += n;
                continue;
            }
            if (c == '[') {
                int close = indexOf(t, i + 1, e, ']');
                if (close > i + 1 && close + 1 < e && t.charAt(close + 1) == '(') {
                    int paren = indexOf(t, close + 2, e, ')');
                    if (paren > 0) {
                        mark(f, i, i + 1);
                        add(f, i + 1, close, LINK);
                        mark(f, close, paren + 1);
                        inline(t, f, i + 1, close);
                        i = paren + 1;
                        continue;
                    }
                }
            }
            i++;
        }
    }

    /** 強調を閉じる記号の位置。同じ字がちょうど n 個並ぶ所で、直前が空白でないもの。無ければ -1。 */
    private static int findCloser(CharSequence t, int from, int e, char c, int n) {
        int j = from;
        while (j < e) {
            char x = t.charAt(j);
            if (x == '\\') {
                j += 2;
            } else if (x == '`') {
                int m = run(t, j, e, '`');
                int k = findRun(t, j + m, e, '`', m);
                j = k >= 0 ? k + m : j + m;     // コードの中は飛ばす
            } else if (x == c) {
                int m = run(t, j, e, c);
                if (m == n && j > from && !isBlank(t.charAt(j - 1))
                        && (c != '_' || j + m >= e || !Character.isLetterOrDigit(t.charAt(j + m)))) {
                    return j;
                }
                j += m;
            } else {
                j++;
            }
        }
        return -1;
    }

    // ---------- 行の見分け ----------

    /** 箇条書きの記号の終わり（「- 」「1. 」の記号の部分）。箇条書きでなければ 0。 */
    private static int bulletEnd(CharSequence t, int p, int e) {
        if (p >= e) {
            return 0;
        }
        char c = t.charAt(p);
        if ((c == '-' || c == '+' || c == '*') && p + 1 < e && isBlank(t.charAt(p + 1))) {
            return p + 1;
        }
        int q = p;
        while (q < e && q - p < 9 && t.charAt(q) >= '0' && t.charAt(q) <= '9') {
            q++;
        }
        if (q > p && q + 1 < e && (t.charAt(q) == '.' || t.charAt(q) == ')') && isBlank(t.charAt(q + 1))) {
            return q + 1;
        }
        return 0;
    }

    /** 区切り線か（- * _ のどれかが3つ以上と空白だけ）。 */
    private static boolean isRule(CharSequence t, int p, int e) {
        char c = t.charAt(p);
        if (c != '-' && c != '*' && c != '_') {
            return false;
        }
        int count = 0;
        for (int i = p; i < e; i++) {
            char x = t.charAt(i);
            if (x == c) {
                count++;
            } else if (!isBlank(x)) {
                return false;
            }
        }
        return count >= 3;
    }

    /**
     * コードブロックの区切りか。閉じるとき（closing）は、同じ字が min 個以上で、ほかに字がない。
     * 開くとき、` の区切りの行には ` を含められない（行の中のコードと見分けるため）。
     */
    private static boolean isFence(CharSequence t, int p, int e, char c, int min, boolean closing) {
        int run = run(t, p, e, c);
        if (run < min) {
            return false;
        }
        if (closing) {
            return skipBlanks(t, p + run, e) >= e;
        }
        return c != '`' || indexOf(t, p + run, e, '`') < 0;
    }

    /** 表の見出しの下の線か（| - : と空白だけで、- と | を含む）。 */
    private static boolean isDelimiterRow(CharSequence t, int s, int e) {
        boolean dash = false;
        boolean pipe = false;
        for (int i = s; i < e; i++) {
            char c = t.charAt(i);
            if (c == '-') {
                dash = true;
            } else if (c == '|') {
                pipe = true;
            } else if (c != ':' && !isBlank(c)) {
                return false;
            }
        }
        return dash && pipe;
    }

    // ---------- 小さな道具 ----------

    private static int lengthOf(CharSequence t, int[] lineStart, int line, int n) {
        int s = lineStart[line];
        int e = line + 1 < lineStart.length ? lineStart[line + 1] - 1 : n;
        if (e > s && t.charAt(e - 1) == '\r') {
            e--;
        }
        return Math.max(0, e - s);
    }

    private static void markPipes(CharSequence t, int[] f, int s, int e) {
        for (int i = s; i < e; i++) {
            if (t.charAt(i) == '|' && (f[i] & CODE) == 0 && (i == s || t.charAt(i - 1) != '\\')) {
                f[i] |= MARK;
            }
        }
    }

    private static void mark(int[] f, int from, int to) {
        add(f, from, to, MARK);
    }

    private static void add(int[] f, int from, int to, int bits) {
        for (int i = Math.max(0, from); i < to && i < f.length; i++) {
            f[i] |= bits;
        }
    }

    /** from から e の間で、同じ字 c が続く長さ。 */
    private static int run(CharSequence t, int from, int e, char c) {
        int i = from;
        while (i < e && t.charAt(i) == c) {
            i++;
        }
        return i - from;
    }

    /** from 以降で、c がちょうど n 個続く所の位置（前後に同じ字が続くものは除く）。無ければ -1。 */
    private static int findRun(CharSequence t, int from, int e, char c, int n) {
        int i = from;
        while (i < e) {
            if (t.charAt(i) == c) {
                int m = run(t, i, e, c);
                if (m == n) {
                    return i;
                }
                i += m;
            } else {
                i++;
            }
        }
        return -1;
    }

    private static int indexOf(CharSequence t, int from, int e, char c) {
        for (int i = from; i < e; i++) {
            if (t.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    private static int skipBlanks(CharSequence t, int from, int e) {
        int i = from;
        while (i < e && isBlank(t.charAt(i))) {
            i++;
        }
        return i;
    }

    /** 半角の空白とタブ（全角スペースは、段落頭の字下げに使うので、空白として扱わない）。 */
    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean isPunctuation(char c) {
        return c < 128 && !Character.isLetterOrDigit(c) && c > ' ' && c != 127;
    }
}
