package com.example.minieditor;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文章を A4 の紙に割り付けて、PDF のページにする。
 * 画面とは違い、白い紙に黒い文字で、印刷しやすい形にしている。
 * 長さの単位はポイント（1/72 インチ）。A4 は 幅 595 × 高さ 842 ポイント。
 */
public final class PagePrinter {

    private static final double PAGE_WIDTH = 595.28;
    private static final double PAGE_HEIGHT = 841.89;
    private static final double MARGIN = 56;                // 余白（約 2cm）
    private static final double PIXELS_PER_POINT = 2.5;     // 画像の細かさ（約 180dpi）
    private static final Color INK = new Color(0x111111);
    private static final Color PAGE_NUMBER = new Color(0x777777);

    private PagePrinter() {
    }

    /** 作業中の1ページ。画像と、それに描くための Graphics2D をまとめて持つ。 */
    private static final class Sheet {
        final double width;
        final double height;
        final BufferedImage image;
        final Graphics2D g;
        final List<PdfWriter.Glyph> glyphs = new ArrayList<>();     // 画像の上に重ねる、文字の層

        Sheet(Font font) {
            this(font, PAGE_WIDTH, PAGE_HEIGHT);
        }

        /** 紙の向きを変えたいとき（原稿用紙は横向き）は、幅と高さを指定する。 */
        Sheet(Font font, double width, double height) {
            this.width = width;
            this.height = height;
            image = new BufferedImage((int) Math.round(width * PIXELS_PER_POINT),
                    (int) Math.round(height * PIXELS_PER_POINT), BufferedImage.TYPE_INT_RGB);
            g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.scale(PIXELS_PER_POINT, PIXELS_PER_POINT);    // 以降は、ポイント単位の座標で描ける
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setFont(font);
            g.setColor(INK);
        }

        /** ページ番号を入れて、PDF に追加する。 */
        void finish(PdfWriter writer) throws IOException {
            String number = String.valueOf(writer.getPageCount() + 1);
            g.setFont(new Font("SansSerif", Font.PLAIN, 9));
            g.setColor(PAGE_NUMBER);
            float width = (float) g.getFont().getStringBounds(number, g.getFontRenderContext()).getWidth();
            g.drawString(number, (float) (this.width - width) / 2, (float) (this.height - 22));
            g.dispose();
            writer.addPage(image, this.width, this.height, glyphs);
        }
    }

    // ---------- 横書き ----------

    public static void printHorizontal(String text, String fontFamily, PdfWriter writer) throws IOException {
        writer.reserveText(text);
        Font font = new Font(fontFamily, Font.PLAIN, 11);
        double lineHeight = 11 * 1.7;
        double usableWidth = PAGE_WIDTH - 2 * MARGIN;
        int linesPerPage = (int) ((PAGE_HEIGHT - 2 * MARGIN) / lineHeight);

        Sheet sheet = new Sheet(font);
        List<String> lines = wrap(text.replace("\r", "").replace("\t", "    "), font,
                sheet.g.getFontRenderContext(), usableWidth);
        float ascent = sheet.g.getFontMetrics().getAscent();
        FontRenderContext context = sheet.g.getFontRenderContext();
        Map<Integer, Double> widths = new HashMap<>();
        int lineOnPage = 0;
        for (String line : lines) {
            if (lineOnPage == linesPerPage) {               // ページがいっぱいになったら、次の紙へ
                sheet.finish(writer);
                sheet = new Sheet(font);
                lineOnPage = 0;
            }
            double baseline = MARGIN + lineOnPage * lineHeight + ascent;
            sheet.g.drawString(line, (float) MARGIN, (float) baseline);
            // 文字の層：1字ずつ、描いたのと同じ位置に置く（控除の幅は、折り返しと同じ計算）
            double x = MARGIN;
            for (int i = 0; i < line.length(); ) {
                int codePoint = line.codePointAt(i);
                String character = new String(Character.toChars(codePoint));
                double w = widths.computeIfAbsent(codePoint,
                        key -> font.getStringBounds(character, context).getWidth());
                if (!Character.isISOControl(codePoint)) {
                    sheet.glyphs.add(new PdfWriter.Glyph(character, x, baseline, 11, w));
                }
                x += w;
                i += character.length();
            }
            lineOnPage++;
        }
        sheet.finish(writer);
    }

    /** 紙の幅に収まるように、1文字ずつ幅を足しながら折り返す。 */
    private static List<String> wrap(String text, Font font, FontRenderContext context, double width) {
        Map<Integer, Double> widths = new HashMap<>();      // 同じ文字の幅を、何度も測らないための控え
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            double used = 0;
            for (int i = 0; i < paragraph.length(); ) {
                int codePoint = paragraph.codePointAt(i);
                String character = new String(Character.toChars(codePoint));
                double w = widths.computeIfAbsent(codePoint,
                        key -> font.getStringBounds(character, context).getWidth());
                if (line.length() > 0 && used + w > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                    used = 0;
                }
                line.append(character);
                used += w;
                i += character.length();
            }
            lines.add(line.toString());
        }
        return lines;
    }

    // ---------- 縦書き ----------

    /**
     * 縦書きの1文字（または縦中横の2文字）を、文字の層に加える。ルビは入れない（本文だけを検索・コピーできるように）。
     * 位置は、画面と同じマスの中心。文字の層の基準線は、描く文字と同じ高さにそろえる。
     */
    private static void addVerticalGlyph(Sheet sheet, FontMetrics fm, VerticalLayout layout, String text, int i,
            double centerX, double centerY, double size) {
        if (!layout.isDrawn(i)) {
            return;
        }
        int end;
        if (i + 1 < text.length() && text.charAt(i + 1) != '\n' && !layout.isDrawn(i + 1)
                && !VerticalLayout.joinsPrevious(text, i + 1)) {
            end = i + 2;                                    // 縦中横：2文字を1つの枠にまとめる（次の文字は描かない）
        } else {
            end = VerticalLayout.clusterEnd(text, i);       // 異体字セレクタなどは、前の文字と一緒に
        }
        double baseline = centerY + (fm.getAscent() - fm.getDescent()) / 2.0;
        sheet.glyphs.add(new PdfWriter.Glyph(text.substring(i, end), centerX - size / 2, baseline, size, size));
    }

    public static void printVertical(String text, List<VerticalLayout.RubyRun> rubies, String fontFamily,
            PdfWriter writer) throws IOException {
        writer.reserveText(text);
        int size = 12;
        Font font = new Font(fontFamily, Font.PLAIN, size);
        double cellHeight = size * 1.12;                    // 1文字ぶんの高さ
        double columnWidth = size * 1.9;                    // 1行ぶんの幅（行間とルビのぶんを含む）
        // 一番下の1文字ぶんは、余白として空けておく（以前の、句読点をマスの外へ出していた版の名残り）
        int rowsPerColumn = (int) ((PAGE_HEIGHT - 2 * MARGIN) / cellHeight) - 1;
        int columnsPerPage = (int) ((PAGE_WIDTH - 2 * MARGIN) / columnWidth);

        // 配置は画面と同じ計算を使う
        String body = text;
        VerticalLayout layout = new VerticalLayout(body, rowsPerColumn, rubies);
        int pages = (layout.usedColumns() + columnsPerPage - 1) / columnsPerPage;     // 文末の空の列だけのページは作らない

        int next = 0;                                       // 次に描く文字
        for (int page = 0; page < pages; page++) {
            int firstColumn = page * columnsPerPage;
            int lastColumn = firstColumn + columnsPerPage - 1;
            Sheet sheet = new Sheet(font);
            FontMetrics fm = sheet.g.getFontMetrics();
            VerticalLayout.Place place = new VerticalLayout.Place() {
                public double centerX(int column) {
                    return PAGE_WIDTH - MARGIN - (column - firstColumn + 0.5) * columnWidth;
                }

                public double centerY(int row) {
                    return MARGIN + (row + 0.5) * cellHeight;
                }
            };
            // 文字は列の順に並んでいるので、このページの最後の列を過ぎたら次のページへ
            while (next < body.length() && layout.col[next] <= lastColumn) {
                double cx = place.centerX(layout.col[next]);
                double cy = place.centerY(layout.row[next]);
                layout.drawCharacter(sheet.g, fm, next, cx, cy, size);
                addVerticalGlyph(sheet, fm, layout, body, next, cx, cy, size);
                next++;
            }
            sheet.g.setColor(INK);
            layout.drawRubies(sheet.g, font, place, cellHeight, size, size * 0.77, firstColumn, lastColumn);
            sheet.finish(writer);
        }
    }

    // ---------- 原稿用紙（20字×20行） ----------

    /** A4 を横向きにして、400字詰めの原稿用紙として書き出す。1ページが、原稿用紙1枚。 */
    public static void printManuscript(String text, List<VerticalLayout.RubyRun> rubies, String fontFamily,
            PdfWriter writer) throws IOException {
        writer.reserveText(text);
        final int rows = VerticalTextPane.SHEET_ROWS;
        final int columns = VerticalTextPane.SHEET_COLUMNS;
        final double width = PAGE_HEIGHT;                   // 横向きなので、幅と高さを入れ替える
        final double height = PAGE_WIDTH;
        final double cell = 25;                             // マス1つの大きさ
        final double strip = 12;                            // ルビ欄の幅
        final double fold = 14;                             // 中央（10行目と11行目のあいだ）の空き
        final double pitch = cell + strip;
        final double side = (width - columns * pitch - fold) / 2;
        final double top = 34;
        final Color gridColor = new Color(0xB08D6A);        // マス目の線（茶色）
        int size = 19;
        Font font = new Font(fontFamily, Font.PLAIN, size);

        VerticalLayout layout = new VerticalLayout(text, rows, rubies);
        int pages = Math.max(1, (layout.usedColumns() + columns - 1) / columns);     // 文末の空の列だけのページは作らない

        int next = 0;
        for (int page = 0; page < pages; page++) {
            int firstColumn = page * columns;
            int lastColumn = firstColumn + columns - 1;
            Sheet sheet = new Sheet(font, width, height);
            Graphics2D g = sheet.g;
            VerticalLayout.Place place = new VerticalLayout.Place() {
                public double centerX(int column) {
                    int c = column - firstColumn;
                    return width - side - (c + 1) * pitch - (c >= columns / 2 ? fold : 0) + cell / 2;
                }

                public double centerY(int row) {
                    return top + (row + 0.5) * cell;
                }
            };

            // マス目とルビ欄
            g.setColor(gridColor);
            g.setStroke(new java.awt.BasicStroke(0.6f));
            for (int c = firstColumn; c <= lastColumn; c++) {
                double left = place.centerX(c) - cell / 2;
                for (int r = 0; r < rows; r++) {
                    g.draw(new java.awt.geom.Rectangle2D.Double(left, top + r * cell, cell, cell));
                }
                g.draw(new java.awt.geom.Rectangle2D.Double(left + cell, top, strip, rows * cell));
            }
            g.setFont(new Font("SansSerif", Font.PLAIN, 8));
            g.drawString(rows + "×" + columns, (float) side, (float) (height - 22));

            // 文字とルビ
            g.setFont(font);
            g.setColor(INK);
            FontMetrics fm = g.getFontMetrics();
            while (next < text.length() && layout.col[next] <= lastColumn) {
                double cx = place.centerX(layout.col[next]);
                double cy = place.centerY(layout.row[next]);
                layout.drawCharacter(g, fm, next, cx, cy, size);
                addVerticalGlyph(sheet, fm, layout, text, next, cx, cy, size);
                next++;
            }
            layout.drawRubies(g, font, place, cell, size, cell / 2 + strip / 2, firstColumn, lastColumn);
            sheet.finish(writer);
        }
    }
}
