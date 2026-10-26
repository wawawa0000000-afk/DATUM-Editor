package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Files.write(sjis, "吾輩は猫である。名前はまだ無い。".getBytes(Charset.forName("windows-31j")));
        String message = failure(files, sjis);
        check(message != null && message.contains("Shift_JIS") && message.contains("sjis.txt") && message.contains("UTF-8"),
                "Shift_JIS のファイルは、そうと分かる言葉で知らせる: " + message);
        check(Files.readAllBytes(sjis).length == 32, "読めなかったファイルには触らない");

        Path binary = dir.resolve("picture.bin");
        Files.write(binary, new byte[]{(byte) 0x89, 'P', 'N', 'G', (byte) 0xFF, (byte) 0xFF, (byte) 0x80});
        message = failure(files, binary);
        check(message != null && !message.contains("Shift_JIS") && message.contains("UTF-8"),
                "Shift_JIS でもないファイルは、そう書かない: " + message);
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
