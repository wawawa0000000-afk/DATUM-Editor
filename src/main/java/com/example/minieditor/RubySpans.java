package com.example.minieditor;

import java.util.ArrayList;
import java.util.List;

/**
 * 横書きの画面で、ルビの記法の場所を探す（画面には依存しない）。
 *
 * 読み方は NarouRuby.parse と同じ（｜親文字《ルビ》、漢字《ルビ》、(ルビ)、（ルビ））。
 * ここでは、画面に描くための位置（親文字の範囲・記法全体の範囲）に直すだけで、文書の中身は変えない。
 */
public final class RubySpans {

    /**
     * ルビ1つぶんの範囲（どれも「何文字目から何文字目の手前まで」）。
     * baseStart〜baseEnd が親文字、reading がふりがな、markupStart〜markupEnd が記法全体（｜から》まで）。
     */
    public record Span(int baseStart, int baseEnd, String reading, int markupStart, int markupEnd) {
    }

    private RubySpans() {
    }

    /** 文章の中のルビの記法をすべて探す。先頭から順に並んだリストを返す。改行をまたぐものは含めない。 */
    public static List<Span> find(String text) {
        List<Span> spans = new ArrayList<>();
        for (NarouRuby.Span span : NarouRuby.parse(text)) {
            if (text.substring(span.start(), span.end()).indexOf('\n') >= 0) {
                continue;               // 改行をまたぐルビは扱わない（今の読み取りでも、通常は起きない）
            }
            spans.add(new Span(span.baseStart(), span.baseEnd(),
                    text.substring(span.rubyStart(), span.rubyEnd()), span.start(), span.end()));
        }
        return spans;
    }
}
