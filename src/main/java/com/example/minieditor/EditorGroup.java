package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * タブの帯と、その下の文書1枚をまとめた部品。
 * 画面を分割すると、このグループが左右（または上下）に2つ並ぶ。
 */
public class EditorGroup extends JPanel {

    /** タブが操作されたことを、ウィンドウ側へ知らせるための窓口。 */
    public interface Host {
        void groupActivated(EditorGroup group);

        void paneSelected(EditorGroup group, EditorPane pane);

        void closeRequested(EditorGroup group, EditorPane pane);

        /** 「+」が押された。invoker の下に、作る種類を選ぶメニューを出す。 */
        void newRequested(EditorGroup group, java.awt.Component invoker);

        /** タブを右クリックした。 */
        void tabMenuRequested(EditorGroup group, EditorPane pane, java.awt.Component invoker, int x, int y);

        /** タブを引きずっている途中（screen は画面全体での位置）。 */
        void tabDragged(EditorGroup group, EditorPane pane, Point screen);

        /** 引きずっていたタブを離した。 */
        void tabDropped(EditorGroup group, EditorPane pane, Point screen);
    }

    private static final Color[] BADGE_COLORS = {
        new Color(0x4FC1FF), new Color(0xCE9178), new Color(0x4EC9B0)
    };

    private final Host host;
    private final List<EditorPane> panes = new ArrayList<>();
    private EditorPane active;
    private boolean groupActive;

    private List<EditorPane> builtFor = new ArrayList<>();     // タブの帯を作ったときの顔ぶれ
    private int dropMarker = -1;           // タブを引きずっているとき、ここに入ると示す位置（-1 なら出さない）

    private final JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)) {
        @Override
        protected void paintChildren(Graphics g) {
            super.paintChildren(g);
            int x = markerX();
            if (x >= 0) {
                g.setColor(Theme.ACCENT);
                g.fillRect(Math.max(0, x - 2), 0, 4, getHeight());     // タブを離すと入る所に、青い縦線を出す
            }
        }
    };
    private final JScrollPane stripScroll = new JScrollPane(strip,
            JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private final JPanel content = new JPanel(new BorderLayout());
    private final JLabel placeholder = new JLabel("Ctrl+N で新しい文書を作成", SwingConstants.CENTER);

    public EditorGroup(Host host) {
        super(new BorderLayout());
        this.host = host;

        strip.setBackground(Theme.PANEL);
        stripScroll.setBorder(null);
        stripScroll.getViewport().setBackground(Theme.PANEL);
        // タブが多くて入りきらないときは、ホイールで横に送る
        stripScroll.setWheelScrollingEnabled(false);
        stripScroll.addMouseWheelListener(e -> {
            JScrollBar bar = stripScroll.getHorizontalScrollBar();
            bar.setValue(bar.getValue() + e.getWheelRotation() * 80);
        });

        placeholder.setForeground(Theme.FG_DIM);
        placeholder.setFont(Theme.ui());
        content.setBackground(Theme.BG);

        add(stripScroll, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        rebuild();
    }

    public List<EditorPane> getPanes() {
        return panes;
    }

    public EditorPane getActive() {
        return active;
    }

    public boolean isEmpty() {
        return panes.isEmpty();
    }

    /** タブを追加して、それを表示する。 */
    public void addPane(EditorPane pane) {
        panes.add(pane);
        select(pane);
    }

    /** タブを取り除く。表示中だった場合は、となりのタブを表示する。 */
    public void removePane(EditorPane pane) {
        int index = panes.indexOf(pane);
        if (index < 0) {
            return;
        }
        panes.remove(index);
        if (pane == active) {
            active = null;
            select(panes.isEmpty() ? null : panes.get(Math.min(index, panes.size() - 1)));
        } else {
            rebuild();
        }
    }

    /** 同じ場所のタブを、別の文書に入れ替える（横書き⇔縦書きの切り替えで使う）。 */
    public void replacePane(EditorPane oldPane, EditorPane newPane) {
        int index = panes.indexOf(oldPane);
        if (index < 0) {
            return;
        }
        panes.set(index, newPane);
        if (active == oldPane) {
            active = null;
            select(newPane);
        } else {
            rebuild();
        }
    }

    /** タブを、指定した位置に入れて表示する（引きずって移したときに使う）。 */
    public void insertPane(EditorPane pane, int index) {
        panes.add(Math.max(0, Math.min(index, panes.size())), pane);
        select(pane);
    }

    /** 同じグループの中で、タブの順番を変える。index は、動かす前の並びで数えた入れたい位置。 */
    public void movePane(EditorPane pane, int index) {
        int from = panes.indexOf(pane);
        if (from < 0) {
            return;
        }
        panes.remove(from);
        if (index > from) {
            index--;                    // 自分が抜けたぶん、後ろの位置が1つ前にずれる
        }
        insertPane(pane, index);
    }

    /** 画面全体での位置が、このグループの上にあるか。 */
    public boolean containsScreen(Point screen) {
        if (!isShowing()) {
            return false;
        }
        Point p = new Point(screen);
        SwingUtilities.convertPointFromScreen(p, this);
        return contains(p);
    }

    /** 画面全体での位置が、タブの帯の上にあるか。 */
    public boolean overStrip(Point screen) {
        if (!stripScroll.isShowing()) {
            return false;
        }
        Point p = new Point(screen);
        SwingUtilities.convertPointFromScreen(p, stripScroll);
        return stripScroll.contains(p);
    }

    /** その位置でタブを離したとき、何番目に入るか。帯より下で離したときは最後に入れる。 */
    public int dropIndex(Point screen) {
        if (!overStrip(screen)) {
            return panes.size();
        }
        Point p = new Point(screen);
        SwingUtilities.convertPointFromScreen(p, strip);
        int index = 0;
        for (java.awt.Component c : strip.getComponents()) {
            if (c instanceof Tab) {
                if (p.x < c.getX() + c.getWidth() / 2) {
                    return index;
                }
                index++;
            }
        }
        return panes.size();
    }

    /** タブを離すと入る位置に、印を出す（-1 で消す）。 */
    public void setDropMarker(int index) {
        if (dropMarker != index) {
            dropMarker = index;
            strip.repaint();
        }
    }

    private int markerX() {
        if (dropMarker < 0) {
            return -1;
        }
        int index = 0;
        java.awt.Component last = null;
        for (java.awt.Component c : strip.getComponents()) {
            if (c instanceof Tab) {
                if (index == dropMarker) {
                    return c.getX();
                }
                last = c;
                index++;
            }
        }
        return last == null ? 0 : last.getX() + last.getWidth();
    }

    public void select(EditorPane pane) {
        active = pane;
        content.removeAll();
        content.add(pane != null ? pane : placeholder, BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
        if (builtFor.equals(panes)) {
            // タブの顔ぶれが同じなら、作り直さずに描き直すだけにする（引きずり始めたタブが消えないように）
            strip.repaint();
            scrollToActive();
        } else {
            rebuild();
        }
        host.paneSelected(this, pane);
    }

    /** となりのタブへ移る。delta が 1 なら右、-1 なら左。 */
    public void selectNext(int delta) {
        if (panes.size() > 1) {
            select(panes.get(Math.floorMod(panes.indexOf(active) + delta, panes.size())));
        }
    }

    /** このグループが操作の対象かどうか（対象のときだけ、タブの上に青い線を出す）。 */
    public void setGroupActive(boolean groupActive) {
        if (this.groupActive != groupActive) {
            this.groupActive = groupActive;
            strip.repaint();
        }
    }

    /** タブの帯を出す／隠す（集中モードで隠す）。 */
    public void setTabsVisible(boolean visible) {
        stripScroll.setVisible(visible);
        revalidate();
    }

    /** タブの名前や未保存マークが変わったときに呼ぶ。 */
    public void refreshTabs() {
        rebuild();
    }

    /** タブの帯を作り直す。 */
    private void rebuild() {
        strip.removeAll();
        for (EditorPane pane : panes) {
            strip.add(new Tab(pane));
        }
        builtFor = new ArrayList<>(panes);
        strip.add(newTabButton());
        strip.revalidate();
        strip.repaint();
        scrollToActive();
    }

    /** 表示中のタブが見える所まで、タブの帯を送る。 */
    private void scrollToActive() {
        for (java.awt.Component c : strip.getComponents()) {
            if (c instanceof Tab tab && tab.pane == active) {
                SwingUtilities.invokeLater(() -> tab.scrollRectToVisible(
                        new java.awt.Rectangle(0, 0, tab.getWidth(), tab.getHeight())));
            }
        }
    }

    private JLabel newTabButton() {
        JLabel plus = new JLabel("+", SwingConstants.CENTER);
        plus.setFont(Theme.ui());
        plus.setForeground(Theme.FG_DIM);
        plus.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        plus.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        plus.setToolTipText("新しい文書（横書き・縦書き・カードボードから選ぶ）");
        plus.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                host.newRequested(EditorGroup.this, plus);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                plus.setForeground(Theme.FG_STRONG);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                plus.setForeground(Theme.FG_DIM);
            }
        });
        return plus;
    }

    /** タブ1枚。種類の印・名前・閉じるボタンを横に並べる。 */
    private final class Tab extends JPanel {
        private final EditorPane pane;

        Tab(EditorPane pane) {
            this.pane = pane;
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(9, 14, 8, 8));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(pane.getPath() == null ? pane.getMode().label : pane.getPath().toString());

            JLabel badge = new JLabel(pane.getMode().badge);
            badge.setFont(Theme.small().deriveFont(Font.BOLD));
            badge.setForeground(BADGE_COLORS[pane.getMode().ordinal()]);

            // 文字の色は、描くたびに「選ばれているか」で決める（選び直すたびにタブを作り直さないため）
            JLabel title = new JLabel(pane.getTitle()) {
                @Override
                public Color getForeground() {
                    return Tab.this.pane == active ? Theme.FG_STRONG : Theme.FG_DIM;
                }
            };
            title.setFont(Theme.ui());

            // 未保存のときは ● を出し、マウスを乗せると × に変わる
            String idle = pane.isDirty() ? "●" : "×";
            JLabel close = new JLabel(idle, SwingConstants.CENTER);
            close.setFont(Theme.ui());
            close.setForeground(pane.isDirty() ? Theme.FG_STRONG : Theme.FG_DIM);
            close.setPreferredSize(new Dimension(34, 28));
            close.setToolTipText("閉じる");
            close.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    host.groupActivated(EditorGroup.this);
                    host.closeRequested(EditorGroup.this, pane);
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    close.setText("×");
                    close.setForeground(Theme.FG_STRONG);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    close.setText(idle);
                    close.setForeground(pane.isDirty() ? Theme.FG_STRONG : Theme.FG_DIM);
                }
            });

            add(badge);
            add(Box.createHorizontalStrut(10));
            add(title);
            add(Box.createHorizontalStrut(10));
            add(close);

            // 押すと表示、右クリックでメニュー、引きずると並べ替え・反対側への移動
            MouseAdapter selectOnPress = new MouseAdapter() {
                private Point pressed;
                private boolean dragging;

                @Override
                public void mousePressed(MouseEvent e) {
                    host.groupActivated(EditorGroup.this);
                    if (SwingUtilities.isMiddleMouseButton(e)) {
                        host.closeRequested(EditorGroup.this, pane);   // 中ボタンで閉じる
                        return;
                    }
                    select(pane);
                    pane.focusEditor();
                    if (SwingUtilities.isRightMouseButton(e)) {
                        host.tabMenuRequested(EditorGroup.this, pane, e.getComponent(), e.getX(), e.getY());
                    } else if (SwingUtilities.isLeftMouseButton(e)) {
                        pressed = e.getLocationOnScreen();
                        dragging = false;
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (pressed == null) {
                        return;
                    }
                    if (!dragging && pressed.distance(e.getLocationOnScreen()) > 8) {
                        dragging = true;        // 少し動かしただけでは、引きずりとみなさない（押すつもりの手ぶれ）
                        e.getComponent().setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    }
                    if (dragging) {
                        host.tabDragged(EditorGroup.this, pane, e.getLocationOnScreen());
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (dragging) {
                        e.getComponent().setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                        host.tabDropped(EditorGroup.this, pane, e.getLocationOnScreen());
                    }
                    pressed = null;
                    dragging = false;
                }
            };
            for (JComponent part : new JComponent[]{this, badge, title}) {
                part.addMouseListener(selectOnPress);
                part.addMouseMotionListener(selectOnPress);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            boolean selected = pane == active;
            g.setColor(selected ? Theme.BG : Theme.TAB_INACTIVE);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (selected) {
                g.setColor(groupActive ? Theme.ACCENT : Theme.BORDER);
                g.fillRect(0, 0, getWidth(), 3);        // 選択中のタブの上に出す線
            }
            g.setColor(Theme.PANEL);
            g.fillRect(getWidth() - 1, 0, 1, getHeight()); // タブとタブの境目
        }
    }
}
