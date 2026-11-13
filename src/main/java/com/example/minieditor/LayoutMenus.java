package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.function.Consumer;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * 「書式」メニューのうち、文章の並べ方を決めるサブメニュー
 * （原稿用紙の字数・行数、行間・字間・余白、横の行の高さ、打つ位置）。EditorFrame から切り出した。
 * 選んだ値を覚え、開いている文書にすぐ反映する。
 */
final class LayoutMenus {

    /** 画面（EditorFrame）の側に頼むこと。 */
    interface Host {
        /** 数を指定する画面を出すときの親。 */
        Component parent();

        AppSettings settings();

        /** 開いている文書の、すべての表示（分割で同じ文書を映しているものも）に apply を当てる。 */
        void forEachOpenView(Consumer<EditorPane> apply);

        /** ステータスバーに、数秒だけお知らせを出す。 */
        void flash(String text);

        /** 並べ方が変わった（ステータスバーの枚数などを出し直す）。 */
        void layoutChanged();

        /** 原稿用紙の字数・行数が変わった（メニューの名前と、覚えている枚数を直す）。 */
        void sheetSizeChanged();
    }

    private final Host host;

    LayoutMenus(Host host) {
        this.host = host;
    }

    /** 原稿用紙の見出し（例「原稿用紙（25字×20行）」）。 */
    static String sheetLabel() {
        return "原稿用紙（" + SheetSize.chars() + "字×" + SheetSize.lines() + "行）";
    }

    /** 原稿用紙の字数（chars が真）または行数を選ぶサブメニュー。今の値にチェックが付く。 */
    JMenu sheetSizeMenu(String title, boolean chars) {
        JMenu menu = new JMenu(title);
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        int current = chars ? SheetSize.chars() : SheetSize.lines();
        for (int value : SheetSize.CHOICES) {
            javax.swing.JRadioButtonMenuItem sizeItem = new javax.swing.JRadioButtonMenuItem(
                    value + (chars ? " 字" : " 行"), value == current);
            sizeItem.addActionListener(e -> setSheetSize(chars, value));
            group.add(sizeItem);
            menu.add(sizeItem);
        }
        return menu;
    }

    /** 行間・字間・余白の段階（狭い・標準・広い）を選ぶサブメニュー。kind は 0 縦の行間・1 縦の字間・2 余白。今の段階にチェックが付く。 */
    JMenu spacingMenu(String title, int kind) {
        JMenu menu = new JMenu(title);
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        int current = kind == 0 ? Spacing.line() : kind == 1 ? Spacing.chars() : Spacing.margin();
        for (int level = 0; level < Spacing.NAMES.length; level++) {
            int value = level;
            javax.swing.JRadioButtonMenuItem levelItem = new javax.swing.JRadioButtonMenuItem(
                    Spacing.NAMES[level], level == current);
            EditorFrame.withIcon(levelItem, ToolIcons.spacing(kind, level));      // 文字を読む前に、形で広さの違いがわかるように
            levelItem.addActionListener(e -> setSpacing(kind, value));
            group.add(levelItem);
            menu.add(levelItem);
        }
        return menu;
    }

    /** 横書きの行の高さを選ぶサブメニュー（よく使う倍率と「指定…」）。選び直すたびに、チェックと「指定」の表示を作り直す。 */
    final JMenu rowHeightMenu = new JMenu("横の行の高さ");

    void rebuildRowHeightMenu() {
        rowHeightMenu.removeAll();
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        int current = Spacing.row();
        boolean preset = false;
        for (int value : Spacing.ROW_CHOICES) {
            String label = Spacing.rowLabel(value) + (value == Spacing.ROW_MIN ? "（標準）" : "");
            javax.swing.JRadioButtonMenuItem rowItem = new javax.swing.JRadioButtonMenuItem(label, value == current);
            rowItem.addActionListener(e -> setRowHeight(value));
            group.add(rowItem);
            rowHeightMenu.add(rowItem);
            preset |= value == current;
        }
        rowHeightMenu.addSeparator();
        // よく使う倍率のどれでもないときは、「指定」に今の倍率を出してチェックを付ける
        javax.swing.JRadioButtonMenuItem customItem = new javax.swing.JRadioButtonMenuItem(
                preset ? "指定…" : "指定（" + Spacing.rowLabel(current) + "）…", !preset);
        customItem.addActionListener(e -> askRowHeight());
        group.add(customItem);
        rowHeightMenu.add(customItem);
    }

    /** 行の高さを、数で指定してもらう（1.0〜3.0 倍、0.05 刻み）。 */
    private void askRowHeight() {
        javax.swing.SpinnerNumberModel model = new javax.swing.SpinnerNumberModel(
                Spacing.row() / 100.0, Spacing.ROW_MIN / 100.0, Spacing.ROW_MAX / 100.0, 0.05);
        javax.swing.JSpinner spinner = new javax.swing.JSpinner(model);
        spinner.setEditor(new javax.swing.JSpinner.NumberEditor(spinner, "0.0#"));
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(new JLabel("横書きの行の高さ（文字の高さの何倍か。1.0 が標準、3.0 まで）"), BorderLayout.NORTH);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(spinner);
        row.add(new JLabel(" 倍"));
        panel.add(row, BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(host.parent(), panel, "横の行の高さ",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            try {
                spinner.commitEdit();           // 打ち込んだまま OK を押したときも、その数を使う
            } catch (java.text.ParseException ex) {
                // 数として読めないときは、矢印で選んでいた値を使う
            }
            setRowHeight((int) Math.round(((Number) spinner.getValue()).doubleValue() * 100));
        } else {
            rebuildRowHeightMenu();             // チェックを、今の倍率の所に戻す
        }
    }

    /** 横書きの行の高さを変えて覚える。開いている横書きの文書（すべて）を、すぐ組み直す。 */
    private void setRowHeight(int percent) {
        Spacing.setRow(percent);
        Spacing.save(host.settings());
        host.settings().save();
        rebuildRowHeightMenu();
        host.forEachOpenView(view -> {
            if (view instanceof TextEditorPane horizontal) {
                horizontal.applyRowHeight();
            }
        });
    }

    /** 横書きの打つ位置のサブメニュー（固定するか・高さ・上へ書くか）。選び直すたびに、チェックと「指定」の表示を作り直す。 */
    final JMenu typingMenu = new JMenu("打つ位置");

    void rebuildTypingMenu() {
        typingMenu.removeAll();
        JCheckBoxMenuItem fixedItem = new JCheckBoxMenuItem("打つ行を画面の同じ高さに保つ", TypingPosition.fixed());
        fixedItem.addActionListener(e -> {
            TypingPosition.setFixed(fixedItem.isSelected());
            saveTypingPosition();
        });
        typingMenu.add(fixedItem);
        typingMenu.addSeparator();
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        int current = TypingPosition.position();
        boolean preset = false;
        for (int i = 0; i < TypingPosition.CHOICES.length; i++) {
            int value = TypingPosition.CHOICES[i];
            javax.swing.JRadioButtonMenuItem positionItem = new javax.swing.JRadioButtonMenuItem(
                    TypingPosition.NAMES[i] + "（上から " + value + "%）", value == current);
            positionItem.addActionListener(e -> setTypingPosition(value));
            group.add(positionItem);
            typingMenu.add(positionItem);
            preset |= value == current;
        }
        // 並べた高さのどれでもないときは、「指定」に今の高さを出してチェックを付ける
        javax.swing.JRadioButtonMenuItem customItem = new javax.swing.JRadioButtonMenuItem(
                preset ? "高さを指定…" : "高さを指定（上から " + current + "%）…", !preset);
        customItem.addActionListener(e -> askTypingPosition());
        group.add(customItem);
        typingMenu.add(customItem);
        typingMenu.addSeparator();
        JCheckBoxMenuItem upwardItem = new JCheckBoxMenuItem("新しい行を上に表示する（下から上へ書く）", TypingPosition.upward());
        upwardItem.addActionListener(e -> {
            TypingPosition.setUpward(upwardItem.isSelected());
            saveTypingPosition();
            host.flash(upwardItem.isSelected()
                    ? "行を下から上へ並べて表示します（保存する中身の順番は、そのままです）"
                    : "行を、ふつうの順番で表示します");
        });
        typingMenu.add(upwardItem);
    }

    /** 打つ位置の高さを、数で指定してもらう（画面の上から 10〜90%）。 */
    private void askTypingPosition() {
        javax.swing.SpinnerNumberModel model = new javax.swing.SpinnerNumberModel(
                TypingPosition.position(), TypingPosition.MIN, TypingPosition.MAX, 5);
        javax.swing.JSpinner spinner = new javax.swing.JSpinner(model);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(new JLabel("打つ行を置く高さ（画面の上から何％の所か。50 が真ん中）"), BorderLayout.NORTH);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(spinner);
        row.add(new JLabel(" %"));
        panel.add(row, BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(host.parent(), panel, "打つ位置の高さ",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            try {
                spinner.commitEdit();           // 打ち込んだまま OK を押したときも、その数を使う
            } catch (java.text.ParseException ex) {
                // 数として読めないときは、矢印で選んでいた値を使う
            }
            setTypingPosition(((Number) spinner.getValue()).intValue());
        } else {
            rebuildTypingMenu();                // チェックを、今の高さの所に戻す
        }
    }

    /** 打つ位置の高さを変える。高さを選んだら、固定も入れる（選んだのに動かない、とならないように）。 */
    private void setTypingPosition(int percent) {
        TypingPosition.setPosition(percent);
        TypingPosition.setFixed(true);
        saveTypingPosition();
    }

    /** 打つ位置の設定を覚え、メニューを作り直し、開いている横書きの文書（すべて）にすぐ反映する。 */
    private void saveTypingPosition() {
        TypingPosition.save(host.settings());
        host.settings().save();
        rebuildTypingMenu();
        host.forEachOpenView(view -> {
            if (view instanceof TextEditorPane horizontal) {
                horizontal.applyTypingPosition();
            }
        });
    }

    /** 行間・字間・余白の段階を変えて覚える。開いている文書（すべて）の並べ方と余白を、すぐ描き直す。 */
    private void setSpacing(int kind, int level) {
        if (kind == 0) {
            Spacing.setLine(level);
        } else if (kind == 1) {
            Spacing.setChars(level);
        } else {
            Spacing.setMargin(level);
        }
        Spacing.save(host.settings());
        host.settings().save();
        host.forEachOpenView(view -> {
            if (view instanceof VerticalTextPane vertical) {
                vertical.refreshLayout();
            } else if (view instanceof TextEditorPane horizontal) {
                horizontal.applyMargin();
            }
        });
        host.layoutChanged();
    }

    /** 原稿用紙の字数または行数を変えて覚える。開いている縦書きの文書（すべて）を描き直し、枚数の表示も直す。 */
    private void setSheetSize(boolean chars, int value) {
        if (chars) {
            SheetSize.setChars(value);
        } else {
            SheetSize.setLines(value);
        }
        SheetSize.save(host.settings());
        host.settings().save();
        host.sheetSizeChanged();
        host.forEachOpenView(view -> {
            if (view instanceof VerticalTextPane vertical) {
                vertical.setManuscript(vertical.isManuscript());
            }
        });
        host.layoutChanged();
    }
}
