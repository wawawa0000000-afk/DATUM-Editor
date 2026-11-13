package com.example.minieditor;

/**
 * 行間・字間・余白の設定。画面の描き方に使う。
 *
 * 縦書きでは「縦の行間」（列と列のあいだ）と「縦の字間」（1列の中で字と字のあいだ）と「余白」（画面の縁）を変えられる。
 * 横書きでは「行の高さ」（文字の高さに対する割合。1.0〜3.0 倍の好きな値）と「余白」を変えられる（字間は JTextArea の制約で変えない）。
 * 原稿用紙の表示では、マスの大きさが決まっているので、行間・字間は変えない（余白は縦書きの画面の縁に効く）。
 * 既定は、3つとも「標準」（今までの描き方と同じ）。値は AppSettings に覚える。
 */
public final class Spacing {

    /** 選べる段階の名前（狭い・標準・広い の順）。 */
    public static final String[] NAMES = {"狭い", "標準", "広い"};

    /** 既定の段階（標準）。 */
    public static final int DEFAULT = 1;

    /** 縦の行間。文字の大きさに掛けて、列と列の間隔（左へ進む量）にする。 */
    private static final float[] LINE_FACTORS = {1.6f, 1.85f, 2.2f};

    /** 縦の字間。文字の大きさに掛けて、1文字ぶんの高さ（下へ進む量）にする。 */
    private static final float[] CHAR_FACTORS = {1.0f, 1.12f, 1.3f};

    /** 余白。今の余白に掛ける。 */
    private static final float[] MARGIN_FACTORS = {0.5f, 1f, 1.5f};

    /** 横書きの行の高さ（百分率）の、いちばん低い値（標準）と高い値。文字より低くすると字が欠けるので、100 より下は選ばせない。 */
    public static final int ROW_MIN = 100;
    public static final int ROW_MAX = 300;

    /** メニューに並べる、横書きの行の高さ（百分率）。ほかの値は「指定…」で選ぶ。 */
    public static final int[] ROW_CHOICES = {100, 120, 150, 180, 200};

    private static int line = DEFAULT;
    private static int chars = DEFAULT;
    private static int margin = DEFAULT;
    private static int row = ROW_MIN;

    private Spacing() {
    }

    /** 縦の行間の段階（0 狭い・1 標準・2 広い）。 */
    public static int line() {
        return line;
    }

    /** 縦の字間の段階。 */
    public static int chars() {
        return chars;
    }

    /** 余白の段階。 */
    public static int margin() {
        return margin;
    }

    /** 横書きの行の高さ（百分率。100 で今までと同じ）。 */
    public static int row() {
        return row;
    }

    /** 横書きの行の高さを決める。範囲の外は、いちばん近い端の値にする。 */
    public static void setRow(int percent) {
        row = Math.max(ROW_MIN, Math.min(ROW_MAX, percent));
    }

    /** 横書きの行の高さの倍率（文字の高さに掛ける）。 */
    public static float rowFactor() {
        return row / 100f;
    }

    /** 行の高さの表示（120 なら「1.2 倍」、135 なら「1.35 倍」）。 */
    public static String rowLabel(int percent) {
        int part = percent % 100;
        String decimals = part == 0 ? "0" : part % 10 == 0 ? String.valueOf(part / 10) : String.format("%02d", part);
        return percent / 100 + "." + decimals + " 倍";
    }

    public static void setLine(int level) {
        line = choose(level);
    }

    public static void setChars(int level) {
        chars = choose(level);
    }

    public static void setMargin(int level) {
        margin = choose(level);
    }

    /** 縦の行間の倍率（文字の大きさに掛ける）。 */
    public static float lineFactor() {
        return LINE_FACTORS[line];
    }

    /** 縦の字間の倍率（文字の大きさに掛ける）。 */
    public static float charFactor() {
        return CHAR_FACTORS[chars];
    }

    /** 余白の倍率。 */
    public static float marginFactor() {
        return MARGIN_FACTORS[margin];
    }

    /** 0・1・2 のどれかならその値を、それ以外は標準に戻す。 */
    static int choose(int level) {
        return level >= 0 && level < NAMES.length ? level : DEFAULT;
    }

    /** 設定から読む。読めないときは標準。 */
    public static void load(AppSettings settings) {
        setLine(settings.getInt(AppSettings.SPACING_LINE, DEFAULT));
        setChars(settings.getInt(AppSettings.SPACING_CHAR, DEFAULT));
        setMargin(settings.getInt(AppSettings.SPACING_MARGIN, DEFAULT));
        setRow(settings.getInt(AppSettings.SPACING_ROW, ROW_MIN));
    }

    /** 設定に入れる。ファイルに書くのは、呼んだあとに AppSettings.save() で行う。 */
    public static void save(AppSettings settings) {
        settings.put(AppSettings.SPACING_LINE, line);
        settings.put(AppSettings.SPACING_CHAR, chars);
        settings.put(AppSettings.SPACING_MARGIN, margin);
        settings.put(AppSettings.SPACING_ROW, row);
    }
}
