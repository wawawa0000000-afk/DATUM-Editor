package com.example.minieditor;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * 検索・置換のバー（画面の右上に出る）。
 *
 * 入力するたびに一致箇所をすべて色付けし、Enter / Shift+Enter で前後に移動する。
 * 相手の文書は Searchable という約束だけを通して操作するので、横書きでも縦書きでも同じ処理で動く。
 * 一致を探すのは FindMatcher に任せる（正規表現・大文字小文字・時間切れの扱いもそこ）。
 */
public class FindBar extends JPanel {

    private final Supplier<EditorPane> currentPane;

    private final JTextField findField = new JTextField(18);
    private final JTextField replaceField = new JTextField(18);
    private final JLabel countLabel = new JLabel(" ");
    private final JButton caseButton = Theme.flatButton("Aa");
    private final JButton regexButton = Theme.flatButton(".*");
    private final JButton selectionButton = Theme.flatButton("選択内");
    private final JPanel replaceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

    private boolean caseSensitive = false;
    private boolean regexMode = false;      // 正規表現で検索するか
    private boolean selectionOnly = false;  // 選んでいる範囲の中だけを検索するか
    private int[] selectionScope;           // 最後に選んでいた範囲（一致へ移ると選びが消えるので、覚えておく）
    private int lastStart = 0;          // 今いる一致箇所の、始まりと終わり
    private int lastEnd = 0;
    private final List<int[]> replaced = new ArrayList<>();    // 置換した範囲（緑で表示する）
    private boolean internalEdit;       // 自分の置換で文書が変わっている最中か
    private EditorPane highlighted;     // 今、色を付けている文書

    public FindBar(Supplier<EditorPane> currentPane) {
        super(new FlowLayout(FlowLayout.RIGHT, 16, 6));
        this.currentPane = currentPane;
        setBackground(Theme.BG);

        Theme.styleField(findField);
        Theme.styleField(replaceField);
        countLabel.setFont(Theme.small());
        countLabel.setForeground(Theme.FG);
        countLabel.setPreferredSize(new Dimension(190, 28));

        JButton previousButton = Theme.flatButton("↑");
        previousButton.setToolTipText("前へ（Shift+Enter）");
        previousButton.addActionListener(e -> move(false));
        JButton nextButton = Theme.flatButton("↓");
        nextButton.setToolTipText("次へ（Enter）");
        nextButton.addActionListener(e -> move(true));
        caseButton.setToolTipText("大文字と小文字を区別する");
        caseButton.addActionListener(e -> {
            caseSensitive = !caseSensitive;
            styleToggle(caseButton, caseSensitive);
            refresh(true);
        });
        styleToggle(caseButton, false);
        regexButton.setToolTipText("正規表現で検索する");
        regexButton.addActionListener(e -> {
            regexMode = !regexMode;
            styleToggle(regexButton, regexMode);
            refresh(true);
        });
        styleToggle(regexButton, false);
        selectionButton.setToolTipText("選んでいる範囲の中だけを検索する");
        selectionButton.addActionListener(e -> {
            selectionOnly = !selectionOnly;
            styleToggle(selectionButton, selectionOnly);
            refresh(true);
        });
        styleToggle(selectionButton, false);
        selectionButton.setEnabled(false);
        JButton closeButton = Theme.flatButton("×");
        closeButton.setToolTipText("閉じる（Esc）");
        closeButton.addActionListener(e -> close());

        JButton replaceOneButton = Theme.flatButton("置換");
        replaceOneButton.addActionListener(e -> replaceOne());
        JButton replaceAllButton = Theme.flatButton("すべて置換");
        replaceAllButton.addActionListener(e -> replaceAll());

        JPanel findRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        findRow.setOpaque(false);
        findRow.add(label("検索"));
        findRow.add(findField);
        findRow.add(caseButton);
        findRow.add(regexButton);
        findRow.add(selectionButton);
        findRow.add(previousButton);
        findRow.add(nextButton);
        findRow.add(countLabel);
        findRow.add(closeButton);

        replaceRow.setOpaque(false);
        replaceRow.add(label("置換"));
        replaceRow.add(replaceField);
        replaceRow.add(replaceOneButton);
        replaceRow.add(replaceAllButton);
        replaceRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        JPanel widget = new JPanel();
        widget.setLayout(new BoxLayout(widget, BoxLayout.Y_AXIS));
        widget.setBackground(Theme.PANEL);
        widget.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        findRow.setAlignmentX(LEFT_ALIGNMENT);
        replaceRow.setAlignmentX(LEFT_ALIGNMENT);
        widget.add(findRow);
        widget.add(replaceRow);
        add(widget);

        // 入力するたびに、検索し直す
        findField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                keywordChanged();
            }

            public void removeUpdate(DocumentEvent e) {
                keywordChanged();
            }

            public void changedUpdate(DocumentEvent e) {
            }
        });
        findField.addActionListener(e -> move(true));                    // Enter で次へ
        bind(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                "findPrevious", () -> move(false));
        bind(findField, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFind", this::close);
        replaceField.addActionListener(e -> replaceOne());               // Enter で1件置換
        bind(replaceField, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFind", this::close);

        setVisible(false);
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.small());
        label.setForeground(Theme.FG_DIM);
        label.setPreferredSize(new Dimension(48, 28));
        return label;
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

    /** 「Aa」「.*」「選択内」の切り替えボタンの見た目。on のときは枠を青にする。 */
    private static void styleToggle(JButton button, boolean on) {
        button.setForeground(on ? Theme.FG_STRONG : Theme.FG_DIM);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(on ? Theme.ACCENT : Theme.INPUT),
                BorderFactory.createEmptyBorder(3, 11, 3, 11)));
    }

    // ---------- 開く・閉じる ----------

    /** バーを表示する。withReplace が true なら、置換の行も出す。 */
    public void open(boolean withReplace) {
        replaceRow.setVisible(withReplace);
        setVisible(true);
        revalidate();
        findField.requestFocusInWindow();
        findField.selectAll();
        rememberSelection();
        refresh(false);
    }

    public void close() {
        clearHighlights();
        replaced.clear();
        selectionScope = null;
        setVisible(false);
        EditorPane pane = currentPane.get();
        if (pane != null) {
            pane.focusEditor();
        }
    }

    /** 表示する文書が切り替わったときに、ウィンドウ側から呼ばれる。 */
    public void paneSwitched() {
        if (highlighted != currentPane.get()) {
            clearHighlights();
            replaced.clear();
            selectionScope = null;
            lastStart = 0;
            lastEnd = 0;
        }
        if (isVisible()) {
            refresh(false);
        }
    }

    /** 文書の中身が変わったときに、ウィンドウ側から呼ばれる。 */
    public void contentChanged(EditorPane pane) {
        if (!isVisible() || internalEdit || pane != currentPane.get()) {
            return;
        }
        replaced.clear();               // 位置がずれるので、緑の表示はやめる
        selectionScope = null;          // 選んでいた範囲の位置も、ずれているかもしれないので、覚えない
        refresh(false);
    }

    private void keywordChanged() {
        replaced.clear();
        refresh(true);
    }

    // ---------- 検索 ----------

    private Searchable target() {
        return currentPane.get() instanceof Searchable s ? s : null;
    }

    /**
     * 選んでいる範囲があれば覚え、「選択内」ボタンを使えるかを決める。
     * 一致へ移ると（reveal で）選びが消えるので、覚えておいた範囲で検索を続ける。
     */
    private void rememberSelection() {
        Searchable s = target();
        if (s != null && s.getSelectionStart() < s.getSelectionEnd()) {
            selectionScope = new int[]{s.getSelectionStart(), s.getSelectionEnd()};
        }
        selectionButton.setEnabled(selectionScope != null);
    }

    /** 検索する範囲。「選択内」のときは覚えておいた範囲（なければ、文書全体）。 */
    private int[] searchRange(int length) {
        if (selectionOnly && selectionScope != null && selectionScope[1] <= length) {
            return selectionScope;
        }
        return new int[]{0, length};
    }

    /** 今の設定で、一致をすべて集める。エラーがあれば、その中身が Result.error に入る。 */
    private FindMatcher.Result findAll(String text, String keyword, String replacement) {
        rememberSelection();
        int[] range = searchRange(text.length());
        return FindMatcher.find(text, keyword, replacement, regexMode, caseSensitive, range[0], range[1]);
    }

    /** from 以上で一番近い一致。なければ先頭に戻る。 */
    private static FindMatcher.Hit nextFrom(List<FindMatcher.Hit> hits, int from) {
        for (FindMatcher.Hit hit : hits) {
            if (hit.start() >= from) {
                return hit;
            }
        }
        return hits.get(0);
    }

    /** from より前で一番近い一致。なければ最後に戻る。 */
    private static FindMatcher.Hit previousFrom(List<FindMatcher.Hit> hits, int from) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i).start() < from) {
                return hits.get(i);
            }
        }
        return hits.get(hits.size() - 1);
    }

    /** 始まりが start の一致。なければ null。 */
    private static FindMatcher.Hit hitAt(List<FindMatcher.Hit> hits, int start) {
        for (FindMatcher.Hit hit : hits) {
            if (hit.start() == start) {
                return hit;
            }
        }
        return null;
    }

    /** 今の場所を保ったまま、検索し直す。 */
    private void refresh(boolean reveal) {
        Searchable s = target();
        if (s == null) {
            showUnsupported();
            return;
        }
        String keyword = findField.getText();
        FindMatcher.Result result = findAll(s.getText(), keyword, replaceField.getText());
        if (result.error() != null) {
            showError(s, result.error());
            return;
        }
        List<FindMatcher.Hit> hits = result.hits();
        if (hits.isEmpty()) {
            show(s, hits, -1, false);
            return;
        }
        FindMatcher.Hit current = nextFrom(hits, lastStart);
        lastStart = current.start();
        lastEnd = current.end();
        show(s, hits, lastStart, reveal);
    }

    /** 次（forward が true）または前の一致へ移る。 */
    private void move(boolean forward) {
        Searchable s = target();
        if (s == null) {
            showUnsupported();
            return;
        }
        String keyword = findField.getText();
        FindMatcher.Result result = findAll(s.getText(), keyword, replaceField.getText());
        if (result.error() != null) {
            showError(s, result.error());
            return;
        }
        List<FindMatcher.Hit> hits = result.hits();
        if (hits.isEmpty()) {
            show(s, hits, -1, false);
            return;
        }
        FindMatcher.Hit current = forward ? nextFrom(hits, lastEnd) : previousFrom(hits, lastStart);
        lastStart = current.start();
        lastEnd = current.end();
        show(s, hits, lastStart, true);
    }

    /** 結果を画面に出す。current が今いる一致の始まり（なければ -1）。 */
    private void show(Searchable s, List<FindMatcher.Hit> hits, int current, boolean reveal) {
        List<EditorPane.Highlight> highlights = new ArrayList<>();
        for (int[] range : replaced) {
            highlights.add(new EditorPane.Highlight(range[0], range[1], Theme.REPLACED));
        }
        for (FindMatcher.Hit hit : hits) {
            highlights.add(new EditorPane.Highlight(hit.start(), hit.end(),
                    hit.start() == current ? Theme.MATCH_CURRENT : Theme.MATCH));
        }
        s.setHighlights(highlights);
        highlighted = currentPane.get();

        int index = -1;
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).start() == current) {
                index = i;
            }
        }
        if (findField.getText().isEmpty()) {
            setCount(" ", Theme.FG);
        } else if (hits.isEmpty()) {
            setCount("見つかりません", Theme.ERROR);
        } else {
            setCount((index + 1) + " / " + hits.size() + " 件", Theme.FG);
        }
        if (reveal && current >= 0) {
            s.reveal(current);
        }
    }

    private void setCount(String text, Color color) {
        countLabel.setText(text);
        countLabel.setForeground(color);
    }

    private void showUnsupported() {
        setCount("この文書は検索できません", Theme.ERROR);
    }

    /** 検索の欄に出すエラー（不正な正規表現など）。一致は消す。 */
    private void showError(Searchable s, String error) {
        show(s, List.of(), -1, false);
        setCount(error, Theme.ERROR);
    }

    private void clearHighlights() {
        if (highlighted instanceof Searchable s) {
            s.setHighlights(List.of());
        }
        highlighted = null;
    }

    // ---------- 置換 ----------

    /** 今いる一致箇所を置き換えて、次へ進む。ルビ記号をまたぐものは置き換えず、次へ進む。 */
    private void replaceOne() {
        Searchable s = target();
        if (s == null) {
            showUnsupported();
            return;
        }
        String keyword = findField.getText();
        String text = s.getText();
        FindMatcher.Result result = findAll(text, keyword, replaceField.getText());
        if (result.error() != null) {
            showError(s, result.error());
            return;
        }
        List<FindMatcher.Hit> hits = result.hits();
        if (hits.isEmpty()) {
            show(s, hits, -1, false);
            return;
        }
        FindMatcher.Hit hit = hitAt(hits, lastStart);
        if (hit == null || hit.end() != lastEnd) {
            move(true);                 // まだ一致箇所の上にいなければ、先に探す
            return;
        }

        if (FindMatcher.touchesRuby(text.substring(hit.start(), hit.end()), hit.replacement())) {
            move(true);                 // ルビ記号をまたぐので、置き換えずに次へ
            setCount(rubySkipped(1), Theme.FG);
            return;
        }

        int start = hit.start();
        int length = hit.replacement().length();
        int delta = length - (hit.end() - hit.start());
        internalEdit = true;
        s.replaceRange(hit.replacement(), start, hit.end());
        internalEdit = false;

        // 置換した場所より後ろにある緑の範囲は、文字数の差だけずらす
        for (int[] range : replaced) {
            if (range[0] > start) {
                range[0] += delta;
                range[1] += delta;
            }
        }
        if (length > 0) {
            replaced.add(new int[]{start, start + length});
        }
        if (selectionScope != null) {
            selectionScope[1] += delta;
        }
        lastStart = start + length;
        lastEnd = lastStart;

        result = findAll(s.getText(), keyword, replaceField.getText());
        if (result.error() != null) {
            showError(s, result.error());
            return;
        }
        hits = result.hits();
        if (hits.isEmpty()) {
            show(s, hits, -1, false);
            setCount("置換しました（残り 0 件）", Theme.FG);
            return;
        }
        FindMatcher.Hit next = nextFrom(hits, lastEnd);
        lastStart = next.start();
        lastEnd = next.end();
        show(s, hits, lastStart, true);
    }

    /** 一致するものを、すべて置き換える。ルビ記号をまたぐものは置き換えない。 */
    private void replaceAll() {
        Searchable s = target();
        if (s == null) {
            showUnsupported();
            return;
        }
        String keyword = findField.getText();
        String text = s.getText();
        FindMatcher.Result result = findAll(text, keyword, replaceField.getText());
        if (result.error() != null) {
            showError(s, result.error());
            return;
        }
        List<FindMatcher.Hit> hits = result.hits();
        if (hits.isEmpty()) {
            show(s, hits, -1, false);
            return;
        }

        // 見つけた場所だけを置き換える（全文を入れ替えると、縦書きのルビや傍点が外れてしまうため）。
        // あわせて、置き換えたあとの文章の中での「置き換えた場所」を覚えておく
        List<Searchable.Replacement> replacements = new ArrayList<>();
        replaced.clear();
        int shift = 0;                  // それまでの置き換えで、位置がどれだけずれたか
        int skipped = 0;                // ルビ記号をまたぐので、置き換えなかった数
        for (FindMatcher.Hit hit : hits) {
            if (FindMatcher.touchesRuby(text.substring(hit.start(), hit.end()), hit.replacement())) {
                skipped++;
                continue;
            }
            int length = hit.replacement().length();
            replacements.add(new Searchable.Replacement(hit.start(), hit.end(), hit.replacement()));
            if (length > 0) {
                replaced.add(new int[]{hit.start() + shift, hit.start() + shift + length});
            }
            shift += length - (hit.end() - hit.start());
        }

        if (!replacements.isEmpty()) {
            internalEdit = true;
            s.replaceRanges(replacements);
            internalEdit = false;
        }
        if (selectionScope != null) {
            selectionScope[1] += shift;
        }
        lastStart = 0;
        lastEnd = 0;

        show(s, new ArrayList<>(), -1, false);
        String message = (hits.size() - skipped) + " 件を置換しました";
        if (skipped > 0) {
            message += "（" + rubySkipped(skipped) + "）";
        }
        setCount(message, Theme.FG);
    }

    /** ルビ記号をまたぐので置き換えなかったことを伝える文。 */
    private static String rubySkipped(int count) {
        return "ルビ記号をまたぐ " + count + " 件は置換しませんでした";
    }
}
