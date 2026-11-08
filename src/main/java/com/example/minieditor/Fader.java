package com.example.minieditor;

import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * 集中モードに出入りするとき、ツールバーとステータスバーを 150ms かけて薄く消す・浮かび上がらせる。
 * 動かすのは透明度だけで、途中で大きさや並びは変えない（本文を毎回並べ直すと、長い原稿で入力がもたつくため）。
 * 並びが変わるのは、消し終わったとき（または浮かび始める前）の1回だけ。
 * 部品の側は、paint を {@link #paint} に通しておく。タイマーは javax.swing.Timer なので、すべて EDT の上で動く。
 */
final class Fader {

    static final int DURATION = 150;                    // ミリ秒
    private static final String ALPHA = "datum.fadeAlpha";

    private final List<JComponent> parts;
    private final Timer timer = new Timer(15, e -> tick());
    private long start;
    private boolean in;
    private Runnable done;

    Fader(JComponent... parts) {
        this.parts = List.of(parts);
    }

    /**
     * 消す（in = false）か、浮かび上がらせる（in = true）。終わったら done を呼ぶ（null でもよい）。
     * 前の動きの途中なら、先にそれを終わらせる。
     */
    void fade(boolean in, Runnable done) {
        finish();
        this.in = in;
        this.done = done;
        for (JComponent c : parts) {
            c.setOpaque(false);         // 透けている間は、後ろ（本文の地）を先に描いてもらう
            set(c, in ? 0f : 1f);
        }
        start = System.nanoTime();
        timer.start();
    }

    /** 動いている途中なら、最後の形にして終わらせる（続けて F11 を押されたとき）。 */
    void finish() {
        if (!timer.isRunning()) {
            return;
        }
        timer.stop();
        end();
    }

    private void tick() {
        float t = Math.min(1f, (System.nanoTime() - start) / (DURATION * 1_000_000f));
        float eased = 1f - (1f - t) * (1f - t);        // はじめ速く、終わりはゆっくり
        for (JComponent c : parts) {
            set(c, in ? eased : 1f - eased);
        }
        if (t >= 1f) {
            timer.stop();
            end();
        }
    }

    private void end() {
        for (JComponent c : parts) {
            c.putClientProperty(ALPHA, null);
            c.setOpaque(true);
            c.repaint();
        }
        Runnable after = done;
        done = null;
        if (after != null) {
            after.run();
        }
    }

    private static void set(JComponent c, float alpha) {
        c.putClientProperty(ALPHA, alpha);
        c.repaint();
    }

    /** 部品の paint から呼ぶ。透明度が決まっていれば、その濃さで中身ごと描く。 */
    static void paint(JComponent c, Graphics g, Consumer<Graphics> paint) {
        Object value = c.getClientProperty(ALPHA);
        if (!(value instanceof Float alpha)) {
            paint.accept(g);
            return;
        }
        if (alpha <= 0f) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setComposite(AlphaComposite.SrcOver.derive(alpha));
        paint.accept(g2);
        g2.dispose();
    }
}
