package com.example.minieditor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FileService {

    /** 最後に読み書きしたときのファイルの様子。ほかのアプリが書き換えたかを見分けるのに使う（AI の保存とも共有する）。 */
    private static final Map<Path, Stamp> stamps = new ConcurrentHashMap<>();

    /** 開いたファイルの書き方。保存するときに、同じ書き方に戻す（エディタの中では、改行は \n だけ、BOM なし）。 */
    private static final Map<Path, EncodingChoice> formats = new ConcurrentHashMap<>();

    private static final String BOM = "\uFEFF";

    private record Stamp(FileTime modified, long size) {
    }

    /** 書けない文字があったときの知らせ。ファイルには何も書いていない。 */
    public static class UnwritableException extends IOException {
        UnwritableException(String message) {
            super(message);
        }
    }

    /** UTF-8 にも Shift_JIS にも読めなかったときの知らせ（文章として開けない）。 */
    public static class UnreadableException extends IOException {
        UnreadableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** このファイルの書き方（開いたとき、または保存したときの書き方）。覚えていなければ、新しい文書の既定。 */
    public EncodingChoice formatOf(Path path) {
        return formats.getOrDefault(key(path), EncodingChoice.UTF8);
    }

    /** 覚えている書き方で保存する（上書き保存）。 */
    public void save(String text, Path path) throws IOException {
        save(text, path, formatOf(path));
    }

    /**
     * 指定した書き方で保存する。書けたら、そのファイルの書き方として覚える（次に上書き保存するとき、同じ書き方で書くため）。
     * 書けない文字があれば、何も書かずに失敗する。
     */
    public void save(String text, Path path, EncodingChoice choice) throws IOException {
        if (choice.crlf()) {
            text = text.replace("\r\n", "\n").replace("\n", "\r\n");      // CRLF で書くときは、改行を \r\n に戻す
        } else {
            text = text.replace("\r\n", "\n");
        }
        if (choice.bom() && !text.startsWith(BOM)) {
            text = BOM + text;
        }
        writeSafely(path, encode(text, choice.charset()), false);
        formats.put(key(path), choice);
        remember(path);
    }

    /**
     * ファイルを文章として読む。UTF-8 として読めればUTF-8、読めなければ Shift_JIS として読む（どちらでもなければ開かない）。
     * 改行は \n にそろえ、先頭の BOM は取り除く（どちらも、保存するときに元に戻す）。
     * 読めた書き方は覚えておき、上書き保存のときに同じ書き方で書く。
     */
    public String read(Path path) throws IOException {
        remember(path);                 // 読んでいる途中で変わっても「変わった」と気づけるよう、先に覚える
        Decoded decoded = decodeFile(path);
        formats.put(key(path), decoded.choice());
        return decoded.text();
    }

    /**
     * ファイルを文章として読むだけ（read と同じ判定。覚えない）。
     * 外で変わったかの記録も、書き方の記録も変えないので、保存の判定に影響させたくない読み取りに使う（履歴・検索・集計など）。
     */
    public String readText(Path path) throws IOException {
        return decodeFile(path).text();
    }

    /** 読んだ文章と、そのファイルの書き方。 */
    private record Decoded(String text, EncodingChoice choice) {
    }

    /** ファイルを読んで判定する。FileService の状態（覚えている記録）は見ないし、変えない。 */
    private static Decoded decodeFile(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String text;
        Charset charset = StandardCharsets.UTF_8;
        try {
            text = decode(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException utf8) {
            try {
                text = decode(bytes, EncodingChoice.SHIFT_JIS_CHARSET);
                charset = EncodingChoice.SHIFT_JIS_CHARSET;
            } catch (CharacterCodingException sjis) {
                throw new UnreadableException(unreadable(path), utf8);
            }
        }
        boolean bom = charset == StandardCharsets.UTF_8 && text.startsWith(BOM);
        if (bom) {
            text = text.substring(1);
        }
        // 改行が CRLF（Windows のメモ帳など）のファイルは、保存するときも CRLF で書く。混ざっているときは、多いほうに合わせる
        int crlf = count(text, "\r\n");
        boolean windows = crlf > 0 && crlf >= count(text, "\n") - crlf;
        text = text.replace("\r\n", "\n");
        return new Decoded(text, new EncodingChoice(charset, bom, windows));
    }

    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** UTF-8 にも Shift_JIS にも読めなかったときの知らせ（Java の「Input length = 1」では、何が起きたか分からないため）。 */
    private static String unreadable(Path path) {
        return "「" + path.getFileName() + "」は UTF-8 の文章として読めませんでした"
                + "（別の文字コードの文章か、文章ではないファイルです）。"
                + "メモ帳などで開いて、文字コードを「UTF-8」にして保存し直すと、開けるようになります。";
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    /** このアプリが最後に読み書きしたあとで、ほかのアプリがファイルを書き換えたか。 */
    public boolean changedOutside(Path path) {
        Stamp known = stamps.get(key(path));
        return known != null && Files.exists(path) && !known.equals(stampOf(path));
    }

    /** このアプリが、まだ一度も読み書きしていないファイルか。 */
    public boolean unknown(Path path) {
        return !stamps.containsKey(key(path));
    }

    private static void remember(Path path) {
        Stamp stamp = stampOf(path);
        if (stamp != null) {
            stamps.put(key(path), stamp);
        }
    }

    private static Stamp stampOf(Path path) {
        try {
            return new Stamp(Files.getLastModifiedTime(path), Files.size(path));
        } catch (IOException ex) {
            return null;
        }
    }

    private static Path key(Path path) {
        return path.toAbsolutePath().normalize();
    }

    /**
     * 途中で止まっても元のファイルが壊れないように書く。
     * 同じフォルダに別の名前で書いて、ディスクに書き終わったのを確かめてから、元のファイルと入れ替える。
     * 停電や強制終了が起きても、残るのは「前の中身」か「新しい中身」のどちらかになる。
     *
     * @param secret 合言葉や API キーが入ったファイル（本人だけが読めるようにする）
     */
    /**
     * 入れ替えに失敗したときの知らせが、一時ファイル（.名前.数字.tmp）を指していたら、保存先のファイルを指すように作り直す。
     * 利用者には一時ファイルは見えないので、見覚えのない名前を出さないため（読み取り専用のファイルに保存したときなど）。
     */
    private static IOException aboutTarget(java.nio.file.FileSystemException ex, Path target) {
        String name = ex.getFile() == null ? "" : Path.of(ex.getFile()).getFileName().toString();
        if (!(name.startsWith("." + target.getFileName() + ".") && name.endsWith(".tmp"))) {
            return ex;                  // 一時ファイルの話でなければ、そのまま
        }
        java.nio.file.FileSystemException fixed = ex instanceof java.nio.file.AccessDeniedException
                ? new java.nio.file.AccessDeniedException(target.toString(), null, ex.getReason())
                : new java.nio.file.FileSystemException(target.toString(), null, ex.getReason());
        fixed.initCause(ex);
        return fixed;
    }

    public static void writeSafely(Path path, String text, boolean secret) throws IOException {
        writeSafely(path, encode(text, StandardCharsets.UTF_8), secret);
    }

    /** 文章を指定した文字コードのバイト列にする。書けない文字があれば、何も書かないうちに失敗させる（? に置き換えて保存してしまわないように）。 */
    private static ByteBuffer encode(String text, Charset charset) throws UnwritableException {
        try {
            return charset.newEncoder().encode(CharBuffer.wrap(text));
        } catch (CharacterCodingException ex) {
            throw new UnwritableException(unwritable(text, charset));
        }
    }

    /** 書けない文字の知らせ。最初の5文字までを、コードポイントと一緒に出す。 */
    private static String unwritable(String text, Charset charset) {
        CharsetEncoder encoder = charset.newEncoder();
        List<String> shown = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            String chars = text.substring(i, i + n);
            if (!encoder.canEncode(chars)) {
                if (shown.size() < 5) {
                    boolean lone = n == 1 && Character.isSurrogate((char) cp);
                    shown.add((lone ? "" : chars) + String.format("（U+%04X）", cp));
                }
                total++;
            }
            i += n;
        }
        String name = charset == StandardCharsets.UTF_8 ? "UTF-8" : "Shift_JIS";
        String more = total > shown.size() ? "、ほか " + (total - shown.size()) + " 文字" : "";
        return name + " で書けない文字が入っているため、保存していません（" + String.join("、", shown) + more
                + "）。ファイルは変わっていません。";
    }

    private static void writeSafely(Path path, ByteBuffer bytes, boolean secret) throws IOException {
        // ファイルの別名（シンボリックリンク）なら、別名そのものではなく、指している先を書き換える
        Path target = (Files.isSymbolicLink(path) ? path.toRealPath() : path).toAbsolutePath();
        Path temp;
        try {
            temp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
        } catch (java.nio.file.FileSystemException ex) {
            throw aboutTarget(ex, target);      // フォルダに書けない（Linux の読み取り専用のフォルダなど）
        }
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            try {
                // 一時ファイルは本人だけが読める権限で作られるので、ふつうのファイルは元の権限にそろえる
                if (!secret) {
                    Files.setPosixFilePermissions(temp, Files.exists(target)
                            ? Files.getPosixFilePermissions(target) : PosixFilePermissions.fromString("rw-r--r--"));
                }
            } catch (UnsupportedOperationException ex) {
                // Windows には、この形の権限がない
            }
            try {
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (java.nio.file.FileSystemException ex) {
                throw aboutTarget(ex, target);  // 入れ替えられない（Windows の読み取り専用のファイルなど）
            }
        } finally {
            Files.deleteIfExists(temp);  // うまく入れ替えられなかったときだけ残っている
        }
    }
}
