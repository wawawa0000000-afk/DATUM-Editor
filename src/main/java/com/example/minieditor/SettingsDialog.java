package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

/**
 * 設定の画面（ファイル → 設定…）。表示・保存・執筆・AI の設定を1か所にまとめた。
 * 「OK」を押すと settings.json に保存する。
 */
final class SettingsDialog extends JDialog {

    private static final String[] BACKENDS = {"claude-code", "anthropic", "openai"};
    private static final String[] BACKEND_LABELS = {
        "Claude Code（月額プランで使える）",
        "Anthropic API（API キー・従量課金）",
        "OpenAI 互換 API（ChatGPT・Gemini・Ollama など）",
    };

    private boolean saved;

    SettingsDialog(Frame owner, AppSettings settings) {
        super(owner, "設定", true);

        // ---- 表示 ----
        Theme.Scheme current = Theme.Scheme.fromId(settings.getString(AppSettings.THEME, "gray"));
        ButtonGroup themes = new ButtonGroup();
        JRadioButton[] schemeButtons = new JRadioButton[Theme.Scheme.values().length];
        for (Theme.Scheme scheme : Theme.Scheme.values()) {
            JRadioButton button = new JRadioButton(scheme.label);
            button.setSelected(scheme == current);
            themes.add(button);
            schemeButtons[scheme.ordinal()] = button;
        }
        JPanel themeRow = row(schemeButtons);

        // ---- 保存 ----
        JCheckBox autoSave = new JCheckBox("30 秒ごとに自動で上書き保存する（保存先のある文書）",
                settings.getBool(AppSettings.AUTO_SAVE, false));
        JSpinner historyLimit = new JSpinner(new SpinnerNumberModel(settings.getInt(AppSettings.HISTORY_LIMIT, 50), 5, 500, 5));

        // ---- 執筆 ----
        JSpinner goal = new JSpinner(new SpinnerNumberModel(settings.getInt(AppSettings.DAILY_GOAL, 0), 0, 100000, 500));

        // ---- AI ----
        JComboBox<String> backend = new JComboBox<>(BACKEND_LABELS);
        String currentBackend = settings.getString(AppSettings.AI_BACKEND, "claude-code");
        for (int i = 0; i < BACKENDS.length; i++) {
            if (BACKENDS[i].equals(currentBackend)) {
                backend.setSelectedIndex(i);
            }
        }
        JTextField model = new JTextField(settings.getString(AppSettings.AI_MODEL, ""), 24);
        JPasswordField key = new JPasswordField(settings.getString(AppSettings.AI_KEY, ""), 24);
        JTextField url = new JTextField(settings.getString(AppSettings.AI_URL, AiBackends.OPENAI_URL), 24);
        JTextField command = new JTextField(settings.getString(AppSettings.CLAUDE_COMMAND, "claude"), 24);
        for (JTextField field : new JTextField[]{model, key, url, command}) {
            Theme.styleField(field);
        }
        JLabel aiHint = hint("");
        Runnable updateAi = () -> {
            int index = backend.getSelectedIndex();
            key.setEnabled(index != 0);
            url.setEnabled(index == 2);
            command.setEnabled(index == 0);
            aiHint.setText("<html><div style='width:520px'>" + switch (index) {
                case 1 -> "モデルを空にすると " + AiBackends.ANTHROPIC_MODEL + " を使います。"
                        + "費用を抑えたいときは claude-haiku-5-5 なども指定できます。";
                case 2 -> "例：OpenAI は https://api.openai.com/v1（モデル例 gpt-5）、"
                        + "Ollama は http://localhost:11434/v1（API キーは空でよい・モデル例 llama3.1）。"
                        + "道具（関数呼び出し）に対応したモデルを選んでください。";
                default -> "先に Claude Code を入れて、ターミナルで一度 claude を起動してログインしておいてください。"
                        + "使うときは「AI 連携（MCP）」も自動で動かします。モデルは空なら Claude Code の設定のまま。";
            } + "</div></html>");
        };
        backend.addActionListener(e -> updateAi.run());
        updateAi.run();

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        int y = 0;
        y = section(form, y, "表示");
        y = field(form, y, "配色", themeRow);
        y = field(form, y, "", hint("OK を押すと、すぐに変わります。「表示 → 配色」からも切り替えられます。"));
        y = section(form, y, "保存");
        y = field(form, y, "", autoSave);
        y = field(form, y, "", hint("<html>自動保存を切っていても、未保存の文書は 30 秒ごとに裏で控えを取ります（異常終了したときに、次の起動で復元できます）。</html>"));
        y = field(form, y, "版の履歴", row(historyLimit, new JLabel("個までファイルごとに残す")));
        y = section(form, y, "執筆");
        y = field(form, y, "1日の目標", row(goal, new JLabel("字（0 で目標なし）")));
        y = section(form, y, "AI（会話欄）");
        y = field(form, y, "使う AI", backend);
        y = field(form, y, "モデル", model);
        y = field(form, y, "API キー", key);
        y = field(form, y, "接続先 URL", url);
        y = field(form, y, "Claude Code のコマンド", command);
        y = field(form, y, "", aiHint);
        field(form, y, "", hint("<html>API キーは、この PC の設定フォルダ（本人だけが読める設定）に保存します。暗号化はしていません。</html>"));

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("キャンセル");
        ok.addActionListener(e -> {
            for (Theme.Scheme scheme : Theme.Scheme.values()) {
                if (schemeButtons[scheme.ordinal()].isSelected()) {
                    settings.put(AppSettings.THEME, scheme.id);
                }
            }
            settings.put(AppSettings.AUTO_SAVE, autoSave.isSelected());
            settings.put(AppSettings.HISTORY_LIMIT, (Integer) historyLimit.getValue());
            settings.put(AppSettings.DAILY_GOAL, (Integer) goal.getValue());
            settings.put(AppSettings.AI_BACKEND, BACKENDS[backend.getSelectedIndex()]);
            settings.put(AppSettings.AI_MODEL, blankToNull(model.getText()));
            settings.put(AppSettings.AI_KEY, blankToNull(new String(key.getPassword())));
            settings.put(AppSettings.AI_URL, blankToNull(url.getText()));
            settings.put(AppSettings.CLAUDE_COMMAND, blankToNull(command.getText()));
            settings.save();
            saved = true;
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(ok);
        buttons.add(cancel);
        getRootPane().setDefaultButton(ok);

        JScrollPane scroll = new JScrollPane(form, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        JPanel root = new JPanel(new BorderLayout());
        root.add(scroll, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(new Dimension(1080, 860));
        setLocationRelativeTo(owner);
    }

    /** OK で閉じたら true。 */
    boolean open() {
        setVisible(true);
        return saved;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static JPanel row(JComponent... parts) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        for (JComponent part : parts) {
            panel.add(part);
        }
        return panel;
    }

    private static JLabel hint(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.small());
        label.setForeground(Theme.FG_DIM);
        label.setPreferredSize(null);
        return label;
    }

    private static int section(JPanel form, int y, String title) {
        JLabel label = new JLabel(title);
        label.setFont(Theme.ui().deriveFont(java.awt.Font.BOLD));
        label.setForeground(Theme.FG_STRONG);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = y;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(y == 0 ? 0 : 18, 0, 6, 0);
        form.add(label, c);
        return y + 1;
    }

    private static int field(JPanel form, int y, String label, JComponent component) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = y;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(4, 12, 4, 12);
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        if (component instanceof JLabel hintLabel && hintLabel.getText().startsWith("<html>") && !hintLabel.getText().contains("<div")) {
            hintLabel.setText(hintLabel.getText().replace("<html>", "<html><div style='width:520px'>").replace("</html>", "</div></html>"));
        }
        form.add(component, c);
        return y + 1;
    }
}
