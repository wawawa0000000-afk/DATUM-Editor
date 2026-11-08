package com.example.minieditor;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * ファイルの書き方（文字コード・BOM・改行）。開くときに覚えておき、保存するときに使う。
 * 利用者には選ばせない（名前を付けて保存でも、開いたときの書き方のまま書く）。ステータスバーに名前だけ出す。
 */
public record EncodingChoice(Charset charset, boolean bom, boolean crlf) {

    /** Shift_JIS（Windows の書き方。windows-31j で読み書きする）。 */
    public static final Charset SHIFT_JIS_CHARSET = Charset.forName("windows-31j");

    /** 新しい文書の既定：UTF-8・BOM なし・LF。 */
    public static final EncodingChoice UTF8 = new EncodingChoice(StandardCharsets.UTF_8, false, false);

    public static final EncodingChoice UTF8_BOM = new EncodingChoice(StandardCharsets.UTF_8, true, false);

    public static final EncodingChoice SHIFT_JIS = new EncodingChoice(SHIFT_JIS_CHARSET, false, false);

    /** ステータスバーに出す文字コードの名前（UTF-8・UTF-8 BOM・Shift_JIS）。改行は、気にしなくてよいので出さない。 */
    public String label() {
        return charset.equals(SHIFT_JIS_CHARSET) ? "Shift_JIS" : bom ? "UTF-8 BOM" : "UTF-8";
    }

    /** 改行だけを変えた書き方。 */
    public EncodingChoice withCrlf(boolean crlf) {
        return new EncodingChoice(charset, bom, crlf);
    }
}
