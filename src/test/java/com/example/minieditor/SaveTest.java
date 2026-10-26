package com.example.minieditor;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Stream;

/**
 * 保存（FileService）と、異常終了からの復旧（Recovery）のテスト。
 *
 * 外から部品を足さないため、テストの枠組み（JUnit など）は使わず、main で動かす。
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.SaveTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class SaveTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("datum-save-test");
        try {
            saving(dir.resolve("save"));
            recovery(dir.resolve("recovery"));
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

    private static void saving(Path dir) throws Exception {
        Files.createDirectories(dir);
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        Path file = dir.resolve("原稿.txt");
        FileService files = new FileService();
        Files.writeString(file, "元の原稿");
        if (posix) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
        }
        check(files.unknown(file), "まだ読み書きしていないファイルは unknown");

        files.save("新しい原稿　日本語・絵文字😀・ｶﾅ", file);
        check(Files.readString(file).equals("新しい原稿　日本語・絵文字😀・ｶﾅ"), "保存して読み直すと同じ中身");
        check(count(dir) == 1, "一時ファイルが残らない");
        if (posix) {
            check(perms(file).equals("rw-rw-r--"), "元の権限のまま");
        }

        try {
            files.save("壊れた\uD800文字", file);
            check(false, "UTF-8 で書けない文字があると失敗する");
        } catch (IOException ex) {
            check(true, "UTF-8 で書けない文字があると失敗する");
        }
        check(Files.readString(file).startsWith("新しい原稿"), "失敗しても元の中身が残る");
        check(count(dir) == 1, "失敗しても一時ファイルが残らない");

        check(!files.changedOutside(file), "自分で保存した直後は「変更なし」");
        Thread.sleep(50);
        Files.writeString(file, "ほかのアプリが書いた、もっと長い文章");
        check(files.changedOutside(file), "ほかのアプリでの書き換えに気づく");
        files.read(file);
        check(!files.changedOutside(file), "読み直したら「変更なし」");

        Path link = dir.resolve("link.txt");
        boolean linked;
        try {
            Files.createSymbolicLink(link, file);
            linked = true;
        } catch (FileSystemException | UnsupportedOperationException ex) {
            linked = false;             // Windows では、権限がないとリンクを作れない
        }
        if (linked) {
            files.save("リンク経由", link);
            check(Files.isSymbolicLink(link) && Files.readString(file).equals("リンク経由"),
                    "リンクは残したまま、指している先を書き換える");
            Files.delete(link);
        }

        if (posix) {
            Path secret = dir.resolve("secret.json");
            FileService.writeSafely(secret, "{}", true);
            check(perms(secret).equals("rw-------"), "秘密のファイルは本人だけが読める");
            Path fresh = dir.resolve("new.txt");
            files.save("x", fresh);
            check(perms(fresh).equals("rw-r--r--"), "新しいファイルはふつうの権限");
        }
    }

    private static void recovery(Path root) throws Exception {
        // 動いているほかのプログラム（このテストを起動したもの）の番号を使う
        long pid = ProcessHandle.current().parent().orElseThrow().pid();
        Path folder = root.resolve(Long.toString(pid));
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("started.txt"), "1999-01-01T00:00:00Z");
        String doc = "{\"title\":\"A\",\"document\":{}}";
        Files.writeString(folder.resolve("1.json"), doc);
        Files.writeString(folder.resolve("2.json"), doc.replace("\"A\"", "\"B\""));

        Recovery recovery = new Recovery(root);
        List<Recovery.Entry> left = recovery.leftovers();
        check(left.size() == 2, "再起動で番号が重なっても、起動時刻が違えば前回の控えとして見える");
        recovery.discard(List.of(left.get(0)));
        check(recovery.leftovers().size() == 1, "復元できたものだけを消す");
        recovery.discard(recovery.leftovers());
        check(!Files.exists(folder), "控えがなくなったら、フォルダも片付ける");

        Files.createDirectories(folder);
        Files.writeString(folder.resolve("1.json"), doc);
        check(new Recovery(root).leftovers().isEmpty(), "起動時刻が書いていない、動いている番号の控えには触らない");
    }

    private static long count(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.count();
        }
    }

    private static String perms(Path file) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    }
}
