package com.example.minieditor.apple;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * Apple的デザインのカードボード。
 * 操作: つかんで動かす / ダブルクリックか Enter・F2 で書く / 矢印キーで 8px（Shift で 32px）/
 *       線をクリックして選び Delete で消す / Alt を押しながらドラッグして線を横切ると切る（UE の BP と同じ）/
 *       Esc で選択解除 / Tab で次のカード / Ctrl+ホイール・Ctrl+「+」「−」で拡大縮小。
 */
public class BoardPanel extends JPanel {

    public static final class Card {
        public final String id;
        public String text;
        public final Rectangle2D.Double r;

        Card(String id, String text, double x, double y, double w, double h) {
            this.id = id;
            this.text = text;
            this.r = new Rectangle2D.Double(x, y, w, h);
        }
    }

    public static final class Group {
        public final String title;
        public final Rectangle2D.Double r;
        public final int depth;

        Group(String title, double x, double y, double w, double h, int depth) {
            this.title = title;
            this.r = new Rectangle2D.Double(x, y, w, h);
            this.depth = depth;
        }
    }

    private static final double[] ZOOMS = {0.75, 1.0, 1.25, 1.5};

    private final List<Group> groups = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<Card[]> links = new ArrayList<>();

    private AppleTheme theme;
    private int zoomIndex = 1;
    private Card selected;
    private Card[] selectedLink;
    private List<double[]> cutPath; // Alt+ドラッグで描いている切り筋（ボードの座標）
    private Card dragging;
    private double grabDx;
    private double grabDy;

    private final JTextField editor = new JTextField();
    private Card editing;
    private String editBackup;

    public BoardPanel(AppleTheme theme) {
        this.theme = theme;
        setLayout(null);
        setFocusable(true);
        setFocusTraversalKeysEnabled(false); // Tab は自分で扱う（端まで来たら外へ出す）
        getAccessibleContext().setAccessibleName("カードボード");

        editor.setBorder(BorderFactory.createEmptyBorder());
        editor.setOpaque(false);
        editor.setVisible(false);
        editor.getAccessibleContext().setAccessibleName("カードの内容");
        editor.addActionListener(e -> commitEdit());
        editor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitEdit();
            }
        });
        editor.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
        editor.getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancelEdit();
            }
        });
        add(editor);

        installMouse();
        installKeys();
    }

    // ---- モデルの組み立て ----

    public Card addCard(String id, String text, double x, double y, double w, double h) {
        Card c = new Card(id, text, x, y, w, h);
        cards.add(c);
        return c;
    }

    public Group addGroup(String title, double x, double y, double w, double h, int depth) {
        Group g = new Group(title, x, y, w, h, depth);
        groups.add(g);
        return g;
    }

    public void link(Card from, Card to) {
        links.add(new Card[] {from, to});
    }

    /** 線を消す。消せたら true。 */
    public boolean unlink(Card from, Card to) {
        boolean removed = links.removeIf(l -> l[0] == from && l[1] == to);
        if (selectedLink != null && selectedLink[0] == from && selectedLink[1] == to) {
            selectedLink = null;
        }
        repaint();
        return removed;
    }

    public int linkCount() {
        return links.size();
    }

    /** 選んでいる線を消す（Delete）。 */
    void deleteSelectedLink() {
        if (selectedLink != null) {
            unlink(selectedLink[0], selectedLink[1]);
        }
    }

    public List<Card> cards() {
        return cards;
    }

    public Card selected() {
        return selected;
    }

    public boolean isEditing() {
        return editing != null;
    }

    public void setTheme(AppleTheme t) {
        this.theme = t;
        if (editing != null) {
            layoutEditor();
        }
        repaint();
    }

    // ---- ズーム ----

    public double zoom() {
        return ZOOMS[zoomIndex];
    }

    public void zoomBy(int step) {
        int next = Math.max(0, Math.min(ZOOMS.length - 1, zoomIndex + step));
        if (next == zoomIndex) {
            return;
        }
        double old = zoom();
        zoomIndex = next;
        firePropertyChange("zoom", old, zoom());
        if (editing != null) {
            layoutEditor();
        }
        repaint();
    }

    // ---- 描画 ----

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        AppleTheme.quality(g);
        g.setColor(theme.canvas);
        g.fillRect(0, 0, getWidth(), getHeight());
        paintDots(g);
        g.scale(zoom(), zoom());
        for (Group gr : groups) {
            paintGroup(g, gr);
        }
        for (Card[] l : links) {
            paintArrow(g, BoardGeometry.arrow(l[0].r, l[1].r), l == selectedLink);
        }
        for (Card c : cards) {
            if (c != dragging) {
                paintCard(g, c);
            }
        }
        if (dragging != null) {
            paintCard(g, dragging); // 持ち上げたカードは最前面
        }
        paintCut(g);
        g.dispose();
    }

    private void paintDots(Graphics2D g) {
        double step = AppleTheme.DOT_STEP * zoom();
        g.setColor(theme.dot);
        for (double y = step; y < getHeight(); y += step) {
            for (double x = step; x < getWidth(); x += step) {
                g.fillRect((int) x, (int) y, 1, 1);
            }
        }
    }

    private void paintGroup(Graphics2D g, Group gr) {
        double radius = gr.depth == 0 ? AppleTheme.RADIUS_GROUP : AppleTheme.RADIUS_GROUP_INNER;
        RoundRectangle2D shape = new RoundRectangle2D.Double(
            gr.r.x, gr.r.y, gr.r.width, gr.r.height, radius, radius);
        g.setColor(gr.depth == 0 ? theme.groupFill : theme.groupFill2);
        g.fill(shape);
        g.setColor(theme.groupLine);
        g.setStroke(new BasicStroke(1f));
        g.draw(shape);
        g.setFont(AppleTheme.font(Font.BOLD, AppleTheme.GROUP_FONT));
        g.setColor(theme.textSecondary);
        int pad = gr.depth == 0 ? 24 : 16;
        int top = gr.depth == 0 ? 16 : 12;
        g.drawString(gr.title, (float) (gr.r.x + pad), (float) (gr.r.y + top + g.getFontMetrics().getAscent()));
    }

    /** Alt+ドラッグの切り筋（点線）。線を横切ると、離したときに切れる。 */
    private void paintCut(Graphics2D g) {
        if (cutPath == null || cutPath.size() < 2) {
            return;
        }
        g.setColor(theme.accent);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            1f, new float[] {4f, 4f}, 0f));
        for (int i = 1; i < cutPath.size(); i++) {
            double[] a = cutPath.get(i - 1);
            double[] b = cutPath.get(i);
            g.draw(new Line2D.Double(a[0], a[1], b[0], b[1]));
        }
    }

    private void paintArrow(Graphics2D g, CubicCurve2D.Double c, boolean isSelected) {
        g.setColor(isSelected ? theme.accent : theme.arrow);
        g.setStroke(new BasicStroke(
            isSelected ? AppleTheme.ARROW_WIDTH + 1.5f : AppleTheme.ARROW_WIDTH,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(c);
        // 先端は小さなシェブロン（く の字）
        double angle = Math.atan2(c.y2 - c.ctrly2, c.x2 - c.ctrlx2);
        for (int s = -1; s <= 1; s += 2) {
            double a = angle + Math.PI + s * AppleTheme.ARROW_SPREAD;
            g.draw(new Line2D.Double(
                c.x2, c.y2, c.x2 + AppleTheme.ARROW_ARM * Math.cos(a), c.y2 + AppleTheme.ARROW_ARM * Math.sin(a)));
        }
    }

    private void paintCard(Graphics2D g0, Card c) {
        Graphics2D g = (Graphics2D) g0.create();
        boolean lifted = c == dragging;
        if (lifted) { // 持ち上げ: 少し大きく、影は深く
            g.translate(c.r.getCenterX(), c.r.getCenterY());
            g.scale(AppleTheme.LIFT_SCALE, AppleTheme.LIFT_SCALE);
            g.translate(-c.r.getCenterX(), -c.r.getCenterY());
        }
        double r = AppleTheme.RADIUS_CARD;
        AppleTheme.softShadow(g, c.r.x, c.r.y, c.r.width, c.r.height, r, lifted ? 18 : 6);
        RoundRectangle2D shape = new RoundRectangle2D.Double(c.r.x, c.r.y, c.r.width, c.r.height, r, r);
        g.setColor(theme.card);
        g.fill(shape);
        if (c == selected || c == editing) { // 選択はアクセント色のリング（色だけでなく形で示す）
            double half = AppleTheme.RING_WIDTH / 2;
            g.setColor(theme.accent);
            g.setStroke(new BasicStroke(AppleTheme.RING_WIDTH));
            g.draw(new RoundRectangle2D.Double(
                c.r.x - half, c.r.y - half, c.r.width + half * 2, c.r.height + half * 2, r + half * 2, r + half * 2));
        }
        if (c != editing) {
            paintCardText(g, c);
        }
        g.dispose();
    }

    private void paintCardText(Graphics2D g, Card c) {
        g.setFont(AppleTheme.font(Font.BOLD, AppleTheme.CARD_FONT));
        FontMetrics fm = g.getFontMetrics();
        double x = c.r.x + AppleTheme.CARD_PAD_X;
        double y = c.r.y + AppleTheme.CARD_PAD_Y;
        if (c.text == null || c.text.isEmpty()) {
            g.setColor(theme.cardPlaceholder);
            g.drawString("無題", (float) x, (float) (y + fm.getAscent()));
            return;
        }
        g.setColor(theme.cardText);
        float lineH = fm.getHeight() * 1.1f;
        int maxW = (int) (c.r.width - AppleTheme.CARD_PAD_X * 2);
        for (String line : wrap(c.text, fm, maxW, AppleTheme.CARD_LINES)) {
            g.drawString(line, (float) x, (float) (y + fm.getAscent()));
            y += lineH;
        }
    }

    /** 文字単位で折り返す（日本語は空白がない）。maxLines 行を超えたら末尾を「…」にする。 */
    static List<String> wrap(String s, FontMetrics fm, int maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        boolean truncated = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || (line.length() > 0 && fm.stringWidth(line.toString() + ch) > maxW)) {
                out.add(line.toString());
                line.setLength(0);
                if (out.size() == maxLines) {
                    truncated = i < s.length() - (ch == '\n' ? 1 : 0);
                    break;
                }
                if (ch == '\n') {
                    continue;
                }
            }
            line.append(ch);
        }
        if (out.size() < maxLines && line.length() > 0) {
            out.add(line.toString());
        }
        if (truncated && !out.isEmpty()) {
            String last = out.get(out.size() - 1);
            out.set(out.size() - 1, (last.length() > 1 ? last.substring(0, last.length() - 1) : last) + "…");
        }
        return out;
    }

    // ---- マウス ----

    private Card cardAt(double mx, double my) {
        for (int i = cards.size() - 1; i >= 0; i--) {
            if (cards.get(i).r.contains(mx, my)) {
                return cards.get(i);
            }
        }
        return null;
    }

    /** 曲線を細かい線分にして返す（当たり判定用）。 */
    private static List<double[]> flatten(CubicCurve2D.Double c) {
        List<double[]> pts = new ArrayList<>();
        double[] co = new double[6];
        FlatteningPathIterator it = new FlatteningPathIterator(c.getPathIterator(null), 0.5);
        while (!it.isDone()) {
            it.currentSegment(co);
            pts.add(new double[] {co[0], co[1]});
            it.next();
        }
        return pts;
    }

    /** (mx,my) に近い線を返す。なければ null。 */
    private Card[] linkAt(double mx, double my) {
        double tol = 6 / zoom();
        for (int i = links.size() - 1; i >= 0; i--) {
            Card[] l = links.get(i);
            List<double[]> pts = flatten(BoardGeometry.arrow(l[0].r, l[1].r));
            for (int k = 1; k < pts.size(); k++) {
                if (Line2D.ptSegDist(pts.get(k - 1)[0], pts.get(k - 1)[1], pts.get(k)[0], pts.get(k)[1], mx, my) <= tol) {
                    return l;
                }
            }
        }
        return null;
    }

    /** 切り筋と交わる線をすべて消す。消した本数を返す。 */
    int cutAlong(List<double[]> path) {
        List<Card[]> doomed = new ArrayList<>();
        for (Card[] l : links) {
            List<double[]> pts = flatten(BoardGeometry.arrow(l[0].r, l[1].r));
            if (crosses(pts, path)) {
                doomed.add(l);
            }
        }
        links.removeAll(doomed);
        if (selectedLink != null && doomed.contains(selectedLink)) {
            selectedLink = null;
        }
        return doomed.size();
    }

    private static boolean crosses(List<double[]> a, List<double[]> b) {
        for (int i = 1; i < a.size(); i++) {
            for (int j = 1; j < b.size(); j++) {
                if (Line2D.linesIntersect(
                    a.get(i - 1)[0], a.get(i - 1)[1], a.get(i)[0], a.get(i)[1],
                    b.get(j - 1)[0], b.get(j - 1)[1], b.get(j)[0], b.get(j)[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    private void installMouse() {
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                commitEdit();
                double mx = e.getX() / zoom();
                double my = e.getY() / zoom();
                if (e.isAltDown() && SwingUtilities.isLeftMouseButton(e)) { // 切り筋を描き始める
                    cutPath = new ArrayList<>();
                    cutPath.add(new double[] {mx, my});
                    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                    repaint();
                    return;
                }
                Card hit = cardAt(mx, my);
                selected = hit;
                selectedLink = hit != null ? null : linkAt(mx, my);
                if (hit != null) {
                    dragging = hit;
                    grabDx = mx - hit.r.x;
                    grabDy = my - hit.r.y;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    if (e.getClickCount() == 2) {
                        dragging = null;
                        beginEdit(hit);
                    }
                }
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (cutPath != null) {
                    cutPath.add(new double[] {e.getX() / zoom(), e.getY() / zoom()});
                    repaint();
                    return;
                }
                if (dragging == null) {
                    return;
                }
                // 拡大率で割って、ボードの座標に直す（ポインタとカードが同じだけ動く）
                dragging.r.x = Math.max(0, Math.round(e.getX() / zoom() - grabDx));
                dragging.r.y = Math.max(0, Math.round(e.getY() / zoom() - grabDy));
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (cutPath != null) {
                    cutAlong(cutPath);
                    cutPath = null;
                }
                dragging = null;
                updateCursor(e);
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                updateCursor(e);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown()) {
                    zoomBy(e.getWheelRotation() < 0 ? 1 : -1);
                } else if (getParent() != null) {
                    getParent().dispatchEvent(retarget(e, getParent()));
                }
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
    }

    private void updateCursor(MouseEvent e) {
        double mx = e.getX() / zoom();
        double my = e.getY() / zoom();
        int cursor = e.isAltDown() ? Cursor.CROSSHAIR_CURSOR
            : cardAt(mx, my) != null || linkAt(mx, my) != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR;
        setCursor(Cursor.getPredefinedCursor(cursor));
    }

    /** 親（スクロール枠など）へホイールをそのまま渡すための写し。 */
    private static MouseWheelEvent retarget(MouseWheelEvent e, Component target) {
        return new MouseWheelEvent(target, e.getID(), e.getWhen(), e.getModifiersEx(),
            e.getX(), e.getY(), e.getClickCount(), e.isPopupTrigger(), e.getScrollType(),
            e.getScrollAmount(), e.getWheelRotation());
    }

    // ---- キーボード ----

    private void bind(String stroke, Runnable r) {
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), stroke);
        getActionMap().put(stroke, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private void installKeys() {
        bind("LEFT", () -> nudge(-8, 0));
        bind("RIGHT", () -> nudge(8, 0));
        bind("UP", () -> nudge(0, -8));
        bind("DOWN", () -> nudge(0, 8));
        bind("shift LEFT", () -> nudge(-32, 0));
        bind("shift RIGHT", () -> nudge(32, 0));
        bind("shift UP", () -> nudge(0, -32));
        bind("shift DOWN", () -> nudge(0, 32));
        bind("ENTER", () -> {
            if (selected != null) {
                beginEdit(selected);
            }
        });
        bind("F2", () -> {
            if (selected != null) {
                beginEdit(selected);
            }
        });
        bind("DELETE", this::deleteSelectedLink);
        bind("BACK_SPACE", this::deleteSelectedLink);
        bind("ESCAPE", () -> {
            selected = null;
            selectedLink = null;
            repaint();
        });
        bind("TAB", () -> step(1));
        bind("shift TAB", () -> step(-1));
        // 「+」は配列によってキーが違う（US は =、JIS は ;、テンキーは ADD）
        bind("ctrl EQUALS", () -> zoomBy(1));
        bind("ctrl SEMICOLON", () -> zoomBy(1));
        bind("ctrl shift SEMICOLON", () -> zoomBy(1));
        bind("ctrl PLUS", () -> zoomBy(1));
        bind("ctrl ADD", () -> zoomBy(1));
        bind("ctrl MINUS", () -> zoomBy(-1));
        bind("ctrl SUBTRACT", () -> zoomBy(-1));
    }

    private void nudge(double dx, double dy) {
        if (selected == null) {
            return;
        }
        selected.r.x = Math.max(0, selected.r.x + dx);
        selected.r.y = Math.max(0, selected.r.y + dy);
        repaint();
    }

    /** Tab: 次のカードへ。端まで行ったら選択を外して、フォーカスをボードの外へ渡す。 */
    void step(int dir) {
        if (cards.isEmpty()) {
            transferFocusOut(dir);
            return;
        }
        int i = selected == null ? (dir > 0 ? -1 : cards.size()) : cards.indexOf(selected);
        int n = i + dir;
        if (n < 0 || n >= cards.size()) {
            selected = null;
            repaint();
            transferFocusOut(dir);
            return;
        }
        selected = cards.get(n);
        repaint();
    }

    private void transferFocusOut(int dir) {
        if (dir > 0) {
            transferFocus();
        } else {
            transferFocusBackward();
        }
    }

    // ---- その場で書く ----

    void beginEdit(Card c) {
        editing = c;
        editBackup = c.text;
        editor.setText(c.text == null ? "" : c.text);
        layoutEditor();
        editor.setVisible(true);
        editor.requestFocusInWindow();
        editor.selectAll();
        repaint();
    }

    private void layoutEditor() {
        double z = zoom();
        editor.setFont(AppleTheme.font(Font.BOLD, (float) (AppleTheme.CARD_FONT * z)));
        editor.setForeground(theme.cardText);
        editor.setCaretColor(theme.cardText);
        editor.setBounds(
            (int) ((editing.r.x + AppleTheme.CARD_PAD_X) * z), (int) ((editing.r.y + AppleTheme.CARD_PAD_Y) * z),
            (int) ((editing.r.width - AppleTheme.CARD_PAD_X * 2) * z), (int) (26 * z));
    }

    /** 書いている内容を確定する（Enter・外をクリック・フォーカスが外れたとき）。 */
    void commitEdit() {
        if (editing == null) {
            return;
        }
        Card c = editing;
        editing = null;
        c.text = editor.getText();
        editor.setVisible(false);
        requestFocusInWindow();
        repaint();
    }

    /** 書いている内容を捨てて、元に戻す（Esc）。 */
    void cancelEdit() {
        if (editing == null) {
            return;
        }
        editing.text = editBackup;
        editing = null;
        editor.setVisible(false);
        requestFocusInWindow();
        repaint();
    }

    /** テスト用：書きかけの欄に文字を入れる。 */
    void typeForTest(String s) {
        editor.setText(s);
    }
}
