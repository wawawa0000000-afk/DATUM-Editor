package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * コマンドパレット（Ctrl+P）。すべての操作を、名前の一部を打つだけで探して実行できる。
 * メニューの項目を自動で集めるので、メニューに機能を足せば、ここにも自動で出る。
 * 何も打っていないときは、最近使った操作を上に出す。
 */
final class CommandPalette {

    /** 操作1つ。id は「最近使った操作」を覚えるための名前（メニューの道筋＋名前）。 */
    record Command(String id, String label, String path, String key, Runnable action) {
    }

    private static final int RECENT_LIMIT = 8;
    private static final int VISIBLE_ROWS = 12;

    private CommandPalette() {
    }

    /** メニューバーの項目と、メニューにない操作（extras）を集める。skip の項目は出さない（パレット自身を開く項目など）。 */
    static List<Command> collect(JMenuBar menuBar, List<Command> extras, JMenuItem skip) {
        List<Command> commands = new ArrayList<>();
        for (int i = 0; i < menuBar.getMenuCount(); i++) {
            JMenu menu = menuBar.getMenu(i);
            if (menu != null) {
                collect(menu, menu.getText(), commands, skip);
            }
        }
        commands.addAll(extras);
        return commands;
    }

    private static void collect(JMenu menu, String path, List<Command> out, JMenuItem skip) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem item = menu.getItem(i);
            if (item == null || item == skip || !item.isEnabled()) {
                continue;
            }
            if (item instanceof JMenu sub) {
                collect(sub, path + " → " + sub.getText(), out, skip);
                continue;
            }
            String label = item.getText();
            if (item instanceof JCheckBoxMenuItem check) {
                label = label + (check.isSelected() ? "（今：オン）" : "（今：オフ）");
            } else if (item instanceof JRadioButtonMenuItem radio && radio.isSelected()) {
                label = label + "（今これ）";
            }
            String key = item.getAccelerator() == null ? "" : HelpDialog.keyText(item.getAccelerator());
            out.add(new Command(path + "/" + item.getText(), label, path, key, () -> item.doClick(0)));
        }
    }

    /** 文字を比べやすい形にそろえる：全角半角・大文字小文字・カタカナとひらがなの違いをなくす。 */
    static String fold(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 'ァ' && c <= 'ヶ' ? (char) (c - 0x60) : c);     // カタカナ → ひらがな
        }
        return sb.toString();
    }

    /**
     * 打った言葉で絞り込んで並べる。空白で区切った言葉が、すべて名前・メニューの道筋・ショートカットのどこかに入っていれば出す。
     * 名前の先頭で一致するもの → 名前の中で一致するもの → 道筋などで一致するもの、の順。何も打っていなければ、最近使った順。
     */
    static List<Command> filter(List<Command> all, String query, List<String> recent) {
        String q = fold(query.strip());
        List<Command> hits = new ArrayList<>();
        if (q.isEmpty()) {
            for (String id : recent) {
                for (Command c : all) {
                    if (c.id().equals(id)) {
                        hits.add(c);
                    }
                }
            }
            for (Command c : all) {
                if (!hits.contains(c)) {
                    hits.add(c);
                }
            }
            return hits;
        }
        String[] terms = q.split("\\s+");
        for (Command c : all) {
            String hay = fold(c.label() + " " + c.path() + " " + c.key());
            boolean ok = true;
            for (String term : terms) {
                if (!hay.contains(term)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                hits.add(c);
            }
        }
        hits.sort(Comparator.comparingInt((Command c) -> {
            String label = fold(c.label());
            if (label.startsWith(terms[0])) {
                return 0;
            }
            return label.contains(terms[0]) ? 1 : 2;
        }).thenComparingInt(c -> {
            int index = recent.indexOf(c.id());
            return index < 0 ? Integer.MAX_VALUE : index;       // 同じくらい合っていれば、最近使ったものを上に
        }));
        return hits;
    }

    /** パレットを開く。選んだ操作は、パレットを閉じてから実行する。 */
    static void show(JFrame owner, AppSettings settings, List<Command> commands) {
        JDialog dialog = new JDialog(owner, false);
        dialog.setUndecorated(true);

        JTextField field = new JTextField() {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty()) {
                    // 何も打っていないときは、打てばよいことがわかるように、薄い字で例を出す
                    java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(Theme.FG_DIM);
                    g2.setFont(getFont());
                    java.awt.Insets in = getInsets();
                    g2.drawString("操作の名前を入力（例：分割、配色、ルビ、保存）", in.left,
                            in.top + (getHeight() - in.top - in.bottom + g2.getFontMetrics().getAscent() - g2.getFontMetrics().getDescent()) / 2);
                    g2.dispose();
                }
            }
        };
        Theme.styleField(field);
        field.setFont(Theme.ui());
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));

        DefaultListModel<Command> model = new DefaultListModel<>();
        JList<Command> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.PANEL);
        list.setCellRenderer(new Renderer());
        list.setFocusable(false);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);

        JLabel empty = new JLabel("見つかりません", JLabel.CENTER);
        empty.setForeground(Theme.FG_DIM);
        empty.setFont(Theme.small());
        empty.setBorder(BorderFactory.createEmptyBorder(16, 0, 16, 0));

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL);
        root.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        root.add(field, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        dialog.setContentPane(root);

        List<String> recent = settings.getStrings(AppSettings.RECENT_COMMANDS);
        Runnable refill = () -> {
            model.clear();
            for (Command c : filter(commands, field.getText(), recent)) {
                model.addElement(c);
            }
            if (model.isEmpty()) {
                root.remove(scroll);
                root.add(empty, BorderLayout.CENTER);
            } else {
                root.remove(empty);
                root.add(scroll, BorderLayout.CENTER);
                list.setSelectedIndex(0);
                list.ensureIndexIsVisible(0);
            }
            root.revalidate();
            root.repaint();
        };
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                refill.run();
            }

            public void removeUpdate(DocumentEvent e) {
                refill.run();
            }

            public void changedUpdate(DocumentEvent e) {
                refill.run();
            }
        });

        boolean[] done = {false};
        Runnable close = () -> {
            if (!done[0]) {
                done[0] = true;
                dialog.dispose();
            }
        };
        Runnable run = () -> {
            Command chosen = list.getSelectedValue();
            if (chosen == null) {
                return;
            }
            close.run();
            // 最近使った操作の先頭に入れる
            List<String> updated = new ArrayList<>(recent);
            updated.remove(chosen.id());
            updated.add(0, chosen.id());
            settings.put(AppSettings.RECENT_COMMANDS, new ArrayList<>(updated.subList(0, Math.min(RECENT_LIMIT, updated.size()))));
            SwingUtilities.invokeLater(chosen.action());    // パレットが閉じて、元の画面に戻ってから実行する
        };

        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int size = model.getSize();
                int index = list.getSelectedIndex();
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> move(list, size == 0 ? -1 : (index + 1) % size);
                    case KeyEvent.VK_UP -> move(list, size == 0 ? -1 : (index - 1 + size) % size);
                    case KeyEvent.VK_PAGE_DOWN -> move(list, Math.min(size - 1, index + VISIBLE_ROWS));
                    case KeyEvent.VK_PAGE_UP -> move(list, Math.max(0, index - VISIBLE_ROWS));
                    case KeyEvent.VK_ENTER -> run.run();
                    case KeyEvent.VK_ESCAPE -> close.run();
                    default -> {
                        return;
                    }
                }
                e.consume();
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index >= 0) {
                    list.setSelectedIndex(index);
                    run.run();
                }
            }
        });
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                close.run();                // ほかの所をクリックしたら閉じる
            }
        });

        refill.run();
        int width = Math.min(760, Math.max(480, owner.getWidth() - 80));
        int rowHeight = new Renderer().getListCellRendererComponent(list, commands.isEmpty() ? null : commands.get(0), 0, false, false)
                .getPreferredSize().height;
        list.setFixedCellHeight(rowHeight);
        dialog.setSize(new Dimension(width, field.getPreferredSize().height + rowHeight * VISIBLE_ROWS + 4));
        Point at = owner.getLocationOnScreen();
        dialog.setLocation(at.x + (owner.getWidth() - width) / 2, at.y + 70);     // VS Code と同じく、上の中央に出す
        dialog.setVisible(true);
        field.requestFocusInWindow();
    }

    private static void move(JList<Command> list, int index) {
        if (index >= 0) {
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        }
    }

    /** 1行の見た目：左に名前とメニューの道筋、右にショートカット。 */
    private static final class Renderer extends JPanel implements ListCellRenderer<Command> {
        private final JLabel label = new JLabel();
        private final JLabel path = new JLabel();
        private final JLabel key = new JLabel();

        Renderer() {
            super(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
            JPanel left = new JPanel(new BorderLayout(10, 0));
            left.setOpaque(false);
            label.setFont(Theme.ui());
            path.setFont(Theme.small());
            key.setFont(Theme.small());
            left.add(label, BorderLayout.WEST);
            left.add(path, BorderLayout.CENTER);
            add(left, BorderLayout.CENTER);
            add(key, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Command> list, Command value, int index,
                boolean selected, boolean focused) {
            label.setText(value == null ? " " : value.label());
            path.setText(value == null ? "" : value.path());
            key.setText(value == null ? "" : value.key());
            setBackground(selected ? Theme.MENU_SELECTION : Theme.PANEL);
            label.setForeground(selected ? Theme.FG_STRONG : Theme.FG);
            path.setForeground(Theme.FG_DIM);
            key.setForeground(selected ? Theme.FG_STRONG : Theme.FG_DIM);
            return this;
        }
    }
}
