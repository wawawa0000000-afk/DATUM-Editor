package com.example.minieditor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.SwingUtilities;

import com.example.minieditor.Proofreader.Category;
import com.example.minieditor.Proofreader.Issue;

/**
 * 校正（Proofreader）の記号の規則のテスト。半角のピリオド・ハイフン・全角のマイナスの連続を見る。
 *
 * 動かし方（Windows では : を ; に）：
 *   mvn -q test-compile
 *   java -cp target/classes:target/test-classes com.example.minieditor.ProofreaderTest
 * 1つでも失敗したら、終了コード 1 で終わる。
 */
public class ProofreaderTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        periods();
        hyphens();
        existingRules();
        panelInBackground();
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    // 記号の項目（SYMBOL）だけを取り出す
    private static List<Issue> symbols(String text) {
        return Proofreader.check(text).stream()
                .filter(issue -> issue.category() == Category.SYMBOL)
                .toList();
    }

    private static void periods() {
        String text = "待って...";
        List<Issue> found = symbols(text);
        check(found.size() == 1, "「待って...」は半角の3つのピリオドを1件にする");
        check(found.size() == 1 && "……".equals(found.get(0).fix()), "「...」の直し方は「……」");
        check(found.size() == 1 && text.substring(found.get(0).start(), found.get(0).end()).equals("..."),
                "「...」の範囲はピリオドの全部");

        found = symbols("..");
        check(found.size() == 1 && "……".equals(found.get(0).fix()), "「..」（2つ）も1件で、直し方は「……」");

        text = "......";
        found = symbols(text);
        check(found.size() == 1 && text.substring(found.get(0).start(), found.get(0).end()).equals(text),
                "「......」（6つ）は1件で、範囲は6字の全部");
    }

    private static void hyphens() {
        List<Issue> found = symbols("--");
        check(found.size() == 1 && "――".equals(found.get(0).fix()), "「--」は1件で、直し方は「――」");

        String text = "－－";
        found = symbols(text);
        check(found.size() == 1 && "――".equals(found.get(0).fix()), "全角の「－－」は1件で、直し方は「――」");
        check(found.size() == 1 && text.substring(found.get(0).start(), found.get(0).end()).equals(text),
                "全角の「－－」の範囲は2字の全部");

        found = symbols("a---b");
        check(found.size() == 1 && "a---b".substring(found.get(0).start(), found.get(0).end()).equals("---"),
                "「a---b」の3つのハイフンは1件で、範囲は3字");

        check(symbols("――").isEmpty(), "既に「――」なら指摘しない");
        check(symbols("ーー").isEmpty(), "長音記号「ーー」は指摘しない");
        check(symbols("-").isEmpty(), "単独の半角ハイフンは指摘しない");
        check(symbols("－").isEmpty(), "単独の全角マイナスは指摘しない");
        check(symbols("日本-語").isEmpty(), "単語の中の1つのハイフンは指摘しない");
        check(symbols("1.5").isEmpty(), "数字の間の1つのピリオドは指摘しない");
        check(symbols("終わり.").isEmpty(), "文末の1つのピリオドは指摘しない");
        check(symbols(".").isEmpty(), "単独のピリオドは指摘しない");
    }

    // 既存の規則と重ならないことを確かめる
    private static void existingRules() {
        List<Issue> found = symbols("…");
        check(found.size() == 1 && found.get(0).message().startsWith("三点リーダーは2つ続けて"),
                "「…」は既存の規則の1件だけ（新しい規則では指摘しない）");
        check(symbols("……").isEmpty(), "既に「……」なら指摘しない");

        found = symbols("・・・");
        check(found.size() == 1 && "……".equals(found.get(0).fix()),
                "「・・・」は既存の規則の1件だけ（新しい規則で増えない）");

        found = symbols("待って…");
        check(found.size() == 1 && "……".equals(found.get(0).fix()),
                "「待って…」は既存の規則の1件だけ");
    }

    /**
     * 校正の欄（ProofreadPanel）：打ったあとの調べ直しは裏のスレッドで行い、結果は、その場で調べたとき（refresh）と同じになる。
     * 調べている間に文章が変わったら、古い結果は出さない。
     */
    private static void panelInBackground() throws Exception {
        Path dir = Files.createTempDirectory("proofread-test");    // 校正辞書.txt を置く所（空のまま。終わったら消す）
        try {
            String first = "見れる。。食べれる。頭痛が痛い。";
            String[] text = {first};
            Searchable doc = new Searchable() {
                @Override public String getText() { return text[0]; }
                @Override public int getSelectionStart() { return 0; }
                @Override public int getSelectionEnd() { return 0; }
                @Override public void setHighlights(List<EditorPane.Highlight> highlights) { }
                @Override public void reveal(int offset) { }
                @Override public void select(int start, int end) { }
                @Override public void replaceRange(String replacement, int start, int end) { }
                @Override public void replaceRanges(List<Replacement> replacements) { }
            };
            AppSettings settings = AppSettings.load();          // 読むだけ（項目の入り切りを、両方の欄で同じにするため）
            ProofreadPanel[] panels = new ProofreadPanel[2];
            SwingUtilities.invokeAndWait(() -> {
                panels[0] = new ProofreadPanel(() -> doc, () -> { }, settings, () -> dir, () -> { });
                panels[1] = new ProofreadPanel(() -> doc, () -> { }, settings, () -> dir, () -> { });
                panels[1].refresh();                            // その場で調べた結果（比べる相手）
                refreshLater(panels[0]);
            });
            waitFor(() -> !panels[0].issues().isEmpty());
            List<Issue>[] got = issuesOf(panels);
            check(!got[1].isEmpty() && got[0].equals(got[1]), "裏で調べた結果は、その場で調べた結果と同じ（" + got[0].size() + " 件）");

            // 調べている間に文章が変わったら、古い結果は出さない
            SwingUtilities.invokeAndWait(() -> {
                panels[0] = new ProofreadPanel(() -> doc, () -> { }, settings, () -> dir, () -> { });
                text[0] = first;
                refreshLater(panels[0]);
                text[0] = "何もない文章です。";                // 結果が届く前に書き換える
            });
            Thread.sleep(1000);
            SwingUtilities.invokeAndWait(() -> { });
            check(issuesOf(panels)[0].isEmpty(), "調べている間に文章が変わったら、古い結果は一覧に出さない");
        } finally {
            Files.deleteIfExists(dir.resolve(ProofreadDictionary.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    private static void refreshLater(ProofreadPanel panel) {
        try {
            java.lang.reflect.Method method = ProofreadPanel.class.getDeclaredMethod("refreshLater");
            method.setAccessible(true);
            method.invoke(panel);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Issue>[] issuesOf(ProofreadPanel[] panels) throws Exception {
        List<Issue>[] result = new List[panels.length];
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < panels.length; i++) {
                result[i] = panels[i].issues();
            }
        });
        return result;
    }

    /** 画面のスレッドで確かめて、成り立つまで待つ（5 秒まで）。 */
    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        boolean[] ok = {false};
        for (int i = 0; i < 100 && !ok[0]; i++) {
            SwingUtilities.invokeAndWait(() -> ok[0] = condition.getAsBoolean());
            if (!ok[0]) {
                Thread.sleep(50);
            }
        }
    }
}
