package com.example.minieditor;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * AI 連携の道具（McpTools）のうち、エディタの画面を使わない部分のテスト（隠しファイル・リンク・重い正規表現）。
 * 開いている文書を扱う道具（id や、確認のダイアログ）は、画面が要るので、ここでは試さない。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.McpTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class McpTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("datum-mcp-test");
        try {
            run(root);
        } finally {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(Path root) throws Exception {
        McpSettings settings = new McpSettings();
        settings.workspace = root;
        McpTools tools = new McpTools(null, settings);      // 画面を使う道具は呼ばないので、ウィンドウは渡さない

        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "[core]");
        check(failed(tools.callTool("read_file", Map.of("path", ".git/config"))), "隠しフォルダの中は読めない");
        check(failed(tools.callTool("create_folder", Map.of("path", ".git/hooks2"))), "隠しフォルダの中に作れない");
        check(failed(tools.callTool("create_folder", Map.of("path", ".hidden"))), "名前が「.」で始まるフォルダは作れない");
        check(!Files.exists(root.resolve(".hidden")), "断ったときは、何も作らない");
        check(!failed(tools.callTool("create_folder", Map.of("path", "第一章"))), "ふつうのフォルダは作れる");
        check(failed(tools.callTool("create_folder", Map.of("path", "../外"))), "フォルダの外には作れない");

        Path outside = Files.createTempDirectory("datum-mcp-outside");
        try {
            Path link = root.resolve("外へのリンク");
            boolean linked;
            try {
                Files.createSymbolicLink(link, outside);
                linked = true;
            } catch (FileSystemException | UnsupportedOperationException ex) {
                linked = false;         // Windows では、権限がないとリンクを作れない
            }
            if (linked) {
                check(failed(tools.callTool("create_folder", Map.of("path", "外へのリンク/x"))), "リンクをたどってフォルダの外に作れない");
                Files.delete(link);
            }
        } finally {
            Files.deleteIfExists(outside);
        }

        // 重い正規表現（同じ字を2通りに読めて、最後だけ合わない形）は、時間を区切って止める
        Files.writeString(root.resolve("重い.txt"), "a".repeat(40) + "!");
        long started = System.nanoTime();
        Map<String, Object> result = tools.callTool("search",
                Map.of("query", "(a|a)+\\1b", "regex", true, "scope", "files"));
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        check(failed(result) && text(result).contains("時間がかかりすぎた"), "重い正規表現は、途中でやめて知らせる");
        check(seconds < 10, "やめるまでの時間が長すぎない（" + seconds + " 秒）");
    }

    private static boolean failed(Map<String, Object> result) {
        return Boolean.TRUE.equals(result.get("isError"));
    }

    private static String text(Map<String, Object> result) {
        return String.valueOf(result.get("content"));
    }
}
