package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * 見出しの一覧の欄（画面の下に出る）。今の文書の「第一話」「第三章」などを、行の番号と一緒に並べる。
 * 一覧の行を選ぶと、その見出しの行へ移る。Esc で欄を閉じて、エディタへ戻る。
 *
 * 見つけるのは OutlineScanner に任せる。今の文書は Supplier で受け取り、移る処理は Searchable に頼む。
 */
final class OutlinePanel extends JPanel {

    private final Supplier<Searchable> target;      // 今の文書（文章でなければ null）
    private final Runnable focusEditor;

    private final JLabel summary = new JLabel(" ");
    private final DefaultListModel<OutlineScanner.Heading> model = new DefaultListModel<>();
    private final JList<OutlineScanner.Heading> list = new JList<>(model);

    /**
     * @param target      今の文書（文章でなければ null を返す）
     * @param focusEditor 移ったあと、エディタへ戻す
     * @param onClose     「閉じる」（×・Esc）のとき、画面の側で欄を隠してもらう
     */
    OutlinePanel(Supplier<Searchable> target, Runnable focusEditor, Runnable onClose) {
        super(new BorderLayout());
        this.target = target;
        this.focusEditor = focusEditor;
        setBackground(Theme.BG);
        setMinimumSize(new Dimension(0, 120));

        JLabel title = new JLabel("見出し一覧");
        title.setFont(Theme.small());
        title.setForeground(Theme.FG);
        JButton close = Theme.flatButton("×");
        close.setToolTipText("閉じる（Esc）");
        close.addActionListener(e -> onClose.run());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(close);
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.PANEL);
        header.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 8));
        header.add(title, BorderLayout.WEST);
        header.add(summary, BorderLayout.CENTER);
        header.add(buttons, BorderLayout.EAST);

        summary.setFont(Theme.small());
        summary.setForeground(Theme.FG_DIM);
        summary.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));

        list.setBackground(Theme.BG);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof OutlineScanner.Heading heading) {
                    setText(heading.line() + "  " + heading.title());
                }
                return this;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    jumpToSelected();
                }
            }
        });
        bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "jumpOutline", this::jumpToSelected);
        bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeOutline", onClose);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    /** 今の文書から見出しを探し直して、一覧を作り直す。文書が無ければ、その旨を出して一覧は空にする。 */
    void refresh() {
        int selected = list.getSelectedIndex();     // 文字を打つたびに呼ばれるので、選んでいた行は残す
        model.clear();
        Searchable doc = target.get();
        if (doc == null) {
            setSummary("この文書では使えません", Theme.ERROR);
            return;
        }
        List<OutlineScanner.Heading> headings = OutlineScanner.scan(doc.getText());
        model.addAll(headings);
        if (headings.isEmpty()) {
            setSummary("見出しが見つかりません", Theme.FG_DIM);
        } else {
            setSummary(headings.size() + " 件", Theme.FG_DIM);
            list.setSelectedIndex(selected >= 0 && selected < headings.size() ? selected : 0);
        }
    }

    /** 欄へ移って、一覧にキーボードの焦点を置く（Enter で移れるように）。 */
    void focusList() {
        SwingUtilities.invokeLater(list::requestFocusInWindow);
    }

    /** 選んでいる見出しの行へ移る。文書が無ければ、その旨を出す。 */
    private void jumpToSelected() {
        OutlineScanner.Heading heading = list.getSelectedValue();
        if (heading == null) {
            return;
        }
        Searchable doc = target.get();
        if (doc == null) {
            setSummary("この文書では使えません", Theme.ERROR);
            model.clear();
            return;
        }
        doc.select(heading.start(), heading.end());
        focusEditor.run();
    }

    private void setSummary(String text, Color color) {
        summary.setText(text);
        summary.setForeground(color);
    }

    private static void bind(JComponent component, KeyStroke key, String name, Runnable action) {
        component.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }
}
