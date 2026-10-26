package com.example.minieditor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Windows の配布版で、ターミナルに「datum」と打って起動できるようにする（ユーザーの PATH に、配布版のフォルダを足す）。
 *
 * 足す作業そのものは、配布 ZIP に入っている command-path.ps1（Add-Command.bat と同じもの）に任せる。
 * 最初の起動で使う人に聞いてから行い、足したフォルダを設定（commandDir）に覚えておく。
 * 同じフォルダなら二度と足さないので、Remove-Command.bat で消したあとに、勝手に足し直すことはない。
 * フォルダを移したときだけ、次の起動で足し直す。
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

    /** dir を、ユーザーの PATH に足す。うまくいったら true。時間がかかることがあるので、画面のスレッドからは呼ばない。 */
    static boolean register(Path dir) {
        try {
            Process process = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", dir.resolve("command-path.ps1").toString(), "-Dir", dir.toString())
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
