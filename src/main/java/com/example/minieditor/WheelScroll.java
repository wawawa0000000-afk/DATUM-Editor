package com.example.minieditor;

import java.awt.event.MouseWheelEvent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;

/**
 * ホイールとタッチパッドのスクロールを、どの画面でも同じ速さにする。
 *
 * Java のふつうのスクロールは、タッチパッドの細かい動きを「1行ぶん」に丸めてしまい、
 * 画面ごとに1回で動く量もばらばらだった。ここでは細かい動き（getPreciseWheelRotation）を
 * そのまま足し合わせ、OS の「一度にスクロールする行数」に合わせて動かす。
 */
final class WheelScroll {

    /** OS の設定の1行あたりに動かす量（倍率 100% のとき、ピクセル） */
    static final int LINE = 16;

    /** 1ピクセルに満たない残り。[0] は縦のバー、[1] は横のバー */
    private final double[] rest = new double[2];
    private final JScrollPane scrollPane;
    private final boolean verticalWriting;

    /**
     * @param verticalWriting 縦書きのとき true。ホイールを下に回すと、読み進める左へ動かす
     */
    WheelScroll(JScrollPane scrollPane, boolean verticalWriting) {
        this.scrollPane = scrollPane;
        this.verticalWriting = verticalWriting;
    }

    /** スクロール枠にこの動きを付ける（Java のふつうのホイール処理は止める）。 */
    static void install(JScrollPane scrollPane, boolean verticalWriting) {
        WheelScroll wheel = new WheelScroll(scrollPane, verticalWriting);
        scrollPane.setWheelScrollingEnabled(false);
        scrollPane.addMouseWheelListener(e -> {
            if (!e.isControlDown()) {       // Ctrl+ホイールは倍率の変更に使う
                wheel.scroll(e);
            }
        });
    }

    /** ホイールの動き1回ぶん、スクロールする。 */
    void scroll(MouseWheelEvent e) {
        // タッチパッドの横スライドは、Java には Shift+ホイールとして届く。横書きやブラウザと同じ向きで横に動かす
        boolean horizontal = e.isShiftDown() || verticalWriting;
        JScrollBar bar = horizontal ? scrollPane.getHorizontalScrollBar() : scrollPane.getVerticalScrollBar();
        double amount;
        if (e.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL) {
            amount = e.getPreciseWheelRotation() * bar.getVisibleAmount();      // OS の設定が「1画面ずつ」のとき
        } else {
            amount = e.getPreciseWheelRotation() * e.getScrollAmount() * LINE * Theme.zoom() / 100.0;
        }
        if (verticalWriting && !e.isShiftDown()) {
            amount = -amount;
        }
        int i = horizontal ? 1 : 0;
        rest[i] += amount;
        int move = (int) rest[i];
        rest[i] -= move;
        bar.setValue(bar.getValue() + move);
    }
}
