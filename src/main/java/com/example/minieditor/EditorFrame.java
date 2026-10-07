package com.example.minieditor;

import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.undo.UndoManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.BoxLayout;

/**
 * メインウィンドウ。テキストエリアを置いて、ウィンドウの設定をする。
 */
public class EditorFrame extends JFrame {

    // 背景は黒、1行おきに黒灰色の縞を入れる
    private final JTextArea area = new StripedTextArea(Color.BLACK, new Color(40, 40, 40));
    private Path currentPath = null;
    private boolean dirty = false;
    private final FileService fileService = new FileService();
    private final UndoManager undoManager = new UndoManager();
    private final JPanel searchPanel = new JPanel();
    private final JPanel findBar = new JPanel();
    private final JPanel replaceBar = new JPanel();
    private final JTextField findField = new JTextField(20);
    private final JTextField replaceField = new JTextField(20);
    private final JLabel countLabel = new JLabel();
    private final JLabel statusBar = new JLabel();
    private int lastMatchStart = 0;
    private int lastMatchEnd = 0;

    public EditorFrame() {
        super("DATUM-Editor");  // ソフト名表示

        // テキストを入力する部品。フォントは部品（JTextArea）ごとに設定する。               
        area.setFont(new Font("Monospaced", Font.PLAIN, 48));
        area.setForeground(Color.WHITE);    // 文字の色を白にする
        area.setCaretColor(Color.WHITE);    // カーソルの色も白にする（黒背景だと見えなくなるため）

        area.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                markDirty();
                updateStatusBar();
            }

            public void removeUpdate(DocumentEvent e) {
                markDirty();
                updateStatusBar();
            }

            public void changedUpdate(DocumentEvent e) {
                markDirty();
                updateStatusBar();
            }
        });

        // カーソルが動くたびに、ステータスバーの行・列を更新する。
        area.addCaretListener(e -> updateStatusBar());

        // 文字を入力・削除するたびに、その操作が履歴に入ります。
        area.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

        // 文章が長くなったときにスクロールできるよう、JScrollPane で包んで追加する。
        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.getViewport().setBackground(Color.BLACK);   // スクロール時に白が見えないようにする
        scrollPane.setBorder(null);                             // ふちの灰色の枠線を消す
        add(scrollPane);                                        //フレームにテキストエリアを追加
        createFindBar();

        // ステータスバー（行番号・列番号・文字数）
        statusBar.setForeground(Color.WHITE);
        statusBar.setBackground(Color.BLACK);
        statusBar.setOpaque(true);
        add(statusBar, BorderLayout.SOUTH);

        // メニューバー
        setJMenuBar(createMenuBar());

        getContentPane().setBackground(Color.BLACK);   // ウィンドウの余白部分も黒にする

        setSize(1200, 600);                                     //フレームサイズの指定
        // ×ボタンを押しても、勝手には閉じない
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitApp();
            }
        });
        updateTitle();
        updateStatusBar();
    }

    // 閉じてよいか確認してから、プログラムを終了する
    private void exitApp() {
        if (confirmDiscardChanges()) {
            System.exit(0);
        }
    }

    // 行番号・列番号・文字数を表示する
    private void updateStatusBar() {
        int caretPos = area.getCaretPosition();
        int line = 1;
        int column = 1;
        try {
            line = area.getLineOfOffset(caretPos) + 1;
            column = caretPos - area.getLineStartOffset(line - 1) + 1;
        } catch (BadLocationException ex) {
            // 通常は起きない
        }
        int length = area.getText().length();
        statusBar.setText(String.format("  行 %d, 列 %d　|　%d 文字", line, column, length));
    }

    private void createFindBar() {
        JButton closeButton = new JButton("X");
        closeButton.addActionListener(e -> hideFindBar());

        JLabel findLabel = new JLabel("検索:");
        findLabel.setForeground(Color.WHITE);
        findField.setBackground(new Color(40, 40, 40));
        findField.setForeground(Color.WHITE);
        findField.setCaretColor(Color.WHITE);

        countLabel.setForeground(Color.WHITE);

        findBar.setBackground(Color.BLACK);
        findBar.add(findLabel);
        findBar.add(findField);
        findBar.add(countLabel);
        findBar.add(closeButton);

        findField.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFind");
        findField.getActionMap().put("closeFind", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                hideFindBar();
            }
        });
        findField.addActionListener(e -> findNext());

        findField.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "findPrevious");
        findField.getActionMap().put("findPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findPrevious();
            }
        });

        // 置換の行
        JLabel replaceLabel = new JLabel("置換:");
        replaceLabel.setForeground(Color.WHITE);
        replaceField.setBackground(new Color(40, 40, 40));
        replaceField.setForeground(Color.WHITE);
        replaceField.setCaretColor(Color.WHITE);

        JButton replaceOneButton = new JButton("置換");
        replaceOneButton.addActionListener(e -> replaceOne());

        JButton replaceAllButton = new JButton("すべて置換");
        replaceAllButton.addActionListener(e -> replaceAll());

        replaceBar.setBackground(Color.BLACK);
        replaceBar.add(replaceLabel);
        replaceBar.add(replaceField);
        replaceBar.add(replaceOneButton);
        replaceBar.add(replaceAllButton);
        replaceBar.setVisible(false);

        replaceField.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFind");
        replaceField.getActionMap().put("closeFind", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                hideFindBar();
            }
        });
        replaceField.addActionListener(e -> replaceOne());

        // 検索の行と、置換の行を、縦に並べる
        searchPanel.setLayout(new BoxLayout(searchPanel, BoxLayout.Y_AXIS));
        searchPanel.setBackground(Color.BLACK);
        searchPanel.add(findBar);
        searchPanel.add(replaceBar);
        searchPanel.setVisible(false);
        add(searchPanel, BorderLayout.NORTH);
    }

    private void toggleFindBar() {
        if (searchPanel.isVisible() && !replaceBar.isVisible()) {
            hideFindBar();
        } else {
            showFindBar();
        }
    }

    private void showFindBar() {
        searchPanel.setVisible(true);
        replaceBar.setVisible(false);
        findField.requestFocusInWindow();
    }

    private void hideFindBar() {
        searchPanel.setVisible(false);
        replaceBar.setVisible(false);
        area.requestFocusInWindow();
    }

    private void toggleReplaceBar() {
        searchPanel.setVisible(true);
        replaceBar.setVisible(!replaceBar.isVisible());
        if (replaceBar.isVisible()) {
            replaceField.requestFocusInWindow();
        } else {
            findField.requestFocusInWindow();
        }
    }

    // 今、ハイライトしている場所を、置換欄の文字で置き換える
    private void replaceOne() {
        String keyword = findField.getText();
        if (keyword.isEmpty()) {
            return;
        }
        String text = area.getText();
        boolean hasCurrentMatch = lastMatchEnd > lastMatchStart
                && lastMatchEnd <= text.length()
                && text.substring(lastMatchStart, lastMatchEnd).equals(keyword);

        if (!hasCurrentMatch) {
            findNext();          // まだ今の場所が一致していなければ、先に探す
            return;
        }

        String replacement = replaceField.getText();
        area.replaceRange(replacement, lastMatchStart, lastMatchEnd);
        lastMatchEnd = lastMatchStart + replacement.length();
        findNext();              // 置換した続きから、次を探す
    }

    // 一致するものを、すべて置き換える
    private void replaceAll() {
        String keyword = findField.getText();
        if (keyword.isEmpty()) {
            return;
        }
        String text = area.getText();
        List<Integer> positions = findAllMatches(text, keyword);
        if (positions.isEmpty()) {
            countLabel.setText("見つかりません");
            return;
        }

        String replacement = replaceField.getText();
        area.setText(text.replace(keyword, replacement));
        countLabel.setText(positions.size() + " 件を置換しました");
        lastMatchStart = 0;
        lastMatchEnd = 0;
    }

    private void findNext() {
        search(true);
    }

    private void findPrevious() {
        search(false);
    }

    // forward が true なら次へ、false なら前へ探す
    private void search(boolean forward) {
        String keyword = findField.getText();
        String text = area.getText();
        List<Integer> positions = findAllMatches(text, keyword);

        if (positions.isEmpty()) {
            countLabel.setText("見つかりません");
            return;
        }

        int index = forward
                ? nextIndexFrom(positions, lastMatchEnd)
                : previousIndexFrom(positions, lastMatchStart);

        int rank = positions.indexOf(index) + 1;
        countLabel.setText(rank + " / " + positions.size() + " 件");

        lastMatchStart = index;
        lastMatchEnd = index + keyword.length();
        highlightMatch(lastMatchStart, lastMatchEnd);
    }

    // 一致する場所を、すべて先頭から順に集める
    private List<Integer> findAllMatches(String text, String keyword) {
        List<Integer> positions = new ArrayList<>();
        if (keyword.isEmpty()) {
            return positions;             // 空欄なら、何も一致しないことにする
        }
        int index = text.indexOf(keyword);
        while (index >= 0) {
            positions.add(index);
            index = text.indexOf(keyword, index + keyword.length());
        }
        return positions;
    }

    // from 以上で、一番近い一致を探す。なければ先頭に戻る
    private int nextIndexFrom(List<Integer> positions, int from) {
        for (int p : positions) {
            if (p >= from) {
                return p;
            }
        }
        return positions.get(0);
    }

    // from より前で、一番近い一致を探す。なければ最後に戻る
    private int previousIndexFrom(List<Integer> positions, int from) {
        for (int i = positions.size() - 1; i >= 0; i--) {
            if (positions.get(i) < from) {
                return positions.get(i);
            }
        }
        return positions.get(positions.size() - 1);
    }

    // 見つかった場所に、直接色を塗る
    private void highlightMatch(int start, int end) {
        try {
            area.getHighlighter().removeAllHighlights();
            area.getHighlighter().addHighlight(start, end,
                    new DefaultHighlighter.DefaultHighlightPainter(new Color(0, 200, 255))); // 水色
            area.setCaretPosition(end);
        } catch (BadLocationException ex) {
            // text.indexOf で得た位置なので、通常は起きない
        }
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("ファイル");
        JMenuItem saveItem = new JMenuItem("保存");
        JMenuItem newItem = new JMenuItem("新規");
        JMenuItem saveAsItem = new JMenuItem("名前を付けて保存");
        JMenuItem openItem = new JMenuItem("開く");
        JMenuItem exitItem = new JMenuItem("終了");

        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        saveItem.addActionListener(e -> saveFile());

        newItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        newItem.addActionListener(e -> newFile());

        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        openItem.addActionListener(e -> openFile());

        exitItem.addActionListener(e -> exitApp());

        fileMenu.setFont(new Font("Dialog", Font.PLAIN, 24));
        saveItem.setFont(new Font("Dialog", Font.PLAIN, 24));
        newItem.setFont(new Font("Dialog", Font.PLAIN, 24));
        saveAsItem.setFont(new Font("Dialog", Font.PLAIN, 24));
        openItem.setFont(new Font("Dialog", Font.PLAIN, 24));
        exitItem.setFont(new Font("Dialog", Font.PLAIN, 24));

        setDialogFont(22);

        // メニューバーと、その中の項目を黒地に白文字にする
        menuBar.setBackground(Color.BLACK);
        for (JMenuItem item : new JMenuItem[]{fileMenu, saveItem, newItem, saveAsItem, openItem, exitItem}) {
            item.setBackground(Color.BLACK);
            item.setForeground(Color.WHITE);
        }

        menuBar.add(fileMenu);
        menuBar.add(createEditMenu());
        menuBar.add(createFormatMenu());
        fileMenu.add(newItem);
        fileMenu.add(openItem);
        fileMenu.add(saveItem);
        fileMenu.add(saveAsItem);
        fileMenu.add(exitItem);

        saveAsItem.addActionListener(e -> saveFileAs());

        return menuBar;
    }

    private JMenu createEditMenu() {
        JMenu editMenu = new JMenu("編集");
        editMenu.setFont(new Font("Dialog", Font.PLAIN, 24));
        editMenu.setBackground(Color.BLACK);
        editMenu.setForeground(Color.WHITE);

        JMenuItem undoItem = new JMenuItem("元に戻す");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK));
        undoItem.addActionListener(e -> {
            if (undoManager.canUndo()) {
                undoManager.undo();
        
            }});

        JMenuItem redoItem = new JMenuItem("やり直し");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK));
        redoItem.addActionListener(e -> {
            if (undoManager.canRedo()) {
                undoManager.redo();
        
            }});

        JMenuItem cutItem = new JMenuItem("切り取り");
        cutItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK));
        cutItem.addActionListener(e -> area.cut());

        JMenuItem copyItem = new JMenuItem("コピー");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK));
        copyItem.addActionListener(e -> area.copy());

        JMenuItem pasteItem = new JMenuItem("貼り付け");
        pasteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK));
        pasteItem.addActionListener(e -> area.paste());

        JMenuItem selectAllItem = new JMenuItem("すべて選択");
        selectAllItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK));
        selectAllItem.addActionListener(e -> area.selectAll());

        JMenuItem findItem = new JMenuItem("検索");
        findItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));
        findItem.addActionListener(e -> toggleFindBar());

        JMenuItem replaceItem = new JMenuItem("置換");
        replaceItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK));
        replaceItem.addActionListener(e -> toggleReplaceBar());

        for (JMenuItem item : new JMenuItem[]{undoItem, redoItem, cutItem, copyItem, pasteItem, selectAllItem, findItem, replaceItem}) {
            item.setFont(new Font("Dialog", Font.PLAIN, 24));
            item.setBackground(Color.BLACK);
            item.setForeground(Color.WHITE);
            editMenu.add(item);
        }

        return editMenu;
    }

    private JMenu createFormatMenu() {
        JMenu formatMenu = new JMenu("書式");
        formatMenu.setFont(new Font("Dialog", Font.PLAIN, 24));
        formatMenu.setBackground(Color.BLACK);
        formatMenu.setForeground(Color.WHITE);

        JMenuItem fontItem = new JMenuItem("フォント変更");
        fontItem.addActionListener(e -> chooseFont());

        JCheckBoxMenuItem wrapItem = new JCheckBoxMenuItem("折り返し");
        wrapItem.addActionListener(e -> {
            area.setLineWrap(wrapItem.isSelected());
            area.setWrapStyleWord(true);
        });

        for (JMenuItem item : new JMenuItem[]{fontItem, wrapItem}) {
            item.setFont(new Font("Dialog", Font.PLAIN, 24));
            item.setBackground(Color.BLACK);
            item.setForeground(Color.WHITE);
            formatMenu.add(item);
        }
        return formatMenu;
    }

    // フォントの種類とサイズを選ぶダイアログ
    private void chooseFont() {
        String[] fontNames = {"Monospaced", "SansSerif", "Serif"};
        Integer[] sizes = {14, 18, 24, 32, 40, 48, 64};

        JComboBox<String> nameBox = new JComboBox<>(fontNames);
        nameBox.setSelectedItem(area.getFont().getFamily());
        JComboBox<Integer> sizeBox = new JComboBox<>(sizes);
        sizeBox.setSelectedItem(area.getFont().getSize());

        JPanel panel = new JPanel();
        panel.add(new JLabel("フォント:"));
        panel.add(nameBox);
        panel.add(new JLabel("サイズ:"));
        panel.add(sizeBox);

        int result = JOptionPane.showConfirmDialog(this, panel, "フォント変更",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            String name = (String) nameBox.getSelectedItem();
            int size = (Integer) sizeBox.getSelectedItem();
            area.setFont(new Font(name, Font.PLAIN, size));
        }
    }

    private void setDialogFont(int size) {
        Font font = new Font("Dialog", Font.PLAIN, size);
        UIManager.put("OptionPane.messageFont", font);   // 確認・エラーダイアログの本文
        UIManager.put("OptionPane.buttonFont", font);    // ダイアログのボタン
        UIManager.put("Label.font", font);               // ラベル（「ファイル名」「ファイルの種類」など）
        UIManager.put("Button.font", font);              // 「保存」「キャンセル」などのボタン
        UIManager.put("ComboBox.font", font);            // ファイルの種類の選択欄
        UIManager.put("List.font", font);                // ファイル一覧
        UIManager.put("Table.font", font);               // 詳細表示のファイル一覧
        UIManager.put("TextField.font", font);           // ファイル名の入力欄
        UIManager.put("Menu.font", font);                // メニューの文字
    }

    private void markDirty() {
        dirty = true;
        updateTitle();
    }

    private void markSaved() {
        dirty = false;
        updateTitle();
    }

    private void updateTitle() {
        String name = (currentPath == null) ? "無題" : currentPath.getFileName().toString();
        String mark = dirty ? " *" : "";
        setTitle(name + mark + " - DATUM-Editor");
    }

    private void newFile() {
        if (!confirmDiscardChanges()) {
            return;                     // 「キャンセル」なら何もしない
        }
        area.setText("");               // テキストを空にする
        undoManager.discardAllEdits();  // 入れ替えた操作は、元に戻す対象から外す
        currentPath = null;             // 保存先をリセットする（次の保存は名前を付けて保存になる）
        markSaved();                    // 未保存マークを消す（setText で dirty が立つので、最後に戻す）
    }

    private void openFile() {
        if (!confirmDiscardChanges()) {
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        int result = fileChooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path path = fileChooser.getSelectedFile().toPath();
        try {
            String text = fileService.read(path);
            area.setText(text);                 // 中身をテキストエリアに入れる
            undoManager.discardAllEdits();      // 入れ替えた操作は、元に戻す対象から外す
            currentPath = path;                 // 開いたファイルを保存先として覚える
            markSaved();                        // setText で dirty が立つので、最後に戻す
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "開けませんでした: " + ex.getMessage(),
                    "エラー",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveFile() {
        if (currentPath == null) {
            saveFileAs();
            return;
        }

        try {
            fileService.save(area.getText(), currentPath);
            markSaved();
            JOptionPane.showMessageDialog(this, "保存しました: " + currentPath.toAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "保存できませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    // 保存に関するUIと振る舞いの記述
    private void saveFileAs() {
        JFileChooser fileChooser = new JFileChooser();
        FileNameExtensionFilter txtFilter = new FileNameExtensionFilter("テキスト(*.txt)", "txt");
        FileNameExtensionFilter jsonFilter = new FileNameExtensionFilter("JSON(*.json)", "json");

        fileChooser.addChoosableFileFilter(txtFilter);
        fileChooser.addChoosableFileFilter(jsonFilter);
        fileChooser.setFileFilter(txtFilter);               // 初期の形式
        fileChooser.setAcceptAllFileFilterUsed(false);      // すべてのファイルは出さない

        int result = fileChooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        FileNameExtensionFilter selected = (FileNameExtensionFilter) fileChooser.getFileFilter();
        String ext = selected.getExtensions()[0];
        String name = fileChooser.getSelectedFile().toString();
        if (!name.toLowerCase().endsWith("." + ext)) {
            name = name + "." + ext;
        }

        Path path = Path.of(name);
        if (!confirmOverwrite(path)) {
            return;
        }
        // 中身を保存する。失敗したらエラーを表示する
        try {
            fileService.save(area.getText(), path);
            currentPath = path;
            markSaved();
            JOptionPane.showMessageDialog(this, "保存しました: " + path.toAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "保存できませんでした: " + ex.getMessage(),
                    "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }

    // 保存の際同じ名前のファイルがないかの判定
    private boolean confirmOverwrite(Path path) {
        if (!path.toFile().exists()) {
            return true;            // 同名なし　判定OK
        }
        int answer = JOptionPane.showConfirmDialog(this,
                path.getFileName() + " は既にあります。上書きしますか？",
                "上書きの確認",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return answer == JOptionPane.YES_OPTION;    // はい の場合だけ true
    }

    private boolean confirmDiscardChanges() {
        if (!dirty) {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "変更を保存しますか？",
                "確認",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (answer == JOptionPane.YES_OPTION) {
            saveFile();         // 保存する
            return !dirty;      // 保存に成功して dirty が false になったときだけ閉じる
        }
        if (answer == JOptionPane.NO_OPTION) {
            return true;        // 保存せずに閉じる
        }
        return false;           // キャンセル、またはダイアログを閉じた場合は閉じない
    }
}
