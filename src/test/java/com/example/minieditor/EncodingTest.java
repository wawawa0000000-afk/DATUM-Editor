package com.example.minieditor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * ファイルの文字コード・改行・BOM（FileService の read と save）のテスト。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.EncodingTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class EncodingTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("datum-encoding-test");
        try {
            run(dir);
            choices(dir.resolve("choices"));
            readTextOnly(dir.resolve("read-text"));
        } finally {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(Path dir) throws Exception {
        FileService files = new FileService();

        Path crlf = dir.resolve("windows.txt");
        Files.writeString(crlf, "一行目\r\n二行目\r\n");
        String text = files.read(crlf);
        check(text.equals("一行目\n二行目\n"), "CRLF のファイルは、エディタの中では \\n だけ");
        files.save(text + "三行目\n", crlf);
        check(Files.readString(crlf).equals("一行目\r\n二行目\r\n三行目\r\n"), "保存すると、足した行も CRLF に戻る");

        Path lf = dir.resolve("unix.txt");
        Files.writeString(lf, "あ\nい\n");
        files.save(files.read(lf) + "う", lf);
        check(Files.readString(lf).equals("あ\nい\nう"), "LF のファイルは LF のまま");

        Path mixed = dir.resolve("mixed.txt");
        Files.writeString(mixed, "a\r\nb\r\nc\nd");
        files.save(files.read(mixed), mixed);
        check(Files.readString(mixed).equals("a\r\nb\r\nc\r\nd"), "混ざっているときは、多いほう（CRLF）にそろえる");

        Path bom = dir.resolve("bom.txt");
        Files.write(bom, ("﻿本文\r\n").getBytes(StandardCharsets.UTF_8));
        text = files.read(bom);
        check(text.equals("本文\n"), "BOM は本文の1文字目に残さない");
        files.save(text + "続き", bom);
        check(Files.readString(bom).equals("﻿本文\r\n続き"), "保存すると、BOM と CRLF が元どおり付く");

        Path fresh = dir.resolve("new.txt");
        files.save("x\ny", fresh);
        check(Files.readString(fresh).equals("x\ny"), "新しいファイルは、LF・BOM なし");

        Path sjis = dir.resolve("sjis.txt");
        Charset windows31j = EncodingChoice.SHIFT_JIS_CHARSET;
        String sjisText = "吾輩は猫である。名前はまだ無い。";
        Files.write(sjis, sjisText.getBytes(windows31j));
        check(files.read(sjis).equals(sjisText), "Shift_JIS のファイルは、本文が正しく読める");
        check(files.formatOf(sjis).equals(EncodingChoice.SHIFT_JIS), "Shift_JIS で開いたことを覚えている");
        check(Files.readAllBytes(sjis).length == 32, "読んだだけでは、ファイルに触らない");
        files.save(sjisText + "続き", sjis);
        byte[] afterSave = Files.readAllBytes(sjis);
        check(Arrays.equals(afterSave, (sjisText + "続き").getBytes(windows31j)) && !isUtf8(afterSave),
                "Shift_JIS の文書を上書き保存すると、Shift_JIS のバイトのまま（UTF-8 ではない）");

        Path binary = dir.resolve("picture.bin");
        byte[] picture = {(byte) 0x89, 'P', 'N', 'G', (byte) 0xFF, (byte) 0xFF, (byte) 0x80};
        Files.write(binary, picture);
        String message = failure(files, binary);
        check(message != null && message.contains("UTF-8") && !message.contains("Shift_JIS"),
                "UTF-8 としても Shift_JIS としても読めないファイルは、今どおり開かない: " + message);
        check(Arrays.equals(Files.readAllBytes(binary), picture), "開けなかったファイルには触らない");
    }

    /** 書き方（文字コード・BOM・改行）を選んで保存する。 */
    private static void choices(Path dir) throws Exception {
        Files.createDirectories(dir);
        FileService files = new FileService();
        Charset windows31j = EncodingChoice.SHIFT_JIS_CHARSET;

        Path utf8 = dir.resolve("utf8.txt");
        Files.writeString(utf8, "東京の本文");
        Path toSjis = dir.resolve("to-sjis.txt");
        files.save(files.read(utf8) + "テスト", toSjis, EncodingChoice.SHIFT_JIS);
        check(Arrays.equals(Files.readAllBytes(toSjis), "東京の本文テスト".getBytes(windows31j)),
                "UTF-8 の文書を Shift_JIS に変えて保存すると、Shift_JIS のバイトになる");
        check(files.read(toSjis).equals("東京の本文テスト"), "Shift_JIS にして保存したファイルは、読み直すと本文が同じ");

        Path crlf = dir.resolve("crlf.txt");
        files.save("一行目\n二行目", crlf, EncodingChoice.UTF8.withCrlf(true));
        check(Files.readString(crlf).equals("一行目\r\n二行目"), "CRLF を選ぶと、改行は \\r\\n になる");
        check(files.read(crlf).equals("一行目\n二行目"), "CRLF で保存したファイルは、読み直すと本文が同じ");

        files.save("一行目\n二行目\n三行目", crlf, EncodingChoice.UTF8);
        check(Files.readString(crlf).equals("一行目\n二行目\n三行目") && Files.readString(crlf).indexOf('\r') < 0,
                "LF を選ぶと、\\r が1つも無い");

        Path bom = dir.resolve("bom.txt");
        files.save("本文", bom, EncodingChoice.UTF8_BOM);
        byte[] bomBytes = Files.readAllBytes(bom);
        check(bomBytes.length >= 3 && (bomBytes[0] & 0xFF) == 0xEF && (bomBytes[1] & 0xFF) == 0xBB && (bomBytes[2] & 0xFF) == 0xBF,
                "UTF-8（BOM 付き）を選ぶと、先頭が EF BB BF");
        check(files.read(bom).equals("本文"), "BOM 付きで保存したファイルは、読み直すと本文が同じ");

        Path unwritableDir = dir.resolve("unwritable");
        Files.createDirectories(unwritableDir);
        Path target = unwritableDir.resolve("emoji.txt");
        Files.writeString(target, "元の本文");
        IOException failed = null;
        try {
            files.save("絵文字😀入り", target, EncodingChoice.SHIFT_JIS);
        } catch (IOException ex) {
            failed = ex;
        }
        check(failed instanceof FileService.UnwritableException && failed.getMessage().contains("U+1F600"),
                "Shift_JIS で書けない絵文字は、保存せず、どの文字かを知らせる: " + (failed == null ? "（失敗しなかった）" : failed.getMessage()));
        check(Files.readString(target).equals("元の本文"), "失敗したら、ファイルは元のまま");
        try (Stream<Path> list = Files.list(unwritableDir)) {
            check(list.count() == 1, "失敗したら、一時ファイルも残さない");
        }
    }

    /** readText は読むだけ。書き方も、外で変わったかの記録も変えない（履歴・検索・集計で使うため）。 */
    private static void readTextOnly(Path dir) throws Exception {
        Files.createDirectories(dir);
        FileService files = new FileService();
        Charset windows31j = EncodingChoice.SHIFT_JIS_CHARSET;

        Path sjis = dir.resolve("sjis.txt");
        Files.write(sjis, "東京の本文です".getBytes(windows31j));
        check(files.readText(sjis).equals("東京の本文です"), "readText で、Shift_JIS のファイルの本文が正しく読める");
        check(files.unknown(sjis) && !files.changedOutside(sjis),
                "readText で読んだだけでは、このアプリが読んだ記録は残らない（書き方も覚えない）");
        check(files.formatOf(sjis).equals(EncodingChoice.UTF8), "readText のあとも、書き方は既定の UTF-8 のまま");

        Path crlf = dir.resolve("crlf.txt");
        Files.write(crlf, "一行目\r\n二行目\r\n".getBytes(StandardCharsets.UTF_8));
        check(files.readText(crlf).equals("一行目\n二行目\n"), "readText でも、改行は \\n にそろえる");
        check(files.formatOf(crlf).equals(EncodingChoice.UTF8) && !files.formatOf(crlf).crlf(),
                "readText では、CRLF だということも覚えない");

        Path bom = dir.resolve("bom.txt");
        Files.write(bom, ("\uFEFF本文").getBytes(StandardCharsets.UTF_8));
        check(files.readText(bom).equals("本文"), "readText でも、先頭の BOM は取る");

        Path seen = dir.resolve("seen.txt");
        Files.writeString(seen, "最初");
        files.read(seen);
        Files.write(seen, "外で書き換えた内容です".getBytes(windows31j));
        check(files.changedOutside(seen), "read で読んだあと、外で書き換えたら changedOutside は true");
        check(files.readText(seen).equals("外で書き換えた内容です"), "readText で、書き換えたあとの中身が読める");
        check(files.changedOutside(seen), "readText のあとも changedOutside は true のまま（記録を更新していない）");

        Path binary = dir.resolve("picture.bin");
        Files.write(binary, new byte[]{(byte) 0x89, 'P', 'N', 'G', (byte) 0xFF, (byte) 0xFF, (byte) 0x80});
        boolean unreadable = false;
        try {
            files.readText(binary);
        } catch (FileService.UnreadableException ex) {
            unreadable = true;
        }
        check(unreadable, "readText でも、読めないファイルは UnreadableException（今どおり）");
    }

    /** UTF-8 として正しく読めるか（Shift_JIS のバイト列は、ふつう読めない）。 */
    private static boolean isUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    private static String failure(FileService files, Path path) {
        try {
            files.read(path);
            return null;
        } catch (IOException ex) {
            return ex.getMessage();
        }
    }
}
