package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Box;
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

        void newRequested(EditorGroup group);
    }

    private static final Color[] BADGE_COLORS = {
        new Color(0x4FC1FF), new Color(0xCE9178), new Color(0x4EC9B0)
    };

    private final Host host;
    private final List<EditorPane> panes = new ArrayList<>();
    private EditorPane active;
    private boolean groupActive;

    private final JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
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

    public void select(EditorPane pane) {
        active = pane;
        content.removeAll();
        content.add(pane != null ? pane : placeholder, BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
        rebuild();
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
        Tab activeTab = null;
        for (EditorPane pane : panes) {
            Tab tab = new Tab(pane);
            strip.add(tab);
            if (pane == active) {
                activeTab = tab;
            }
        }
        strip.add(newTabButton());
        strip.revalidate();
        strip.repaint();
        if (activeTab != null) {
            Tab visible = activeTab;
            SwingUtilities.invokeLater(() -> visible.scrollRectToVisible(
                    new java.awt.Rectangle(0, 0, visible.getWidth(), visible.getHeight())));
        }
    }

    private JLabel newTabButton() {
        JLabel plus = new JLabel("+", SwingConstants.CENTER);
        plus.setFont(Theme.ui());
        plus.setForeground(Theme.FG_DIM);
        plus.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        plus.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        plus.setToolTipText("新しい文書");
        plus.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                host.newRequested(EditorGroup.this);
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

            JLabel title = new JLabel(pane.getTitle());
            title.setFont(Theme.ui());
            title.setForeground(pane == active ? Theme.FG_STRONG : Theme.FG_DIM);

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

            MouseAdapter selectOnPress = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    host.groupActivated(EditorGroup.this);
                    if (SwingUtilities.isMiddleMouseButton(e)) {
                        host.closeRequested(EditorGroup.this, pane);   // 中ボタンで閉じる
                    } else {
                        select(pane);
                        pane.focusEditor();
                    }
                }
            };
            addMouseListener(selectOnPress);
            badge.addMouseListener(selectOnPress);
            title.addMouseListener(selectOnPress);
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
