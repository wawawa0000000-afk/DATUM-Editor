package com.example.minieditor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本文の中の見出し（「第一話　はじまり」「第12話」「第三章」など）を、行の形で探す。
 * 本文には記号を足さず、行の先頭の形だけで判定する。画面には依存しないので、テストから直接動かせる。
 */
final class OutlineScanner {

    /**
     * 見出し1つ。line は 1 から数える。start・end は本文での、その行の範囲（改行を含まない）。
     * title は行の文字（前後の空白を除く。長いときは 60 文字に切って「…」を付ける）。
     */
    record Heading(int line, int start, int end, String title) {
    }

    /** 一覧に出す見出しの長さ（文字）。これを超えると切る。 */
    private static final int MAX_TITLE = 60;

    /** 行の先頭の空白、「第」、数字（半角・全角・漢数字）1字以上、「話」か「章」。行の途中の「第」には当たらない。 */
    private static final Pattern HEADING = Pattern.compile("[ 　]*第[0-9０-９〇一二三四五六七八九十百千零]+[話章]");

    private OutlineScanner() {
    }

    /** 本文を行に分けて、見出しの行を順に返す。見出しがなければ空の一覧。 */
    static List<Heading> scan(String text) {
        List<Heading> headings = new ArrayList<>();
        int length = text.length();
        int line = 0;
        int start = 0;
        while (true) {
            int newline = text.indexOf('\n', start);
            int lineEnd = newline < 0 ? length : newline;
            line++;
            // 行末に \r が残っていれば取り除いて判定する（end は \r の手前のまま）
            int end = lineEnd;
            if (end > start && text.charAt(end - 1) == '\r') {
                end--;
            }
            String body = text.substring(start, end);
            Matcher matcher = HEADING.matcher(body);
            if (matcher.lookingAt()) {
                headings.add(new Heading(line, start, end, title(body)));
            }
            if (newline < 0) {
                break;
            }
            start = newline + 1;
        }
        return headings;
    }

    /** 行の文字から、前後の空白を除いて、長すぎるときは切る。 */
    private static String title(String body) {
        String title = body.strip();
        if (title.codePointCount(0, title.length()) > MAX_TITLE) {
            title = title.substring(0, title.offsetByCodePoints(0, MAX_TITLE)) + "…";
        }
        return title;
    }
}
