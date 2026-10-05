package com.example.minieditor;

import java.awt.Font;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;


/**
 * メインウィンドウ。テキストエリアを置いて、ウィンドウの設定をする。
 */
public class EditorFrame extends JFrame {

    private final JTextArea area = new JTextArea();             //ライブラリにあるテキストエリアをインスタンス化
    private Path currentPath = null;
    private boolean dirty = false;
    private final FileService fileService = new FileService();

    public EditorFrame() {
        super("DATUM-Editor");  // ソフト名表示

        // テキストを入力する部品。フォントは部品（JTextArea）ごとに設定する。               
        area.setFont(new Font("Monospaced", Font.PLAIN, 48));
        area.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { markDirty(); }
            public void removeUpdate(DocumentEvent e) { markDirty(); }
            public void changedUpdate(DocumentEvent e) { markDirty(); }
        });

        // 文章が長くなったときにスクロールできるよう、JScrollPane で包んで追加する。
        add(new JScrollPane(area));                             //フレームにテキストエリアを追加

        // メニューバー
        setJMenuBar(createMenuBar());

        setSize(1200, 600);                                     //フレームサイズの指定
        // ×ボタンを押しても、勝手には閉じない
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (confirmClose()) {
                    System.exit(0);     // 閉じて良いときだけ、プログラムを終了する
                }
            }
        });
        updateTitle();
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("ファイル");
        JMenuItem saveItem = new JMenuItem("保存");
        JMenuItem saveAsItem = new JMenuItem("名前を付けて保存");
        
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        saveItem.addActionListener(e -> saveFile());

        fileMenu.setFont(new Font("Dialog", Font.PLAIN, 24));
        saveItem.setFont(new Font("Dialog", Font.PLAIN, 24));
        saveAsItem.setFont(new Font("Dialog", Font.PLAIN, 24));
        setDialogFont(22);

        menuBar.add(fileMenu);
        fileMenu.add(saveItem);
        fileMenu.add(saveAsItem);

        saveAsItem.addActionListener(e -> saveFileAs());
        
        return menuBar;
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

    private void saveFile() {
        if (currentPath == null) {
            saveFileAs();
            return;
        }

        try{
            fileService.save(area.getText(), currentPath);
            markSaved();
            JOptionPane.showMessageDialog(this, "保存しました: " + currentPath.toAbsolutePath());
        } 
        catch (IOException ex) {
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
        } 
        catch (IOException ex) {
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

    private boolean confirmClose() {
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
