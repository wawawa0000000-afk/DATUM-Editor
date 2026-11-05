package com.example.minieditor;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI を使わない校正チェック。決まった規則（ら抜き言葉・表記ゆれなど）で、気になる所を探す。
 * 文章は外に送らないので、どんな原稿にも使える。
 *
 * 規則で探すだけなので、間違いとは限らない（会話文でわざと崩している場合など）。
 * そのため「直す」は、利用者が1件ずつ確かめて押す形にしている。
 */
public final class Proofreader {

    /** 調べる項目。設定で、使わない項目を外せる。 */
    public enum Category {
        RA_NUKI("ら抜き言葉"),
        KEIGO("二重敬語"),
        DUPLICATE("重複表現"),
        SYMBOL("記号の使い方"),
        YURE("表記ゆれ"),
        INDENT("段落の字下げ"),
        BRACKET("かっこの対応"),
        LONG("長い文"),
        ENDING("文末の繰り返し"),
        NO_CHAIN("「の」の連続"),
        HANKAKU("半角カタカナ");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    /**
     * 見つかった所1つ。start〜end が気になる範囲。
     * fix が null でなければ、その範囲を fix に置き換える「直す」が使える。
     */
    public record Issue(int start, int end, Category category, String message, String fix) {
    }

    private static final int MAX_ISSUES = 2000;
    private static final int LONG_SENTENCE = 100;

    private Proofreader() {
    }

    public static List<Issue> check(String text) {
        return check(text, EnumSet.allOf(Category.class));
    }

    public static List<Issue> check(String text, Set<Category> enabled) {
        List<Issue> issues = new ArrayList<>();
        if (enabled.contains(Category.RA_NUKI)) {
            raNuki(text, issues);
        }
        if (enabled.contains(Category.KEIGO)) {
            keigo(text, issues);
        }
        if (enabled.contains(Category.DUPLICATE)) {
            duplicates(text, issues);
        }
        if (enabled.contains(Category.SYMBOL)) {
            symbols(text, issues);
        }
        if (enabled.contains(Category.YURE)) {
            yure(text, issues);
            width(text, issues);
        }
        if (enabled.contains(Category.INDENT)) {
            indent(text, issues);
        }
        if (enabled.contains(Category.BRACKET)) {
            brackets(text, issues);
        }
        if (enabled.contains(Category.LONG) || enabled.contains(Category.ENDING)) {
            sentences(text, issues, enabled);
        }
        if (enabled.contains(Category.NO_CHAIN)) {
            each(text, NO_CHAIN, m -> new Issue(m.start(), m.end(), Category.NO_CHAIN,
                    "「の」が続いています。言い換えると読みやすくなることがあります", null), issues);
        }
        if (enabled.contains(Category.HANKAKU)) {
            each(text, HANKAKU, m -> new Issue(m.start(), m.end(), Category.HANKAKU,
                    "半角カタカナです。全角にそろえましょう", Normalizer.normalize(m.group(), Normalizer.Form.NFKC)), issues);
        }

        // 場所の順に並べ、同じ範囲・同じ項目の重複を除く
        issues.sort(Comparator.comparingInt(Issue::start).thenComparingInt(Issue::end));
        List<Issue> result = new ArrayList<>();
        for (Issue issue : issues) {
            Issue last = result.isEmpty() ? null : result.get(result.size() - 1);
            if (last != null && last.start() == issue.start() && last.end() == issue.end()
                    && last.category() == issue.category()) {
                continue;
            }
            result.add(issue);
            if (result.size() >= MAX_ISSUES) {
                break;
            }
        }
        return result;
    }

    // ---------- 規則 ----------

    // 活用の続き方（「見れる」「見れない」「見れた」など）。「見れば」は正しい形なので含めない
    private static final String RA_TAIL = "(?=る|ない|なかった|なく|ます|ません|た|て)";
    // 「見れる」「来れる」など、1文字の動詞
    private static final Pattern RA_SHORT = Pattern.compile("(?<!\\p{IsHan})([見来出寝着居])れ" + RA_TAIL);
    // 「食べれる」「考えれる」など、漢字＋い段・え段のかな＋れる
    private static final Pattern RA_LONG = Pattern.compile("(?<=\\p{IsHan})([いきぎじちにびみりえけげせぜてでねべめれ])れ" + RA_TAIL);

    private static void raNuki(String text, List<Issue> issues) {
        for (Pattern pattern : new Pattern[]{RA_SHORT, RA_LONG}) {
            each(text, pattern, m -> new Issue(m.start(), m.end(), Category.RA_NUKI,
                    "ら抜き言葉かもしれません（「" + m.group(1) + "られ」）", m.group(1) + "られ"), issues);
        }
    }

    private static final Pattern KEIGO_GENERIC = Pattern.compile("[おご][\\p{IsHan}\\p{IsHiragana}]{1,5}になられ");
    private static final String[][] KEIGO_WORDS = {
        {"おっしゃられ", "「おっしゃる」だけで尊敬語です（おっしゃった・おっしゃいます）"},
        {"召し上がられ", "「召し上がる」だけで尊敬語です"},
        {"拝見させていただ", "「拝見する」だけで謙譲語です（拝見しました）"},
    };

    private static void keigo(String text, List<Issue> issues) {
        each(text, KEIGO_GENERIC, m -> new Issue(m.start(), m.end(), Category.KEIGO,
                "二重敬語です（「お〜になる」に「られる」が重なっています）", null), issues);
        for (String[] word : KEIGO_WORDS) {
            literal(text, word[0], start -> new Issue(start, start + word[0].length(), Category.KEIGO,
                    word[1], null), issues);
        }
    }

    // {見つける言葉, 直した形（null なら直し方の案だけ出す）, 説明}
    private static final String[][] DUPLICATES = {
        {"頭痛が痛", "頭が痛", null},
        {"馬から落馬", "落馬", null},
        {"一番最初", "最初", null},
        {"一番最後", "最後", null},
        {"まず最初に", "最初に", null},
        {"まず始めに", "始めに", null},
        {"後で後悔", "後悔", null},
        {"あとで後悔", "後悔", null},
        {"違和感を感じ", "違和感を覚え", null},
        {"犯罪を犯", "罪を犯", null},
        {"炎天下の中", "炎天下", null},
        {"今の現状", "現状", null},
        {"必ず必要", "必要", null},
        {"日本に来日", "来日", null},
        {"古来から", "古来", null},
        {"従来から", "従来", null},
        {"元旦の朝", "元旦", null},
        {"製造メーカー", "メーカー", null},
        {"各国ごと", "国ごと", null},
        {"第一日目", "第一日", null},
        {"最後の切り札", "切り札", null},
        {"余分な贅肉", "贅肉", null},
        {"あらかじめ予約", "予約", null},
        {"あらかじめ予定", "予定", null},
        {"いまだに未", "未", null},
        {"後ろへバック", "バック", null},
        {"被害を被", null, "「被害を受ける」または「害を被る」"},
        {"返事を返", null, "「返事をする」"},
        {"過半数を超え", null, "「過半数に達する」または「半数を超える」"},
        {"内定が決ま", null, "「内定する」"},
        {"挙式を挙げ", null, "「式を挙げる」"},
    };

    private static void duplicates(String text, List<Issue> issues) {
        for (String[] entry : DUPLICATES) {
            String message = "意味が重なっています（" + (entry[1] != null ? "「" + entry[1] + "」" : entry[2]) + "）";
            literal(text, entry[0], start -> new Issue(start, start + entry[0].length(), Category.DUPLICATE,
                    message, entry[1]), issues);
        }
    }

    private static final Pattern DOUBLE_COMMA = Pattern.compile("、{2,}");
    private static final Pattern DOUBLE_PERIOD = Pattern.compile("。{2,}");
    private static final Pattern NAKAGURO_DOTS = Pattern.compile("・{2,}");
    private static final Pattern SINGLE_LEADER = Pattern.compile("(?<!…)…(?!…)");
    private static final Pattern SINGLE_DASH = Pattern.compile("(?<!―)―(?!―)");
    private static final Pattern NO_SPACE_AFTER_MARK = Pattern.compile("[！？]+(?=[^\\s　！？!?」』）)\\]】])");
    private static final Pattern PERIOD_BEFORE_CLOSE = Pattern.compile("。(?=[」』])");
    private static final Pattern ASCII_PERIOD_RUN = Pattern.compile("\\.{2,}");
    private static final Pattern ASCII_HYPHEN_RUN = Pattern.compile("-{2,}");
    private static final Pattern FULL_MINUS_RUN = Pattern.compile("－{2,}");

    private static void symbols(String text, List<Issue> issues) {
        each(text, DOUBLE_COMMA, m -> new Issue(m.start(), m.end(), Category.SYMBOL, "読点（、）が続いています", "、"), issues);
        each(text, DOUBLE_PERIOD, m -> new Issue(m.start(), m.end(), Category.SYMBOL, "句点（。）が続いています", "。"), issues);
        each(text, NAKAGURO_DOTS, m -> new Issue(m.start(), m.end(), Category.SYMBOL,
                "「・・・」より、三点リーダーを2つ「……」が一般的です", "……"), issues);
        each(text, SINGLE_LEADER, m -> new Issue(m.start(), m.end(), Category.SYMBOL,
                "三点リーダーは2つ続けて「……」と書くのが一般的です", "……"), issues);
        each(text, SINGLE_DASH, m -> new Issue(m.start(), m.end(), Category.SYMBOL,
                "ダッシュは2つ続けて「――」と書くのが一般的です", "――"), issues);
        each(text, ASCII_PERIOD_RUN, m -> new Issue(m.start(), m.end(), Category.SYMBOL,
                "半角のピリオドが続いています。三点リーダーは2つ続けて「……」と書くのが一般的です", "……"), issues);
        each(text, ASCII_HYPHEN_RUN, m -> new Issue(m.start(), m.end(), Category.SYMBOL,
                "ダッシュは2つ続けて「――」と書くのが一般的です", "――"), issues);
        each(text, FULL_MINUS_RUN, m -> new Issue(m.start(), m.end(), Category.SYMBOL,
                "ダッシュは2つ続けて「――」と書くのが一般的です", "――"), issues);
        each(text, NO_SPACE_AFTER_MARK, m -> new Issue(m.start(), m.end(), Category.SYMBOL,
                "「！」「？」のあとに文が続くときは、全角スペースを1つ空けるのが一般的です", m.group() + "　"), issues);
        each(text, PERIOD_BEFORE_CLOSE, m -> new Issue(m.start(), m.end(), Category.SYMBOL,
                "閉じかっこの前の「。」は、小説では付けないのが一般的です", ""), issues);
    }

    // 表記ゆれ：{漢字の書き方の正規表現, かなの書き方の正規表現, 漢字の形, かなの形}
    private static final String YURE_TAIL = "(?=る|ない|なかった|た|ます|ません|て|れば|そう)";
    private static final String[][] YURE = {
        {"出来" + YURE_TAIL, "(?<![\\p{IsHiragana}])でき" + YURE_TAIL, "出来", "でき"},
        {"下さい", "ください", "下さい", "ください"},
        {"頂(?=く|き|いた|け|こう)", "いただ(?=く|き|いた|け|こう)", "頂", "いただ"},
        {"全て", "すべて", "全て", "すべて"},
        {"沢山", "たくさん", "沢山", "たくさん"},
        {"様々", "さまざま", "様々", "さまざま"},
        {"色々", "いろいろ", "色々", "いろいろ"},
        {"何故", "なぜ", "何故", "なぜ"},
        {"一緒", "いっしょ", "一緒", "いっしょ"},
        {"綺麗", "きれい", "綺麗", "きれい"},
        {"殆ど", "ほとんど", "殆ど", "ほとんど"},
        {"予め", "あらかじめ", "予め", "あらかじめ"},
        {"但し", "ただし", "但し", "ただし"},
        {"例えば", "たとえば", "例えば", "たとえば"},
        {"勿論", "もちろん", "勿論", "もちろん"},
        {"流石", "さすが", "流石", "さすが"},
        {"時々", "ときどき", "時々", "ときどき"},
        {"又は", "または", "又は", "または"},
        {"既に", "すでに", "既に", "すでに"},
        {"僅か", "わずか", "僅か", "わずか"},
        {"敢えて", "あえて", "敢えて", "あえて"},
    };

    private static void yure(String text, List<Issue> issues) {
        for (String[] pair : YURE) {
            List<int[]> kanji = find(text, Pattern.compile(pair[0]));
            List<int[]> kana = find(text, Pattern.compile(pair[1]));
            if (kanji.isEmpty() || kana.isEmpty()) {
                continue;               // どちらか一方だけなら、そろっているので問題ない
            }
            // 少ないほうを、多いほうに合わせる案を出す（同じ数なら、かなに合わせる）
            boolean toKana = kana.size() >= kanji.size();
            List<int[]> minority = toKana ? kanji : kana;
            String message = "表記ゆれ：「" + pair[2] + "」" + kanji.size() + " 回、「" + pair[3] + "」" + kana.size()
                    + " 回。「" + (toKana ? pair[3] : pair[2]) + "」にそろえる案です";
            for (int[] range : minority) {
                issues.add(new Issue(range[0], range[1], Category.YURE, message, toKana ? pair[3] : pair[2]));
            }
        }
    }

    private static final Pattern HALF_ALNUM = Pattern.compile("[0-9A-Za-z]+");
    private static final Pattern FULL_ALNUM = Pattern.compile("[０-９Ａ-Ｚａ-ｚ]+");

    /** 全角と半角の英数字が混ざっていたら、少ないほうを知らせる。 */
    private static void width(String text, List<Issue> issues) {
        List<int[]> half = find(text, HALF_ALNUM);
        List<int[]> full = find(text, FULL_ALNUM);
        if (half.isEmpty() || full.isEmpty()) {
            return;
        }
        boolean toFull = full.size() >= half.size();
        String message = "表記ゆれ：全角と半角の英数字が混ざっています。" + (toFull ? "全角" : "半角") + "にそろえる案です";
        for (int[] range : toFull ? half : full) {
            String part = text.substring(range[0], range[1]);
            issues.add(new Issue(range[0], range[1], Category.YURE, message, toFull ? toFullWidth(part)
                    : Normalizer.normalize(part, Normalizer.Form.NFKC)));
        }
    }

    private static String toFullWidth(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append((char) (c + 0xFEE0));     // 半角の英数字は、0xFEE0 足すと全角になる
        }
        return sb.toString();
    }

    /** 段落の多くが全角スペースで始まっているのに、そうなっていない段落を知らせる。 */
    private static void indent(String text, List<Issue> issues) {
        List<Integer> plain = new ArrayList<>();
        int indented = 0;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            if (lineEnd > lineStart && !text.substring(lineStart, lineEnd).isBlank()) {
                char first = text.charAt(lineStart);
                if (first == '　') {
                    indented++;
                } else if ("「『（(【〈《“―…‥・#＃■□◆◇*＊ \t".indexOf(first) < 0) {
                    plain.add(lineStart);   // 会話文や見出し・記号で始まる行は、字下げしないのがふつうなので除く
                }
            }
            lineStart = lineEnd + 1;
        }
        if (indented < 3 || indented <= plain.size()) {
            return;                         // 字下げしない書き方の文書には、何も言わない
        }
        for (int start : plain) {
            int end = text.offsetByCodePoints(start, 1);
            issues.add(new Issue(start, end, Category.INDENT,
                    "段落の始めが字下げされていません（ほかの段落は全角スペースで始まっています）",
                    "　" + text.substring(start, end)));
        }
    }

    private static final String OPEN = "「『（(【〈《";
    private static final String CLOSE = "」』）)】〉》";

    private static void brackets(String text, List<Issue> issues) {
        Deque<Integer> stack = new ArrayDeque<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int open = OPEN.indexOf(c);
            int close = CLOSE.indexOf(c);
            if (open >= 0) {
                stack.push(i);
            } else if (close >= 0) {
                if (stack.isEmpty()) {
                    issues.add(new Issue(i, i + 1, Category.BRACKET, "対応する開きかっこがありません", null));
                } else if (OPEN.indexOf(text.charAt(stack.peek())) != close) {
                    int opener = stack.pop();
                    issues.add(new Issue(opener, i + 1, Category.BRACKET,
                            "かっこの組み合わせが違います（" + text.charAt(opener) + " と " + c + "）", null));
                } else {
                    stack.pop();
                }
            }
        }
        for (int opener : stack) {
            issues.add(new Issue(opener, opener + 1, Category.BRACKET,
                    "閉じかっこ（" + CLOSE.charAt(OPEN.indexOf(text.charAt(opener))) + "）がありません", null));
        }
    }

    /** 一文の長さと、同じ文末の繰り返しを調べる。 */
    private static void sentences(String text, List<Issue> issues, Set<Category> enabled) {
        int start = 0;
        String previousEnding = null;
        int repeat = 0;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : '\n';
            if (c != '。' && c != '！' && c != '？' && c != '\n') {
                continue;
            }
            String sentence = text.substring(start, i).strip();
            int length = sentence.replaceAll("[\\s　]", "").codePointCount(0, sentence.replaceAll("[\\s　]", "").length());
            if (enabled.contains(Category.LONG) && length > LONG_SENTENCE) {
                int from = start + (text.substring(start, i).length() - text.substring(start, i).stripLeading().length());
                issues.add(new Issue(from, i, Category.LONG,
                        "一文が長めです（" + length + " 字）。句点で区切ると読みやすくなります", null));
            }
            if (c == '。' && sentence.length() >= 2) {
                String ending = sentence.substring(sentence.length() - 2);
                repeat = ending.equals(previousEnding) ? repeat + 1 : 1;
                previousEnding = ending;
                if (enabled.contains(Category.ENDING) && repeat >= 3) {
                    issues.add(new Issue(i - 2, i + 1, Category.ENDING,
                            "同じ文末「〜" + ending + "。」が " + repeat + " 回続いています", null));
                }
            } else if (c != '。') {
                previousEnding = null;      // 改行や「！」「？」で、続きは途切れたものとする
                repeat = 0;
            }
            start = i + 1;
        }
    }

    private static final Pattern NO_CHAIN = Pattern.compile("(?:[\\p{IsHan}\\p{IsKatakana}ー]{1,6}の){3,}[\\p{IsHan}\\p{IsKatakana}ー]");
    private static final Pattern HANKAKU = Pattern.compile("[\\uFF61-\\uFF9F]+");

    // ---------- 共通の道具 ----------

    private interface IssueMaker {
        Issue make(Matcher m);
    }

    private interface LiteralMaker {
        Issue make(int start);
    }

    private static void each(String text, Pattern pattern, IssueMaker maker, List<Issue> issues) {
        Matcher m = pattern.matcher(text);
        while (m.find() && issues.size() < MAX_ISSUES * 2) {
            issues.add(maker.make(m));
        }
    }

    private static void literal(String text, String word, LiteralMaker maker, List<Issue> issues) {
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + word.length())) {
            issues.add(maker.make(at));
        }
    }

    private static List<int[]> find(String text, Pattern pattern) {
        List<int[]> ranges = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            ranges.add(new int[]{m.start(), m.end()});
        }
        return ranges;
    }

    /** 項目ごとの件数（「ら抜き言葉 2・表記ゆれ 5」のような要約に使う）。 */
    public static Map<Category, Integer> countByCategory(List<Issue> issues) {
        Map<Category, Integer> counts = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            int n = (int) issues.stream().filter(i -> i.category() == category).count();
            if (n > 0) {
                counts.put(category, n);
            }
        }
        return counts;
    }
}
