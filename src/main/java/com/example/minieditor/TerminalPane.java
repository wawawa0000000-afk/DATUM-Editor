package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;

/**
 * エディタの下に出す、かんたんなターミナル。
 *
 * OS のシェル（Linux なら bash、Windows なら cmd）を裏で動かし、
 * 下の入力欄に書いた1行をそのまま渡して、返ってきた文字を上の欄に表示する。
 *
 * 本物のターミナルとの違い：1行ずつ送る方式なので、画面全体を書き換えるプログラム
 * （vim、top、対話式の AI の画面など）は表示できない。ls、git、mvn のように、
 * 文字を順に出すだけのコマンド向け。
 */
public final class TerminalPane extends JPanel {

    private static final int MAX_CHARS = 200_000;       // これより古い出力は、上から捨てる
    private static final int MAX_PENDING = 500_000;     // 表示が追いつかないときに、ためておく上限
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private final Supplier<Path> startFolder;           // シェルを始めるフォルダを決める
    private final JTextArea output = new JTextArea();
    private final JTextField input = new JTextField();

    private final List<String> history = new ArrayList<>();     // 入力した行の履歴（↑↓で呼び出す）
    private int historyIndex;
    private final List<String> waiting = new ArrayList<>();     // シェルの準備中に入力された行

    private Process process;            // 動いているシェル。なければ null
    private Writer toShell;
    private boolean starting;
    private boolean hasTty;             // 疑似端末つきで動かせたか（Ctrl+C を送れる・入力が自動で表示される）

    // シェルからの出力は別スレッドで読むので、いったんここにためて、画面のスレッドでまとめて表示する
    private final StringBuilder pending = new StringBuilder();
    private boolean flushQueued;

    // 色や、カーソル移動の指示（エスケープシーケンス）を読み飛ばすための状態
    private int escape;                 // 0: ふつう、1: ESC の直後、2: CSI の途中、3: OSC の途中、4: OSC の終わり、5: あと1文字
    private boolean pendingReturn;      // 行頭に戻る指示（\r）を受けた直後

    public TerminalPane(Supplier<Path> startFolder, Runnable onClose) {
        super(new BorderLayout());
        this.startFolder = startFolder;
        setBackground(Theme.BG);
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));

        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, Theme.SMALL_SIZE);

        // 上の帯：名前と、操作のボタン
        JButton stop = Theme.flatButton("中断");
        stop.setToolTipText("動いているコマンドを止める（Ctrl+C）");
        stop.addActionListener(e -> interrupt());
        JButton clear = Theme.flatButton("消去");
        clear.setToolTipText("表示を消す（Ctrl+L）");
        clear.addActionListener(e -> clear());
        JButton close = PanelHeader.closeButton("ターミナルを隠す（中のコマンドは動いたまま）", onClose);
        for (JButton button : new JButton[]{stop, clear, close}) {
            button.setFocusable(false);         // 押しても、入力欄からカーソルが離れないようにする
        }
        PanelHeader header = new PanelHeader("ターミナル").with(stop, clear, close);

        // 中央：シェルからの出力
        output.setEditable(false);
        output.setLineWrap(true);
        output.setFont(mono);
        output.setBackground(Theme.BG);
        output.setForeground(Theme.FG);
        output.setCaretColor(Theme.BG);
        output.setSelectionColor(Theme.SELECTION);
        output.setSelectedTextColor(Theme.FG_STRONG);
        output.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        JScrollPane scroll = new JScrollPane(output);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.BG);

        // 下：コマンドを書く欄
        JLabel prompt = new JLabel("❯ ");
        prompt.setFont(mono);
        prompt.setForeground(Theme.ACCENT);
        Theme.styleField(input);
        input.setFont(mono);
        input.addActionListener(e -> submit());
        bind(input, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), () -> recall(-1));
        bind(input, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), () -> recall(1));
        bind(input, KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK), this::clear);
        // Ctrl+C：文字を選んでいればコピー、そうでなければコマンドの中断（本物のターミナルに合わせる）
        bind(input, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), () -> {
            if (input.getSelectedText() != null) {
                input.copy();
            } else {
                interrupt();
            }
        });
        JPanel inputRow = new JPanel(new BorderLayout());
        inputRow.setBackground(Theme.PANEL);
        inputRow.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        inputRow.add(prompt, BorderLayout.WEST);
        inputRow.add(input, BorderLayout.CENTER);

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(inputRow, BorderLayout.SOUTH);

        // エディタを閉じるときに、裏のシェルを残さない
        Runtime.getRuntime().addShutdownHook(new Thread(this::killShell, "terminal-cleanup"));
    }

    private static void bind(JComponent component, KeyStroke key, Runnable action) {
        Keys.bind(component, key, "terminal-" + key, action);
    }

    /** 入力欄にカーソルを置く。 */
    public void focusInput() {
        input.requestFocusInWindow();
    }

    // ---------- シェルを動かす ----------

    /** シェルがまだ動いていなければ、始める。 */
    public void start() {
        if (process != null || starting) {
            return;
        }
        starting = true;
        Path folder = startFolder.get();
        // PATH を調べるのに少し時間がかかることがあるので、画面を止めないように別スレッドで始める
        Thread thread = new Thread(() -> launch(folder), "terminal-start");
        thread.setDaemon(true);
        thread.start();
    }

    private void launch(Path folder) {
        ProcessBuilder builder;
        boolean tty = false;
        if (WINDOWS) {
            // 日本語が文字化けしないように、文字コードを UTF-8 に切り替えてから使う
            builder = new ProcessBuilder("cmd.exe", "/K", "chcp 65001>nul");
        } else {
            boolean bash = Files.isExecutable(Path.of("/bin/bash"));
            String shell = bash ? "/bin/bash --noprofile --norc --noediting -i" : "/bin/sh -i";
            String inner = "stty cols 100 rows 40 2>/dev/null; exec " + shell;
            // script コマンドを通すと、シェルに「疑似端末」が付く。これがあると、プロンプトが出て、Ctrl+C も効く
            if (Files.isExecutable(Path.of("/usr/bin/script"))) {
                builder = MAC
                        ? new ProcessBuilder("/usr/bin/script", "-q", "/dev/null", "/bin/sh", "-c", inner)
                        : new ProcessBuilder("/usr/bin/script", "-qfec", inner, "/dev/null");
                tty = true;
            } else {
                builder = new ProcessBuilder(bash ? "/bin/bash" : "/bin/sh");
            }
            builder.environment().put("TERM", "dumb");          // 色や画面の書き換えを使わないように伝える
            builder.environment().put("PS1", "\\w \\$ ");       // プロンプトは「今いるフォルダ $」
            builder.environment().put("PAGER", "cat");          // 1画面ごとに止まる表示（less）を使わせない
            builder.environment().put("GIT_PAGER", "cat");
            String path = loginPath();
            if (path != null) {
                builder.environment().put("PATH", path);
            }
        }
        if (folder != null && Files.isDirectory(folder)) {
            builder.directory(folder.toFile());
        }
        builder.redirectErrorStream(true);

        Process started;
        try {
            started = builder.start();
        } catch (IOException ex) {
            SwingUtilities.invokeLater(() -> {
                starting = false;
                waiting.clear();
                show("［ターミナルを始められませんでした: " + ex.getMessage() + "］\n");
            });
            return;
        }
        boolean withTty = tty;
        SwingUtilities.invokeLater(() -> {
            process = started;
            toShell = new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8);
            hasTty = withTty;
            starting = false;
            List<String> lines = new ArrayList<>(waiting);
            waiting.clear();
            lines.forEach(this::send);
        });
        pump(started);
    }

    /**
     * ふだんのターミナルと同じ PATH を調べる。
     * エディタをアイコンから起動すると、シェルの設定ファイルで足した場所（Java や AI のコマンドなど）が
     * PATH に入っていないことがあるので、利用者のシェルに一度だけ尋ねる。分からなければ null。
     */
    static String loginPath() {
        String shell = System.getenv("SHELL");
        if (shell == null || !Files.isExecutable(Path.of(shell))) {
            return null;
        }
        String marker = "__DATUM_PATH__";
        try {
            Process ask = new ProcessBuilder(shell, "-ilc", "printf '\\n" + marker + "%s\\n' \"$PATH\"")
                    .redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .start();
            if (!ask.waitFor(5, TimeUnit.SECONDS)) {
                ask.destroyForcibly();
                return null;
            }
            InputStream in = ask.getInputStream();
            String text = new String(in.readNBytes(in.available()), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.startsWith(marker) && line.length() > marker.length()) {
                    return line.substring(marker.length()).strip();
                }
            }
        } catch (IOException ex) {
            // 分からなければ、エディタと同じ PATH のまま使う
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** シェルの出力を、終わるまで読み続ける（別スレッドで動く）。 */
    private void pump(Process shell) {
        try (Reader reader = new InputStreamReader(shell.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                synchronized (pending) {
                    pending.append(buffer, 0, count);
                    if (pending.length() > MAX_PENDING) {
                        pending.delete(0, pending.length() - MAX_PENDING);
                    }
                    if (!flushQueued) {
                        flushQueued = true;
                        SwingUtilities.invokeLater(this::flushPending);
                    }
                }
            }
        } catch (IOException ex) {
            // シェルが終わると、読めなくなる。下で「終了」として扱う
        }
        SwingUtilities.invokeLater(() -> {
            if (process == shell) {
                process = null;
                toShell = null;
                show("\n［シェルが終了しました。何か入力すると、もう一度始めます］\n");
            }
        });
    }

    private void flushPending() {
        String text;
        synchronized (pending) {
            text = pending.toString();
            pending.setLength(0);
            flushQueued = false;
        }
        receive(text);
    }

    private void killShell() {
        Process shell = process;
        if (shell != null) {
            shell.descendants().forEach(ProcessHandle::destroyForcibly);
            shell.destroyForcibly();
        }
    }

    // ---------- 入力 ----------

    private void submit() {
        String line = input.getText();
        input.setText("");
        if (!line.isBlank() && (history.isEmpty() || !history.get(history.size() - 1).equals(line))) {
            history.add(line);
        }
        historyIndex = history.size();
        // 画面を消すコマンドは、シェルに任せても効かない（TERM=dumb のため）ので、ここで消す
        if (line.strip().equals("clear") || line.strip().equals("cls")) {
            clear();
            line = "";
        }
        send(line);
    }

    private void send(String line) {
        if (process == null) {
            waiting.add(line);
            start();
            return;
        }
        try {
            if (!hasTty && !WINDOWS) {
                show("$ " + line + "\n");       // 疑似端末がないと、入力した行が表示されないので、自分で出す
            }
            toShell.write(line);
            toShell.write(WINDOWS ? "\r\n" : "\n");
            toShell.flush();
        } catch (IOException ex) {
            show("［シェルに送れませんでした: " + ex.getMessage() + "］\n");
        }
    }

    /** ↑↓で、前に入力した行を呼び出す。 */
    private void recall(int step) {
        if (history.isEmpty()) {
            return;
        }
        historyIndex = Math.max(0, Math.min(history.size(), historyIndex + step));
        input.setText(historyIndex == history.size() ? "" : history.get(historyIndex));
    }

    /** 動いているコマンドを止める。 */
    private void interrupt() {
        if (process == null) {
            return;
        }
        if (hasTty) {
            try {
                toShell.write(3);               // Ctrl+C と同じ文字。疑似端末が、動いているコマンドに「中断」を伝える
                toShell.flush();
            } catch (IOException ex) {
                // シェルが終わっていたら、何もしない
            }
        } else {
            // 疑似端末がないときは、シェルから起動されたコマンドを直接止める
            process.descendants().forEach(ProcessHandle::destroy);
        }
    }

    private void clear() {
        output.setText("");
        pendingReturn = false;
    }

    // ---------- 表示 ----------

    /** エディタからのお知らせを、そのまま表示する。 */
    private void show(String text) {
        output.append(text);
        output.setCaretPosition(output.getDocument().getLength());
    }

    /**
     * シェルから届いた文字を表示する。
     * 色やカーソル移動の指示（ESC で始まる並び）は読み飛ばし、「行頭に戻る」（\r）は、その行の書き直しとして扱う。
     */
    private void receive(String text) {
        StringBuilder add = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape != 0) {
                switch (escape) {
                    case 1 -> escape = (c == '[') ? 2 : (c == ']') ? 3 : (c == '(' || c == ')' || c == '#') ? 5 : 0;
                    case 2 -> escape = (c >= 0x40 && c <= 0x7E) ? 0 : 2;    // CSI は、英字などの「終わりの文字」まで
                    case 3 -> escape = (c == 7) ? 0 : (c == 27) ? 4 : 3;    // OSC は、BEL か「ESC \」まで
                    default -> escape = 0;
                }
                continue;
            }
            if (c == 27) {
                escape = 1;
            } else if (c == '\r') {
                pendingReturn = true;
            } else if (c == '\n') {
                pendingReturn = false;
                add.append('\n');
            } else if (c == '\b') {
                append(add);
                eraseLastChar();
            } else if (c >= 0x20 || c == '\t') {
                if (pendingReturn) {            // \r のあとに文字が来た＝同じ行を書き直している（進み具合の表示など）
                    append(add);
                    eraseLastLine();
                    pendingReturn = false;
                }
                add.append(c);
            }
        }
        append(add);
        Document document = output.getDocument();
        try {
            if (document.getLength() > MAX_CHARS) {
                document.remove(0, document.getLength() - MAX_CHARS);
            }
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
        output.setCaretPosition(document.getLength());
    }

    private void append(StringBuilder text) {
        if (text.length() > 0) {
            output.append(text.toString());
            text.setLength(0);
        }
    }

    private void eraseLastChar() {
        Document document = output.getDocument();
        try {
            int length = document.getLength();
            if (length > 0 && !document.getText(length - 1, 1).equals("\n")) {
                document.remove(length - 1, 1);
            }
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void eraseLastLine() {
        Document document = output.getDocument();
        try {
            String all = document.getText(0, document.getLength());
            int lineStart = all.lastIndexOf('\n') + 1;
            document.remove(lineStart, all.length() - lineStart);
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** テスト用：今、表示している文字。 */
    String outputText() {
        return output.getText();
    }

    /** テスト用：入力欄に書いて Enter を押したのと同じことをする。 */
    void type(String line) {
        input.setText(line);
        submit();
    }

    /** テスト用：「中断」ボタンと同じことをする。 */
    void pressInterrupt() {
        interrupt();
    }
}
