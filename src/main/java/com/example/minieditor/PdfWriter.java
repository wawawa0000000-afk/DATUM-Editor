package com.example.minieditor;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * 外部ライブラリを使わずに PDF を書き出す、小さなクラス。
 *
 * 1ページを1枚の画像として入れる。その上に、透明な文字の層（テキスト描画モード 3）を重ねる。
 * 文字の層は見えないので、見た目は画像のまま。選択・検索・コピーは、この文字の層で行われる。
 * 日本語フォントは埋め込まず、Adobe-Japan1 の HeiseiMin-W3 を参照するだけにしている（大きくなりすぎないように）。
 * 検索とコピーのために ToUnicode の表を付けている。
 *
 * PDF は「番号の付いた部品（オブジェクト）」を並べたファイルで、最後に
 * 「何番の部品が、ファイルの何バイト目にあるか」という表（xref）を付ける決まりになっている。
 * そのため、書いたバイト数を数えながら出力している。
 */
public final class PdfWriter implements Closeable {

    private final OutputStream out;
    private long position = 0;                              // ここまでに書いたバイト数
    private final List<Long> offsets = new ArrayList<>();   // offsets.get(n - 1) が、n 番の部品の位置
    private final List<Integer> pageObjects = new ArrayList<>();
    private boolean closed;

    // 1番は「カタログ」（入口）、2番は「ページの一覧」と決めておき、最後に中身を書く
    private static final int CATALOG = 1;
    private static final int PAGES = 2;
    // 3〜6番は、文字の層のフォント（ページから先に参照するので、番号を決めておき、最後に中身を書く）
    private static final int FONT = 3;          // Type0 フォント
    private static final int CID_FONT = 4;      // 日本語の CID フォント（参照だけ）
    private static final int DESCRIPTOR = 5;    // フォントの説明
    private static final int TO_UNICODE = 6;    // 文字コードを Unicode に戻す表
    private static final int FIRST_FREE = 7;    // ここから先は、ふつうに番号を振る
    // 検索・コピーに使う表（文字コード → 文字）。すべてのページで共通にする
    private final Map<Integer, String> codes = new LinkedHashMap<>();
    // 本文に使われている文字（reserveText で教えてもらう）。代わりの符号に使わない
    private final Set<Integer> reserved = new HashSet<>();
    // 代わりの符号に使った番号（サロゲートペアの文字 → 私用領域の1文字）
    private final Map<Integer, Integer> substitutes = new HashMap<>();
    private int nextSubstitute = 0xF8FF;                     // 私用領域（E000〜F8FF）を後ろから使う

    public PdfWriter(Path path) throws IOException {
        out = new BufferedOutputStream(Files.newOutputStream(path));
        for (int n = 1; n < FIRST_FREE; n++) {
            offsets.add(0L);                    // 固定番号の部品は、位置を後で書き込む
        }
        write("%PDF-1.4\n");
        // 2行目に英数字以外のバイトを置くのが慣例（テキストファイルと間違われないようにするため）
        write(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});
    }

    /**
     * これから書く本文を教える。本文の文字と同じ番号を、代わりの符号に使わないようにするため。
     * 本文を書く前（PagePrinter の各関数の最初）に呼ぶ。
     */
    public void reserveText(String text) {
        for (int i = 0; i < text.length(); i++) {
            reserved.add((int) text.charAt(i));
        }
    }

    public int getPageCount() {
        return pageObjects.size();
    }

    /** 画像1枚を、1ページとして追加する。ページの大きさはポイント（1/72 インチ）で指定する。 */
    public void addPage(BufferedImage image, double widthPt, double heightPt) throws IOException {
        addPage(image, widthPt, heightPt, List.of());
    }

    /**
     * 画像1枚と、その上に重ねる文字の層を、1ページとして追加する。
     * 文字の位置は、ページの左上を原点にしたポイントで指定する（y は下向き）。
     * 文字が1つもないときは、画像だけのページと同じ内容になる。
     */
    public void addPage(BufferedImage image, double widthPt, double heightPt, List<Glyph> glyphs) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();

        // 画像を「赤・緑・青」の順のバイト列にして、圧縮する
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(compressed, deflater)) {
            int[] row = new int[width];
            byte[] bytes = new byte[width * 3];
            for (int y = 0; y < height; y++) {
                image.getRGB(0, y, width, 1, row, 0, width);
                for (int x = 0; x < width; x++) {
                    bytes[x * 3] = (byte) (row[x] >> 16);
                    bytes[x * 3 + 1] = (byte) (row[x] >> 8);
                    bytes[x * 3 + 2] = (byte) row[x];
                }
                deflate.write(bytes);
            }
        } finally {
            deflater.end();
        }

        // 部品1：画像そのもの
        int imageObject = beginObject();
        write("<< /Type /XObject /Subtype /Image /Width " + width + " /Height " + height
                + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length "
                + compressed.size() + " >>\nstream\n");
        write(compressed.toByteArray());
        write("\nendstream\nendobj\n");

        // 部品2：「画像をページいっぱいに描く」という命令。文字があれば、その後ろに文字の層を書く
        StringBuilder content = new StringBuilder();
        content.append("q ").append(number(widthPt)).append(" 0 0 ").append(number(heightPt))
                .append(" 0 0 cm /Im0 Do Q\n");
        if (!glyphs.isEmpty()) {
            appendTextLayer(content, glyphs, heightPt);
        }
        byte[] contentBytes = content.toString().getBytes(StandardCharsets.US_ASCII);
        // 文字の層があるときは、圧縮する（長い文章では、文字の数だけ命令が並ぶため）
        String filter = glyphs.isEmpty() ? "" : " /Filter /FlateDecode";
        if (!glyphs.isEmpty()) {
            contentBytes = deflate(contentBytes);
        }
        int contentObject = beginObject();
        write("<< /Length " + contentBytes.length + filter + " >>\nstream\n");
        write(contentBytes);
        write("endstream\nendobj\n");

        // 部品3：ページ（大きさと、使う画像・命令・フォントを指す）
        String fonts = glyphs.isEmpty() ? "" : " /Font << /F1 " + FONT + " 0 R >>";
        int pageObject = beginObject();
        write("<< /Type /Page /Parent " + PAGES + " 0 R /MediaBox [0 0 " + number(widthPt) + " " + number(heightPt)
                + "] /Resources << /XObject << /Im0 " + imageObject + " 0 R >>" + fonts + " >> /Contents "
                + contentObject + " 0 R >>\nendobj\n");
        pageObjects.add(pageObject);
    }

    /**
     * 文字の層を書く。1文字ずつ、描く位置（Tm）と横の伸び縮み（Tz）を指定する。
     * Tz は、文字の幅を、フォントの標準の幅（1 em）に合わせるための倍率（％）。
     * 見えない文字なので、見た目には影響しない。選択の枠と、検索の位置を合わせるために使う。
     */
    private void appendTextLayer(StringBuilder content, List<Glyph> glyphs, double heightPt) {
        content.append("BT 3 Tr\n");                          // 3：描かない（見えない文字）
        for (Glyph glyph : glyphs) {
            if (glyph.text().isEmpty()) {
                continue;
            }
            StringBuilder hex = new StringBuilder();
            int count = 0;
            for (int i = 0; i < glyph.text().length(); ) {
                int codePoint = glyph.text().codePointAt(i);
                int code = codeFor(codePoint);
                if (code >= 0) {
                    hex.append(String.format(Locale.ROOT, "%04X", code));
                    count++;
                }
                i += Character.charCount(codePoint);
            }
            if (count == 0) {
                continue;
            }
            double tz = glyph.width() > 0 ? glyph.width() / (count * glyph.size()) * 100 : 100;
            content.append("/F1 ").append(number(glyph.size())).append(" Tf ")
                    .append(number(tz)).append(" Tz 1 0 0 1 ")
                    .append(number(glyph.x())).append(' ').append(number(heightPt - glyph.baseline()))
                    .append(" Tm <").append(hex).append("> Tj\n");
        }
        content.append("ET\n");
    }

    /**
     * 文字1つを、文字の層に書くときの2バイトの符号を決める。検索に使う表（codes）にも覚える。
     * BMP の文字は、そのままの符号（UTF-16 の1単位）。サロゲートペアの文字（𠮷 など）は、
     * 使われていない私用領域の1文字を代わりの符号にし、表でその文字に戻す。
     * 代わりの番号が足りなくなったら -1 を返す（その文字は、文字の層に入らない）。
     */
    private int codeFor(int codePoint) {
        if (codePoint <= 0xFFFF && !Character.isSurrogate((char) codePoint)) {
            String same = String.valueOf((char) codePoint);
            String known = codes.putIfAbsent(codePoint, same);
            if (known != null && !known.equals(same)) {
                throw new IllegalStateException("文字の層の符号が重なりました: " + codePoint);   // reserveText を忘れたとき
            }
            return codePoint;
        }
        Integer code = substitutes.get(codePoint);
        if (code == null) {
            while (nextSubstitute >= 0xE000 && reserved.contains(nextSubstitute)) {
                nextSubstitute--;
            }
            if (nextSubstitute < 0xE000) {
                return -1;
            }
            code = nextSubstitute--;
            substitutes.put(codePoint, code);
            codes.put(code, new String(Character.toChars(codePoint)));
        }
        return code;
    }

    /**
     * 文字の層の1文字（または、縦中横のような2文字ぶんの並び）。
     * text は文字そのもの、x と baseline は、ページの左上を原点にしたポイント（y は下向き）で、
     * x は文字の左端、baseline は文字の基準線の高さ。width は文字の幅（ポイント）。
     */
    public record Glyph(String text, double x, double baseline, double size, double width) {
    }

    /** 最後に、ページの一覧・入口・位置の表を書いて、ファイルを閉じる。 */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            writeFontObjects();
            StringBuilder kids = new StringBuilder();
            for (int page : pageObjects) {
                kids.append(page).append(" 0 R ");
            }
            offsets.set(PAGES - 1, position);
            write(PAGES + " 0 obj\n<< /Type /Pages /Kids [ " + kids + "] /Count " + pageObjects.size() + " >>\nendobj\n");
            offsets.set(CATALOG - 1, position);
            write(CATALOG + " 0 obj\n<< /Type /Catalog /Pages " + PAGES + " 0 R >>\nendobj\n");

            long xrefPosition = position;
            StringBuilder xref = new StringBuilder();
            xref.append("xref\n0 ").append(offsets.size() + 1).append('\n');
            xref.append("0000000000 65535 f \n");           // 0番は「空き」と決まっている
            for (long offset : offsets) {
                xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));  // 1行はちょうど 20 バイト
            }
            xref.append("trailer\n<< /Size ").append(offsets.size() + 1)
                    .append(" /Root ").append(CATALOG).append(" 0 R >>\nstartxref\n")
                    .append(xrefPosition).append("\n%%EOF\n");
            write(xref.toString());
        } finally {
            out.close();
        }
    }

    /**
     * 文字の層のフォントを書く。日本語フォントは埋め込まず、Adobe-Japan1 の HeiseiMin-W3 を参照するだけ。
     * 文字の位置は、こちらで決めて1字ずつ書いているので、フォントの幅の表（/W）は使わず、標準の幅（DW 1000）にしておく。
     */
    private void writeFontObjects() throws IOException {
        writeFixed(FONT, "<< /Type /Font /Subtype /Type0 /BaseFont /HeiseiMin-W3 /Encoding /UniJIS-UTF16-H"
                + " /DescendantFonts [" + CID_FONT + " 0 R] /ToUnicode " + TO_UNICODE + " 0 R >>");
        writeFixed(CID_FONT, "<< /Type /Font /Subtype /CIDFontType0 /BaseFont /HeiseiMin-W3"
                + " /CIDSystemInfo << /Registry (Adobe) /Ordering (Japan1) /Supplement 6 >>"
                + " /FontDescriptor " + DESCRIPTOR + " 0 R /DW 1000 >>");
        writeFixed(DESCRIPTOR, "<< /Type /FontDescriptor /FontName /HeiseiMin-W3 /Flags 4"
                + " /FontBBox [0 -200 1000 900] /ItalicAngle 0 /Ascent 880 /Descent -120 /CapHeight 700 /StemV 80 >>");

        // ToUnicode：文字コードを、もとの文字に戻す表。文字コードは、すべて2バイト。
        // サロゲートペアの文字は、codeFor で私用領域の符号に置き換えてあるので、ここで1文字に戻せる
        StringBuilder cmap = new StringBuilder();
        cmap.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n");
        cmap.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n");
        cmap.append("/CMapName /DATUM-UCS def\n/CMapType 2 def\n");
        cmap.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        List<Map.Entry<Integer, String>> entries = new ArrayList<>(codes.entrySet());
        for (int from = 0; from < entries.size(); from += 100) {       // bfchar は1回に100組まで
            int to = Math.min(entries.size(), from + 100);
            cmap.append(to - from).append(" beginbfchar\n");
            for (int i = from; i < to; i++) {
                Map.Entry<Integer, String> entry = entries.get(i);
                cmap.append('<').append(String.format(Locale.ROOT, "%04X", entry.getKey())).append("> <")
                        .append(hex(entry.getValue())).append(">\n");
            }
            cmap.append("endbfchar\n");
        }
        cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        byte[] cmapBytes = cmap.toString().getBytes(StandardCharsets.US_ASCII);
        writeFixed(TO_UNICODE, "<< /Length " + cmapBytes.length + " >>\nstream\n", cmapBytes, "endstream");
    }

    private static byte[] deflate(byte[] bytes) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflate = new DeflaterOutputStream(out, deflater)) {
                deflate.write(bytes);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);     // メモリの中への書き込みなので、ここでは起きない
        } finally {
            deflater.end();
        }
    }

    /** 固定の番号の部品を、今の位置に書く。 */
    private void writeFixed(int number, String body) throws IOException {
        writeFixed(number, body, new byte[0], "");
    }

    private void writeFixed(int number, String head, byte[] stream, String tail) throws IOException {
        offsets.set(number - 1, position);
        write(number + " 0 obj\n" + head);
        if (stream.length > 0) {
            write(stream);
            write("\n" + tail + "\n");
        } else if (!head.contains("stream")) {
            write("\n");
        }
        write("endobj\n");
    }

    /** 新しい部品を書き始める。位置を記録して、その番号を返す。 */
    private int beginObject() throws IOException {
        offsets.add(position);
        int number = offsets.size();
        write(number + " 0 obj\n");
        return number;
    }

    /** 文字を UTF-16 の16進数にする（例：「あ」→ 3042）。 */
    private static String hex(String text) {
        StringBuilder sb = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_16BE)) {
            sb.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);   // 国によって小数点が「,」にならないようにする
    }

    private void write(String text) throws IOException {
        write(text.getBytes(StandardCharsets.US_ASCII));
    }

    private void write(byte[] bytes) throws IOException {
        out.write(bytes);
        position += bytes.length;
    }
}
