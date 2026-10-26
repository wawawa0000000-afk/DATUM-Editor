package com.example.minieditor;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 */
public class FindBar extends JPanel {

    private final Supplier<EditorPane> currentPane;

    private final JTextField findField = new JTextField(18);
    private final JTextField replaceField = new JTextField(18);
    private final JLabel countLabel = new JLabel(" ");
    private final JButton caseButton = Theme.flatButton("Aa");
    private final JPanel replaceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

    private boolean caseSensitive = false;
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
            caseButton.setForeground(caseSensitive ? Theme.FG_STRONG : Theme.FG_DIM);
            caseButton.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(caseSensitive ? Theme.ACCENT : Theme.INPUT),
                    BorderFactory.createEmptyBorder(3, 11, 3, 11)));
            refresh(true);
        });
        caseButton.setForeground(Theme.FG_DIM);
        caseButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.INPUT),
                BorderFactory.createEmptyBorder(3, 11, 3, 11)));
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

    // ---------- 開く・閉じる ----------

    /** バーを表示する。withReplace が true なら、置換の行も出す。 */
    public void open(boolean withReplace) {
        replaceRow.setVisible(withReplace);
        setVisible(true);
        revalidate();
        findField.requestFocusInWindow();
        findField.selectAll();
        refresh(false);
    }

    public void close() {
        clearHighlights();
        replaced.clear();
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

    /** 一致する場所を、先頭から順にすべて集める。 */
    private List<Integer> findAll(String text, String keyword) {
        List<Integer> positions = new ArrayList<>();
        if (keyword.isEmpty()) {
            return positions;           // 空欄は「一致なし」にする（ここを抜かすと、無限に見つかり続ける）
        }
        if (!caseSensitive) {
            text = text.toLowerCase(Locale.ROOT);
            keyword = keyword.toLowerCase(Locale.ROOT);
        }
        int index = text.indexOf(keyword);
        while (index >= 0) {
            positions.add(index);
            index = text.indexOf(keyword, index + keyword.length());
        }
        return positions;
    }

    /** from 以上で一番近い一致。なければ先頭に戻る。 */
    private static int nextFrom(List<Integer> positions, int from) {
        for (int p : positions) {
            if (p >= from) {
                return p;
            }
        }
        return positions.get(0);
    }

    /** from より前で一番近い一致。なければ最後に戻る。 */
    private static int previousFrom(List<Integer> positions, int from) {
        for (int i = positions.size() - 1; i >= 0; i--) {
            if (positions.get(i) < from) {
                return positions.get(i);
            }
        }
        return positions.get(positions.size() - 1);
    }

    /** 今の場所を保ったまま、検索し直す。 */
    private void refresh(boolean reveal) {
        Searchable s = target();
        if (s == null) {
            showUnsupported();
            return;
        }
        String keyword = findField.getText();
        List<Integer> positions = findAll(s.getText(), keyword);
        if (positions.isEmpty()) {
            show(s, positions, keyword.length(), -1, false);
            return;
        }
        lastStart = nextFrom(positions, lastStart);
        lastEnd = lastStart + keyword.length();
        show(s, positions, keyword.length(), lastStart, reveal);
    }

    /** 次（forward が true）または前の一致へ移る。 */
    private void move(boolean forward) {
        Searchable s = target();
        if (s == null) {
            showUnsupported();
            return;
        }
        String keyword = findField.getText();
        List<Integer> positions = findAll(s.getText(), keyword);
        if (positions.isEmpty()) {
            show(s, positions, keyword.length(), -1, false);
            return;
        }
        lastStart = forward ? nextFrom(positions, lastEnd) : previousFrom(positions, lastStart);
        lastEnd = lastStart + keyword.length();
        show(s, positions, keyword.length(), lastStart, true);
    }

    /** 結果を画面に出す。current が今いる一致の始まり（なければ -1）。 */
    private void show(Searchable s, List<Integer> positions, int length, int current, boolean reveal) {
        List<EditorPane.Highlight> highlights = new ArrayList<>();
        for (int[] range : replaced) {
            highlights.add(new EditorPane.Highlight(range[0], range[1], Theme.REPLACED));
        }
        for (int start : positions) {
            highlights.add(new EditorPane.Highlight(start, start + length,
                    start == current ? Theme.MATCH_CURRENT : Theme.MATCH));
        }
        s.setHighlights(highlights);
        highlighted = currentPane.get();

        if (findField.getText().isEmpty()) {
            setCount(" ", Theme.FG);
        } else if (positions.isEmpty()) {
            setCount("見つかりません", Theme.ERROR);
        } else {
            setCount((positions.indexOf(current) + 1) + " / " + positions.size() + " 件", Theme.FG);
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

    private void clearHighlights() {
        if (highlighted instanceof Searchable s) {
            s.setHighlights(List.of());
        }
        highlighted = null;
    }

    // ---------- 置換 ----------

    /** 今いる一致箇所を置き換えて、次へ進む。 */
    private void replaceOne() {
        Searchable s = target();
        if (s == null) {
            showUnsupported();
            return;
        }
        String keyword = findField.getText();
        List<Integer> positions = findAll(s.getText(), keyword);
        if (positions.isEmpty()) {
            show(s, positions, keyword.length(), -1, false);
            return;
        }
        if (!positions.contains(lastStart) || lastEnd != lastStart + keyword.length()) {
            move(true);                 // まだ一致箇所の上にいなければ、先に探す
            return;
        }

        String replacement = replaceField.getText();
        int start = lastStart;
        int delta = replacement.length() - keyword.length();
        internalEdit = true;
        s.replaceRange(replacement, start, start + keyword.length());
        internalEdit = false;

        // 置換した場所より後ろにある緑の範囲は、文字数の差だけずらす
        for (int[] range : replaced) {
            if (range[0] > start) {
                range[0] += delta;
                range[1] += delta;
            }
        }
        if (!replacement.isEmpty()) {
            replaced.add(new int[]{start, start + replacement.length()});
        }
        lastStart = start + replacement.length();
        lastEnd = lastStart;

        positions = findAll(s.getText(), keyword);
        if (positions.isEmpty()) {
            show(s, positions, keyword.length(), -1, false);
            setCount("置換しました（残り 0 件）", Theme.FG);
            return;
        }
        lastStart = nextFrom(positions, lastEnd);
        lastEnd = lastStart + keyword.length();
        show(s, positions, keyword.length(), lastStart, true);
    }

    /** 一致するものを、すべて置き換える。 */
    private void replaceAll() {
        Searchable s = target();
        if (s == null) {
            showUnsupported();
            return;
        }
        String keyword = findField.getText();
        String text = s.getText();
        List<Integer> positions = findAll(text, keyword);
        if (positions.isEmpty()) {
            show(s, positions, keyword.length(), -1, false);
            return;
        }

        // 見つけた場所だけを置き換える（全文を入れ替えると、縦書きのルビや傍点が外れてしまうため）。
        // あわせて、置き換えたあとの文章の中での「置き換えた場所」を覚えておく
        String replacement = replaceField.getText();
        List<Searchable.Replacement> replacements = new ArrayList<>();
        replaced.clear();
        int shift = 0;                  // それまでの置き換えで、位置がどれだけずれたか
        for (int pos : positions) {
            replacements.add(new Searchable.Replacement(pos, pos + keyword.length(), replacement));
            if (!replacement.isEmpty()) {
                replaced.add(new int[]{pos + shift, pos + shift + replacement.length()});
            }
            shift += replacement.length() - keyword.length();
        }

        internalEdit = true;
        s.replaceRanges(replacements);
        internalEdit = false;
        lastStart = 0;
        lastEnd = 0;

        show(s, new ArrayList<>(), keyword.length(), -1, false);
        setCount(positions.size() + " 件を置換しました", Theme.FG);
    }
}
