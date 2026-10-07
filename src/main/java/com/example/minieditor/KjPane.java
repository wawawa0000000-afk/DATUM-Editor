package com.example.minieditor;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * KJ法のボード。付箋（カード）を並べ、グループの枠で囲み、線でつなぐ。
 *
 * 操作：
 *   何もない所をダブルクリック … カードを追加
 *   カードをダブルクリック     … 文字を編集（Ctrl+Enter か、外をクリックで確定。Esc で取り消し）
 *   ドラッグ                   … 移動（グループ枠を動かすと、中のカードも一緒に動く）
 *   右下の角をドラッグ         … 大きさを変える
 *   右クリック                 … 色の変更・線でつなぐ・削除など
 *   Delete                     … 選んでいるカードを削除
 */
public class KjPane extends EditorPane {

    /** カード1枚（グループ枠も、group が true のカードとして扱う）。 */
    private static final class Card {
        int id;
        int x;
        int y;
        int w;
        int h;
        String text = "";
        int color;          // PALETTE の何番目か
        boolean group;

        Rectangle bounds() {
            return new Rectangle(x, y, w, h);
        }
    }

    /** カードとカードをつなぐ線。 */
    private record Link(int from, int to) {
    }

    private static final Color[] PALETTE = {
        new Color(0xF9E79F), new Color(0xABEBC6), new Color(0xAED6F1),
        new Color(0xF5B7B1), new Color(0xD7BDE2), new Color(0xD5D8DC)
    };
    private static final String[] PALETTE_NAMES = {"黄", "緑", "青", "赤", "紫", "灰"};
    private static final int HANDLE = 18;       // 右下の、大きさを変えるつまみの大きさ
    private static final int UNDO_LIMIT = 100;

    private final List<Card> cards = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private int nextId = 1;
    private Card selected;
    private Card linkSource;                    // 「線でつなぐ」を選んだあと、相手を待っている元のカード
    private Font cardFont = new Font("Dialog", Font.PLAIN, 24);

    // 「元に戻す」は、変更前のボード全体を JSON の文字列で覚えておく方式
    private final Deque<String> undoStack = new ArrayDeque<>();
    private final Deque<String> redoStack = new ArrayDeque<>();

    private final Board board = new Board();
    private final JScrollPane scrollPane = new JScrollPane(board);
    private JTextArea editor;                   // 文字を編集している間だけ出す入力欄
    private Card editingCard;

    public KjPane() {
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(Theme.BG);
        scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(24);
        add(scrollPane, BorderLayout.CENTER);
    }

    public static KjPane fromJson(Map<String, Object> json) {
        KjPane pane = new KjPane();
        pane.load(json);
        pane.setDirty(false);
        return pane;
    }

    // ---------- 保存・読み込み ----------

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> json = baseJson();
        json.put("fontSize", cardFont.getSize());
        List<Object> cardList = new ArrayList<>();
        for (Card card : cards) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", card.id);
            m.put("x", card.x);
            m.put("y", card.y);
            m.put("w", card.w);
            m.put("h", card.h);
            m.put("color", card.color);
            m.put("group", card.group);
            m.put("text", card.text);
            cardList.add(m);
        }
        json.put("cards", cardList);
        List<Object> linkList = new ArrayList<>();
        for (Link link : links) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", link.from());
            m.put("to", link.to());
            linkList.add(m);
        }
        json.put("links", linkList);
        return json;
    }

    @SuppressWarnings("unchecked")
    private void load(Map<String, Object> json) {
        cards.clear();
        links.clear();
        selected = null;
        linkSource = null;
        nextId = 1;
        cardFont = cardFont.deriveFont((float) Json.num(json, "fontSize", cardFont.getSize()));
        for (Object o : Json.list(json, "cards")) {
            if (o instanceof Map<?, ?> raw) {
                Map<String, Object> m = (Map<String, Object>) raw;
                Card card = new Card();
                card.id = Json.num(m, "id", nextId);
                card.x = Json.num(m, "x", 40);
                card.y = Json.num(m, "y", 40);
                card.w = Math.max(60, Json.num(m, "w", 240));
                card.h = Math.max(40, Json.num(m, "h", 140));
                card.color = Math.floorMod(Json.num(m, "color", 0), PALETTE.length);
                card.group = Json.bool(m, "group", false);
                card.text = Json.str(m, "text", "");
                cards.add(card);
                nextId = Math.max(nextId, card.id + 1);
            }
        }
        for (Object o : Json.list(json, "links")) {
            if (o instanceof Map<?, ?> raw) {
                Map<String, Object> m = (Map<String, Object>) raw;
                Link link = new Link(Json.num(m, "from", -1), Json.num(m, "to", -1));
                if (find(link.from()) != null && find(link.to()) != null) {
                    links.add(link);
                }
            }
        }
        board.revalidate();
        board.repaint();
    }

    /** .txt で書き出すときは、グループとカードの文字を箇条書きにする（位置は保存されない）。 */
    @Override
    public String toPlainText() {
        StringBuilder sb = new StringBuilder();
        for (Card card : cards) {
            sb.append(card.group ? "■ " : "- ").append(card.text.replace("\n", " ")).append('\n');
        }
        return sb.toString();
    }

    // ---------- 変更と「元に戻す」 ----------

    private String snapshot() {
        return Json.write(toJson());
    }

    /** 変更する前の状態を覚えてから、変更があったことを知らせる。 */
    private void commit(String before) {
        undoStack.push(before);
        while (undoStack.size() > UNDO_LIMIT) {
            undoStack.removeLast();
        }
        redoStack.clear();
        contentUpdated();
    }

    private void contentUpdated() {
        board.revalidate();
        board.repaint();
        fireContentChanged();
    }

    @SuppressWarnings("unchecked")
    private void restore(String state) {
        try {
            if (Json.parse(state) instanceof Map<?, ?> map) {
                load((Map<String, Object>) map);
            }
        } catch (Json.JsonException ex) {
            // 自分で書き出した JSON なので、通常は起きない
        }
        contentUpdated();
    }

    @Override
    public void undo() {
        if (editor != null || undoStack.isEmpty()) {
            return;
        }
        redoStack.push(snapshot());
        restore(undoStack.pop());
    }

    @Override
    public void redo() {
        if (editor != null || redoStack.isEmpty()) {
            return;
        }
        undoStack.push(snapshot());
        restore(redoStack.pop());
    }

    // ---------- カードの操作 ----------

    private Card find(int id) {
        for (Card card : cards) {
            if (card.id == id) {
                return card;
            }
        }
        return null;
    }

    /** その点にあるカードを返す。ふつうのカードを先に調べ、なければグループ枠を調べる。 */
    private Card cardAt(Point p) {
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card card = cards.get(i);
            if (!card.group && card.bounds().contains(p)) {
                return card;
            }
        }
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card card = cards.get(i);
            if (card.group && card.bounds().contains(p)) {
                return card;
            }
        }
        return null;
    }

    private Card addCard(Point p, boolean group) {
        String before = snapshot();
        int size = cardFont.getSize();
        Card card = new Card();
        card.id = nextId++;
        card.group = group;
        card.w = group ? size * 22 : size * 10;
        card.h = group ? size * 14 : size * 6;
        card.x = Math.max(8, p.x - (group ? 20 : card.w / 2));
        card.y = Math.max(8, p.y - (group ? 20 : card.h / 2));
        card.color = group ? 2 : 0;
        card.text = group ? "グループ" : "";
        cards.add(card);
        selected = card;
        commit(before);
        return card;
    }

    private void deleteCard(Card card) {
        if (card == null) {
            return;
        }
        String before = snapshot();
        cards.remove(card);
        links.removeIf(link -> link.from() == card.id || link.to() == card.id);
        if (selected == card) {
            selected = null;
        }
        commit(before);
    }

    private void connect(Card from, Card to) {
        if (from == to || from.group || to.group) {
            return;
        }
        for (Link link : links) {
            boolean same = (link.from() == from.id && link.to() == to.id)
                    || (link.from() == to.id && link.to() == from.id);
            if (same) {
                return;                 // もうつながっている
            }
        }
        String before = snapshot();
        links.add(new Link(from.id, to.id));
        commit(before);
    }

    // ---------- 文字の編集 ----------

    private void startEditing(Card card) {
        stopEditing(true);
        editingCard = card;
        editor = new JTextArea(card.text);
        editor.setFont(card.group ? cardFont.deriveFont(Font.BOLD) : cardFont);
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setBackground(card.group ? Theme.INPUT : PALETTE[card.color]);
        editor.setForeground(card.group ? Color.WHITE : new Color(0x1E1E1E));
        editor.setCaretColor(card.group ? Color.WHITE : Color.BLACK);
        editor.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        if (card.group) {
            editor.setBounds(card.x + 6, card.y + 6, card.w - 12, cardFont.getSize() * 2);
        } else {
            editor.setBounds(card.x, card.y, card.w, card.h);
        }

        // Esc で取り消し、Ctrl+Enter で確定
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelEdit");
        editor.getActionMap().put("cancelEdit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopEditing(false);
                board.requestFocusInWindow();
            }
        });
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "commitEdit");
        editor.getActionMap().put("commitEdit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopEditing(true);
                board.requestFocusInWindow();
            }
        });
        // 入力欄の外をクリックしたら確定
        editor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!e.isTemporary()) {
                    stopEditing(true);
                }
            }
        });

        board.add(editor);
        editor.requestFocusInWindow();
        editor.selectAll();
        board.repaint();
    }

    private void stopEditing(boolean keep) {
        if (editor == null) {
            return;
        }
        JTextArea finished = editor;
        Card card = editingCard;
        editor = null;                  // 先に null にする（focusLost から、もう一度呼ばれるため）
        editingCard = null;
        board.remove(finished);
        if (keep && cards.contains(card) && !finished.getText().equals(card.text)) {
            String before = snapshot();
            card.text = finished.getText();
            commit(before);
        }
        board.repaint();
    }

    // ---------- EditorPane ----------

    @Override
    public Mode getMode() {
        return Mode.KJ;
    }

    @Override
    public boolean isEmpty() {
        return cards.isEmpty();
    }

    @Override
    public String statusText() {
        long groups = cards.stream().filter(card -> card.group).count();
        String status = "カード " + (cards.size() - groups) + " 枚    グループ " + groups + "    線 " + links.size();
        if (linkSource != null) {
            status = "つなぐ相手のカードをクリックしてください（Esc で取り消し）    " + status;
        }
        return status;
    }

    @Override
    public void focusEditor() {
        board.requestFocusInWindow();
    }

    @Override
    public Font getEditorFont() {
        return cardFont;
    }

    @Override
    public void setEditorFont(Font font) {
        cardFont = font;
        board.repaint();
    }

    @Override
    public void cut() {
        if (editor == null && selected != null) {
            copy();
            deleteCard(selected);
        }
    }

    @Override
    public void copy() {
        if (editor == null && selected != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(selected.text), null);
        }
    }

    /** クリップボードの文字で、新しいカードを作る。 */
    @Override
    public void paste() {
        if (editor != null) {
            return;
        }
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (data instanceof String s && !s.isBlank()) {
                Rectangle visible = board.getVisibleRect();
                Card card = addCard(new Point(visible.x + visible.width / 2, visible.y + visible.height / 2), false);
                card.text = s.strip();
                board.repaint();
            }
        } catch (Exception ex) {
            // クリップボードに文字が入っていないときは、何もしない
        }
    }

    @Override
    public void selectAll() {
        // ボードには「すべて選択」がないので、何もしない
    }

    // ---------- 画面 ----------

    private final class Board extends JComponent {

        private Point pressPoint;
        private String stateAtPress;
        private boolean resizing;
        private boolean moved;
        private Rectangle startBounds;
        private final List<Card> dragMembers = new ArrayList<>();
        private final List<Point> dragStarts = new ArrayList<>();

        Board() {
            setLayout(null);            // 入力欄を、カードの位置にそのまま置くため
            setFocusable(true);
            setOpaque(true);

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    press(e);
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    drag(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    release(e);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        Card card = cardAt(e.getPoint());
                        startEditing(card != null ? card : addCard(e.getPoint(), false));
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);

            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_DELETE) {
                        deleteCard(selected);
                    } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        linkSource = null;
                        repaint();
                        fireStatusChanged();
                    } else if (e.getKeyCode() == KeyEvent.VK_ENTER && selected != null) {
                        startEditing(selected);
                    }
                }
            });
        }

        private void press(MouseEvent e) {
            stopEditing(true);
            requestFocusInWindow();
            Card card = cardAt(e.getPoint());

            if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                selected = card;
                repaint();
                showMenu(card, e.getPoint());
                return;
            }

            // 「線でつなぐ」の相手を選んでいる途中なら、ここでつなぐ
            if (linkSource != null) {
                if (card != null) {
                    connect(linkSource, card);
                }
                linkSource = null;
                selected = card;
                repaint();
                fireStatusChanged();
                return;
            }

            selected = card;
            pressPoint = e.getPoint();
            stateAtPress = snapshot();
            moved = false;
            resizing = false;
            dragMembers.clear();
            dragStarts.clear();
            if (card != null) {
                startBounds = card.bounds();
                resizing = e.getX() >= card.x + card.w - HANDLE && e.getY() >= card.y + card.h - HANDLE;
                dragMembers.add(card);
                // グループ枠を動かすときは、中に入っているカードも一緒に動かす
                if (card.group && !resizing) {
                    for (Card other : cards) {
                        if (!other.group && card.bounds().contains(other.x + other.w / 2, other.y + other.h / 2)) {
                            dragMembers.add(other);
                        }
                    }
                }
                for (Card member : dragMembers) {
                    dragStarts.add(new Point(member.x, member.y));
                }
                // 選んだカードを、重なりの一番上に出す（グループ枠は後ろのまま）
                if (!card.group) {
                    cards.remove(card);
                    cards.add(card);
                }
            }
            repaint();
        }

        private void drag(MouseEvent e) {
            if (selected == null || pressPoint == null || dragMembers.isEmpty()) {
                return;
            }
            int dx = e.getX() - pressPoint.x;
            int dy = e.getY() - pressPoint.y;
            if (resizing) {
                selected.w = Math.max(80, startBounds.width + dx);
                selected.h = Math.max(50, startBounds.height + dy);
            } else {
                for (int i = 0; i < dragMembers.size(); i++) {
                    Card member = dragMembers.get(i);
                    member.x = Math.max(0, dragStarts.get(i).x + dx);
                    member.y = Math.max(0, dragStarts.get(i).y + dy);
                }
            }
            moved = true;
            revalidate();
            repaint();
        }

        private void release(MouseEvent e) {
            if (e.isPopupTrigger() && pressPoint == null) {
                showMenu(cardAt(e.getPoint()), e.getPoint());
            }
            if (moved && stateAtPress != null) {
                commit(stateAtPress);
            }
            pressPoint = null;
            stateAtPress = null;
            moved = false;
        }

        private void showMenu(Card card, Point p) {
            JPopupMenu menu = new JPopupMenu();
            if (card == null) {
                menu.add(item("カードを追加", () -> startEditing(addCard(p, false))));
                menu.add(item("グループ枠を追加", () -> startEditing(addCard(p, true))));
            } else {
                menu.add(item("文字を編集", () -> startEditing(card)));
                JMenu colors = new JMenu("色");
                for (int i = 0; i < PALETTE.length; i++) {
                    int index = i;
                    colors.add(item(PALETTE_NAMES[i], () -> {
                        String before = snapshot();
                        card.color = index;
                        commit(before);
                    }));
                }
                menu.add(colors);
                if (!card.group) {
                    menu.add(item("線でつなぐ…", () -> {
                        linkSource = card;
                        repaint();
                        fireStatusChanged();
                    }));
                    menu.add(item("このカードの線を消す", () -> {
                        String before = snapshot();
                        if (links.removeIf(link -> link.from() == card.id || link.to() == card.id)) {
                            commit(before);
                        }
                    }));
                }
                menu.addSeparator();
                menu.add(item("削除", () -> deleteCard(card)));
            }
            menu.show(this, p.x, p.y);
        }

        private JMenuItem item(String label, Runnable action) {
            JMenuItem item = new JMenuItem(label);
            item.addActionListener(e -> action.run());
            return item;
        }

        @Override
        public Dimension getPreferredSize() {
            int width = 0;
            int height = 0;
            for (Card card : cards) {
                width = Math.max(width, card.x + card.w);
                height = Math.max(height, card.y + card.h);
            }
            Dimension viewport = scrollPane.getViewport().getExtentSize();
            // カードの外側にも余白を取り、いつでも右や下へ広げられるようにする
            return new Dimension(Math.max(width + 400, viewport.width), Math.max(height + 400, viewport.height));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle clip = g2.getClipBounds();
            g2.setColor(Theme.BG);
            g2.fillRect(clip.x, clip.y, clip.width, clip.height);

            // 背景の点（位置の目安）
            g2.setColor(new Color(0x3A3A3A));
            for (int x = clip.x - clip.x % 32; x < clip.x + clip.width; x += 32) {
                for (int y = clip.y - clip.y % 32; y < clip.y + clip.height; y += 32) {
                    g2.fillRect(x, y, 2, 2);
                }
            }

            if (cards.isEmpty()) {
                g2.setFont(Theme.ui());
                g2.setColor(Theme.FG_DIM);
                g2.drawString("ダブルクリックでカードを追加　／　右クリックでメニュー", 40, 60);
            }

            // 奥から順に：グループ枠 → 線 → カード
            for (Card card : cards) {
                if (card.group) {
                    paintGroup(g2, card);
                }
            }
            g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(0x9CDCFE));
            for (Link link : links) {
                Card from = find(link.from());
                Card to = find(link.to());
                if (from != null && to != null) {
                    g2.drawLine(from.x + from.w / 2, from.y + from.h / 2, to.x + to.w / 2, to.y + to.h / 2);
                }
            }
            for (Card card : cards) {
                if (!card.group && card != editingCard) {
                    paintCard(g2, card);
                }
            }
            g2.dispose();
        }

        private void paintGroup(Graphics2D g2, Card card) {
            Color base = PALETTE[card.color];
            g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 28));
            g2.fillRoundRect(card.x, card.y, card.w, card.h, 18, 18);
            g2.setStroke(new BasicStroke(card == selected ? 3f : 2f));
            g2.setColor(card == selected ? Theme.ACCENT : base);
            g2.drawRoundRect(card.x, card.y, card.w, card.h, 18, 18);
            if (card != editingCard) {
                g2.setFont(cardFont.deriveFont(Font.BOLD));
                g2.setColor(base);
                FontMetrics fm = g2.getFontMetrics();
                Shape oldClip = g2.getClip();
                g2.clipRect(card.x, card.y, card.w, card.h);
                g2.drawString(card.text.replace("\n", " "), card.x + 16, card.y + 12 + fm.getAscent());
                g2.setClip(oldClip);
            }
            paintHandle(g2, card);
        }

        private void paintCard(Graphics2D g2, Card card) {
            g2.setColor(new Color(0, 0, 0, 90));                   // 影
            g2.fillRoundRect(card.x + 3, card.y + 5, card.w, card.h, 12, 12);
            g2.setColor(PALETTE[card.color]);
            g2.fillRoundRect(card.x, card.y, card.w, card.h, 12, 12);
            if (card == selected || card == linkSource) {
                g2.setStroke(new BasicStroke(3f));
                g2.setColor(card == linkSource ? new Color(0x9CDCFE) : Theme.ACCENT);
                g2.drawRoundRect(card.x, card.y, card.w, card.h, 12, 12);
            }

            g2.setFont(cardFont);
            g2.setColor(new Color(0x1E1E1E));
            FontMetrics fm = g2.getFontMetrics();
            Shape oldClip = g2.getClip();
            g2.clipRect(card.x + 6, card.y + 4, card.w - 12, card.h - 8);
            int y = card.y + 8 + fm.getAscent();
            for (String line : wrap(card.text, fm, card.w - 20)) {
                g2.drawString(line, card.x + 10, y);
                y += fm.getHeight();
            }
            g2.setClip(oldClip);
            paintHandle(g2, card);
        }

        /** 選んでいるカードの右下に、大きさを変えるつまみ（三角）を描く。 */
        private void paintHandle(Graphics2D g2, Card card) {
            if (card != selected) {
                return;
            }
            int x = card.x + card.w;
            int y = card.y + card.h;
            g2.setColor(Theme.ACCENT);
            g2.fillPolygon(new int[]{x - HANDLE, x - 3, x - 3}, new int[]{y - 3, y - HANDLE, y - 3}, 3);
        }
    }

    /** 幅に収まるように、文字を1文字ずつ見て折り返す（日本語は単語の区切りがないため）。 */
    private static List<String> wrap(String text, FontMetrics fm, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < paragraph.length(); i++) {
                char c = paragraph.charAt(i);
                if (line.length() > 0 && fm.stringWidth(line.toString() + c) > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(c);
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
