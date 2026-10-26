package com.example.minieditor;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 縦書きの「組版」（文字をどこに置くか）を計算し、文字を描く。
 * 画面（VerticalTextPane）と PDF（PagePrinter）の両方が、このクラスを使う。
 *
 * ここで扱っている、縦書きならではの決まり：
 *   ルビ       … 親文字の右に、小さくルビを描く
 *   禁則処理   … 行の先頭に「、」「。」や閉じかっこが来そうなときは、原稿用紙の書き方と同じく、
 *                前の行の一番下のマスに、その文字と一緒に入れる（2つまで。マスの外にははみ出さない）。
 *                行の一番下に開きかっこが来そうなときは、次の行の頭へ送る
 *   縦中横     … 半角の数字2桁や「!?」は、横に並べたまま1文字ぶんのマスに入れる
 *   ひとまとまりの文字 … 異体字セレクタ（字形を選ぶ見えない印）や結合文字（か + ゛ など）は、前の文字と同じマスに入れる
 */
public final class VerticalLayout {

    // 縦書きでは 90 度回して描く記号（長音・かっこ など）
    private static final String ROTATED = "ー－—―…‥〜～（）〔〕［］｛｝〈〉《》「」『』【】＝：；｜";
    // 縦書きではマスの右上に寄せる句読点
    private static final String PUNCTUATION = "、。，．";
    // 少し右上に寄せる小さい仮名
    private static final String SMALL_KANA = "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ";
    // 行の先頭に置かない文字（前の行の一番下のマスに、一緒に入れる）
    private static final String HANGING = "、。，．」』）】〕〉》";
    // 行の一番下に置かない文字（次の行の頭へ送る）
    private static final String OPENING = "「『（【〔〈《［｛";

    /** 親文字の範囲と、その右に描くルビ。 */
    public record RubyRun(int baseStart, int baseEnd, String ruby) {
    }

    /** マスの位置（何列目・何文字目）から、描く場所（中心の座標）を求める方法。画面と PDF で違う。 */
    public interface Place {
        double centerX(int column);

        double centerY(int row);
    }

    public final int[] col;             // col[i]：i 文字目が、右から何列目か
    public final int[] row;             // row[i]：i 文字目が、上から何文字目か（列をちょうど埋めた段落の改行だけは、1列の文字数と同じ＝一番下のマスのすぐ下）
    private final boolean[] pairHead;   // 縦中横の1文字目
    private final boolean[] pairTail;   // 縦中横の2文字目（1文字目と一緒に描くので、単独では描かない）
    private final int[] tucked;         // 禁則処理で前の行の一番下のマスに入れた句読点：そのマスの何個目か（1 か 2）。ふつうは 0
    private final boolean[] hasTucked;  // 一番下のマスに、句読点を一緒に入れられた文字
    public final List<RubyRun> rubies;
    public final int totalColumns;
    private final String text;

    /**
     * 配置を計算する。
     *
     * @param text          並べる文章（ルビの記号を含まない、本文だけ）
     * @param rowsPerColumn 1列に入る文字数
     * @param rubies        ルビの一覧（親文字の範囲と、ルビの文字）
     */
    public VerticalLayout(String text, int rowsPerColumn, List<RubyRun> rubies) {
        this.text = text;
        this.rubies = List.copyOf(rubies);
        int n = text.length();
        col = new int[n + 1];
        row = new int[n + 1];
        pairHead = new boolean[n + 1];
        pairTail = new boolean[n + 1];
        tucked = new int[n + 1];
        hasTucked = new boolean[n + 1];

        // 1) 縦中横にする2文字の組を探す
        for (int i = 0; i + 1 < n; i++) {
            if (isPair(i)) {
                pairHead[i] = true;
                pairTail[i + 1] = true;
                i++;
            }
        }

        // 2) 1文字ずつ、置く場所を決める
        int rows = Math.max(1, rowsPerColumn);
        int c = 0;
        int r = 0;
        boolean wrapped = false;        // 今の列が、折り返しで始まったか（改行で始まった列では、前の行へ入れない）
        int tuckedCount = 0;            // 前の列の一番下のマスに、いくつ入れたか
        int lastPlaced = -1;            // 最後にマスへ置いた文字（句読点を一緒に入れる相手）
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (joinsPrevious(text, i) || pairTail[i]) {
                col[i] = col[i - 1];    // 前の文字と同じマスに入る
                row[i] = row[i - 1];
                continue;
            }
            if (ch != '\n' && r == 0 && wrapped && tuckedCount < 2 && lastPlaced >= 0 && HANGING.indexOf(ch) >= 0) {
                col[i] = c - 1;         // 前の列の、一番下のマス（直前の文字と同じマス）に入れる
                row[i] = rows - 1;
                tucked[i] = ++tuckedCount;
                hasTucked[lastPlaced] = true;
                continue;
            }
            if (ch != '\n' && r == rows - 1 && rows > 1 && OPENING.indexOf(ch) >= 0
                    && i + 1 < n && text.charAt(i + 1) != '\n') {
                c++;                    // 開きかっこは行の一番下に置かず、次の行の頭へ送る（一番下のマスは空ける）
                r = 0;
                wrapped = true;
                tuckedCount = 0;
                lastPlaced = -1;        // 空けたマスには、句読点を入れない
            }
            if (ch == '\n' && wrapped && r == 0) {
                // 段落が列の一番下までちょうど埋まって（句読点を一番下のマスに入れた場合も）、もう折り返しで
                // 次の列へ来ているときは、さらに列を進めない（進めると、空の列ができてしまう）。
                // 改行そのものは、前の列の一番下のマスの「すぐ下」に置く（カーソルは、段落の終わりに出る。
                // 次の段落の1文字目と、同じマスを取り合わないように）
                col[i] = c - 1;
                row[i] = rows;
                wrapped = false;
                tuckedCount = 0;
                lastPlaced = -1;
                continue;
            }
            col[i] = c;
            row[i] = r;
            if (ch == '\n') {
                c++;                    // 改行したら、左の列の一番上へ
                r = 0;
                wrapped = false;
                tuckedCount = 0;
                lastPlaced = -1;
            } else {
                lastPlaced = i;
                r++;
                if (r >= rows) {        // 下まで来たら、折り返して左の列へ
                    c++;
                    r = 0;
                    wrapped = true;
                    tuckedCount = 0;
                }
            }
        }
        col[n] = c;                     // 文末のカーソル位置
        row[n] = r;
        totalColumns = c + 1;
    }

    /**
     * 文字が入っている列の数（少なくとも 1）。totalColumns と違い、文末のカーソルのためだけの空の列は数えない。
     * PDF の枚数に使う（文末が列のちょうど終わりのときに、白紙のページが1枚増えないように）。
     */
    public int usedColumns() {
        int last = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isDrawn(i)) {
                last = Math.max(last, col[i]);
            }
        }
        return last + 1;
    }

    /** i 文字目と次の文字が、縦中横にする組（半角数字2桁、または !? の2文字）かどうか。 */
    private boolean isPair(int i) {
        char a = text.charAt(i);
        char b = text.charAt(i + 1);
        boolean digits = isDigit(a) && isDigit(b);
        boolean marks = isMark(a) && isMark(b);
        if (!digits && !marks) {
            return false;
        }
        if (i + 2 < text.length() && joinsPrevious(text, i + 2)) {
            return false;               // 2文字目に結合文字などが付いているときは、ふつうに1文字ずつ置く
        }
        // 3文字以上続いているとき（例：2024）は、縦中横にしない
        char before = i > 0 ? text.charAt(i - 1) : ' ';
        char after = i + 2 < text.length() ? text.charAt(i + 2) : ' ';
        return digits ? !isAsciiWordChar(before) && !isAsciiWordChar(after) : !isMark(before) && !isMark(after);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isMark(char c) {
        return c == '!' || c == '?';
    }

    private static boolean isAsciiWordChar(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '.' || c == ',';
    }

    /**
     * i 文字目が、前の文字と同じマスに入るか（1文字として、間にカーソルを置かない）。
     * サロゲートペアの2つ目、異体字セレクタ、結合文字（濁点の ゙ やアクセントなど）、ゼロ幅接合子とその次の文字。
     * 改行のすぐ後ろでは、くっつける相手がないので false。
     */
    public static boolean joinsPrevious(String text, int i) {
        if (i <= 0 || i >= text.length() || text.charAt(i - 1) == '\n') {
            return false;
        }
        char ch = text.charAt(i);
        if (Character.isLowSurrogate(ch)) {
            return Character.isHighSurrogate(text.charAt(i - 1));
        }
        if (text.charAt(i - 1) == '\u200D') {
            return true;                // 絵文字をつなぐ印の次の文字
        }
        int codePoint = text.codePointAt(i);
        if (codePoint == 0x200D || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || (codePoint >= 0xE0100 && codePoint <= 0xE01EF)) {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    /** position を含む「ひとまとまりの文字」の始まり。カーソルを、その途中に止めないために使う。 */
    public static int clusterStart(String text, int position) {
        int p = Math.max(0, Math.min(position, text.length()));
        while (joinsPrevious(text, p)) {
            p--;
        }
        return p;
    }

    /** position から始まる「ひとまとまりの文字」の終わり（次の文字の始まり）。 */
    public static int clusterEnd(String text, int position) {
        if (position >= text.length()) {
            return text.length();
        }
        int p = position + 1;
        while (joinsPrevious(text, p)) {
            p++;
        }
        return p;
    }

    /** i 文字目が、画面に何かを描く文字かどうか（改行と、前の文字とまとめて描く文字は false）。 */
    public boolean isDrawn(int i) {
        return text.charAt(i) != '\n' && !pairTail[i] && !joinsPrevious(text, i);
    }

    /** i 文字目を、マスの中心 (centerX, centerY) に描く。 */
    public void drawCharacter(Graphics2D g2, FontMetrics fm, int i, double centerX, double centerY, int size) {
        if (!isDrawn(i)) {
            return;
        }
        if (tucked[i] == 0 && !hasTucked[i]) {
            drawPlain(g2, fm, i, centerX, centerY, size);
            return;
        }
        // 一番下のマスに、文字と句読点を一緒に入れる：文字は少し小さくして左上へ、句読点は小さくして右下へ
        if (tucked[i] == 0) {
            Graphics2D small = (Graphics2D) g2.create();
            small.translate(centerX - size * 0.12, centerY - size * 0.12);
            small.scale(0.75, 0.75);
            drawPlain(small, fm, i, 0, 0, size);
            small.dispose();
            return;
        }
        // 句読点は、ふだんの「右上に寄せる」ずらしを使わず、見た目の中心を置きたい場所に合わせる。
        // 1つなら右下の角、2つなら右側に上下に並べる（。」なら「。」が上）
        boolean two = tucked[i] == 2 || (i + 1 < tucked.length && tucked[i + 1] == 2);
        double x = centerX + size * 0.42;
        double y = !two ? centerY + size * 0.38 : tucked[i] == 1 ? centerY + size * 0.02 : centerY + size * 0.4;
        drawCentered(g2, String.valueOf(text.charAt(i)), x, y, 0.6);
    }

    /** 1文字を、見た目の中心が (x, y) に来るように、scale 倍の大きさで描く。かっこなどは 90 度回す。 */
    private static void drawCentered(Graphics2D g2, String s, double x, double y, double scale) {
        java.awt.font.GlyphVector glyphs = g2.getFont().createGlyphVector(g2.getFontRenderContext(), s);
        java.awt.geom.Rectangle2D box = glyphs.getVisualBounds();
        Graphics2D g = (Graphics2D) g2.create();
        g.translate(x, y);
        g.scale(scale, scale);
        if (ROTATED.indexOf(s.charAt(0)) >= 0) {
            g.rotate(Math.PI / 2);
        }
        g.drawGlyphVector(glyphs, (float) -box.getCenterX(), (float) -box.getCenterY());
        g.dispose();
    }

    private void drawPlain(Graphics2D g2, FontMetrics fm, int i, double centerX, double centerY, int size) {
        char ch = text.charAt(i);
        if (pairHead[i]) {
            // 縦中横：2文字を横に並べたまま、マスの中央に描く
            String pair = text.substring(i, i + 2);
            float x = (float) (centerX - fm.stringWidth(pair) / 2.0);
            float baseline = (float) (centerY + (fm.getAscent() - fm.getDescent()) / 2.0);
            g2.drawString(pair, x, baseline);
            return;
        }
        boolean aloneBefore = i == 0 || !isAsciiWordChar(text.charAt(i - 1));
        boolean aloneAfter = i + 1 >= text.length() || !isAsciiWordChar(text.charAt(i + 1));
        if ((isDigit(ch) || isMark(ch)) && aloneBefore && aloneAfter && !joinsPrevious(text, i + 1)) {
            // 1文字だけの数字や「!」「?」は、回さずに立てて描く
            float x = (float) (centerX - fm.charWidth(ch) / 2.0);
            g2.drawString(String.valueOf(ch), x, (float) (centerY + (fm.getAscent() - fm.getDescent()) / 2.0));
            return;
        }
        String s = text.substring(i, clusterEnd(text, i));     // 異体字セレクタや結合文字も一緒に描く
        drawVerticalCharacter(g2, fm, s, centerX, centerY, size);
    }

    /**
     * 縦書き用に、1文字を (centerX, centerY) を中心にして描く。
     * 記号や半角文字は 90 度回し、句読点や小さい仮名は右上に寄せる。
     */
    public static void drawVerticalCharacter(Graphics2D g2, FontMetrics fm, String s,
            double centerX, double centerY, double size) {
        char c = s.charAt(0);
        float x = (float) (centerX - fm.stringWidth(s) / 2.0);
        float baseline = (float) (centerY + (fm.getAscent() - fm.getDescent()) / 2.0);

        boolean halfWidth = c <= 0x00FF || (c >= 0xFF61 && c <= 0xFF9F);
        if (halfWidth || ROTATED.indexOf(c) >= 0) {
            Graphics2D rotated = (Graphics2D) g2.create();
            rotated.rotate(Math.PI / 2, centerX, centerY);      // 時計回りに 90 度
            rotated.drawString(s, x, baseline);
            rotated.dispose();
        } else if (PUNCTUATION.indexOf(c) >= 0) {
            g2.drawString(s, x + (float) (size * 0.55), baseline - (float) (size * 0.55));
        } else if (SMALL_KANA.indexOf(c) >= 0) {
            g2.drawString(s, x + (float) (size * 0.1), baseline - (float) (size * 0.1));
        } else {
            g2.drawString(s, x, baseline);
        }
    }

    /**
     * ルビを、親文字の右に小さく描く。
     *
     * @param rubyOffset  親文字のマスの中心から、ルビの中心までの横の距離
     * @param firstColumn 描く範囲の、最初の列（PDF で、今のページに入る列だけを描くために使う）
     * @param lastColumn  描く範囲の、最後の列
     */
    public void drawRubies(Graphics2D g2, Font font, Place place, double cellHeight, double size,
            double rubyOffset, int firstColumn, int lastColumn) {
        float rubySize = (float) (size * 0.42);
        double pitch = size * 0.46;                 // ルビ1文字ぶんの高さ
        Font saved = g2.getFont();
        g2.setFont(font.deriveFont(rubySize));
        FontMetrics fm = g2.getFontMetrics();

        for (RubyRun run : rubies) {
            // 親文字のうち、実際にマスを持っている文字を集める
            List<Integer> bases = new ArrayList<>();
            for (int i = run.baseStart(); i < run.baseEnd(); i++) {
                if (isDrawn(i)) {
                    bases.add(i);
                }
            }
            if (bases.isEmpty()) {
                continue;
            }
            int first = bases.get(0);
            int last = bases.get(bases.size() - 1);
            if (col[first] > lastColumn || col[last] < firstColumn) {
                continue;
            }
            int count = run.ruby().codePointCount(0, run.ruby().length());
            boolean oneColumn = col[first] == col[last];
            int k = 0;
            for (int offset = 0; offset < run.ruby().length(); k++) {
                int codePoint = run.ruby().codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                offset += character.length();

                int column;
                double y;
                if (oneColumn) {
                    // 親文字が1列に収まっている：親文字の長さに合わせて、均等に並べる。
                    // ルビのほうが長いときは、上下に少しはみ出させて、中心を合わせる
                    double top = place.centerY(row[first]) - cellHeight / 2;
                    double span = place.centerY(row[last]) + cellHeight / 2 - top;
                    double step = Math.max(span / count, pitch);
                    double start = top + (span - step * count) / 2;
                    column = col[first];
                    y = start + step * (k + 0.5);
                } else {
                    // 親文字が列をまたいでいる：ルビの文字ごとに、対応する親文字の横に置く
                    double position = (k + 0.5) / count * bases.size();
                    int base = bases.get(Math.min((int) position, bases.size() - 1));
                    column = col[base];
                    y = place.centerY(row[base]) - cellHeight / 2 + (position - (int) position) * cellHeight;
                }
                if (column < firstColumn || column > lastColumn) {
                    continue;
                }
                double x = place.centerX(column) + rubyOffset;
                drawVerticalCharacter(g2, fm, character, x, y, rubySize);
            }
        }
        g2.setFont(saved);
    }
}
