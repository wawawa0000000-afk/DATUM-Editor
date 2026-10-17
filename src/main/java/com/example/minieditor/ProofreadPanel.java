package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;

/**
 * 画面の右に出す「校正」の欄。今の文書を Proofreader で調べて、見つかった所を一覧にする。
 * 一覧をクリックするとその場所へ飛び、「直す」で案のとおりに書き換える（Ctrl+Z で戻せる）。
 * 文書を書き換えるたびに、少し待ってから自動で調べ直す。
 */
public final class ProofreadPanel extends JPanel {

    private final Supplier<Searchable> target;      // 今の文書（文章でなければ null）
    private final Runnable focusEditor;
    private final AppSettings settings;

    private final DefaultListModel<Proofreader.Issue> model = new DefaultListModel<>();
    private final JList<Proofreader.Issue> list = new JList<>(model) {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;            // 欄の幅に合わせて折り返す（横にはみ出さない）
        }
    };
    private final JLabel summary = new JLabel(" ");
    private final JButton fixButton = Theme.flatButton("直す");
    private final JButton ignoreButton = Theme.flatButton("無視");
    private final Set<String> ignored = new HashSet<>();     // 「無視」した所（言葉と項目の組。エディタを閉じるまで覚える）
    private final Timer recheck = new Timer(700, e -> refresh());
    private String checkedText;                             // 最後に調べた文章（変わっていなければ、調べ直さない）

    public ProofreadPanel(Supplier<Searchable> target, Runnable focusEditor, AppSettings settings, Runnable onClose) {
        super(new BorderLayout());
        this.target = target;
        this.focusEditor = focusEditor;
        this.settings = settings;
        recheck.setRepeats(false);
        setBackground(Theme.PANEL);

        JLabel title = new JLabel("校正");
        title.setFont(Theme.small());
        title.setForeground(Theme.FG);
        JButton items = Theme.flatButton("項目");
        items.setToolTipText("調べる項目を選ぶ");
        items.addActionListener(e -> showItemMenu(items));
        JButton close = Theme.flatButton("×");
        close.setToolTipText("校正の欄を隠す");
        close.addActionListener(e -> onClose.run());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(items);
        buttons.add(close);
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.PANEL);
        header.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 8));
        header.add(title, BorderLayout.WEST);
        header.add(buttons, BorderLayout.EAST);

        summary.setFont(Theme.small());
        summary.setForeground(Theme.FG_DIM);
        summary.setBorder(BorderFactory.createEmptyBorder(0, 12, 6, 12));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(summary, BorderLayout.SOUTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.BG);
        list.setCellRenderer(new IssueRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtons();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index >= 0 && list.getCellBounds(index, index).contains(e.getPoint())) {
                    jumpTo(model.get(index));
                }
            }
        });
        JScrollPane scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.BORDER));
        // 幅が変わったら、行の高さを測り直す（JList は一度測った高さを覚えてしまうため）
        list.addComponentListener(new java.awt.event.ComponentAdapter() {
            private int lastWidth;

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (list.getWidth() != lastWidth) {
                    lastWidth = list.getWidth();
                    list.setFixedCellHeight(10);
                    list.setFixedCellHeight(-1);
                }
            }
        });
        scroll.getViewport().setBackground(Theme.BG);

        fixButton.setToolTipText("案のとおりに直す（Ctrl+Z で戻せます）");
        fixButton.addActionListener(e -> fixSelected());
        ignoreButton.setToolTipText("この言葉を、今回は一覧に出さない");
        ignoreButton.addActionListener(e -> ignoreSelected());
        JButton recheckButton = Theme.flatButton("調べ直す");
        recheckButton.addActionListener(e -> {
            checkedText = null;
            refresh();
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        actions.setBackground(Theme.PANEL);
        actions.add(fixButton);
        actions.add(ignoreButton);
        actions.add(recheckButton);
        for (JButton button : new JButton[]{items, close, fixButton, ignoreButton, recheckButton}) {
            button.setFocusable(false);
        }

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);
        updateButtons();
    }

    /** 文書が書き換えられたときに呼ぶ。少し待ってから調べ直す（打っている間は待つ）。 */
    public void contentChanged() {
        if (isShowing()) {
            recheck.restart();
        }
    }

    /** 今の文書を調べて、一覧を作り直す。 */
    public void refresh() {
        Searchable doc = target.get();
        if (doc == null) {
            model.clear();
            checkedText = null;
            summary.setText("校正は、テキストと縦書きの文書で使えます");
            updateButtons();
            return;
        }
        String text = doc.getText();
        if (text.equals(checkedText)) {
            return;
        }
        checkedText = text;
        Proofreader.Issue selected = list.getSelectedValue();
        List<Proofreader.Issue> issues = new ArrayList<>();
        for (Proofreader.Issue issue : Proofreader.check(text, enabledCategories())) {
            if (!ignored.contains(ignoreKey(text, issue))) {
                issues.add(issue);
            }
        }
        model.clear();
        model.addAll(issues);
        // 直したあとも、だいたい同じ場所を選んだままにする（続けて確かめやすいように）
        if (selected != null && !issues.isEmpty()) {
            int index = 0;
            while (index < issues.size() - 1 && issues.get(index).start() < selected.start()) {
                index++;
            }
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        }
        Map<Proofreader.Category, Integer> counts = Proofreader.countByCategory(issues);
        if (issues.isEmpty()) {
            summary.setText("気になる所は見つかりませんでした");
        } else {
            StringBuilder sb = new StringBuilder("<html>" + issues.size() + " 件：");
            counts.forEach((category, n) -> sb.append(category.label).append(' ').append(n).append("　"));
            summary.setText(sb.append("</html>").toString());
        }
        updateButtons();
    }

    public List<Proofreader.Issue> issues() {
        List<Proofreader.Issue> all = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            all.add(model.get(i));
        }
        return all;
    }

    private Set<Proofreader.Category> enabledCategories() {
        Set<Proofreader.Category> enabled = EnumSet.allOf(Proofreader.Category.class);
        for (String name : settings.getStrings(AppSettings.PROOF_OFF)) {
            try {
                enabled.remove(Proofreader.Category.valueOf(name));
            } catch (IllegalArgumentException ex) {
                // 前の版にあって、今はない項目
            }
        }
        return enabled;
    }

    private void showItemMenu(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        Set<Proofreader.Category> enabled = enabledCategories();
        for (Proofreader.Category category : Proofreader.Category.values()) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(category.label, enabled.contains(category));
            item.addActionListener(e -> {
                Set<Proofreader.Category> now = enabledCategories();
                if (item.isSelected()) {
                    now.add(category);
                } else {
                    now.remove(category);
                }
                List<String> off = new ArrayList<>();
                for (Proofreader.Category c : Proofreader.Category.values()) {
                    if (!now.contains(c)) {
                        off.add(c.name());
                    }
                }
                settings.put(AppSettings.PROOF_OFF, off);
                settings.save();
                checkedText = null;
                refresh();
            });
            menu.add(item);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void jumpTo(Proofreader.Issue issue) {
        Searchable doc = target.get();
        if (doc != null && issue.end() <= doc.getText().length()) {
            focusEditor.run();
            doc.select(issue.start(), issue.end());
        }
    }

    private void fixSelected() {
        Proofreader.Issue issue = list.getSelectedValue();
        Searchable doc = target.get();
        if (issue == null || issue.fix() == null || doc == null) {
            return;
        }
        String text = doc.getText();
        if (issue.end() > text.length()) {
            return;
        }
        doc.replaceRange(issue.fix(), issue.start(), issue.end());
        refresh();          // 位置がずれるので、すぐに調べ直す
        Proofreader.Issue next = list.getSelectedValue();
        if (next != null) {
            jumpTo(next);
        }
    }

    private void ignoreSelected() {
        Proofreader.Issue issue = list.getSelectedValue();
        Searchable doc = target.get();
        if (issue == null || doc == null) {
            return;
        }
        ignored.add(ignoreKey(doc.getText(), issue));
        checkedText = null;
        refresh();
    }

    private static String ignoreKey(String text, Proofreader.Issue issue) {
        int end = Math.min(issue.end(), text.length());
        return issue.category() + ":" + text.substring(Math.min(issue.start(), end), end);
    }

    private void updateButtons() {
        Proofreader.Issue issue = list.getSelectedValue();
        fixButton.setEnabled(issue != null && issue.fix() != null);
        ignoreButton.setEnabled(issue != null);
    }

    /** 一覧の1行：項目名と説明、その下に該当する文字と直す案。 */
    private final class IssueRenderer extends JLabel implements ListCellRenderer<Proofreader.Issue> {
        IssueRenderer() {
            setOpaque(true);
            setFont(Theme.small());
            setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Proofreader.Issue> l, Proofreader.Issue issue,
                int index, boolean selected, boolean focused) {
            String text = checkedText == null ? "" : checkedText;
            int end = Math.min(issue.end(), text.length());
            String found = text.substring(Math.min(issue.start(), end), end).replace("\n", "↵");
            if (found.length() > 30) {
                found = found.substring(0, 30) + "…";
            }
            String line = issue.start() <= text.length()
                    ? (text.substring(0, issue.start()).chars().filter(c -> c == '\n').count() + 1) + " 行目" : "";
            String accent = hex(Theme.ACCENT);
            String dim = hex(Theme.FG_DIM);
            String fix = issue.fix() == null ? "" : "　→　<b>" + escape(issue.fix().isEmpty() ? "（削除）" : issue.fix()) + "</b>";
            int width = Math.max(160, l.getWidth() - 90);     // 欄の幅で折り返す（余白と、文字の幅の測り方の差のぶん、少し狭くする）
            setText("<html><div style='width:" + width + "px'><span style='color:" + accent + "'>" + issue.category().label + "</span>"
                    + "　<span style='color:" + dim + "'>" + line + "</span><br>"
                    + "「" + escape(found) + "」" + fix + "<br>"
                    + "<span style='color:" + dim + "'>" + escape(issue.message()) + "</span></div></html>");
            setBackground(selected ? Theme.MENU_SELECTION : (index % 2 == 0 ? Theme.BG : Theme.BG_STRIPE));
            setForeground(selected ? Theme.FG_STRONG : Theme.FG);
            return this;
        }
    }

    private static String hex(java.awt.Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
