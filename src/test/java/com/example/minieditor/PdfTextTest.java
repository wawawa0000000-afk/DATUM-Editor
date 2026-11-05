package com.example.minieditor;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * PDF の文字の層（選択・検索・コピーできる文字）のテスト。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.PdfTextTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 *
 * pdftotext（poppler-utils）が無い環境では、取り出しの項目は「確認できなかった」と表示する（失敗にはしない）。
 * 作ったPDFは、一時フォルダに作って、終わったら消す。
 */
public class PdfTextTest {

    private static int failures;

    // 本文。改行・縦中横（2024）・半角英字・非 BMP の文字（𠮷）を含む
    private static final String BODY = "吾輩は猫である。名前はまだ無い。\n"
            + "𠮷野家で2024年、「ＡＢ」を食べた。\n"
            + "東京の空は青い。\n"
            + "長い文章です。".repeat(60);
    // ルビ（「東京」の読み）。ルビは文字の層に入らないので、取り出した文字に出てこない
    private static final String RUBY_READING = "とうきょう";

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static void unverified(String what) {
        System.out.println("確認できなかった: " + what + "（pdftotext が見つからない）");
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("datum-pdftext-");
        try {
            structure(dir);
            imageUnchanged(dir);
            if (pdftotextAvailable()) {
                extraction(dir);
            } else {
                for (String what : List.of("横書きの本文を取り出せる", "縦書きの本文を取り出せる",
                        "原稿用紙の本文を取り出せる", "ルビは取り出した文字に入らない")) {
                    unverified(what);
                }
            }
        } finally {
            deleteRecursively(dir);
        }
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------- 作る ----------

    /** 横書き・縦書き・原稿用紙の PDF を作って、そのパスを返す。 */
    private static Path make(Path dir, String name, int kind) throws IOException {
        Path path = dir.resolve(name);
        List<VerticalLayout.RubyRun> runs = List.of(new VerticalLayout.RubyRun(
                BODY.indexOf("東京"), BODY.indexOf("東京") + 2, RUBY_READING));
        try (PdfWriter writer = new PdfWriter(path)) {
            if (kind == 0) {
                PagePrinter.printHorizontal(BODY, "Serif", writer);
            } else if (kind == 1) {
                PagePrinter.printVertical(BODY, runs, "Serif", writer);
            } else {
                PagePrinter.printManuscript(BODY, runs, "Serif", writer);
            }
        }
        return path;
    }

    // ---------- PDF の構造 ----------

    private static void structure(Path dir) throws IOException {
        String[] names = {"横書き", "縦書き", "原稿用紙"};
        for (int kind = 0; kind < names.length; kind++) {
            Path pdf = make(dir, "structure" + kind + ".pdf", kind);
            check(xrefOk(pdf), names[kind] + "：xref と trailer が正しく読める");
        }
    }

    /** 最後の xref の位置から、すべての部品が正しい位置にあるかを確かめる。 */
    private static boolean xrefOk(Path pdf) throws IOException {
        String s = new String(Files.readAllBytes(pdf), StandardCharsets.ISO_8859_1);
        if (!s.startsWith("%PDF-1.4") || !s.trim().endsWith("%%EOF")) {
            return false;
        }
        int sx = s.lastIndexOf("startxref");
        if (sx < 0) {
            return false;
        }
        int xref;
        try {
            xref = Integer.parseInt(s.substring(sx + "startxref".length()).trim().split("\\s+")[0]);
        } catch (NumberFormatException ex) {
            return false;
        }
        if (xref < 0 || xref >= s.length() || !s.startsWith("xref\n", xref)) {
            return false;
        }
        int pos = xref + "xref\n".length();
        int eol = s.indexOf('\n', pos);
        String[] header = s.substring(pos, eol).split(" ");
        if (header.length != 2 || !"0".equals(header[0])) {
            return false;
        }
        int count = Integer.parseInt(header[1]);
        pos = eol + 1;
        for (int i = 0; i < count; i++) {
            String entry = s.substring(pos, pos + 20);           // 1行はちょうど 20 バイト
            pos += 20;
            if (i == 0) {
                if (!entry.startsWith("0000000000 65535 f")) {
                    return false;
                }
                continue;
            }
            if (!entry.endsWith(" 00000 n \n") && !entry.endsWith(" 00000 n\r\n")) {
                return false;
            }
            int offset = Integer.parseInt(entry.substring(0, 10));
            if (!s.startsWith(i + " 0 obj", offset)) {
                return false;
            }
        }
        return s.indexOf("trailer", pos) > 0 && s.indexOf("/Root 1 0 R", pos) > 0;
    }

    // ---------- 見た目（画像）は変わらない ----------

    private static void imageUnchanged(Path dir) throws IOException {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 40; x++) {
                image.setRGB(x, y, (x * 6) << 16 | (y * 8) << 8 | 0x40);
            }
        }
        Path plain = dir.resolve("image-only.pdf");
        try (PdfWriter writer = new PdfWriter(plain)) {
            writer.addPage(image, 100, 80);
        }
        Path layered = dir.resolve("image-text.pdf");
        try (PdfWriter writer = new PdfWriter(layered)) {
            writer.addPage(image, 100, 80, List.of(
                    new PdfWriter.Glyph("あ", 10, 20, 12, 12),
                    new PdfWriter.Glyph("𠮷", 30, 40, 12, 12)));
        }
        String plainText = new String(Files.readAllBytes(plain), StandardCharsets.ISO_8859_1);
        String layeredText = new String(Files.readAllBytes(layered), StandardCharsets.ISO_8859_1);
        check(imageBytes(plainText).equals(imageBytes(layeredText)), "画像のバイト列は、文字の層があっても変わらない");
        check(!plainText.contains("/F1 3 0 R") && layeredText.contains("/F1 3 0 R"),
                "画像だけのページには文字の層がない・文字があれば層が入る");
    }

    /** ページに入っている画像の中身（圧縮したままのバイト列）を取り出す。 */
    private static String imageBytes(String pdf) {
        int start = pdf.indexOf("/Subtype /Image");
        int body = pdf.indexOf("stream\n", start) + "stream\n".length();
        return pdf.substring(body, pdf.indexOf("\nendstream", body));
    }

    // ---------- pdftotext で取り出す ----------

    private static boolean pdftotextAvailable() {
        try {
            Process p = new ProcessBuilder("pdftotext", "-v")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor() == 0;
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** pdftotext で文字を取り出す。-raw なので、文字は書いた順（読む順）に出る。 */
    private static String extract(Path dir, Path pdf) throws IOException, InterruptedException {
        Path out = dir.resolve(pdf.getFileName() + ".txt");
        Process p = new ProcessBuilder("pdftotext", "-raw", "-enc", "UTF-8", pdf.toString(), out.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        p.waitFor();
        return Files.readString(out, StandardCharsets.UTF_8);
    }

    /** 改行・空白・ページ送りを除いて比べる（縦書きは、文字ごとに行が分かれて出ることがあるため）。 */
    private static String squeeze(String s) {
        return s.replaceAll("\\s", "");
    }

    private static void extraction(Path dir) throws Exception {
        String expected = squeeze(BODY);
        String[] names = {"横書きの本文を取り出せる", "縦書きの本文を取り出せる", "原稿用紙の本文を取り出せる"};
        for (int kind = 0; kind < names.length; kind++) {
            Path pdf = make(dir, "text" + kind + ".pdf", kind);
            String got = squeeze(extract(dir, pdf));
            check(got.equals(expected), names[kind] + "（改行・縦中横・非 BMP の文字を含む）");
            if (kind > 0) {
                check(!got.contains(RUBY_READING), "ルビ（" + RUBY_READING + "）は取り出した文字に入らない（" + names[kind] + "）");
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
