package com.example.minieditor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 版の履歴。ファイルを保存するたびに、その内容を設定フォルダの history に1つ残す。
 * 「元に戻す」では戻れない「昨日の状態」にも戻れるようにするためのもの。
 *
 * ファイルごとに1つのフォルダ（場所の文字列から作った名前）を作り、版は「日時.拡張子」で並べる。
 * 古い版は、決めた数を超えたら消す。
 */
public final class VersionHistory {

    /** 残してある版1つ。 */
    public record Version(Path file, LocalDateTime time, long size) {
    }

    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path root;

    public VersionHistory() {
        this(McpSettings.directory().resolve("history"));
    }

    VersionHistory(Path root) {
        this.root = root;
    }

    /**
     * 版を1つ残す。直前の版と同じ内容なら、残さない（同じ版が並ばないように）。
     *
     * @param limit このファイルについて残す版の数（超えたぶんは古いほうから消す）
     */
    public void record(Path file, String content, int limit) {
        try {
            Path folder = folderOf(file);
            Files.createDirectories(folder);
            FileService.writeSafely(folder.resolve("path.txt"), file.toAbsolutePath().normalize().toString(), false);
            List<Version> versions = list(file);
            if (!versions.isEmpty() && Files.readString(versions.get(0).file(), StandardCharsets.UTF_8).equals(content)) {
                return;
            }
            String name = LocalDateTime.now().format(NAME) + extensionOf(file);
            FileService.writeSafely(folder.resolve(name), content, false);
            versions = list(file);
            for (int i = Math.max(1, limit); i < versions.size(); i++) {
                Files.deleteIfExists(versions.get(i).file());
            }
        } catch (IOException ex) {
            // 履歴を残せなくても、保存そのものは成功しているので、作業は止めない
        }
    }

    /** そのファイルの版を、新しい順に返す。 */
    public List<Version> list(Path file) {
        List<Version> versions = new ArrayList<>();
        Path folder = folderOf(file);
        if (!Files.isDirectory(folder)) {
            return versions;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.equals("path.txt") || name.length() < 19) {
                    continue;
                }
                try {
                    LocalDateTime time = LocalDateTime.parse(name.substring(0, 19), NAME);
                    versions.add(new Version(entry, time, Files.size(entry)));
                } catch (RuntimeException ex) {
                    // このクラスが作ったのではないファイルは、飛ばす
                }
            }
        } catch (IOException ex) {
            return versions;
        }
        versions.sort(Comparator.comparing(Version::time).reversed());
        return versions;
    }

    public String read(Version version) throws IOException {
        return Files.readString(version.file(), StandardCharsets.UTF_8);
    }

    private Path folderOf(Path file) {
        String key = file.toAbsolutePath().normalize().toString();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            String base = file.getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_");
            return root.resolve(base + "-" + HexFormat.of().formatHex(hash, 0, 6));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);    // SHA-256 は、どの Java にも必ずある
        }
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : ".txt";
    }
}
