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
    private final JPanel findBar = new JPanel();
    private final JTextField findField = new JTextField(20);
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
            }

            public void removeUpdate(DocumentEvent e) {
                markDirty();
            }

            public void changedUpdate(DocumentEvent e) {
                markDirty();
            }
        });

        // 文字を入力・削除するたびに、その操作が履歴に入ります。
        area.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

        // 文章が長くなったときにスクロールできるよう、JScrollPane で包んで追加する。
        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.getViewport().setBackground(Color.BLACK);   // スクロール時に白が見えないようにする
        scrollPane.setBorder(null);                             // ふちの灰色の枠線を消す
        add(scrollPane);                                        //フレームにテキストエリアを追加
        createFindBar();

        // メニューバー
        setJMenuBar(createMenuBar());

        getContentPane().setBackground(Color.BLACK);   // ウィンドウの余白部分も黒にする

        setSize(1200, 600);                                     //フレームサイズの指定
        // ×ボタンを押しても、勝手には閉じない
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (confirmDiscardChanges()) {
                    System.exit(0);     // 閉じて良いときだけ、プログラムを終了する
                }
            }
        });
        updateTitle();
    }

    private void createFindBar() {
        JButton closeButton = new JButton("閉じる");
        closeButton.addActionListener(e -> hideFindBar());

        JLabel findLabel = new JLabel("検索:");
        findLabel.setForeground(Color.WHITE);
        findField.setBackground(new Color(40, 40, 40));
        findField.setForeground(Color.WHITE);
        findField.setCaretColor(Color.WHITE);

        findBar.setBackground(Color.BLACK);
        findBar.add(findLabel);
        findBar.add(findField);
        findBar.add(closeButton);
        findBar.setVisible(false);
        add(findBar, BorderLayout.NORTH);
        
        findField.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFind");
        findField.getActionMap().put("closeFind", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                hideFindBar();
            }
        });
        findField.addActionListener(e -> findNext());
    }

    private void toggleFindBar() {
        if (findBar.isVisible()) {
            hideFindBar();
        }
        else {
            showFindBar();
        }
    }

    private  void showFindBar() {
        findBar.setVisible(true);
        findField.requestFocusInWindow();
    }

    private void hideFindBar() {
        findBar.setVisible(false);
        area.requestFocusInWindow();
    }

    private void findNext() {
        String keyword = findField.getText();
        if (keyword.isEmpty()) {
            return;
        }

        String text = area.getText();
        int index = text.indexOf(keyword, lastMatchEnd);
        if (index < 0) {
            index = text.indexOf(keyword, 0);        // 先頭から探し直す（ループ）
        }
        if (index < 0) {
            JOptionPane.showMessageDialog(this, "見つかりません: " + keyword);
            return;
        }

        highlightMatch(index, index + keyword.length());
        lastMatchEnd = index + keyword.length();
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

        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        saveItem.addActionListener(e -> saveFile());

        newItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK));
        newItem.addActionListener(e -> newFile());

        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        openItem.addActionListener(e -> openFile());

        fileMenu.setFont(new Font("Dialog", Font.PLAIN, 24));
        saveItem.setFont(new Font("Dialog", Font.PLAIN, 24));
        newItem.setFont(new Font("Dialog", Font.PLAIN, 24));
        saveAsItem.setFont(new Font("Dialog", Font.PLAIN, 24));
        openItem.setFont(new Font("Dialog", Font.PLAIN, 24));

        setDialogFont(22);

        // メニューバーと、その中の項目を黒地に白文字にする
        menuBar.setBackground(Color.BLACK);
        for (JMenuItem item : new JMenuItem[]{fileMenu, saveItem, newItem, saveAsItem, openItem}) {
            item.setBackground(Color.BLACK);
            item.setForeground(Color.WHITE);
        }

        menuBar.add(fileMenu);
        menuBar.add(createEditMenu());
        fileMenu.add(newItem);
        fileMenu.add(openItem);
        fileMenu.add(saveItem);
        fileMenu.add(saveAsItem);

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

        for (JMenuItem item : new JMenuItem[]{undoItem, redoItem, cutItem, copyItem, pasteItem, selectAllItem, findItem}) {
            item.setFont(new Font("Dialog", Font.PLAIN, 24));
            item.setBackground(Color.BLACK);
            item.setForeground(Color.WHITE);
            editMenu.add(item);
        }

        return editMenu;
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
