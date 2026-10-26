package com.example.minieditor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FileService {

    /** 最後に読み書きしたときのファイルの様子。ほかのアプリが書き換えたかを見分けるのに使う（AI の保存とも共有する）。 */
    private static final Map<Path, Stamp> stamps = new ConcurrentHashMap<>();

    /** 開いたファイルの書き方。保存するときに、同じ書き方に戻す（エディタの中では、改行は \n だけ、BOM なし）。 */
    private static final Map<Path, Format> formats = new ConcurrentHashMap<>();

    private static final String BOM = "\uFEFF";

    private record Stamp(FileTime modified, long size) {
    }

    private record Format(boolean bom, boolean crlf) {
    }

    public void save(String text,Path path) throws IOException {
        Format format = formats.get(key(path));
        if (format != null) {
            if (format.crlf()) {
                text = text.replace("\r\n", "\n").replace("\n", "\r\n");      // 開いたときの CRLF に戻す
            }
            if (format.bom() && !text.startsWith(BOM)) {
                text = BOM + text;
            }
        }
        writeSafely(path, text, false);
        remember(path);
    }

    /**
     * ファイルを UTF-8 の文章として読む。改行は \n にそろえ、先頭の BOM は取り除く（どちらも、保存するときに元に戻す）。
     * UTF-8 として読めないときは、Shift_JIS らしいかどうかも添えて、分かる言葉で知らせる。
     */
    public String read(Path path) throws IOException {
        remember(path);                 // 読んでいる途中で変わっても「変わった」と気づけるよう、先に覚える
        byte[] bytes = Files.readAllBytes(path);
        String text;
        try {
            text = decode(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ex) {
            throw new IOException(notUtf8(path, bytes), ex);
        }
        boolean bom = text.startsWith(BOM);
        if (bom) {
            text = text.substring(1);
        }
        // 改行が CRLF（Windows のメモ帳など）のファイルは、保存するときも CRLF で書く。混ざっているときは、多いほうに合わせる
        int crlf = count(text, "\r\n");
        boolean windows = crlf > 0 && crlf >= count(text, "\n") - crlf;
        text = text.replace("\r\n", "\n");
        formats.put(key(path), new Format(bom, windows));
        return text;
    }

    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** UTF-8 で読めなかったときの知らせ（Java の「Input length = 1」では、何が起きたか分からないため）。 */
    private static String notUtf8(Path path, byte[] bytes) {
        String name = "「" + path.getFileName() + "」";
        String fix = "メモ帳などで開いて、文字コードを「UTF-8」にして保存し直すと、開けるようになります。";
        try {
            decode(bytes, Charset.forName("windows-31j"));
            return name + "は Shift_JIS（古い Windows のメモ帳などの形式）で書かれているようです。"
                    + "このエディタは UTF-8 のファイルだけを開けます。" + fix;
        } catch (CharacterCodingException | IllegalArgumentException ex) {
            return name + "は UTF-8 の文章として読めませんでした（別の文字コードの文章か、文章ではないファイルです）。" + fix;
        }
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
    public static void writeSafely(Path path, String text, boolean secret) throws IOException {
        // 書けない文字があれば、何も書かないうちに失敗させる（? に置き換えて保存してしまわないように）
        ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
        // ファイルの別名（シンボリックリンク）なら、別名そのものではなく、指している先を書き換える
        Path target = (Files.isSymbolicLink(path) ? path.toRealPath() : path).toAbsolutePath();
        Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
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
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);  // うまく入れ替えられなかったときだけ残っている
        }
    }
}
