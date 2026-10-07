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
        final BufferedImage image = new BufferedImage((int) Math.round(PAGE_WIDTH * PIXELS_PER_POINT),
                (int) Math.round(PAGE_HEIGHT * PIXELS_PER_POINT), BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();

        Sheet(Font font) {
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
            g.drawString(number, (float) (PAGE_WIDTH - width) / 2, (float) (PAGE_HEIGHT - 28));
            g.dispose();
            writer.addPage(image, PAGE_WIDTH, PAGE_HEIGHT);
        }
    }

    // ---------- 横書き ----------

    public static void printHorizontal(String text, String fontFamily, PdfWriter writer) throws IOException {
        Font font = new Font(fontFamily, Font.PLAIN, 11);
        double lineHeight = 11 * 1.7;
        double usableWidth = PAGE_WIDTH - 2 * MARGIN;
        int linesPerPage = (int) ((PAGE_HEIGHT - 2 * MARGIN) / lineHeight);

        Sheet sheet = new Sheet(font);
        List<String> lines = wrap(text.replace("\r", "").replace("\t", "    "), font,
                sheet.g.getFontRenderContext(), usableWidth);
        float ascent = sheet.g.getFontMetrics().getAscent();
        int lineOnPage = 0;
        for (String line : lines) {
            if (lineOnPage == linesPerPage) {               // ページがいっぱいになったら、次の紙へ
                sheet.finish(writer);
                sheet = new Sheet(font);
                lineOnPage = 0;
            }
            sheet.g.drawString(line, (float) MARGIN, (float) (MARGIN + lineOnPage * lineHeight) + ascent);
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

    public static void printVertical(String text, String fontFamily, PdfWriter writer) throws IOException {
        int size = 12;
        Font font = new Font(fontFamily, Font.PLAIN, size);
        double cellHeight = size * 1.12;                    // 1文字ぶんの高さ
        double columnWidth = size * 1.8;                    // 1行ぶんの幅（行間を含む）
        int rowsPerColumn = (int) ((PAGE_HEIGHT - 2 * MARGIN) / cellHeight);
        int columnsPerPage = (int) ((PAGE_WIDTH - 2 * MARGIN) / columnWidth);

        Sheet sheet = new Sheet(font);
        FontMetrics fm = sheet.g.getFontMetrics();
        String body = text.replace("\r", "");
        int column = 0;
        int row = 0;
        for (int i = 0; i < body.length(); ) {
            int codePoint = body.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            i += character.length();

            if (column == columnsPerPage) {                 // 左端まで来たら、次の紙の右端から
                sheet.finish(writer);
                sheet = new Sheet(font);
                fm = sheet.g.getFontMetrics();
                column = 0;
            }
            if (codePoint == '\n') {
                column++;
                row = 0;
                continue;
            }
            double centerX = PAGE_WIDTH - MARGIN - (column + 0.5) * columnWidth;
            double centerY = MARGIN + (row + 0.5) * cellHeight;
            VerticalTextPane.drawVerticalCharacter(sheet.g, fm, character, centerX, centerY, size);
            row++;
            if (row >= rowsPerColumn) {
                column++;
                row = 0;
            }
        }
        sheet.finish(writer);
    }
}
