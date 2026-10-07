package com.example.minieditor;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * 外部ライブラリを使わずに PDF を書き出す、小さなクラス。
 *
 * 1ページを1枚の画像として入れる方式にしている。
 * 文字をそのまま PDF に入れるには、日本語フォントを PDF の中に埋め込む処理が必要で、
 * それはとても大きな仕事になるため。画像にしておけば、画面と同じ見た目で、どの PC でも同じように表示できる。
 * （そのかわり、PDF の中の文字を選択・検索することはできない）
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

    public PdfWriter(Path path) throws IOException {
        out = new BufferedOutputStream(Files.newOutputStream(path));
        offsets.add(0L);
        offsets.add(0L);
        write("%PDF-1.4\n");
        // 2行目に英数字以外のバイトを置くのが慣例（テキストファイルと間違われないようにするため）
        write(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});
    }

    public int getPageCount() {
        return pageObjects.size();
    }

    /** 画像1枚を、1ページとして追加する。ページの大きさはポイント（1/72 インチ）で指定する。 */
    public void addPage(BufferedImage image, double widthPt, double heightPt) throws IOException {
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

        // 部品2：「画像をページいっぱいに描く」という命令
        byte[] content = ("q " + number(widthPt) + " 0 0 " + number(heightPt) + " 0 0 cm /Im0 Do Q\n")
                .getBytes(StandardCharsets.US_ASCII);
        int contentObject = beginObject();
        write("<< /Length " + content.length + " >>\nstream\n");
        write(content);
        write("endstream\nendobj\n");

        // 部品3：ページ（大きさと、使う画像・命令を指す）
        int pageObject = beginObject();
        write("<< /Type /Page /Parent " + PAGES + " 0 R /MediaBox [0 0 " + number(widthPt) + " " + number(heightPt)
                + "] /Resources << /XObject << /Im0 " + imageObject + " 0 R >> >> /Contents "
                + contentObject + " 0 R >>\nendobj\n");
        pageObjects.add(pageObject);
    }

    /** 最後に、ページの一覧・入口・位置の表を書いて、ファイルを閉じる。 */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
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

    /** 新しい部品を書き始める。位置を記録して、その番号を返す。 */
    private int beginObject() throws IOException {
        offsets.add(position);
        int number = offsets.size();
        write(number + " 0 obj\n");
        return number;
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
