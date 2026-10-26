package com.example.minieditor;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * 執筆の記録。1日に書いた字数を数えて、設定ファイルに日ごとに残す。目標字数に対する進み具合も出す。
 *
 * 数え方：文書の字数（改行を除く）の増減を、書き換えのたびに足していく。
 * 開いた時点の字数は数えないので、「その日に増えた字数」になる（消すと減る）。
 */
public final class WritingStats {

    private final AppSettings settings;
    private final Map<EditorPane, Integer> last = new WeakHashMap<>();     // 文書ごとの、前回数えた字数

    public WritingStats(AppSettings settings) {
        this.settings = settings;
    }

    /** 数え始める（文書を開いた・作ったとき）。この時点の字数は、今日の分に入れない。 */
    public void track(EditorPane pane) {
        last.put(pane.firstView(), count(pane));
    }

    /** 文書が書き換えられたときに呼ぶ。 */
    public void changed(EditorPane pane) {
        if (pane != pane.firstView()) {
            return;                     // 分割で同じ文書を映しているときは、代表のタブの知らせだけで数える（全文を2回数えない）
        }
        int now = count(pane);
        // 同じ文書を2つのタブで映していると、1回の書き換えで両方から知らせが来るので、文書ごとに数える
        Integer before = last.put(pane.firstView(), now);
        if (before != null && before != now) {
            add(now - before);
        }
    }

    private void add(int delta) {
        Map<String, Object> daily = new LinkedHashMap<>();
        Map<String, Object> saved = settings.getMap(AppSettings.DAILY);
        if (saved != null) {
            daily.putAll(saved);
        }
        String key = LocalDate.now().toString();
        int value = daily.get(key) instanceof Number n ? n.intValue() : 0;
        daily.put(key, value + delta);
        // 1年より前の記録は消す（設定ファイルが大きくなりすぎないように）
        String oldest = LocalDate.now().minusDays(366).toString();
        daily.keySet().removeIf(day -> day.compareTo(oldest) < 0);
        settings.put(AppSettings.DAILY, daily);
    }

    public int on(LocalDate day) {
        Map<String, Object> daily = settings.getMap(AppSettings.DAILY);
        return daily != null && daily.get(day.toString()) instanceof Number n ? n.intValue() : 0;
    }

    public int today() {
        return on(LocalDate.now());
    }

    public int goal() {
        return settings.getInt(AppSettings.DAILY_GOAL, 0);
    }

    /** ステータスバーに出す、今日の執筆量。 */
    public String statusText() {
        int today = today();
        int goal = goal();
        String amount = (today >= 0 ? "+" : "") + String.format("%,d", today);
        if (goal > 0) {
            return "今日 " + amount + " / " + String.format("%,d", goal) + " 字（" + Math.max(0, today * 100 / goal) + "%）";
        }
        return "今日 " + amount + " 字";
    }

    /** 字数（改行は数えない）。カードボードは数えない。 */
    public static int count(EditorPane pane) {
        if (pane instanceof Searchable s) {
            String text = s.getText();
            int n = text.codePointCount(0, text.length());
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    n--;
                }
            }
            return n;
        }
        return 0;
    }

    /** 400字詰め原稿用紙に換算した枚数（1行20字・20行。段落ごとに行を改める）。 */
    public static int manuscriptSheets(String text) {
        // 段落ごとに文字数を数える（split で段落の文字列を作らずに、1回たどるだけにする）
        int lines = 0;
        int length = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines += Math.max(1, (length + 19) / 20);
                length = 0;
            } else if (!Character.isLowSurrogate(c)) {
                length++;
            }
        }
        lines += Math.max(1, (length + 19) / 20);
        return Math.max(1, (lines + 19) / 20);
    }

    /** 連続して書いた日数（今日、まだ書いていなければ昨日まで）。 */
    public int streak() {
        LocalDate day = LocalDate.now();
        if (on(day) <= 0) {
            day = day.minusDays(1);
        }
        int days = 0;
        while (on(day) > 0) {
            days++;
            day = day.minusDays(1);
        }
        return days;
    }

    private int total(int days) {
        int sum = 0;
        for (int i = 0; i < days; i++) {
            sum += Math.max(0, on(LocalDate.now().minusDays(i)));
        }
        return sum;
    }

    // ---------- 「執筆の記録」の画面 ----------

    /** 直近 14 日の棒グラフと、合計・連続日数・目標の設定を出す。 */
    public void showDialog(Frame owner) {
        JDialog dialog = new JDialog(owner, "執筆の記録", true);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));

        JPanel numbers = new JPanel(new GridLayout(1, 4, 12, 0));
        numbers.add(stat("今日", String.format("%,d 字", today())));
        numbers.add(stat("この 7 日", String.format("%,d 字", total(7))));
        numbers.add(stat("この 30 日", String.format("%,d 字", total(30))));
        numbers.add(stat("連続", streak() + " 日"));

        JPanel goalRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        goalRow.add(new JLabel("1日の目標："));
        JSpinner goalSpinner = new JSpinner(new SpinnerNumberModel(goal(), 0, 100000, 500));
        goalRow.add(goalSpinner);
        goalRow.add(new JLabel("字（0 で目標なし）"));

        Chart chart = new Chart();
        goalSpinner.addChangeListener(e -> {
            settings.put(AppSettings.DAILY_GOAL, (Integer) goalSpinner.getValue());
            settings.save();
            chart.repaint();
        });

        JButton close = new JButton("閉じる");
        close.addActionListener(e -> dialog.dispose());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(goalRow, BorderLayout.WEST);
        bottom.add(close, BorderLayout.EAST);

        root.add(numbers, BorderLayout.NORTH);
        root.add(chart, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.setSize(new Dimension(900, 560));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static JComponent stat(String label, String value) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Theme.BG);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        JLabel top = new JLabel(label);
        top.setFont(Theme.small());
        top.setForeground(Theme.FG_DIM);
        JLabel big = new JLabel(value);
        big.setFont(Theme.ui().deriveFont(Font.BOLD, Theme.UI_SIZE * 1.3f));
        big.setForeground(Theme.FG_STRONG);
        panel.add(top, BorderLayout.NORTH);
        panel.add(big, BorderLayout.CENTER);
        return panel;
    }

    /** 直近 14 日の棒グラフ。目標があれば、その高さに点線を引く。 */
    private final class Chart extends JComponent {
        Chart() {
            setPreferredSize(new Dimension(800, 320));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(Theme.BG);
            g2.fillRect(0, 0, getWidth(), getHeight());
            int days = 14;
            int goal = goal();
            int max = Math.max(goal, 1);
            for (int i = 0; i < days; i++) {
                max = Math.max(max, on(LocalDate.now().minusDays(i)));
            }
            g2.setFont(Theme.small());
            int labelHeight = g2.getFontMetrics().getHeight();
            int top = labelHeight + 10;
            int bottom = getHeight() - labelHeight - 10;
            int left = 20;
            int slot = (getWidth() - left * 2) / days;
            for (int i = 0; i < days; i++) {
                LocalDate day = LocalDate.now().minusDays(days - 1 - i);
                int value = Math.max(0, on(day));
                int height = (int) ((bottom - top) * (value / (double) max));
                int x = left + i * slot + slot / 5;
                int width = slot * 3 / 5;
                boolean reached = goal > 0 && value >= goal;
                g2.setColor(i == days - 1 ? Theme.ACCENT : reached ? Theme.REPLACED : Theme.HOVER);
                g2.fillRoundRect(x, bottom - height, width, height, 6, 6);
                g2.setColor(Theme.FG_DIM);
                String date = day.getMonthValue() + "/" + day.getDayOfMonth();
                g2.drawString(date, x + (width - g2.getFontMetrics().stringWidth(date)) / 2, getHeight() - 8);
                if (value > 0) {
                    String text = String.format("%,d", value);
                    g2.setColor(Theme.FG);
                    g2.drawString(text, x + (width - g2.getFontMetrics().stringWidth(text)) / 2, bottom - height - 4);
                }
            }
            if (goal > 0) {
                int y = bottom - (int) ((bottom - top) * (goal / (double) max));
                g2.setColor(Theme.ACCENT);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{6, 6}, 0));
                g2.drawLine(left, y, getWidth() - left, y);
                g2.drawString("目標 " + String.format("%,d", goal), left, Math.max(labelHeight, y - 4));
            }
            g2.dispose();
        }
    }
}
