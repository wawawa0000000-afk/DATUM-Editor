import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class MiniEditor
{
    public static void main(String[] args)
    {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("DATUM-Editor");                //名前付きのJavaフレームをインスタンス化
            JTextArea area = new JTextArea();                       //ライブラリにあるテキストエリアをインスタンス化
            frame.add( new JScrollPane(area));                      //フレームにテキストエリアを追加
            frame.setSize(1200, 600);                                //フレームサイズの指定
            area.setFont(new Font("Monospaced", Font.PLAIN, 48));
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);   //フレームにデフォルトのクローズ機能を追加　最小化　最大化　削除下？を入れてるのか？
            frame.setVisible(true);                                 //有効化してるだけ　無効化すると動かない　表示されない可能性がある 試したけど走らせても映らない
        });
    }
}