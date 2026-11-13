package com.example.minieditor;

import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;

/**
 * ボタンのプレート（地と枠）の濃さを、マウスを乗せた・オンにしたのに合わせて 100ms で移す。
 * ボタンの側は、描くときに {@link #plate} の濃さを使う。画面に出ていない間は、動かさずにすぐ変える。
 */
final class PlateFade {

    private final AbstractButton button;
    private final boolean focusCounts;      // 焦点があるときも、乗せたときと同じ濃さにするか
    private final Motion hot;
    private final Motion on;

    PlateFade(AbstractButton button, boolean focusCounts) {
        this.button = button;
        this.focusCounts = focusCounts;
        hot = new Motion(Motion.FAST, 0f, button::repaint);
        on = new Motion(Motion.FAST, 0f, button::repaint);
        button.getModel().addChangeListener(e -> update(true));
        if (focusCounts) {
            button.addFocusListener(new FocusListener() {
                @Override
                public void focusGained(FocusEvent e) {
                    update(true);
                }

                @Override
                public void focusLost(FocusEvent e) {
                    update(true);
                }
            });
        }
        update(false);
    }

    private void update(boolean animate) {
        ButtonModel m = button.getModel();
        boolean nowHot = button.isEnabled() && (m.isRollover() || m.isPressed() || focusCounts && button.isFocusOwner());
        float h = nowHot ? 1f : 0f;
        float o = m.isSelected() ? 1f : 0f;
        if (animate && button.isShowing()) {
            hot.to(h);
            on.to(o);
        } else {
            hot.set(h);
            on.set(o);
        }
    }

    /** 乗せている度合い（0〜1）。 */
    float hot() {
        return hot.value();
    }

    /** 今のプレートの濃さ（地・枠の順。Theme.overlay に渡す割合）。 */
    float[] plate() {
        float[] idle = Theme.plate(false, false);
        float[] over = Theme.plate(false, true);
        float[] lit = Theme.plate(true, false);
        float[] now = new float[2];
        for (int i = 0; i < 2; i++) {
            // 端（0 と 1）で、元の値とぴったり同じになる混ぜ方にする
            float base = idle[i] * (1f - hot.value()) + over[i] * hot.value();
            now[i] = base * (1f - on.value()) + lit[i] * on.value();
        }
        return now;
    }
}
