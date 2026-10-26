package com.example.minieditor;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「datum」コマンドの登録で、PATH の古い項目を外してよいかの見分け方（CommandRegistration.isStale）のテスト。
 * PATH そのものには触らない（command-path.ps1 は動かさない）。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.CommandTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class CommandTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("datum-command-test");
        try {
            Path now = Files.createDirectory(root.resolve("今の配布版"));
            Path kept = Files.createDirectory(root.resolve("前の配布版（まだある）"));
            Path gone = root.resolve("前の配布版（消した）");
            check(CommandRegistration.isStale(now, gone.toString()), "前のフォルダが今と違い、もう無ければ外す");
            check(CommandRegistration.isStale(now, gone + java.io.File.separator), "末尾に区切りが付いていても同じ");
            check(!CommandRegistration.isStale(now, kept.toString()), "前のフォルダがまだあれば、外さない");
            check(!CommandRegistration.isStale(now, now.toString()), "今と同じフォルダなら、外さない");
            check(!CommandRegistration.isStale(now, now.resolve(".").toString()), "書き方が違うだけの同じフォルダも、外さない");
            check(!CommandRegistration.isStale(now, null) && !CommandRegistration.isStale(now, "  "),
                    "前に登録していなければ、何もしない");
            check(!CommandRegistration.isStale(now, "前の配布版"), "相対パスは、どこを指すか分からないので外さない");
            check(!CommandRegistration.isStale(now, "\0"), "読めない値は、外さない");
        } finally {
            Files.deleteIfExists(root.resolve("今の配布版"));
            Files.deleteIfExists(root.resolve("前の配布版（まだある）"));
            Files.deleteIfExists(root);
        }
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }
}
