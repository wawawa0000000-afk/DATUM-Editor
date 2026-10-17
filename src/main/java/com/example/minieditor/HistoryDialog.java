package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.ButtonGroup;
import javax.swing.ListSelectionModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * 「版の履歴」の画面。左に保存した版の一覧、右にその版の内容か、今の文書との違いを出す。
 * 「この版に戻す」を押すと、今の内容も履歴に残してから、その版に入れ替える（戻したあとでも、元に戻れる）。
 */
final class HistoryDialog extends JDialog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M月d日（E） HH:mm:ss");

    private final List<String> contents = new ArrayList<>();
    private final String current;
    private final JTextArea contentView = new JTextArea();
    private final JTextPane diffView = new JTextPane();
    private final JLabel diffSummary = new JLabel(" ");

    /**
     * @param current  今の文書の内容（ファイルに書くときと同じ形）
     * @param restore  「この版に戻す」で呼ぶ（選んだ版の内容を渡す）
     * @param openCopy 「別のタブで開く」で呼ぶ
     */
    HistoryDialog(Frame owner, String fileName, List<VersionHistory.Version> versions, VersionHistory history,
            String current, Consumer<String> restore, Consumer<String> openCopy) {
        super(owner, "版の履歴 - " + fileName, true);
        this.current = current;

        List<String> labels = new ArrayList<>();
        for (VersionHistory.Version version : versions) {
            String text;
            try {
                text = history.read(version);
            } catch (IOException ex) {
                text = "";
            }
            contents.add(text);
            String mark = text.equals(current) ? "　＝今と同じ" : "";
            labels.add(version.time().format(TIME) + "　" + String.format("%,d", text.codePointCount(0, text.length())) + " 字" + mark);
        }

        JList<String> list = new JList<>(labels.toArray(new String[0]));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(Theme.small());
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focused) {
                JLabel label = (JLabel) super.getListCellRendererComponent(l, value, index, selected, focused);
                label.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
                label.setBackground(selected ? Theme.MENU_SELECTION : Theme.BG);
                label.setForeground(selected ? Theme.FG_STRONG : Theme.FG);
                return label;
            }
        });
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.getViewport().setBackground(Theme.BG);

        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, Theme.SMALL_SIZE);
        contentView.setEditable(false);
        contentView.setLineWrap(true);
        contentView.setFont(mono);
        contentView.setBackground(Theme.BG);
        contentView.setForeground(Theme.FG);
        contentView.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        diffView.setEditable(false);
        diffView.setFont(mono);
        diffView.setBackground(Theme.BG);
        diffView.setForeground(Theme.FG);
        diffView.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        CardLayout cards = new CardLayout();
        JPanel views = new JPanel(cards);
        views.add(new JScrollPane(contentView), "content");
        JPanel diffPanel = new JPanel(new BorderLayout());
        diffSummary.setFont(Theme.small());
        diffSummary.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        diffPanel.add(diffSummary, BorderLayout.NORTH);
        diffPanel.add(new JScrollPane(diffView), BorderLayout.CENTER);
        views.add(diffPanel, "diff");

        JToggleButton showContent = new JToggleButton("この版の内容", true);
        JToggleButton showDiff = new JToggleButton("今との違い");
        ButtonGroup group = new ButtonGroup();
        group.add(showContent);
        group.add(showDiff);
        showContent.addActionListener(e -> cards.show(views, "content"));
        showDiff.addActionListener(e -> cards.show(views, "diff"));
        JPanel switcher = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        switcher.add(showContent);
        switcher.add(showDiff);
        JPanel right = new JPanel(new BorderLayout());
        right.add(switcher, BorderLayout.NORTH);
        right.add(views, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, right);
        split.setDividerLocation(360);
        split.setBorder(null);

        JButton restoreButton = new JButton("この版に戻す");
        JButton openButton = new JButton("別のタブで開く");
        JButton closeButton = new JButton("閉じる");
        restoreButton.addActionListener(e -> {
            int index = list.getSelectedIndex();
            if (index < 0) {
                return;
            }
            int answer = JOptionPane.showConfirmDialog(this,
                    "この版の内容に戻しますか？\n今の内容は履歴に残すので、あとからこの画面で戻せます。",
                    "この版に戻す", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) {
                restore.accept(contents.get(index));
                dispose();
            }
        });
        openButton.addActionListener(e -> {
            int index = list.getSelectedIndex();
            if (index >= 0) {
                openCopy.accept(contents.get(index));
                dispose();
            }
        });
        closeButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(restoreButton);
        buttons.add(openButton);
        buttons.add(closeButton);

        list.addListSelectionListener(e -> {
            int index = list.getSelectedIndex();
            boolean chosen = index >= 0;
            restoreButton.setEnabled(chosen);
            openButton.setEnabled(chosen);
            if (chosen) {
                show(contents.get(index));
            }
        });
        if (!labels.isEmpty()) {
            list.setSelectedIndex(0);
        }

        JPanel root = new JPanel(new BorderLayout());
        root.add(split, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(new Dimension(1200, 760));
        setLocationRelativeTo(owner);
    }

    private void show(String version) {
        contentView.setText(version);
        contentView.setCaretPosition(0);
        showDiff(version);
    }

    /** 選んだ版から今までに、どの行が増えた（＋）・減った（−）かを、色を付けて出す。同じ行が長く続く所は畳む。 */
    private void showDiff(String version) {
        StyledDocument doc = diffView.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            List<LineDiff.Line> lines = LineDiff.diff(version, current);
            if (lines == null) {
                diffSummary.setText("文章が長すぎるため、違いを計算できませんでした（" + LineDiff.MAX_LINES + " 行まで）");
                return;
            }
            int added = 0;
            int removed = 0;
            SimpleAttributeSet same = new SimpleAttributeSet();
            StyleConstants.setForeground(same, Theme.FG_DIM);
            SimpleAttributeSet plus = new SimpleAttributeSet();
            StyleConstants.setBackground(plus, Theme.REPLACED);
            StyleConstants.setForeground(plus, Theme.FG_STRONG);
            SimpleAttributeSet minus = new SimpleAttributeSet();
            StyleConstants.setBackground(minus, Theme.isLight() ? new java.awt.Color(0xF7C6C6) : new java.awt.Color(0x6E2A2A));
            StyleConstants.setForeground(minus, Theme.FG_STRONG);
            for (int i = 0; i < lines.size(); i++) {
                LineDiff.Line line = lines.get(i);
                if (line.kind() == LineDiff.Kind.SAME) {
                    // 前後 2 行だけ残して、変わっていない所は畳む
                    int runEnd = i;
                    while (runEnd < lines.size() && lines.get(runEnd).kind() == LineDiff.Kind.SAME) {
                        runEnd++;
                    }
                    int keepHead = i == 0 ? 0 : 2;
                    int keepTail = runEnd == lines.size() ? 0 : 2;
                    if (runEnd - i > keepHead + keepTail + 1) {
                        for (int k = i; k < i + keepHead; k++) {
                            doc.insertString(doc.getLength(), "  " + lines.get(k).text() + "\n", same);
                        }
                        doc.insertString(doc.getLength(), "  ……（" + (runEnd - i - keepHead - keepTail) + " 行は同じ）……\n", same);
                        for (int k = runEnd - keepTail; k < runEnd; k++) {
                            doc.insertString(doc.getLength(), "  " + lines.get(k).text() + "\n", same);
                        }
                    } else {
                        for (int k = i; k < runEnd; k++) {
                            doc.insertString(doc.getLength(), "  " + lines.get(k).text() + "\n", same);
                        }
                    }
                    i = runEnd - 1;
                } else if (line.kind() == LineDiff.Kind.ADDED) {
                    added++;
                    doc.insertString(doc.getLength(), "＋ " + line.text() + "\n", plus);
                } else {
                    removed++;
                    doc.insertString(doc.getLength(), "− " + line.text() + "\n", minus);
                }
            }
            diffSummary.setText(added == 0 && removed == 0 ? "今の文書と同じです"
                    : "この版から今までに：増えた行 " + added + "（＋）、減った行 " + removed + "（−）");
            diffView.setCaretPosition(0);
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
