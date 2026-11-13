package com.example.minieditor;

import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JComponent;

/**
 * 部品を 150ms かけて薄く消す・浮かび上がらせる（集中モードに出入りするときのツールバーとステータスバー、検索バーなど）。
 * 動かすのは透明度だけで、途中で大きさや並びは変えない（本文を毎回並べ直すと、長い原稿で入力がもたつくため）。
 * 並びが変わるのは、消し終わったとき（または浮かび始める前）の1回だけ。
 * 部品の側は、paint を {@link #paint} に通しておく。動かす仕組みは {@link Motion}（EDT の上で動く）。
 */
final class Fader {

    static final int DURATION = Motion.NORMAL;          // ミリ秒
    private static final String ALPHA = "datum.fadeAlpha";

    private final List<JComponent> parts;
    private final boolean[] opaque;                     // 動かす前に、地を塗る部品だったか
    private final Motion motion = new Motion(DURATION, 1f, this::apply);
    private boolean running;
    private Runnable done;

    Fader(JComponent... parts) {
        this.parts = List.of(parts);
        this.opaque = new boolean[parts.length];
    }

    /**
     * 消す（in = false）か、浮かび上がらせる（in = true）。終わったら done を呼ぶ（null でもよい）。
     * 前の動きの途中なら、先にそれを終わらせる。
     */
    void fade(boolean in, Runnable done) {
        finish();
        this.done = done;
        running = true;
        for (int i = 0; i < parts.size(); i++) {
            opaque[i] = parts.get(i).isOpaque();
            parts.get(i).setOpaque(false);      // 透けている間は、後ろ（本文の地）を先に描いてもらう
        }
        motion.set(in ? 0f : 1f);
        apply();
        motion.to(in ? 1f : 0f, this::end);
    }

    /** 動いている途中なら、最後の形にして終わらせる（続けて F11 を押されたとき）。 */
    void finish() {
        if (running) {
            motion.finish();
        }
    }

    private void apply() {
        if (!running) {
            return;
        }
        for (JComponent c : parts) {
            c.putClientProperty(ALPHA, motion.value());
            c.repaint();
        }
    }

    private void end() {
        running = false;
        for (int i = 0; i < parts.size(); i++) {
            parts.get(i).putClientProperty(ALPHA, null);
            parts.get(i).setOpaque(opaque[i]);
            parts.get(i).repaint();
        }
        Runnable after = done;
        done = null;
        if (after != null) {
            after.run();
        }
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
        g2.setComposite(AlphaComposite.SrcOver.derive(Math.min(1f, alpha)));
        paint.accept(g2);
        g2.dispose();
    }
}
