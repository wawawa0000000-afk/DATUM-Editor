package com.example.minieditor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 検索・置換の一致を探す処理（画面には触らない）。
 *
 * 通常の検索も正規表現も、java.util.regex で同じように探す。
 * 本文を toLowerCase などで変えると位置がずれるので、本文はそのまま使う。
 */
final class FindMatcher {

    /** 一致の始まり・終わり（本文の位置）と、置換後の文字列（正規表現のときは $1 などを展開したもの）。 */
    record Hit(int start, int end, String replacement) {
    }

    /** error が null でなければ、一致は空。error は画面に出す文。 */
    record Result(List<Hit> hits, String error) {
    }

    /** 3秒を超えたら、探すのを止める（壊れやすい正規表現で、画面が固まらないように）。 */
    private static final long LIMIT_NANOS = 3_000_000_000L;

    /** ルビの記号。これをまたぐ置換は、ルビを壊すので行わない。 */
    private static final String RUBY_MARKS = "｜《》";

    private FindMatcher() {
    }

    /** text の from〜to の中で keyword の一致をすべて集める。 */
    static Result find(String text, String keyword, String replacement, boolean regex, boolean caseSensitive, int from, int to) {
        if (keyword.isEmpty() || from >= to) {
            return new Result(List.of(), null);
        }
        Pattern pattern;
        try {
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            pattern = Pattern.compile(regex ? keyword : Pattern.quote(keyword), flags);
        } catch (PatternSyntaxException e) {
            return new Result(List.of(), "正規表現が正しくありません");
        }
        String template = regex ? replacement : Matcher.quoteReplacement(replacement);

        Matcher matcher = pattern.matcher(new Timed(text, System.nanoTime() + LIMIT_NANOS));
        matcher.useAnchoringBounds(false);      // 範囲の始まりを、行頭・文頭とみなさない
        matcher.useTransparentBounds(true);     // 範囲の外の文字を、先読み・後読みで見られるようにする
        matcher.region(from, to);

        List<Hit> hits = new ArrayList<>();
        // appendReplacement の作業用。本文の先頭から写していくので、位置は本文と同じ
        StringBuilder out = new StringBuilder();
        int copied = 0;                         // appendReplacement が写し終えた位置（前の一致の終わり）
        try {
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                if (start == end) {
                    continue;                   // 長さ 0 の一致（x* など）は数えない
                }
                int before = out.length();
                try {
                    matcher.appendReplacement(out, template);
                } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                    return new Result(List.of(), "置換文字列が正しくありません");
                }
                // 追加された部分は、写し終えた所から今の一致の始まりまでの文字（本文のまま）と、置換後の文字列
                hits.add(new Hit(start, end, out.substring(before + (start - copied))));
                copied = end;
            }
        } catch (TooSlow e) {
            return new Result(List.of(), "時間がかかりすぎたので、止めました");
        }
        return new Result(hits, null);
    }

    /** 一致の文字列か置換後の文字列に、ルビの記号（｜《》）が含まれるか。 */
    static boolean touchesRuby(String span, String replacement) {
        return containsRubyMark(span) || containsRubyMark(replacement);
    }

    private static boolean containsRubyMark(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (RUBY_MARKS.indexOf(s.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** 時間切れのときに、探すのを止めるための印。 */
    private static final class TooSlow extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** 本文を包んで、読むたびに時間を確かめる。毎回は確かめない（時刻を読むのも手間なので）。 */
    private static final class Timed implements CharSequence {
        private final CharSequence text;
        private final long deadline;
        private int reads;

        Timed(CharSequence text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFFF) == 0 && System.nanoTime() > deadline) {
                throw new TooSlow();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Timed(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
