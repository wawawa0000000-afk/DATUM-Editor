package com.example.minieditor;

import javax.swing.Timer;

/**
 * 1つの値（透明度・濃さなど）を、決まった時間をかけて目標の値へ寄せる。画面の小さな動きは、全部これで動かす。
 * 動かしてよいのは、透明度・位置・大きさだけ。本文を並べ直すような動きには使わない（長い原稿で入力がもたつくため）。
 * タイマーは javax.swing.Timer なので、すべて EDT の上で動く。
 */
final class Motion {

    static final int FAST = 100;            // 乗せた・押したの反応（ミリ秒）
    static final int NORMAL = 150;          // 出る・消える

    private final Timer timer = new Timer(15, e -> tick());
    private final int duration;
    private final Runnable onChange;
    private float from;
    private float to;
    private float value;
    private long start;
    private Runnable done;

    /** onChange は、値が変わるたびに呼ぶ（ふつうは部品の repaint）。 */
    Motion(int duration, float initial, Runnable onChange) {
        this.duration = duration;
        this.onChange = onChange;
        this.value = this.to = initial;
    }

    float value() {
        return value;
    }

    boolean isRunning() {
        return timer.isRunning();
    }

    void to(float target) {
        to(target, null);
    }

    /**
     * 今の値から target へ動かす。着いたら done を呼ぶ（null でもよい）。
     * 前の動きの途中なら、そこから向きを変える（前の done は呼ばない）。
     */
    void to(float target, Runnable done) {
        this.done = done;
        if (target == value) {
            timer.stop();
            to = target;
            arrived();
            return;
        }
        if (timer.isRunning() && target == to) {
            return;                     // もう同じ所へ向かっている
        }
        from = value;
        to = target;
        start = System.nanoTime();
        timer.start();
    }

    /** 動かさずに、すぐその値にする。 */
    void set(float target) {
        timer.stop();
        done = null;
        to = target;
        if (value != target) {
            value = target;
            onChange.run();
        }
    }

    /** 動いている途中なら、目標の値にして終わらせる。 */
    void finish() {
        if (!timer.isRunning()) {
            return;
        }
        timer.stop();
        value = to;
        onChange.run();
        arrived();
    }

    private void tick() {
        float t = Math.min(1f, (System.nanoTime() - start) / (duration * 1_000_000f));
        value = from + (to - from) * ease(t);
        onChange.run();
        if (t >= 1f) {
            timer.stop();
            value = to;
            arrived();
        }
    }

    private void arrived() {
        Runnable after = done;
        done = null;
        if (after != null) {
            after.run();
        }
    }

    /** はじめ速く、終わりはゆっくり（t は 0〜1）。 */
    static float ease(float t) {
        return 1f - (1f - t) * (1f - t);
    }
}
