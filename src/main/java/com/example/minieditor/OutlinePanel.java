package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
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

        PanelHeader header = new PanelHeader(new JLabel("見出し一覧"), summary)
                .with(PanelHeader.closeButton("閉じる（Esc）", onClose));

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
        Keys.bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "jumpOutline", this::jumpToSelected);
        Keys.bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeOutline", onClose);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    /** 今の文書から見出しを探し直して、一覧を作り直す。文書が無ければ、その旨を出して一覧は空にする。 */
    void refresh() {
        int selected = list.getSelectedIndex();     // 文字を打つたびに呼ばれるので、選んでいた行は残す
        Searchable doc = target.get();
        if (doc == null) {
            model.clear();
            setSummary("この文書では使えません", Theme.ERROR);
            return;
        }
        List<OutlineScanner.Heading> headings = OutlineScanner.scan(doc.getText());
        if (updateInPlace(headings)) {
            return;
        }
        model.clear();
        model.addAll(headings);
        if (headings.isEmpty()) {
            setSummary("見出しが見つかりません", Theme.FG_DIM);
        } else {
            setSummary(headings.size() + " 件", Theme.FG_DIM);
            list.setSelectedIndex(selected >= 0 && selected < headings.size() ? selected : 0);
        }
    }

    /**
     * 見出しの数が同じなら、変わった行だけを入れ替えて true（打つたびに一覧を空にして作り直さないため。選んでいる行もそのまま）。
     * 見出しの後ろで打っているときは、何も変わらない。数が変わったとき・一覧が空のときは false（作り直す）。
     */
    private boolean updateInPlace(List<OutlineScanner.Heading> headings) {
        if (headings.isEmpty() || headings.size() != model.size()) {
            return false;
        }
        for (int i = 0; i < headings.size(); i++) {
            if (!headings.get(i).equals(model.get(i))) {
                model.set(i, headings.get(i));
            }
        }
        if (list.getSelectedIndex() < 0) {
            list.setSelectedIndex(0);           // 作り直すときと同じく、選んでいなければ先頭を選ぶ
        }
        return true;
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
}
