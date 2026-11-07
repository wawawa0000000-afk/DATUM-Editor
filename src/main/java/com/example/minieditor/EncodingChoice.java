package com.example.minieditor;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ファイルの書き方（文字コード・BOM・改行）。開くときに覚えておき、保存するときに使う。
 * 「名前を付けて保存」の画面で選ぶ選択肢も、ここにまとめる。
 */
public record EncodingChoice(Charset charset, boolean bom, boolean crlf) {

    /** Shift_JIS（Windows の書き方。windows-31j で読み書きする）。 */
    public static final Charset SHIFT_JIS_CHARSET = Charset.forName("windows-31j");

    /** 新しい文書の既定：UTF-8・BOM なし・LF。 */
    public static final EncodingChoice UTF8 = new EncodingChoice(StandardCharsets.UTF_8, false, false);

    public static final EncodingChoice UTF8_BOM = new EncodingChoice(StandardCharsets.UTF_8, true, false);

    public static final EncodingChoice SHIFT_JIS = new EncodingChoice(SHIFT_JIS_CHARSET, false, false);

    /** 「書き方」の画面に出す文字コードの選択肢（並びは画面の並び）。 */
    public static final List<EncodingChoice> ENCODINGS = List.of(UTF8, UTF8_BOM, SHIFT_JIS);

    /** 「書き方」の画面に出す改行の選択肢（並びは画面の並び。0 が LF、1 が CRLF）。 */
    public static final List<String> NEWLINES = List.of("LF（\\n）", "CRLF（\\r\\n）");

    /** この書き方を、画面の文字コードの選択肢の並びで表した番号。 */
    public int encodingIndex() {
        return ENCODINGS.indexOf(withCrlf(false));
    }

    /** 改行だけを変えた書き方。 */
    public EncodingChoice withCrlf(boolean crlf) {
        return new EncodingChoice(charset, bom, crlf);
    }
}
