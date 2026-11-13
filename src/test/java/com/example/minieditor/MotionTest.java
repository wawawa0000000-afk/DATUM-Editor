package com.example.minieditor;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * 画面の小さな動き（Motion・Fader・PlateFade）のテスト。
 * 時間をかけた動きは待たずに、始まり・途中で終わらせたとき・終わったあとの値を確かめる。
 */
public class MotionTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "OK   " : "NG   ") + what);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ease();
            motion();
            fader();
            plate();
        });
        timed();
        System.out.println(failures == 0 ? "すべて通りました" : failures + " 件、失敗しました");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void ease() {
        check(Motion.ease(0f) == 0f && Motion.ease(1f) == 1f, "緩急は 0 で 0、1 で 1");
        boolean rising = true;
        for (int i = 1; i <= 100; i++) {
            rising &= Motion.ease(i / 100f) > Motion.ease((i - 1) / 100f);
        }
        check(rising, "緩急は、戻らずに増えていく");
        check(Motion.ease(0.5f) > 0.5f, "はじめが速い（半分の時間で、半分より先）");
        check(Motion.FAST >= 100 && Motion.NORMAL <= 200, "動きの時間は 100〜200ms");
    }

    private static void motion() {
        int[] changes = {0};
        List<String> done = new ArrayList<>();
        Motion m = new Motion(Motion.NORMAL, 0f, () -> changes[0]++);
        check(m.value() == 0f && !m.isRunning(), "作ったときは、初めの値で止まっている");

        m.to(1f, () -> done.add("a"));
        check(m.isRunning() && m.value() == 0f && done.isEmpty(), "動かし始めた直後は、まだ元の値");
        m.finish();
        check(m.value() == 1f && !m.isRunning(), "途中で終わらせると、目標の値になる");
        check(done.equals(List.of("a")), "終わらせたとき、終わりの知らせが1回だけ来る");
        check(changes[0] > 0, "値が変わると、知らせが来る");

        m.finish();
        check(done.size() == 1, "止まっているときに終わらせても、何も起きない");

        m.to(1f, () -> done.add("b"));
        check(!m.isRunning() && done.equals(List.of("a", "b")), "もう目標の値にいるときは、すぐ終わりの知らせが来る");

        m.to(0f, () -> done.add("c"));
        m.to(1f, () -> done.add("d"));
        check(!m.isRunning() && m.value() == 1f && done.equals(List.of("a", "b", "d")),
                "動かし始めてすぐ元へ戻すと、動かずに終わる（前の知らせは来ない）");

        m.to(0f);
        m.set(0.5f);
        check(!m.isRunning() && m.value() == 0.5f, "set は、動かさずにすぐその値にする");
    }

    private static void fader() {
        JLabel label = new JLabel("保存しました") {
            @Override
            public void paint(java.awt.Graphics g) {
                Fader.paint(this, g, super::paint);
            }
        };
        label.setForeground(java.awt.Color.WHITE);
        label.setSize(label.getPreferredSize());
        Fader fader = new Fader(label);
        List<String> done = new ArrayList<>();

        check(ink(label) > 0, "動かしていないときは、ふつうに描かれる");
        fader.fade(true, () -> done.add("in"));
        check(ink(label) == 0, "浮かび上がらせ始めた直後は、見えない");
        check(!label.isOpaque(), "地を塗らない部品は、塗らないまま");
        fader.finish();
        check(ink(label) > 0 && done.equals(List.of("in")), "終わらせると、見えて、終わりの知らせが来る");

        fader.fade(false, () -> done.add("out"));
        check(ink(label) > 0, "消し始めた直後は、まだ見える");
        fader.fade(true, null);
        check(done.equals(List.of("in", "out")), "途中で次を始めると、前の動きを先に終わらせる");
        fader.finish();
        check(ink(label) > 0 && !label.isOpaque(), "最後は見えていて、地を塗らない部品のまま");

        JLabel solid = new JLabel("x");
        solid.setOpaque(true);
        Fader other = new Fader(solid);
        other.fade(false, null);
        check(!solid.isOpaque(), "地を塗る部品は、動いている間だけ塗らない");
        other.finish();
        check(solid.isOpaque(), "終わったら、地を塗る部品に戻る");
    }

    /** 部品を黒い画像に描いて、黒でない点の数を返す。 */
    private static int ink(JLabel label) {
        BufferedImage image = new BufferedImage(label.getWidth(), label.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        label.paint(g);
        g.dispose();
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void plate() {
        JButton button = new JButton("Aa");
        PlateFade fade = new PlateFade(button, true);
        check(java.util.Arrays.equals(fade.plate(), Theme.plate(false, false)), "ふだんは、ふだんの濃さ");
        button.setSelected(true);
        check(java.util.Arrays.equals(fade.plate(), Theme.plate(true, false)), "画面に出ていないボタンは、オンにするとすぐオンの濃さ");
        button.setSelected(false);
        button.getModel().setRollover(true);
        check(java.util.Arrays.equals(fade.plate(), Theme.plate(false, true)) && fade.hot() == 1f, "乗せると、乗せたときの濃さ");
        button.setEnabled(false);
        check(java.util.Arrays.equals(fade.plate(), Theme.plate(false, false)), "押せないボタンは、乗せても変わらない");
    }

    /** 実際に時間を置いて、タイマーで目標の値に着くことを確かめる。 */
    private static void timed() throws Exception {
        Motion[] m = new Motion[1];
        boolean[] done = {false};
        float[] mid = {-1f};
        SwingUtilities.invokeAndWait(() -> {
            m[0] = new Motion(Motion.NORMAL, 0f, () -> {
                if (m[0].value() > 0f && m[0].value() < 1f) {
                    mid[0] = m[0].value();
                }
            });
            m[0].to(1f, () -> done[0] = true);
        });
        Thread.sleep(600);
        SwingUtilities.invokeAndWait(() -> {
            check(done[0] && m[0].value() == 1f && !m[0].isRunning(), "時間が過ぎると、目標の値に着いて止まる");
            check(mid[0] > 0f, "途中の値を通る");
        });
    }
}
