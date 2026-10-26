package com.example.minieditor;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Windows の配布版で、ターミナルに「datum」と打って起動できるようにする（ユーザーの PATH に、配布版のフォルダを足す）。
 *
 * 足す作業そのものは、配布 ZIP に入っている command-path.ps1（Add-Command.bat と同じもの）に任せる。
 * 最初の起動で使う人に聞いてから行い、足したフォルダを設定（commandDir）に覚えておく。
 * 同じフォルダなら二度と足さないので、Remove-Command.bat で消したあとに、勝手に足し直すことはない。
 * フォルダを移したときだけ、次の起動で足し直す。
 * そのとき、前に足したフォルダがもう無ければ、その古い項目を PATH から外す（それ以外では、PATH の項目を外さない）。
 */
final class CommandRegistration {

    private CommandRegistration() {
    }

    /**
     * 登録できる配布版のフォルダ。Windows で、起動している jar の隣に datum.cmd と command-path.ps1 があるときだけ返す。
     * IDE や target/classes からの起動（jar でない）や、リポジトリの開発用ビルドでは null（開発用のフォルダを PATH に足さないため）。
     */
    static Path packageDir() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return null;
        }
        try {
            Path code = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(code) || !code.getFileName().toString().endsWith(".jar")) {
                return null;
            }
            Path dir = code.toAbsolutePath().getParent();
            if (Files.isRegularFile(dir.resolve("datum.cmd")) && Files.isRegularFile(dir.resolve("command-path.ps1"))) {
                return dir;
            }
        } catch (Exception ex) {
            // 場所がわからなければ、登録しない
        }
        return null;
    }

    /**
     * dir を、ユーザーの PATH に足す。うまくいったら true。時間がかかることがあるので、画面のスレッドからは呼ばない。
     *
     * @param previous 前に足したフォルダ（設定の commandDir）。dir と違い、しかももう無いときだけ、PATH から外す。
     *                 移したり消したりした古い配布版のフォルダが、PATH に残り続けないように
     */
    static boolean register(Path dir, String previous) {
        if (isStale(dir, previous)) {
            // 古いフォルダの command-path.ps1 は、フォルダごと無くなっているので、今のフォルダのものを使う。
            // 外せなくても、登録は続ける（古い項目が残るだけで、datum は今のフォルダから動く）
            run(dir, "-Dir", previous, "-Remove");
        }
        return run(dir, "-Dir", dir.toString());
    }

    /**
     * previous が、外してよい古い項目か：今のフォルダと違い、かつ、そのフォルダがもう存在しない。
     * USB メモリや Google ドライブなど、ドライブそのものが今つながっていないだけのときは、外さない（差し直せば、また使える）。
     * 有るか無いか確かめられないとき（権限がないなど）も、外さない。
     */
    static boolean isStale(Path dir, String previous) {
        if (previous == null || previous.isBlank()) {
            return false;
        }
        try {
            Path old = Path.of(previous);
            if (!old.isAbsolute() || old.normalize().equals(dir.toAbsolutePath().normalize())) {
                return false;
            }
            return Files.isDirectory(old.getRoot()) && Files.notExists(old);
        } catch (InvalidPathException ex) {
            return false;               // 読めない値は、PATH のどの項目か分からないので触らない
        }
    }

    /** dir にある command-path.ps1 を、args を付けて動かす。うまくいったら true。 */
    private static boolean run(Path dir, String... args) {
        List<String> command = new ArrayList<>(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", dir.resolve("command-path.ps1").toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ex) {
            return false;               // 失敗しても、エディタは止めない（次の起動で、もう一度試す）
        }
    }
}
