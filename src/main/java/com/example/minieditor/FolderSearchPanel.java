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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

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
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

/**
 * フォルダの中を探す欄（画面の下に出る）。検索の欄と、結果の一覧で、結果を選ぶと、そのファイルの一致へ移る。
 *
 * 探す処理は FolderSearch に任せ、ここでは別のスレッドで動かして、結果を少しずつ一覧に足す。
 * 開く・選ぶなどの画面の操作は、Host（EditorFrame）に頼む。
 */
final class FolderSearchPanel extends JPanel {

    /** この欄から画面の側（EditorFrame）に頼むこと。 */
    interface Host {

        /** 探す範囲にするフォルダ。開いていなければ null。 */
        Path searchRoot();

        /** 開いている文書の中身（絶対パス → 保存形式の文字）。未保存の変更も入る。 */
        Map<Path, String> openTexts();

        /** 一致へ移る。jsonBody のときは、ファイルを開くだけで、選ばない（位置がファイルの中のものではないため）。 */
        void reveal(Path file, int start, int end, boolean jsonBody);
    }

    private final Host host;
    private final Runnable onClose;

    private final JTextField keywordField = new JTextField(18);
    private final JButton caseButton = Theme.flatButton("Aa");
    private final JButton regexButton = Theme.flatButton(".*");
    private final JButton searchButton = Theme.flatButton("検索");
    private final JButton stopButton = Theme.flatButton("中止");
    private final JLabel statusLabel = new JLabel(" ");
    private final DefaultListModel<FolderSearch.Hit> model = new DefaultListModel<>();
    private final JList<FolderSearch.Hit> list = new JList<>(model);

    private boolean caseSensitive = false;
    private boolean regexMode = false;
    private Path currentRoot;               // 今の結果の、探したフォルダ（一覧ではここからの相対パスで出す）
    private Run current;                    // 今、探している（終わった・止めた検索は null）

    /**
     * @param host    画面の側に頼む操作
     * @param onClose 「閉じる」（×・Esc）のとき、画面の側で欄を隠してもらう
     */
    FolderSearchPanel(Host host, Runnable onClose) {
        super(new BorderLayout());
        this.host = host;
        this.onClose = onClose;
        setBackground(Theme.BG);
        setMinimumSize(new Dimension(0, 120));

        Theme.styleField(keywordField);
        statusLabel.setFont(Theme.small());
        statusLabel.setForeground(Theme.FG);
        caseButton.setToolTipText("大文字と小文字を区別する");
        caseButton.addActionListener(e -> {
            caseSensitive = !caseSensitive;
            styleToggle(caseButton, caseSensitive);
        });
        styleToggle(caseButton, false);
        regexButton.setToolTipText("正規表現で検索する");
        regexButton.addActionListener(e -> {
            regexMode = !regexMode;
            styleToggle(regexButton, regexMode);
        });
        styleToggle(regexButton, false);
        searchButton.setToolTipText("フォルダの中を探す（Enter）");
        searchButton.addActionListener(e -> startSearch());
        stopButton.setToolTipText("探すのを止める");
        stopButton.addActionListener(e -> stopSearch());
        stopButton.setEnabled(false);
        JButton closeButton = Theme.flatButton("×");
        closeButton.setToolTipText("閉じる（Esc）");
        closeButton.addActionListener(e -> onClose.run());

        keywordField.addActionListener(e -> startSearch());     // Enter で検索する
        bind(keywordField, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFolderSearch", onClose);

        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof FolderSearch.Hit hit) {
                    setText(relativeName(hit.file()) + "  " + hit.line() + " 行  " + hit.lineText());
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
        bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openFolderHit", this::openSelected);
        bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFolderSearch", onClose);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        row.add(label("検索"));
        row.add(keywordField);
        row.add(caseButton);
        row.add(regexButton);
        row.add(searchButton);
        row.add(stopButton);
        row.add(statusLabel);
        row.add(closeButton);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

        JPanel widget = new JPanel(new BorderLayout());
        widget.setBackground(Theme.PANEL);
        widget.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        widget.add(row, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        widget.add(scroll, BorderLayout.CENTER);
        add(widget, BorderLayout.CENTER);
    }

    /** 検索の欄へ移って、文字を全部選ぶ（打ち直せるように）。 */
    void focusKeyword() {
        SwingUtilities.invokeLater(() -> {
            keywordField.requestFocusInWindow();
            keywordField.selectAll();
        });
    }

    /** 探している最中なら止めて、結果を出さないようにする（欄を隠すときに使う）。結果の一覧はそのまま残す。 */
    void cancelSearch() {
        detachSearch();
    }

    private void startSearch() {
        detachSearch();
        model.clear();
        Path root = host.searchRoot();
        if (root == null) {
            setStatus("フォルダを開いていません", Theme.ERROR);
            return;
        }
        String keyword = keywordField.getText();
        if (keyword.isEmpty()) {
            setStatus(" ", Theme.FG);
            return;
        }
        currentRoot = root.toAbsolutePath().normalize();
        current = new Run(currentRoot, keyword, regexMode, caseSensitive, host.openTexts());
        setStatus("探しています…", Theme.FG);
        searchButton.setEnabled(false);
        stopButton.setEnabled(true);
        current.execute();
    }

    /** 「中止」が押された。探している処理は、次のファイルの前で止まり、そのとき「中止しました」と出す。 */
    private void stopSearch() {
        if (current != null) {
            current.stop.set(true);
        }
    }

    /** 探している処理を切り離す。切り離した処理は、終わっても欄へ何も出さない。 */
    private void detachSearch() {
        if (current != null) {
            current.stop.set(true);
            current = null;
        }
        searchButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    /** 選んでいる結果の一致へ移る。 */
    private void openSelected() {
        FolderSearch.Hit hit = list.getSelectedValue();
        if (hit != null) {
            host.reveal(hit.file(), hit.start(), hit.end(), hit.jsonBody());
        }
    }

    private void showOutcome(FolderSearch.Outcome outcome) {
        if (outcome.error() != null) {
            setStatus(outcome.error(), Theme.ERROR);
        } else if (outcome.cancelled()) {
            setStatus("中止しました", Theme.FG);
        } else if (outcome.limited()) {
            setStatus(FolderSearch.MAX_HITS + " 件で打ち切りました", Theme.FG);
        } else if (outcome.hits() == 0) {
            setStatus("見つかりません", Theme.FG);
        } else {
            setStatus(outcome.hits() + " 件（" + outcome.files() + " 個のファイルを探しました）", Theme.FG);
        }
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    /** 探したフォルダからの相対パス（わからなければ、そのままのパス）。区切りは / にそろえる。 */
    private String relativeName(Path file) {
        if (currentRoot != null && file.startsWith(currentRoot) && !file.equals(currentRoot)) {
            return currentRoot.relativize(file).toString().replace('\\', '/');
        }
        return file.toString();
    }

    /** 探す処理を、画面のスレッドの外で動かす。結果は少しずつ一覧に足し、終わったら件数などを出す。 */
    private final class Run extends SwingWorker<FolderSearch.Outcome, FolderSearch.Hit> {

        private final Path root;
        private final String keyword;
        private final boolean regex;
        private final boolean caseSensitive;
        private final Map<Path, String> openTexts;
        private final AtomicBoolean stop = new AtomicBoolean();

        Run(Path root, String keyword, boolean regex, boolean caseSensitive, Map<Path, String> openTexts) {
            this.root = root;
            this.keyword = keyword;
            this.regex = regex;
            this.caseSensitive = caseSensitive;
            this.openTexts = openTexts;
        }

        @Override
        protected FolderSearch.Outcome doInBackground() {
            return FolderSearch.search(root, keyword, regex, caseSensitive, openTexts,
                    hit -> publish(hit), stop::get);
        }

        @Override
        protected void process(List<FolderSearch.Hit> hits) {
            if (this == current) {
                model.addAll(hits);
                if (list.getSelectedIndex() < 0) {
                    list.setSelectedIndex(0);
                }
            }
        }

        @Override
        protected void done() {
            if (this != current) {
                return;                     // 新しい検索が始まっている、または止めたあとの結果は出さない
            }
            current = null;
            searchButton.setEnabled(true);
            stopButton.setEnabled(false);
            try {
                showOutcome(get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                setStatus("検索を続けられませんでした", Theme.ERROR);
            } catch (ExecutionException ex) {
                setStatus("検索中にエラーが出ました: " + ex.getCause(), Theme.ERROR);
            }
        }
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.small());
        label.setForeground(Theme.FG_DIM);
        label.setPreferredSize(new Dimension(48, 28));
        return label;
    }

    /** 「Aa」「.*」の切り替えボタンの見た目（FindBar と同じ）。on のときは枠を青にする。 */
    private static void styleToggle(JButton button, boolean on) {
        button.setForeground(on ? Theme.FG_STRONG : Theme.FG_DIM);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(on ? Theme.ACCENT : Theme.INPUT),
                BorderFactory.createEmptyBorder(3, 11, 3, 11)));
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
