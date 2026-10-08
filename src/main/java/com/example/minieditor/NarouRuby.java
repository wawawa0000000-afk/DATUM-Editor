package com.example.minieditor;

import java.util.ArrayList;
import java.util.List;

/**
 * 「小説家になろう」のルビ（ふりがな）記法を読み取る。
 *
 * 対応している書き方：
 *   ｜山田太郎《やまだたろう》   … ｜ から 《 までが親文字、《》の中がルビ
 *   漢字《かんじ》               … 漢字が続いているときは、｜ を省略できる
 *   漢字(かんじ) 漢字（かんじ）  … かっこでもよい（中身が、ひらがな・カタカナだけのとき）
 *   ｜《そのまま》 ｜(そのまま)  … ｜ の直後がかっこなら、ルビにしない（打ち消し）
 *
 * 親文字もルビも、10文字まで。｜ は全角（｜）でも半角（|）でもよい。
 * 傍点は「｜字《・》」のように、1文字ずつ「・」のルビを振って表す。
 */
public final class NarouRuby {

    public static final int MAX_LENGTH = 10;

    /**
     * ルビ1つぶんの範囲（どれも「何文字目から何文字目の手前まで」）。
     * start〜end が記法全体、baseStart〜baseEnd が親文字、rubyStart〜rubyEnd がルビ。
     */
    public record Span(int start, int end, int baseStart, int baseEnd, int rubyStart, int rubyEnd) {
    }

    private NarouRuby() {
    }

    /** 文章の中から、ルビの記法をすべて探す。先頭から順に並んだリストを返す。 */
    public static List<Span> parse(String s) {
        List<Span> spans = new ArrayList<>();
        int consumed = 0;               // ここより前は、もう別のルビの一部として使った
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (isBar(c)) {
                if (i + 1 < n && isOpen(s.charAt(i + 1))) {
                    i++;                // ｜《 は「ルビにしない」という意味なので、かっこごと読み飛ばす
                    consumed = i + 1;
                    continue;
                }
                // ｜ の後ろ、10文字以内に開きかっこがあるかを探す
                int open = -1;
                for (int j = i + 1; j < n && j - (i + 1) <= MAX_LENGTH; j++) {
                    char d = s.charAt(j);
                    if (d == '\n' || isBar(d)) {
                        break;
                    }
                    if (isOpen(d)) {
                        open = j;
                        break;
                    }
                }
                if (open > i + 1) {
                    int close = findClose(s, open);
                    if (close > 0) {
                        spans.add(new Span(i, close + 1, i + 1, open, open + 1, close));
                        i = close;
                        consumed = close + 1;
                    }
                }
            } else if (isOpen(c) && i > consumed) {
                // ｜ がない場合：直前に続いている漢字が親文字になる
                int baseStart = i;
                while (baseStart > consumed && i - baseStart < MAX_LENGTH) {
                    int length = kanjiLengthBefore(s, baseStart);
                    if (length == 0 || baseStart - length < consumed) {
                        break;
                    }
                    baseStart -= length;
                }
                if (baseStart == i) {
                    continue;           // 直前が漢字ではない
                }
                if (baseStart > consumed && kanjiLengthBefore(s, baseStart) > 0) {
                    continue;           // 漢字が10文字より長く続いている場合は、ルビにしない
                }
                int close = findClose(s, i);
                if (close > 0) {
                    spans.add(new Span(baseStart, close + 1, baseStart, i, i + 1, close));
                    i = close;
                    consumed = close + 1;
                }
            }
        }
        return spans;
    }

    /** 記法を読み取った結果：記号を取り除いた本文と、ルビの一覧（位置は、本文の中での位置）。 */
    public record Parsed(String text, List<VerticalLayout.RubyRun> rubies) {
    }

    /** 記法入りの文章を、「本文」と「ルビの一覧」に分ける（ファイルを開くとき）。 */
    public static Parsed read(String notation) {
        StringBuilder text = new StringBuilder();
        List<VerticalLayout.RubyRun> rubies = new ArrayList<>();
        int from = 0;
        for (Span span : parse(notation)) {
            text.append(notation, from, span.start());
            int baseStart = text.length();
            text.append(notation, span.baseStart(), span.baseEnd());
            rubies.add(new VerticalLayout.RubyRun(baseStart, text.length(),
                    notation.substring(span.rubyStart(), span.rubyEnd())));
            from = span.end();
        }
        text.append(notation.substring(from));
        return new Parsed(text.toString(), rubies);
    }

    /**
     * 「本文」と「ルビの一覧」から、記法入りの文章を作る（ファイルに保存するとき）。
     * from〜to の範囲だけを取り出す。範囲からはみ出したルビは、付けない。
     */
    public static String write(String text, List<VerticalLayout.RubyRun> rubies, int from, int to) {
        StringBuilder sb = new StringBuilder();
        int position = from;
        for (VerticalLayout.RubyRun run : rubies) {
            if (run.baseStart() < position || run.baseEnd() > to) {
                continue;
            }
            sb.append(text, position, run.baseStart());
            sb.append(format(text.substring(run.baseStart(), run.baseEnd()), run.ruby()));
            position = run.baseEnd();
        }
        return sb.append(text, position, to).toString();
    }

    /** ルビの記法を組み立てる。 */
    public static String format(String base, String ruby) {
        return "｜" + base + "《" + ruby + "》";
    }

    /** 1文字ずつ「・」のルビを振って、傍点にする。空白や改行には付けない。 */
    public static String emphasize(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            i += character.length();
            if (Character.isWhitespace(codePoint) || isBar(character.charAt(0)) || isOpen(character.charAt(0))) {
                sb.append(character);
            } else {
                sb.append(format(character, "・"));
            }
        }
        return sb.toString();
    }

    /** 記法を取り除いて、親文字だけにする。 */
    public static String strip(String text) {
        StringBuilder sb = new StringBuilder();
        int from = 0;
        for (Span span : parse(text)) {
            sb.append(text, from, span.start()).append(text, span.baseStart(), span.baseEnd());
            from = span.end();
        }
        return sb.append(text.substring(from)).toString();
    }

    public static boolean isBar(char c) {
        return c == '｜' || c == '|';
    }

    private static boolean isOpen(char c) {
        return c == '《' || c == '(' || c == '（';
    }

    /**
     * open にある開きかっこに対応する、閉じかっこの位置を返す。ルビとして使えなければ -1。
     * 《》の中身は何でもよいが、()（）の中身は、ひらがな・カタカナだけに限る
     * （ふつうの文章の「（注）」などを、ルビと間違えないため）。
     */
    private static int findClose(String s, int open) {
        char opener = s.charAt(open);
        char closer = opener == '《' ? '》' : opener == '(' ? ')' : '）';
        for (int k = open + 1; k < s.length() && k - (open + 1) <= MAX_LENGTH; k++) {
            char d = s.charAt(k);
            if (d == closer) {
                return k > open + 1 ? k : -1;       // 中身が空なら、ルビではない
            }
            if (d == '\n' || isOpen(d) || isBar(d)) {
                return -1;
            }
            if (opener != '《' && !isKana(d)) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isKana(char c) {
        return (c >= 0x3041 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF) || (c >= 0xFF66 && c <= 0xFF9F);
    }

    /** end の直前が漢字なら、その文字数（ふつうは 1、特殊な漢字は 2）を返す。漢字でなければ 0。 */
    private static int kanjiLengthBefore(String s, int end) {
        if (end <= 0) {
            return 0;
        }
        int codePoint = s.codePointBefore(end);
        boolean kanji = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                || codePoint == '々' || codePoint == '〇' || codePoint == '〆' || codePoint == 'ヶ';
        return kanji ? Character.charCount(codePoint) : 0;
    }
}
