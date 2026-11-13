package com.example.minieditor;

/**
 * 横書きの「打つ位置」の設定。画面の描き方（スクロールと、行を並べる向き）に使う。
 *
 * 「固定」を入れると、カーソルのある行が、いつも画面の同じ高さ（上から position ％の所）に来るようにスクロールする
 * （書き進めても、目線が画面の下へずれていかない）。集中モードのあいだは、固定を入れていなくても、この高さに保つ。
 * 「上へ書く」を入れると、行を下から上へ並べて見せる（文書の最後の行がいちばん上。書いた行は下へ送られる）。
 * 見え方だけを変え、文書の中身の順番は変えない（途中で切り替えても、前後の行が混ざらない。保存したファイルもふつうの順番）。
 * 既定は、どちらも切・高さは真ん中（今までと同じ動き）。値は AppSettings に覚える。
 */
public final class TypingPosition {

    /** 高さ（画面の上からの百分率）の、いちばん上と下。端に寄せすぎると、行が画面の縁に掛かるので、この範囲にする。 */
    public static final int MIN = 10;
    public static final int MAX = 90;

    /** 既定の高さ（真ん中）。 */
    public static final int DEFAULT = 50;

    /** メニューに並べる高さと、その名前。ほかの値は「指定…」で選ぶ。 */
    public static final int[] CHOICES = {30, 50, 70};
    public static final String[] NAMES = {"上寄り", "真ん中", "下寄り"};

    private static boolean fixed;
    private static int position = DEFAULT;
    private static boolean upward;

    private TypingPosition() {
    }

    /** 打つ位置を、画面の同じ高さに固定するか。 */
    public static boolean fixed() {
        return fixed;
    }

    public static void setFixed(boolean on) {
        fixed = on;
    }

    /** 打つ位置の高さ（画面の上からの百分率）。 */
    public static int position() {
        return position;
    }

    /** 高さを決める。範囲の外は、いちばん近い端の値にする。 */
    public static void setPosition(int percent) {
        position = Math.max(MIN, Math.min(MAX, percent));
    }

    /** 行を下から上へ並べて見せるか（新しく書いた行が、上に来る）。 */
    public static boolean upward() {
        return upward;
    }

    public static void setUpward(boolean on) {
        upward = on;
    }

    /** 設定から読む。読めないときは既定。 */
    public static void load(AppSettings settings) {
        setFixed(settings.getBool(AppSettings.TYPING_FIXED, false));
        setPosition(settings.getInt(AppSettings.TYPING_POSITION, DEFAULT));
        setUpward(settings.getBool(AppSettings.TYPING_UPWARD, false));
    }

    /** 設定に入れる。ファイルに書くのは、呼んだあとに AppSettings.save() で行う。 */
    public static void save(AppSettings settings) {
        settings.put(AppSettings.TYPING_FIXED, fixed);
        settings.put(AppSettings.TYPING_POSITION, position);
        settings.put(AppSettings.TYPING_UPWARD, upward);
    }
}
