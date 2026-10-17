package com.example.minieditor;

import java.util.ArrayList;
import java.util.List;

/**
 * 2つの文章を行ごとに比べて、どの行が増えた・減ったかを求める（版の履歴の「今との違い」に使う）。
 * 「最長共通部分列」という考え方で、両方に共通して残っている行を最大にとり、残りを増減とみなす。
 */
public final class LineDiff {

    public enum Kind { SAME, ADDED, REMOVED }

    public record Line(Kind kind, String text) {
    }

    /** 行数がこれより多いと、計算に時間とメモリがかかりすぎるので比べない。 */
    public static final int MAX_LINES = 2000;

    private LineDiff() {
    }

    /** before から after への違いを返す。大きすぎて比べられないときは null。 */
    public static List<Line> diff(String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        // 先頭と末尾の同じ行は、表を作る前に取り除く（ふつうの編集では、これだけでかなり小さくなる）
        int head = 0;
        while (head < a.length && head < b.length && a[head].equals(b[head])) {
            head++;
        }
        int tail = 0;
        while (tail < a.length - head && tail < b.length - head
                && a[a.length - 1 - tail].equals(b[b.length - 1 - tail])) {
            tail++;
        }
        int n = a.length - head - tail;
        int m = b.length - head - tail;
        if (n > MAX_LINES || m > MAX_LINES) {
            return null;
        }
        // lcs[i][j]：a の i 行目以降と、b の j 行目以降で、共通する行の数の最大
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a[head + i].equals(b[head + j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Line> lines = new ArrayList<>();
        for (int k = 0; k < head; k++) {
            lines.add(new Line(Kind.SAME, a[k]));
        }
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a[head + i].equals(b[head + j])) {
                lines.add(new Line(Kind.SAME, a[head + i]));
                i++;
                j++;
            } else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                lines.add(new Line(Kind.ADDED, b[head + j]));
                j++;
            } else {
                lines.add(new Line(Kind.REMOVED, a[head + i]));
                i++;
            }
        }
        for (int k = a.length - tail; k < a.length; k++) {
            lines.add(new Line(Kind.SAME, a[k]));
        }
        return lines;
    }
}
