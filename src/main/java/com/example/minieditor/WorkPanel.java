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
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

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
import javax.swing.SwingWorker;

/**
 * 作品の集計の欄（画面の下に出る）。フォルダの中の話（ファイル）ごとの字数と、合計を並べる。
 * 一覧の行を選ぶと、そのファイルを開く。Esc で欄を閉じて、エディタへ戻る。
 *
 * 数える処理は WorkTotals に任せ、別のスレッドで動かす。数えるのは欄を出したときと「再集計」のときだけ
 * （文書を書き換えても、自動では数え直さない。重いため）。
 */
final class WorkPanel extends JPanel {

    /** この欄から画面の側（EditorFrame）に頼むこと。 */
    interface Host {

        /** 数える対象のフォルダ（サイドバーで開いているもの）。開いていなければ null。 */
        Path root();

        /** 開いている文書の中身（絶対パス → 保存形式の文字）。未保存の変更も入る。 */
        Map<Path, String> openTexts();

        /** 今の字数の数え方（ステータスバーと同じ）。 */
        WritingStats.CountMode countMode();

        /** 横書きの記法（ルビ記号）として数えるか（ステータスバーと同じ判定）。 */
        boolean rubyNotation();

        /** 一覧のファイルを開く（開いていればそのタブを前に出す）。 */
        void open(Path file);
    }

    private final Host host;
    private final Runnable onClose;

    private final JLabel summary = new JLabel(" ");
    private final JButton recount = Theme.flatButton("再集計");
    private final DefaultListModel<WorkTotals.Entry> model = new DefaultListModel<>();
    private final JList<WorkTotals.Entry> list = new JList<>(model);

    private Count current;                  // 今、数えている処理（終わった・切り離した処理は null）

    /**
     * @param host    画面の側に頼む操作
     * @param onClose 「閉じる」（×・Esc）のとき、画面の側で欄を隠してもらう
     */
    WorkPanel(Host host, Runnable onClose) {
        super(new BorderLayout());
        this.host = host;
        this.onClose = onClose;
        setBackground(Theme.BG);
        setMinimumSize(new Dimension(0, 120));

        JLabel title = new JLabel("作品の集計");
        title.setFont(Theme.small());
        title.setForeground(Theme.FG);
        summary.setFont(Theme.small());
        summary.setForeground(Theme.FG_DIM);
        recount.setToolTipText("フォルダの中の文章を、もう一度数える");
        recount.addActionListener(e -> refresh());
        JButton close = Theme.flatButton("×");
        close.setToolTipText("閉じる（Esc）");
        close.addActionListener(e -> onClose.run());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(recount);
        buttons.add(close);
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.PANEL);
        header.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 8));
        header.add(title, BorderLayout.WEST);
        header.add(summary, BorderLayout.CENTER);
        header.add(buttons, BorderLayout.EAST);

        list.setBackground(Theme.BG);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof WorkTotals.Entry entry) {
                    setText(entry.name() + "  " + String.format("%,d 字", entry.chars()));
                }
                return this;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    openSelected();
                }
            }
        });
        bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openWorkEntry", this::openSelected);
        // Esc は、欄の中のどこにフォーカスがあっても効くようにする（一覧・ボタンのどれでも）
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeWork");
        getActionMap().put("closeWork", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                onClose.run();
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    /** 数え直す（欄を出したとき・「再集計」・数え方を変えたときに使う）。数えるのは別のスレッドで、終わったら一覧を出す。 */
    void refresh() {
        cancel();
        model.clear();
        Path root = host.root();
        if (root == null) {
            setSummary("フォルダを開いていません", Theme.ERROR);
            return;
        }
        setSummary("数えています…", Theme.FG_DIM);
        recount.setEnabled(false);
        current = new Count(root.toAbsolutePath().normalize(), host.countMode(), host.rubyNotation(), host.openTexts());
        current.execute();
    }

    /** 数えている処理を切り離す。切り離した処理は、終わっても欄へ何も出さない（欄を隠すときに使う）。 */
    void cancel() {
        current = null;
        recount.setEnabled(true);
    }

    /** 欄へ移って、一覧にキーボードの焦点を置く（Enter で開けるように）。 */
    void focusList() {
        SwingUtilities.invokeLater(list::requestFocusInWindow);
    }

    /** 選んでいる話のファイルを開く。 */
    private void openSelected() {
        WorkTotals.Entry entry = list.getSelectedValue();
        if (entry != null) {
            host.open(entry.file());
        }
    }

    private void show(WorkTotals.Totals totals) {
        model.clear();
        model.addAll(totals.entries());
        if (totals.entries().isEmpty()) {
            setSummary("対象のテキストファイルがありません", Theme.FG_DIM);
            return;
        }
        list.setSelectedIndex(0);
        String text = "合計 " + String.format("%,d", totals.total()) + " 字（" + totals.mode() + "）";
        if (totals.skippedAny()) {
            text += "　一部のファイルは飛ばしました";
        }
        setSummary(text, Theme.FG);
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

    /** 数える処理を、画面のスレッドの外で動かす。終わったら、切り離されていなければ一覧を出す。 */
    private final class Count extends SwingWorker<WorkTotals.Totals, Void> {

        private final Path root;
        private final WritingStats.CountMode mode;
        private final boolean rubyNotation;
        private final Map<Path, String> openTexts;

        Count(Path root, WritingStats.CountMode mode, boolean rubyNotation, Map<Path, String> openTexts) {
            this.root = root;
            this.mode = mode;
            this.rubyNotation = rubyNotation;
            this.openTexts = openTexts;
        }

        @Override
        protected WorkTotals.Totals doInBackground() {
            return WorkTotals.count(root, mode, rubyNotation, openTexts);
        }

        @Override
        protected void done() {
            if (this != current) {
                return;                     // 新しく数え直したか、欄を隠した後の結果は出さない
            }
            current = null;
            recount.setEnabled(true);
            try {
                show(get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                setSummary("数えられませんでした", Theme.ERROR);
            } catch (ExecutionException ex) {
                setSummary("数えるときにエラーが出ました: " + ex.getCause(), Theme.ERROR);
            }
        }
    }
}
