package com.example.minieditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.Normalizer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * ステータスバーに出す、今日の執筆量と目標の進み具合。
 * 「今日 1,234 / 3,000 字 ■■■■□□□□□□ 41%」のように、10 個のマス（1 マス＝10%）を塗って見せる。
 * 目標に届くと、マスが緑になる。クリックすると、目標字数をその場で変えられる。
 */
final class GoalMeter extends JComponent {

    private static final int CELLS = 10;
    private static final Color DONE = new Color(0x5BD07A);          // 目標に届いたときのマスの色
    private static final int[] PRESETS = {1000, 2000, 3000, 5000};

    private final WritingStats stats;
    private final AppSettings settings;
    private final Runnable onChange;

    GoalMeter(WritingStats stats, AppSettings settings, Runnable onChange) {
        this.stats = stats;
        this.settings = settings;
        this.onChange = onChange;
        setFont(Theme.small());
        setForeground(Color.WHITE);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("クリックで、1日の目標字数を変えられます");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showEditor();
            }
        });
    }

    /** 字数や目標が変わったときに呼ぶ。 */
    void refresh() {
        revalidate();
        repaint();
    }

    private String label() {
        int goal = stats.goal();
        String today = String.format("%,d", stats.today());
        return goal > 0 ? "今日 " + today + " / " + String.format("%,d", goal) + " 字" : "今日 " + today + " 字";
    }

    private int percent() {
        int goal = stats.goal();
        return goal > 0 ? Math.max(0, (int) Math.floor(stats.today() * 100.0 / goal)) : 0;
    }

    private int cellSize() {
        return Math.max(8, getFontMetrics(getFont()).getAscent() - 3);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int width = fm.stringWidth(label());
        if (stats.goal() > 0) {
            width += 10 + CELLS * (cellSize() + 2) + 8 + fm.stringWidth("100%");
        } else {
            width += 10 + fm.stringWidth("目標を決める");
        }
        return new Dimension(width + 4, fm.getHeight());
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        int baseline = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
        g2.setColor(getForeground());
        String text = label();
        g2.drawString(text, 0, baseline);
        int x = fm.stringWidth(text) + 10;

        int goal = stats.goal();
        if (goal <= 0) {
            // 目標がまだないときは、決められることがわかるように、薄い文字で誘う
            g2.setColor(new Color(255, 255, 255, 170));
            g2.drawString("目標を決める", x, baseline);
            g2.dispose();
            return;
        }
        int size = cellSize();
        int top = (getHeight() - size) / 2;
        double ratio = Math.max(0, stats.today() / (double) goal);
        boolean reached = ratio >= 1.0;
        for (int i = 0; i < CELLS; i++) {
            int cx = x + i * (size + 2);
            // マスの枠（まだ塗っていない所）
            g2.setColor(new Color(255, 255, 255, 70));
            g2.fillRect(cx, top, size, size);
            // 塗る量：満たしたマスは全部、途中のマスは割合のぶんだけ左から塗る
            double fill = Math.min(1.0, Math.max(0.0, ratio * CELLS - i));
            if (fill > 0) {
                g2.setColor(reached ? DONE : Color.WHITE);
                g2.fillRect(cx, top, (int) Math.round(size * fill), size);
            }
        }
        x += CELLS * (size + 2) + 6;
        g2.setColor(getForeground());       // 数字は白のまま（青い地の上で読めるように）。届いたことは、マスの緑で伝える
        g2.setFont(reached ? getFont().deriveFont(java.awt.Font.BOLD) : getFont());
        g2.drawString(percent() + "%", x, baseline);
        g2.dispose();
    }

    // ---------- 目標字数を変える小さな欄 ----------

    private void showEditor() {
        JPopupMenu popup = new JPopupMenu();
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel title = new JLabel("1日の目標字数（Enter で決定）");
        title.setFont(Theme.small());
        JTextField field = new JTextField(stats.goal() > 0 ? Integer.toString(stats.goal()) : "", 8);
        Theme.styleField(field);
        field.addActionListener(e -> {
            Integer value = parse(field.getText());
            if (value == null) {
                field.selectAll();      // 数字として読めないときは、選んだままにして打ち直してもらう
                return;
            }
            apply(value, popup);
        });
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(field, BorderLayout.CENTER);
        JLabel unit = new JLabel("字");
        unit.setFont(Theme.small());
        row.add(unit, BorderLayout.EAST);

        // よく使う字数は、ボタン1つで決められるようにする
        JPanel presets = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        presets.setOpaque(false);
        for (int preset : PRESETS) {
            JButton button = Theme.flatButton(String.format("%,d", preset));
            button.addActionListener(e -> apply(preset, popup));
            presets.add(button);
        }
        JButton none = Theme.flatButton("目標なし");
        none.addActionListener(e -> apply(0, popup));
        presets.add(none);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(title, BorderLayout.NORTH);
        top.add(row, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);
        panel.add(presets, BorderLayout.CENTER);
        popup.add(panel);

        Dimension size = popup.getPreferredSize();
        popup.show(this, Math.min(0, getWidth() - size.width), -size.height - 4);   // ステータスバーの上に出す
        SwingUtilities.invokeLater(() -> {
            field.requestFocusInWindow();
            field.selectAll();
        });
    }

    private void apply(int goal, JPopupMenu popup) {
        settings.put(AppSettings.DAILY_GOAL, goal);
        settings.save();
        popup.setVisible(false);
        onChange.run();
    }

    /** 「3000」「3,000」「３０００」などを数として読む。読めなければ null。 */
    static Integer parse(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(",", "").replace("字", "").strip();
        if (normalized.isEmpty()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(normalized);
            return value >= 0 && value <= 1_000_000 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
