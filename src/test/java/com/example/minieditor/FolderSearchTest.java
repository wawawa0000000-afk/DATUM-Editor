package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * フォルダ内の検索（FolderSearch）のテスト。
 *
 * 一時フォルダを作って、終わったら消す（原稿のフォルダは使わない）。
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.FolderSearchTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class FolderSearchTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("datum-folder-search-test");
        try {
            targets(base);
            hiddenAndTrash(base);
            symlinks(base);
            lineNumbers(base);
            openTextsWin(base);
            jsonBody(base);
            limit(base);
            cancel(base);
            regexAndEmpty(base);
            sizeLimit(base);
            order(base);
            shiftJis(base);
        } finally {
            deleteTree(base);
        }
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 対象は .txt・.md・.json だけ。ほかの拡張子は、中に文字があっても探さない。 */
    private static void targets(Path base) throws IOException {
        Path root = folder(base, "targets");
        write(root.resolve("a.txt"), "key\n");
        write(root.resolve("b.md"), "key\n");
        write(root.resolve("c.json"), "key\n");          // 形式の無い JSON は、ふつうの文章として探す
        write(root.resolve("d.png"), "key\n");
        write(root.resolve("e.docx"), "key\n");
        write(root.resolve("sub/f.TXT"), "key\n");        // 拡張子の大文字小文字は問わない
        Set<String> names = names(root, found(root));
        check(names.equals(Set.of("a.txt", "b.md", "c.json", "sub/f.TXT")),
                "対象は .txt・.md・.json だけ（.png・.docx は探さない）: " + names);
    }

    /** 隠しファイル・隠しフォルダ（.git）・ごみ箱（.datum-trash）は探さない。 */
    private static void hiddenAndTrash(Path base) throws IOException {
        Path root = folder(base, "hidden");
        write(root.resolve(".secret.txt"), "key\n");
        write(root.resolve(".git/a.txt"), "key\n");
        write(root.resolve(".datum-trash/x.txt"), "key\n");
        write(root.resolve("visible.txt"), "key\n");
        Set<String> names = names(root, found(root));
        check(names.equals(Set.of("visible.txt")), "隠しファイル・隠しフォルダ・ごみ箱は探さない: " + names);
    }

    /** リンク（シンボリックリンク）はたどらない。作れない環境では確認できなかったことにする。 */
    private static void symlinks(Path base) throws IOException {
        Path root = folder(base, "links");
        Path outside = folder(base, "links-outside");
        write(outside.resolve("outside.txt"), "key\n");
        write(outside.resolve("sub/deep.txt"), "key\n");
        write(root.resolve("real.txt"), "key\n");
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("outside.txt"));
            Files.createSymbolicLink(root.resolve("linkdir"), outside);
        } catch (IOException | UnsupportedOperationException ex) {
            System.out.println("確認できなかった  リンクはたどらない（この環境ではリンクを作れなかった）");
            return;
        }
        Set<String> names = names(root, found(root));
        check(names.equals(Set.of("real.txt")), "リンクのファイル・リンクのフォルダはたどらない: " + names);
    }

    /** 行番号と行の文字。同じ行に一致が複数あれば、一致ごとに別々に出す。 */
    private static void lineNumbers(Path base) throws IOException {
        Path root = folder(base, "lines");
        write(root.resolve("n.txt"), "一行目\n二 key\n三\nkey 四 key\n");
        List<FolderSearch.Hit> hits = new ArrayList<>();
        search(root, "key", false, false, Map.of(), hits);
        check(hits.size() == 3, "同じ行の一致は別々に出す（3件）: " + hits.size());
        if (hits.size() == 3) {
            check(hits.get(0).line() == 2 && hits.get(0).lineText().equals("二 key"), "1件目: 2行目「二 key」");
            check(hits.get(1).line() == 4 && hits.get(1).lineText().equals("key 四 key"), "2件目: 4行目「key 四 key」");
            check(hits.get(2).line() == 4 && hits.get(2).lineText().equals("key 四 key"), "3件目: 同じ行の2つ目");
            check(hits.get(1).start() == 12 && hits.get(1).end() == 15, "2件目の位置（本文の位置）: " + hits.get(1).start());
        }
    }

    /** 開いている未保存の文書は、ディスクの中身より優先して探す。 */
    private static void openTextsWin(Path base) throws IOException {
        Path root = folder(base, "open");
        Path file = root.resolve("p.txt");
        write(file, "old text\n");
        Map<Path, String> open = Map.of(file.toAbsolutePath().normalize(), "unsaved key\n");
        List<FolderSearch.Hit> hits = new ArrayList<>();
        search(root, "key", false, false, open, hits);
        check(hits.size() == 1 && hits.get(0).lineText().equals("unsaved key"),
                "開いている未保存の文書を、ディスクより優先して探す");
        List<FolderSearch.Hit> none = new ArrayList<>();
        search(root, "old", false, false, open, none);
        check(none.isEmpty(), "開いている文書では、ディスクにしかない文字は出ない");
    }

    /** .json の本文とカードの文字だけを探す。JSON の記法（format・text という名前）は探さない。 */
    private static void jsonBody(Path base) throws IOException {
        Path root = folder(base, "json");
        // 本文の文書は text、カードボードは cards を持つ（EditorPane の保存形式）
        write(root.resolve("body.json"), "{\"format\":\"" + EditorPane.FORMAT_NAME
                + "\",\"version\":1,\"text\":\"本文の key です\"}");
        write(root.resolve("board.json"), "{\"format\":\"" + EditorPane.FORMAT_NAME
                + "\",\"version\":1,\"cards\":[{\"text\":\"カードの key\"}]}");
        List<FolderSearch.Hit> hits = new ArrayList<>();
        search(root, "key", false, false, Map.of(), hits);
        check(hits.size() == 2, "JSON の本文とカードの文字を探す（2件）: " + hits.size());
        check(hits.stream().allMatch(FolderSearch.Hit::jsonBody), "JSON の一致は jsonBody が true");
        Set<String> lines = new TreeSet<>();
        hits.forEach(hit -> lines.add(hit.lineText()));       // 順は、パスの順（board.json が先）
        check(lines.equals(Set.of("本文の key です", "カードの key")), "本文とカードの一致の行の文字: " + lines);
        List<FolderSearch.Hit> format = new ArrayList<>();
        search(root, "format", false, false, Map.of(), format);
        check(format.isEmpty(), "JSON の記法の文字（format）は探さない");
        List<FolderSearch.Hit> text = new ArrayList<>();
        search(root, "text", false, false, Map.of(), text);
        check(text.isEmpty(), "JSON の記法の文字（text）は探さない");
    }

    /** 500 件で打ち切る。ちょうど 500 件なら、打ち切りではない。 */
    private static void limit(Path base) throws IOException {
        Path many = folder(base, "limit-over");
        write(many.resolve("big.txt"), "key\n".repeat(600));
        FolderSearch.Outcome over = search(many, "key", false, false, Map.of(), null);
        check(over.hits() == 500 && over.limited(), "501 件以上は 500 件で打ち切る: " + over.hits() + " 件");

        Path exact = folder(base, "limit-exact");
        write(exact.resolve("a.txt"), "key\n".repeat(250));
        write(exact.resolve("b.txt"), "key\n".repeat(250));
        FolderSearch.Outcome same = search(exact, "key", false, false, Map.of(), null);
        check(same.hits() == 500 && !same.limited(), "ちょうど 500 件は打ち切りではない");
    }

    /** キャンセルされたら、そこで止まる。 */
    private static void cancel(Path base) throws IOException {
        Path root = folder(base, "cancel");
        write(root.resolve("a.txt"), "key\n");
        write(root.resolve("b.txt"), "key\n");
        FolderSearch.Outcome already = FolderSearch.search(root, "key", false, false, Map.of(),
                hit -> { }, () -> true);
        check(already.cancelled() && already.hits() == 0, "最初からキャンセルされていれば、何も出さずに止まる");

        AtomicBoolean stop = new AtomicBoolean();
        List<FolderSearch.Hit> hits = new ArrayList<>();
        FolderSearch.Outcome later = FolderSearch.search(root, "key", false, false, Map.of(),
                hit -> {
                    hits.add(hit);
                    stop.set(true);             // 1件出したところで止めてもらう
                }, stop::get);
        check(later.cancelled() && hits.size() == 1, "途中でキャンセルされたら、次のファイルに進まず止まる: " + hits.size());
    }

    /** 正規表現が正しくなければ error。空の検索語は何も出さない。 */
    private static void regexAndEmpty(Path base) throws IOException {
        Path root = folder(base, "regex");
        write(root.resolve("a.txt"), "Key one\nkey two\n");
        FolderSearch.Outcome bad = search(root, "(", true, false, Map.of(), null);
        check(bad.error() != null && bad.hits() == 0, "正規表現が不正なら error が出る: " + bad.error());
        List<FolderSearch.Hit> regex = new ArrayList<>();
        FolderSearch.Outcome ok = search(root, "k.y", true, false, Map.of(), regex);
        check(ok.error() == null && regex.size() == 2, "正規表現で探せる（大文字小文字は区別しない）: " + regex.size());
        List<FolderSearch.Hit> sensitive = new ArrayList<>();
        search(root, "key", false, true, Map.of(), sensitive);
        check(sensitive.size() == 1, "大文字と小文字を区別すると、1件だけ");
        FolderSearch.Outcome empty = search(root, "", false, false, Map.of(), null);
        check(empty.hits() == 0 && empty.error() == null && empty.files() == 0, "空の検索語は何もしない");
    }

    /** 2MB を超えるファイルは飛ばす。 */
    private static void sizeLimit(Path base) throws IOException {
        Path root = folder(base, "size");
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        byte[] head = "key\n".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(head, 0, big, 0, head.length);
        java.util.Arrays.fill(big, head.length, big.length, (byte) 'a');
        Files.write(root.resolve("huge.txt"), big);
        write(root.resolve("small.txt"), "key\n");
        List<FolderSearch.Hit> hits = new ArrayList<>();
        FolderSearch.Outcome outcome = search(root, "key", false, false, Map.of(), hits);
        check(hits.size() == 1 && hits.get(0).file().getFileName().toString().equals("small.txt"),
                "2MB を超えるファイルは探さない");
        check(outcome.files() == 1, "飛ばしたファイルは、探したファイルの数に入らない: " + outcome.files());
    }

    /** ファイルの順は、パスの順。 */
    private static void order(Path base) throws IOException {
        Path root = folder(base, "order");
        write(root.resolve("b.txt"), "key\n");
        write(root.resolve("a.txt"), "key\n");
        List<FolderSearch.Hit> hits = new ArrayList<>();
        search(root, "key", false, false, Map.of(), hits);
        check(hits.size() == 2 && hits.get(0).file().getFileName().toString().equals("a.txt"),
                "結果はパスの順（a.txt の次に b.txt）");
    }

    // ---------- 補助 ----------

    /** フォルダの中で「key」を探して、見つかった一致の一覧。 */
    /** Shift_JIS で書いたファイルも、UTF-8 のファイルと同じように探せる。 */
    private static void shiftJis(Path base) throws IOException {
        Path root = folder(base, "sjis");
        Files.write(root.resolve("sjis.txt"), "一行目\n東京の本文です\n".getBytes(EncodingChoice.SHIFT_JIS_CHARSET));
        write(root.resolve("utf8.txt"), "一行目\n東京の本文です\n");
        List<FolderSearch.Hit> hits = new ArrayList<>();
        search(root, "東京", false, false, Map.of(), hits);
        Set<String> names = names(root, hits);
        check(names.equals(Set.of("sjis.txt", "utf8.txt")), "Shift_JIS のファイルも、UTF-8 のファイルと同じく「東京」を見つける: " + names);
        check(hits.stream().filter(h -> h.file().getFileName().toString().equals("sjis.txt"))
                .allMatch(h -> h.line() == 2 && h.lineText().equals("東京の本文です")),
                "Shift_JIS のファイルの一致は、2行目「東京の本文です」");
    }

    private static List<FolderSearch.Hit> found(Path root) {
        List<FolderSearch.Hit> hits = new ArrayList<>();
        search(root, "key", false, false, Map.of(), hits);
        return hits;
    }

    /** 結果を集める付きで探す（out が null なら集めない）。 */
    private static FolderSearch.Outcome search(Path root, String keyword, boolean regex, boolean caseSensitive,
            Map<Path, String> open, List<FolderSearch.Hit> out) {
        Map<Path, String> texts = new HashMap<>(open);
        return FolderSearch.search(root, keyword, regex, caseSensitive, texts,
                out == null ? hit -> { } : out::add, () -> false);
    }

    /** 見つかったファイルの、フォルダからの相対パスの集まり。 */
    private static Set<String> names(Path root, List<FolderSearch.Hit> hits) {
        Set<String> names = new TreeSet<>();
        Path real = root.toAbsolutePath().normalize();
        for (FolderSearch.Hit hit : hits) {
            names.add(real.relativize(hit.file()).toString().replace('\\', '/'));
        }
        return names;
    }

    private static Path folder(Path base, String name) throws IOException {
        return Files.createDirectories(base.resolve(name));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path base) {
        try (Stream<Path> stream = Files.walk(base)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);     // リンクは、リンクそのものを消す（たどらない）
                } catch (IOException ex) {
                    System.out.println("一時フォルダを消せませんでした: " + p);
                }
            });
        } catch (IOException ex) {
            System.out.println("一時フォルダを消せませんでした: " + base);
        }
    }
}
