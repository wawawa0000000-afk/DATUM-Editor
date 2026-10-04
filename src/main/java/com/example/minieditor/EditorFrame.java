package com.example.minieditor;

import java.awt.Font;

import javax.swing.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;


/**
 * メインウィンドウ。テキストエリアを置いて、ウィンドウの設定をする。
 */
public class EditorFrame extends JFrame {

    private final JTextArea area = new JTextArea();             //ライブラリにあるテキストエリアをインスタンス化
    private final FileService fileService = new FileService();

    public EditorFrame() {
        super("DATUM-Editor");  // ソフト名表示

        // テキストを入力する部品。フォントは部品（JTextArea）ごとに設定する。               
        area.setFont(new Font("Monospaced", Font.PLAIN, 48));

        // 文章が長くなったときにスクロールできるよう、JScrollPane で包んで追加する。
        add(new JScrollPane(area));                             //フレームにテキストエリアを追加

        // メニューバー
        setJMenuBar(createMenuBar());

        setSize(1200, 600);                                     //フレームサイズの指定
        // ×ボタンを押したらプログラムを終了する（後の step で確認ダイアログに変える）。
        setDefaultCloseOperation(EXIT_ON_CLOSE);
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("ファイル");
        JMenuItem saveItem = new JMenuItem("保存");
        
        saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        saveItem.addActionListener(e -> saveFile());

        fileMenu.setFont(new Font("Dialog", Font.PLAIN, 24));
        saveItem.setFont(new Font("Dialog", Font.PLAIN, 24));

        fileMenu.add(saveItem);
        menuBar.add(fileMenu);
        return menuBar;
    }

    private void saveFile() {
        Path path = Path.of("memo.txt");
        UIManager.put("OptionPane.messageFont", new Font("Dialog", Font.PLAIN, 24));
        UIManager.put("OptionPane.buttonFont", new Font("Dialog", Font.PLAIN, 24));

        try{
            fileService.save(area.getText(), path);
            JOptionPane.showMessageDialog(this, "保存しました: " + path.toAbsolutePath());
        } 
        catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "保存できませんでした: " + ex.getMessage(),
            "エラー", JOptionPane.ERROR_MESSAGE);
        }
    }
}
