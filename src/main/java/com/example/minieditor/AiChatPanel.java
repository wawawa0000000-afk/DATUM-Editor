package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * 画面の右に出す「AI」の会話欄。ここに書いた頼みごとを、設定で選んだ AI に送る。
 * AI は、エディタの道具（文書を読む・直す・探す・保存する など）を使って作業する。
 * 直した所は Ctrl+Z で戻せる（AI 連携と同じしくみ）。
 */
public final class AiChatPanel extends JPanel {

    /** 会話欄が、ウィンドウ側に頼むこと。 */
    public interface Host {
        AppSettings settings();

        /** Claude Code を使うときに、AI 連携（MCP）が動いているようにする。動かせなければ false。 */
        boolean ensureMcp();

        /** AI に任せるフォルダ（なければ null）。 */
        Path workspace();

        /** エディタの道具を、このプログラムの中で直接使う窓口。 */
        McpServer.Backend tools();

        void openSettings();
    }

    private final Host host;
    private final JTextPane transcript = new JTextPane();
    private final JTextArea input = new JTextArea(3, 20);
    private final JButton sendButton = Theme.flatButton("送信");
    private final JLabel backendLabel = new JLabel(" ");
    private AiBackends.Backend backend;
    private String backendKey;
    private volatile boolean busy;

    public AiChatPanel(Host host, Runnable onClose) {
        super(new BorderLayout());
        this.host = host;
        setBackground(Theme.PANEL);

        JLabel title = new JLabel("AI");
        title.setFont(Theme.small().deriveFont(java.awt.Font.BOLD));
        title.setForeground(Theme.FG);
        backendLabel.setFont(Theme.small());
        backendLabel.setForeground(Theme.FG_DIM);
        JButton fresh = Theme.flatButton("新しい会話");
        fresh.setToolTipText("これまでのやり取りを忘れて、最初から話す");
        fresh.addActionListener(e -> newConversation());
        JButton settingsButton = Theme.flatButton("設定");
        settingsButton.setToolTipText("どの AI を使うかを選ぶ");
        settingsButton.addActionListener(e -> host.openSettings());
        JButton close = Theme.flatButton("×");
        close.addActionListener(e -> onClose.run());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        for (JButton button : new JButton[]{fresh, settingsButton, close}) {
            button.setFocusable(false);
            buttons.add(button);
        }
        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.WEST);
        titleRow.add(backendLabel, BorderLayout.CENTER);
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.PANEL);
        header.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 6));
        header.add(titleRow, BorderLayout.CENTER);
        header.add(buttons, BorderLayout.EAST);

        transcript.setEditable(false);
        transcript.setBackground(Theme.BG);
        transcript.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane scroll = new JScrollPane(transcript);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.BORDER));
        scroll.getViewport().setBackground(Theme.BG);

        // よく使う頼みごとは、ボタン1つで送れるようにする
        JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        quick.setBackground(Theme.PANEL);
        quick.add(quickButton("誤字・脱字", "今開いている文書の誤字・脱字を探して、直す所を「どこを・どう直すか」の一覧で見せてください。"));
        quick.add(quickButton("表記ゆれ", "今開いている文書の表記ゆれ（同じ言葉の書き方の違い）を探して、一覧で見せてください。"));
        quick.add(quickButton("要約", "今開いている文書の内容を、3行で要約してください。"));

        input.setLineWrap(true);
        input.setFont(Theme.small());
        input.setBackground(Theme.INPUT);
        input.setForeground(Theme.FG_STRONG);
        input.setCaretColor(Theme.FG_STRONG);
        input.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        // Enter で送る。改行したいときは Shift+Enter
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");
        input.getActionMap().put("send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                submit();
            }
        });
        JScrollPane inputScroll = new JScrollPane(input);
        inputScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        sendButton.setFocusable(false);
        sendButton.addActionListener(e -> {
            if (busy) {
                stop();
            } else {
                submit();
            }
        });
        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.setBackground(Theme.PANEL);
        inputRow.setBorder(BorderFactory.createEmptyBorder(4, 10, 10, 10));
        inputRow.add(inputScroll, BorderLayout.CENTER);
        inputRow.add(sendButton, BorderLayout.EAST);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(quick, BorderLayout.NORTH);
        bottom.add(inputRow, BorderLayout.CENTER);

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(420, 400));

        append(Kind.INFO, "AI に頼みたいことを書いて Enter で送ります（改行は Shift+Enter）。\n"
                + "AI が直した所は、エディタで Ctrl+Z を押すと戻せます。\n"
                + "送った文章や、AI が読んだ文書は、選んだ AI のサービスに送られます。\n");
        refreshBackendLabel();
    }

    private JButton quickButton(String label, String prompt) {
        JButton button = Theme.flatButton(label);
        button.setFocusable(false);
        button.setToolTipText(prompt);
        button.addActionListener(e -> {
            if (!busy) {
                send(prompt);
            }
        });
        return button;
    }

    public void focusInput() {
        input.requestFocusInWindow();
    }

    /** 設定が変わったときに呼ぶ（表示を合わせ、次に送るときに、つなぎ先を作り直す）。 */
    public void refreshBackendLabel() {
        AppSettings s = host.settings();
        String kind = s.getString(AppSettings.AI_BACKEND, "claude-code");
        String model = s.getString(AppSettings.AI_MODEL, "");
        backendLabel.setText(switch (kind) {
            case "anthropic" -> "Anthropic API（" + (model.isEmpty() ? AiBackends.ANTHROPIC_MODEL : model) + "）";
            case "openai" -> "OpenAI 互換（" + (model.isEmpty() ? "モデル未設定" : model) + "）";
            default -> "Claude Code" + (model.isEmpty() ? "" : "（" + model + "）");
        });
    }

    private void submit() {
        String text = input.getText().strip();
        if (text.isEmpty() || busy) {
            return;
        }
        input.setText("");
        send(text);
    }

    private void newConversation() {
        if (busy) {
            stop();
        }
        if (backend != null) {
            backend.reset();
        }
        try {
            transcript.getStyledDocument().remove(0, transcript.getStyledDocument().getLength());
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
        append(Kind.INFO, "新しい会話を始めました。\n");
    }

    private void stop() {
        if (backend != null) {
            backend.stop();
        }
        append(Kind.INFO, "（止めています…）\n");
    }

    /** 頼みごとを AI に送る。返事は別のスレッドで待ち、届いたものから順に表示する。 */
    void send(String text) {
        AiBackends.Backend chosen = prepareBackend();
        if (chosen == null) {
            return;
        }
        append(Kind.USER_NAME, "あなた\n");
        append(Kind.USER, text + "\n");
        append(Kind.AI_NAME, "AI\n");
        setBusy(true);
        Thread worker = new Thread(() -> {
            try {
                chosen.send(text, new AiBackends.Listener() {
                    @Override
                    public void text(String t) {
                        later(Kind.AI, t.strip() + "\n");
                    }

                    @Override
                    public void tool(String name) {
                        later(Kind.TOOL, "・道具を使いました：" + name + "\n");
                    }

                    @Override
                    public void info(String t) {
                        later(Kind.INFO, t + "\n");
                    }

                    @Override
                    public void error(String t) {
                        later(Kind.ERROR, t + "\n");
                    }
                });
            } catch (Exception ex) {
                later(Kind.ERROR, "うまくいきませんでした：" + ex.getMessage() + "\n");
            } finally {
                SwingUtilities.invokeLater(() -> setBusy(false));
            }
        }, "ai-chat");
        worker.setDaemon(true);
        worker.start();
    }

    /** 設定に合わせて、つなぎ先を用意する。足りない設定があれば、案内を出して null。 */
    private AiBackends.Backend prepareBackend() {
        AppSettings s = host.settings();
        String kind = s.getString(AppSettings.AI_BACKEND, "claude-code");
        String model = s.getString(AppSettings.AI_MODEL, "");
        String key = s.getString(AppSettings.AI_KEY, "");
        String url = s.getString(AppSettings.AI_URL, AiBackends.OPENAI_URL);
        String command = s.getString(AppSettings.CLAUDE_COMMAND, "claude");
        boolean claudeCode = !kind.equals("anthropic") && !kind.equals("openai");
        if (claudeCode && !host.ensureMcp()) {
            // Claude Code は、AI 連携（MCP）を通して文書を扱うので、動いている必要がある
            append(Kind.ERROR, "Claude Code に文書を扱ってもらうには、AI 連携（MCP）を動かす必要があります。"
                    + "メニューの「AI 連携 → MCP サーバーを動かす」を入れてから、もう一度送ってください。\n");
            return null;
        }
        // Claude Code は「AI に任せるフォルダ」で起動するので、フォルダが変わったら作り直す
        String fingerprint = kind + "|" + model + "|" + url + "|" + command + "|" + key.hashCode()
                + "|" + (claudeCode ? host.workspace() : "");
        if (backend == null || !fingerprint.equals(backendKey)) {
            if (backend != null) {
                append(Kind.INFO, "（AI の設定が変わったので、新しい会話として始めます）\n");
            }
            switch (kind) {
                case "anthropic" -> {
                    if (key.isEmpty()) {
                        needSettings("Anthropic API を使うには、設定で API キーを入れてください。");
                        return null;
                    }
                    backend = new AiBackends.Anthropic(key, model.isEmpty() ? AiBackends.ANTHROPIC_MODEL : model, host.tools());
                }
                case "openai" -> {
                    if (model.isEmpty()) {
                        needSettings("OpenAI 互換の AI を使うには、設定でモデル名（例: gpt-5、llama3.1）を入れてください。");
                        return null;
                    }
                    backend = new AiBackends.OpenAiCompatible(url, key, model, host.tools());
                }
                default -> backend = new AiBackends.ClaudeCode(command, model, host.workspace());
            }
            backendKey = fingerprint;
            refreshBackendLabel();
        }
        return backend;
    }

    private void needSettings(String text) {
        append(Kind.ERROR, text + "\n");
        backend = null;
        backendKey = null;
        host.openSettings();
    }

    private void setBusy(boolean value) {
        busy = value;
        sendButton.setText(value ? "停止" : "送信");
        sendButton.setToolTipText(value ? "AI の作業を止める" : "送る（Enter）");
    }

    // ---------- 表示 ----------

    private enum Kind { USER_NAME, USER, AI_NAME, AI, TOOL, INFO, ERROR }

    private void later(Kind kind, String text) {
        SwingUtilities.invokeLater(() -> append(kind, text));
    }

    private void append(Kind kind, String text) {
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setFontFamily(style, Theme.small().getFamily());
        StyleConstants.setFontSize(style, Theme.SMALL_SIZE);
        Color color = switch (kind) {
            case USER_NAME, AI_NAME -> Theme.ACCENT;
            case USER, AI -> Theme.FG;
            case TOOL, INFO -> Theme.FG_DIM;
            case ERROR -> Theme.ERROR;
        };
        StyleConstants.setForeground(style, color);
        if (kind == Kind.USER_NAME || kind == Kind.AI_NAME) {
            StyleConstants.setBold(style, true);
            StyleConstants.setSpaceAbove(style, 8);
        }
        StyledDocument doc = transcript.getStyledDocument();
        try {
            doc.insertString(doc.getLength(), text, style);
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
        transcript.setCaretPosition(doc.getLength());
    }

    /** テスト用：会話欄に表示されている文字。 */
    String transcriptText() {
        return transcript.getText();
    }

    boolean isBusy() {
        return busy;
    }
}
