package com.example.minieditor;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
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
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * カードボード。付箋（カード）を並べ、グループの枠で囲み、矢印でつなぐ。
 * Miro や FigJam などのオンラインのボードにある機能を参考にしている。
 *
 * 操作：
 *   何もない所をダブルクリック … カードを追加
 *   カードをダブルクリック     … 文字を編集（Ctrl+Enter か、外をクリックで確定。Esc で取り消し）
 *   ドラッグ                   … 移動（グループ枠を動かすと、中のカードも一緒に動く）
 *   何もない所からドラッグ     … 囲んだカードをまとめて選択（Shift+クリックで追加）
 *   ふちや角をドラッグ         … 大きさを変える（ふちに触れると、矢印の形が変わる）
 *   Ctrl+ホイール              … 拡大・縮小　／　中ボタンでドラッグ … 表示位置を動かす
 *   右クリック                 … 色・投票・矢印・整列・グループ化など
 */
public class BoardPane extends EditorPane {

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
        int votes;          // ドット投票の数

        Rectangle bounds() {
            return new Rectangle(x, y, w, h);
        }
    }

    /** カードからカードへの矢印。 */
    private record Link(int from, int to) {
    }

    private static final Color[] PALETTE = {
        new Color(0xF9E79F), new Color(0xABEBC6), new Color(0xAED6F1),
        new Color(0xF5B7B1), new Color(0xD7BDE2), new Color(0xD5D8DC)
    };
    private static final String[] PALETTE_NAMES = {"黄", "緑", "青", "赤", "紫", "灰"};
    private static final Color LINK_COLOR = new Color(0x9CDCFE);
    private static final int HANDLE = 18;       // 選んだカードの右下に出す、目印の三角の大きさ
    private static final int GAP = 24;          // 整列するときの、カードとカードのすき間
    private static final int UNDO_LIMIT = 100;
    private static final double MIN_SCALE = 0.2;
    private static final double MAX_SCALE = 3.0;

    private final List<Card> cards = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private final Set<Card> selection = new LinkedHashSet<>();     // 選んでいるカード（複数可）
    private int nextId = 1;
    private Card linkSource;                    // 「矢印でつなぐ」を選んだあと、相手を待っている元のカード
    private Font cardFont = new Font("Dialog", Font.PLAIN, Theme.UI_SIZE + 2);
    private double scale = 1.0;                 // 表示の倍率（保存はしない）

    // 「元に戻す」は、変更前のボード全体を JSON の文字列で覚えておく方式
    private final Deque<String> undoStack = new ArrayDeque<>();
    private final Deque<String> redoStack = new ArrayDeque<>();

    private final Board board = new Board();
    private final JScrollPane scrollPane = new JScrollPane(board);
    private final WheelScroll wheelScroll = new WheelScroll(scrollPane, false);
    private JTextArea editor;                   // 文字を編集している間だけ出す入力欄
    private Card editingCard;

    public BoardPane() {
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(Theme.BG);
        scrollPane.setWheelScrollingEnabled(false);     // ホイールは Board 側で受け取る（拡大・縮小のため）
        add(scrollPane, BorderLayout.CENTER);
    }

    public static BoardPane fromJson(Map<String, Object> json) {
        BoardPane pane = new BoardPane();
        pane.load(json);
        pane.setDirty(false);
        return pane;
    }

    // ---------- 保存・読み込み ----------

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> json = baseJson();
        json.put("fontSize", Theme.unzoomed(cardFont.getSize()));   // 等倍のときの大きさで保存する
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
            m.put("votes", card.votes);
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
        selection.clear();
        linkSource = null;
        nextId = 1;
        cardFont = cardFont.deriveFont((float) Theme.zoomed(Json.num(json, "fontSize", Theme.unzoomed(cardFont.getSize()))));
        for (Object o : Json.list(json, "cards")) {
            if (o instanceof Map<?, ?> raw) {
                Map<String, Object> m = (Map<String, Object>) raw;
                Card card = new Card();
                card.id = Json.num(m, "id", nextId);
                card.x = Math.max(0, Json.num(m, "x", 40));
                card.y = Math.max(0, Json.num(m, "y", 40));
                card.w = Math.max(60, Json.num(m, "w", 240));
                card.h = Math.max(40, Json.num(m, "h", 140));
                card.color = Math.floorMod(Json.num(m, "color", 0), PALETTE.length);
                card.group = Json.bool(m, "group", false);
                card.votes = Math.max(0, Json.num(m, "votes", 0));
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

    /** ボード全体を1枚の画像にする（「画像として書き出す」で使う）。カードがなければ null。 */
    public BufferedImage renderImage() {
        return renderImage(1.0);
    }

    /** zoom 倍の細かさで、ボード全体を画像にする（PDF では、拡大しても粗くならないように 2 倍にする）。 */
    private BufferedImage renderImage(double zoom) {
        Rectangle bounds = contentBounds(cards);
        if (bounds == null) {
            return null;
        }
        bounds.grow(40, 40);
        BufferedImage image = new BufferedImage((int) Math.ceil(bounds.width * zoom),
                (int) Math.ceil(bounds.height * zoom), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        g2.scale(zoom, zoom);
        g2.translate(-bounds.x, -bounds.y);
        g2.setClip(bounds);
        board.paintBoard(g2, false);
        g2.dispose();
        return image;
    }

    /** ボード全体を、PDF の1ページにする。紙の大きさは、ボードの形に合わせる。 */
    @Override
    public void exportPdf(PdfWriter writer) throws java.io.IOException {
        BufferedImage image = renderImage(2.0);
        if (image == null) {
            throw new java.io.IOException("カードがないので、書き出すものがありません");
        }
        // 画面の 1 ピクセルを 0.75 ポイント（= 1/96 インチ）として、紙の大きさを決める
        writer.addPage(image, image.getWidth() / 2.0 * 0.75, image.getHeight() / 2.0 * 0.75);
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

    // ---------- カードを探す・選ぶ ----------

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

    private void select(Card card) {
        selection.clear();
        if (card != null) {
            selection.add(card);
        }
    }

    /** 選んでいるカードが1枚だけなら、それを返す。 */
    private Card single() {
        return selection.size() == 1 ? selection.iterator().next() : null;
    }

    /** 選んでいる、ふつうのカード（グループ枠を除く）。 */
    private List<Card> selectedNotes() {
        List<Card> notes = new ArrayList<>();
        for (Card card : selection) {
            if (!card.group) {
                notes.add(card);
            }
        }
        return notes;
    }

    private List<Card> allNotes() {
        List<Card> notes = new ArrayList<>();
        for (Card card : cards) {
            if (!card.group) {
                notes.add(card);
            }
        }
        return notes;
    }

    /** カードの集まりを、ちょうど囲む四角。1枚もなければ null。 */
    private static Rectangle contentBounds(Iterable<Card> items) {
        Rectangle bounds = null;
        for (Card card : items) {
            bounds = (bounds == null) ? card.bounds() : bounds.union(card.bounds());
        }
        return bounds;
    }

    // ---------- カードの操作 ----------

    private Card newCard(int x, int y, boolean group) {
        int size = cardFont.getSize();
        Card card = new Card();
        card.id = nextId++;
        card.group = group;
        card.w = group ? size * 22 : size * 10;
        card.h = group ? size * 14 : size * 6;
        card.x = Math.max(8, x);
        card.y = Math.max(8, y);
        card.color = group ? 2 : 0;
        card.text = group ? "グループ" : "";
        cards.add(group ? 0 : cards.size(), card);  // グループ枠は奥に、カードは手前に置く
        return card;
    }

    private Card addCard(Point p, boolean group) {
        String before = snapshot();
        Card card = newCard(p.x, p.y, group);
        if (!group) {
            card.x = Math.max(8, p.x - card.w / 2);
            card.y = Math.max(8, p.y - card.h / 2);
        }
        select(card);
        commit(before);
        return card;
    }

    /** 選んでいるカードを削除する。つながっている矢印も消す。 */
    public void deleteSelected() {
        if (editor != null || selection.isEmpty()) {
            return;
        }
        String before = snapshot();
        for (Card card : selection) {
            links.removeIf(link -> link.from() == card.id || link.to() == card.id);
        }
        cards.removeAll(selection);
        selection.clear();
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

    /** 文字が全部見えるように、カードの高さを広げる。 */
    private void fitHeight(Card card) {
        if (card.group) {
            return;
        }
        FontMetrics fm = board.getFontMetrics(cardFont);
        int needed = wrap(card.text, fm, card.w - 20).size() * fm.getHeight() + 20 + voteHeight(card);
        card.h = Math.max(card.h, needed);
    }

    private int voteHeight(Card card) {
        return card.votes > 0 ? 22 : 0;
    }

    /** 選んでいるカードを、少しずらして複製する。 */
    public void duplicateSelected() {
        if (editor != null || selection.isEmpty()) {
            return;
        }
        String before = snapshot();
        List<Card> copies = new ArrayList<>();
        for (Card source : new ArrayList<>(selection)) {
            Card copy = newCard(source.x + 30, source.y + 30, source.group);
            copy.w = source.w;
            copy.h = source.h;
            copy.color = source.color;
            copy.text = source.text;
            copies.add(copy);
        }
        selection.clear();
        selection.addAll(copies);
        commit(before);
    }

    /** 選んでいるカードを囲む、グループ枠を作る（オンラインのボードの「クラスタ」にあたる）。 */
    public void groupSelected() {
        List<Card> notes = selectedNotes();
        if (editor != null || notes.isEmpty()) {
            return;
        }
        String before = snapshot();
        Rectangle bounds = contentBounds(notes);
        int titleHeight = cardFont.getSize() * 2 + 12;
        Card group = newCard(bounds.x - GAP, bounds.y - titleHeight, true);
        group.w = bounds.width + 2 * GAP;
        group.h = bounds.height + titleHeight + GAP;
        select(group);
        commit(before);
        startEditing(group);            // そのまま、グループの名前を入力してもらう
    }

    /** カードを、すき間をそろえて格子状に並べ直す。選んでいなければ、全部のカードが対象。 */
    public void tidy() {
        List<Card> notes = selectedNotes().size() >= 2 ? selectedNotes() : allNotes();
        if (editor != null || notes.size() < 2) {
            return;
        }
        String before = snapshot();
        Rectangle origin = contentBounds(notes);
        int cellW = 0;
        int cellH = 0;
        for (Card card : notes) {
            cellW = Math.max(cellW, card.w);
            cellH = Math.max(cellH, card.h);
        }
        // 今の位置に近い順（上から下、左から右）に並べるので、並びが大きく入れ替わらない
        int rowHeight = Math.max(1, cellH);
        notes.sort(Comparator.comparingInt((Card card) -> card.y / rowHeight)
                .thenComparingInt(card -> card.x));
        int columns = (int) Math.ceil(Math.sqrt(notes.size()));
        for (int i = 0; i < notes.size(); i++) {
            notes.get(i).x = origin.x + (i % columns) * (cellW + GAP);
            notes.get(i).y = origin.y + (i / columns) * (cellH + GAP);
        }
        commit(before);
    }

    /** 同じ色のカードを、縦の列にまとめて並べる。 */
    public void arrangeByColor() {
        List<Card> notes = selectedNotes().size() >= 2 ? selectedNotes() : allNotes();
        if (editor != null || notes.size() < 2) {
            return;
        }
        String before = snapshot();
        Rectangle origin = contentBounds(notes);
        int cellW = 0;
        for (Card card : notes) {
            cellW = Math.max(cellW, card.w);
        }
        int column = 0;
        for (int color = 0; color < PALETTE.length; color++) {
            int y = origin.y;
            boolean used = false;
            for (Card card : notes) {
                if (card.color == color) {
                    card.x = origin.x + column * (cellW + GAP);
                    card.y = y;
                    y += card.h + GAP;
                    used = true;
                }
            }
            if (used) {
                column++;
            }
        }
        commit(before);
    }

    /** 選んでいるカードの投票数を増減する（ドット投票）。 */
    public void vote(int delta) {
        List<Card> notes = selectedNotes();
        if (editor != null || notes.isEmpty()) {
            return;
        }
        String before = snapshot();
        for (Card card : notes) {
            card.votes = Math.max(0, card.votes + delta);
            fitHeight(card);
        }
        commit(before);
    }

    /** 複数行の文章から、1行につき1枚のカードを作る。 */
    public void addCardsFromLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }
        if (editor != null || lines.isEmpty()) {
            return;
        }
        String before = snapshot();
        Rectangle visible = visibleModelRect();
        int columns = (int) Math.ceil(Math.sqrt(lines.size()));
        selection.clear();
        int rowTop = visible.y + 40;
        int rowHeight = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0 && i % columns == 0) {
                rowTop += rowHeight + GAP;
                rowHeight = 0;
            }
            Card card = newCard(0, rowTop, false);
            card.text = lines.get(i);
            fitHeight(card);
            card.x = visible.x + 40 + (i % columns) * (card.w + GAP);
            rowHeight = Math.max(rowHeight, card.h);
            selection.add(card);
        }
        commit(before);
    }

    // ---------- 拡大・縮小 ----------

    private Point toModel(Point view) {
        return new Point((int) Math.round(view.x / scale), (int) Math.round(view.y / scale));
    }

    private Rectangle visibleModelRect() {
        Rectangle v = board.getVisibleRect();
        return new Rectangle((int) (v.x / scale), (int) (v.y / scale),
                (int) (v.width / scale), (int) (v.height / scale));
    }

    /** 倍率を変える。anchor（画面上の点）にある物が、動かないようにする。 */
    private void setScale(double newScale, Point anchor) {
        newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
        if (newScale == scale) {
            return;
        }
        stopEditing(true);
        JViewport viewport = scrollPane.getViewport();
        Point position = viewport.getViewPosition();
        double modelX = anchor.x / scale;
        double modelY = anchor.y / scale;
        int offsetX = anchor.x - position.x;
        int offsetY = anchor.y - position.y;
        scale = newScale;
        board.revalidate();
        scrollPane.validate();
        viewport.setViewPosition(new Point(
                Math.max(0, (int) Math.round(modelX * scale) - offsetX),
                Math.max(0, (int) Math.round(modelY * scale) - offsetY)));
        board.repaint();
        fireStatusChanged();
    }

    public void zoomBy(double factor) {
        Rectangle v = board.getVisibleRect();
        setScale(scale * factor, new Point(v.x + v.width / 2, v.y + v.height / 2));
    }

    /** すべてのカードが画面に入るように、倍率と位置を合わせる。 */
    public void fitAll() {
        Rectangle bounds = contentBounds(cards);
        Dimension extent = scrollPane.getViewport().getExtentSize();
        if (bounds == null || extent.width <= 0 || extent.height <= 0) {
            return;
        }
        bounds.grow(40, 40);
        stopEditing(true);
        scale = Math.max(MIN_SCALE, Math.min(1.0,     // 100% より大きくはしない
                Math.min(extent.width / (double) bounds.width, extent.height / (double) bounds.height)));
        board.revalidate();
        scrollPane.validate();
        scrollPane.getViewport().setViewPosition(new Point(
                Math.max(0, (int) (bounds.x * scale)), Math.max(0, (int) (bounds.y * scale))));
        board.repaint();
        fireStatusChanged();
    }

    // ---------- 文字の編集 ----------

    private void startEditing(Card card) {
        stopEditing(true);
        editingCard = card;
        editor = new JTextArea(card.text);
        Font base = card.group ? cardFont.deriveFont(Font.BOLD) : cardFont;
        editor.setFont(base.deriveFont((float) (base.getSize() * scale)));
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setBackground(card.group ? Theme.INPUT : PALETTE[card.color]);
        editor.setForeground(card.group ? Theme.FG_STRONG : new Color(0x1E1E1E));
        editor.setCaretColor(card.group ? Theme.FG_STRONG : Color.BLACK);
        int pad = (int) Math.round(8 * scale);
        editor.setBorder(BorderFactory.createEmptyBorder(pad, pad, pad, pad));
        // 入力欄は拡大・縮小の外にあるので、位置と大きさに倍率を掛けて合わせる
        Rectangle r = card.group
                ? new Rectangle(card.x + 6, card.y + 6, card.w - 12, cardFont.getSize() * 2)
                : card.bounds();
        editor.setBounds((int) (r.x * scale), (int) (r.y * scale), (int) (r.width * scale), (int) (r.height * scale));

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
            fitHeight(card);
            commit(before);
        }
        board.repaint();
    }

    // ---------- EditorPane ----------

    @Override
    public Mode getMode() {
        return Mode.BOARD;
    }

    @Override
    public boolean isEmpty() {
        return cards.isEmpty();
    }

    @Override
    public String statusText() {
        long groups = cards.stream().filter(card -> card.group).count();
        String status = "カード " + (cards.size() - groups) + " 枚    グループ " + groups + "    矢印 " + links.size();
        if (selection.size() > 1) {
            status += "    " + selection.size() + " 個を選択";
        }
        status += "    " + Math.round(scale * 100) + "%";
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
        if (editor == null && !selection.isEmpty()) {
            copy();
            deleteSelected();
        }
    }

    /** 選んでいるカードの文字を、1枚1行でコピーする。 */
    @Override
    public void copy() {
        if (editor != null || selection.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Card card : selection) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(card.text.replace("\n", " "));
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
    }

    /** クリップボードの文字から、カードを作る。複数行なら、1行につき1枚になる。 */
    @Override
    public void paste() {
        if (editor != null) {
            return;
        }
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (data instanceof String s) {
                addCardsFromLines(s);
            }
        } catch (Exception ex) {
            // クリップボードに文字が入っていないときは、何もしない
        }
    }

    @Override
    public void selectAll() {
        if (editor == null) {
            selection.clear();
            selection.addAll(cards);
            board.repaint();
            fireStatusChanged();
        }
    }

    // ---------- 画面 ----------

    private final class Board extends JComponent {

        private Point pressModel;               // 押した位置（ボード上の座標）
        private Point pressView;                // 押した位置（画面上の座標）
        private String stateAtPress;
        private boolean resizing;
        private int resizeEdges;                // 動かしているふち（NORTH などの組み合わせ）
        private Card resizeCard;                // 大きさを変えているカード
        private boolean moved;
        private boolean panning;
        private Point panStartPosition;
        private Rectangle startBounds;
        private Rectangle rubberBand;           // 範囲選択の四角
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
                    release();
                    updateCursor(e.getPoint());
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    updateCursor(e.getPoint());     // ふちに触れたら、矢印の形を変える
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        Point p = toModel(e.getPoint());
                        Card card = cardAt(p);
                        startEditing(card != null ? card : addCard(p, false));
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    wheel(e);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);

            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.isControlDown() || e.isAltDown()) {
                        return;         // Ctrl+D などは、メニューのショートカットに任せる
                    }
                    if (e.getKeyCode() == KeyEvent.VK_DELETE) {
                        deleteSelected();
                    } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        linkSource = null;
                        selection.clear();
                        repaint();
                        fireStatusChanged();
                    } else if (e.getKeyCode() == KeyEvent.VK_ENTER && single() != null) {
                        startEditing(single());
                    }
                }
            });
        }

        // ふちの向き。足し合わせて「左上の角」（NORTH + WEST）のように表す
        private static final int NORTH = 1;
        private static final int SOUTH = 2;
        private static final int WEST = 4;
        private static final int EAST = 8;

        /** ふちとみなす幅。縮小表示でも、画面上で 8 ピクセルぶんはつかめるようにする。 */
        private int edgeTolerance() {
            return (int) Math.ceil(8 / scale);
        }

        /** 点 p が、カードのどのふちに触れているかを返す。触れていなければ 0。 */
        private int edgesAt(Card card, Point p) {
            int t = edgeTolerance();
            Rectangle outer = card.bounds();
            outer.grow(t, t);
            if (!outer.contains(p)) {
                return 0;
            }
            int edges = 0;
            if (Math.abs(p.y - card.y) <= t) {
                edges |= NORTH;
            } else if (Math.abs(p.y - (card.y + card.h)) <= t) {
                edges |= SOUTH;
            }
            if (Math.abs(p.x - card.x) <= t) {
                edges |= WEST;
            } else if (Math.abs(p.x - (card.x + card.w)) <= t) {
                edges |= EAST;
            }
            return edges;
        }

        /** 点 p でふちをつかめるカードを探す。なければ null。 */
        private Card resizableAt(Point p) {
            // 手前のカードの内側にいるときは、下に隠れたカードのふちはつかませない
            Card top = cardAt(p);
            if (top != null && !top.group) {
                return edgesAt(top, p) != 0 ? top : null;
            }
            for (int i = cards.size() - 1; i >= 0; i--) {
                if (!cards.get(i).group && edgesAt(cards.get(i), p) != 0) {
                    return cards.get(i);
                }
            }
            for (int i = cards.size() - 1; i >= 0; i--) {
                if (cards.get(i).group && edgesAt(cards.get(i), p) != 0) {
                    return cards.get(i);
                }
            }
            return null;
        }

        /** ふちの向きに合った、矢印の形にする（ウィンドウのふちと同じ）。 */
        private void updateCursor(Point viewPoint) {
            int type = Cursor.DEFAULT_CURSOR;
            if (editor == null && linkSource == null) {
                Point p = toModel(viewPoint);
                Card card = resizableAt(p);
                if (card != null) {
                    type = switch (edgesAt(card, p)) {
                        case NORTH -> Cursor.N_RESIZE_CURSOR;
                        case SOUTH -> Cursor.S_RESIZE_CURSOR;
                        case WEST -> Cursor.W_RESIZE_CURSOR;
                        case EAST -> Cursor.E_RESIZE_CURSOR;
                        case NORTH | WEST -> Cursor.NW_RESIZE_CURSOR;
                        case NORTH | EAST -> Cursor.NE_RESIZE_CURSOR;
                        case SOUTH | WEST -> Cursor.SW_RESIZE_CURSOR;
                        case SOUTH | EAST -> Cursor.SE_RESIZE_CURSOR;
                        default -> Cursor.DEFAULT_CURSOR;
                    };
                }
            }
            if (getCursor().getType() != type) {
                setCursor(Cursor.getPredefinedCursor(type));
            }
        }

        /** ふちを引っぱって、大きさを変える。反対側のふちは動かさない。 */
        private void resizeTo(int dx, int dy) {
            final int minWidth = 80;
            final int minHeight = 50;
            int left = startBounds.x;
            int top = startBounds.y;
            int right = startBounds.x + startBounds.width;
            int bottom = startBounds.y + startBounds.height;
            if ((resizeEdges & WEST) != 0) {
                left = Math.max(0, Math.min(left + dx, right - minWidth));
            }
            if ((resizeEdges & EAST) != 0) {
                right = Math.max(right + dx, left + minWidth);
            }
            if ((resizeEdges & NORTH) != 0) {
                top = Math.max(0, Math.min(top + dy, bottom - minHeight));
            }
            if ((resizeEdges & SOUTH) != 0) {
                bottom = Math.max(bottom + dy, top + minHeight);
            }
            resizeCard.x = left;
            resizeCard.y = top;
            resizeCard.w = right - left;
            resizeCard.h = bottom - top;
        }

        private void wheel(MouseWheelEvent e) {
            if (e.isControlDown()) {
                // タッチパッドの細かい動きでは、少しずつ拡大・縮小する
                setScale(scale * Math.pow(1.1, -e.getPreciseWheelRotation()), e.getPoint());
                return;
            }
            // ふつうのホイールは縦、Shift を押していたら横にスクロールする
            wheelScroll.scroll(e);
        }

        private void press(MouseEvent e) {
            stopEditing(true);
            requestFocusInWindow();
            Point p = toModel(e.getPoint());
            Card card = cardAt(p);
            pressView = e.getPoint();

            if (SwingUtilities.isMiddleMouseButton(e)) {
                panning = true;         // 中ボタン：表示位置を動かす
                panStartPosition = scrollPane.getViewport().getViewPosition();
                pressView = SwingUtilities.convertPoint(this, e.getPoint(), scrollPane);
                return;
            }
            if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                if (card == null || !selection.contains(card)) {
                    select(card);       // 選択の外を右クリックしたら、そのカードだけを選び直す
                }
                repaint();
                showMenu(card, e.getPoint(), p);
                return;
            }

            // 「矢印でつなぐ」の相手を選んでいる途中なら、ここでつなぐ
            if (linkSource != null) {
                if (card != null) {
                    connect(linkSource, card);
                }
                linkSource = null;
                select(card);
                repaint();
                fireStatusChanged();
                return;
            }

            pressModel = p;
            stateAtPress = snapshot();
            moved = false;
            resizing = false;
            rubberBand = null;
            dragMembers.clear();
            dragStarts.clear();

            Card edgeCard = e.isShiftDown() ? null : resizableAt(p);
            if (edgeCard != null) {
                // ふちをつかんだ：そのカードの大きさを変える
                select(edgeCard);
                resizing = true;
                resizeCard = edgeCard;
                resizeEdges = edgesAt(edgeCard, p);
                startBounds = edgeCard.bounds();
                dragMembers.add(edgeCard);
                dragStarts.add(new Point(edgeCard.x, edgeCard.y));
            } else if (card == null) {
                if (!e.isShiftDown()) {
                    selection.clear();
                }
                rubberBand = new Rectangle(p);          // 何もない所から：範囲選択を始める
            } else {
                if (e.isShiftDown()) {
                    if (!selection.remove(card)) {       // Shift+クリック：選択に足す／外す
                        selection.add(card);
                    }
                } else if (!selection.contains(card)) {
                    select(card);
                }
                dragMembers.addAll(selection);
                // グループ枠を動かすときは、中に入っているカードも一緒に動かす
                for (Card selected : selection) {
                    if (selected.group) {
                        for (Card other : cards) {
                            if (!other.group && !dragMembers.contains(other)
                                    && selected.bounds().contains(other.x + other.w / 2, other.y + other.h / 2)) {
                                dragMembers.add(other);
                            }
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
            fireStatusChanged();
        }

        private void drag(MouseEvent e) {
            if (panning) {
                Point now = SwingUtilities.convertPoint(this, e.getPoint(), scrollPane);
                Dimension extent = scrollPane.getViewport().getExtentSize();
                int x = panStartPosition.x - (now.x - pressView.x);
                int y = panStartPosition.y - (now.y - pressView.y);
                scrollPane.getViewport().setViewPosition(new Point(
                        Math.max(0, Math.min(x, getWidth() - extent.width)),
                        Math.max(0, Math.min(y, getHeight() - extent.height))));
                return;
            }
            if (pressModel == null) {
                return;
            }
            Point p = toModel(e.getPoint());
            if (rubberBand != null) {
                rubberBand.setFrameFromDiagonal(pressModel, p);
                repaint();
                return;
            }
            if (dragMembers.isEmpty()) {
                return;
            }
            int dx = p.x - pressModel.x;
            int dy = p.y - pressModel.y;
            if (resizing) {
                resizeTo(dx, dy);
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

        private void release() {
            if (rubberBand != null) {
                // 囲んだ四角に少しでも重なったカードを、選択に加える
                for (Card card : cards) {
                    if (!card.group && rubberBand.intersects(card.bounds())) {
                        selection.add(card);
                    }
                }
                rubberBand = null;
                repaint();
                fireStatusChanged();
            }
            if (moved && stateAtPress != null) {
                commit(stateAtPress);
            }
            panning = false;
            pressModel = null;
            stateAtPress = null;
            moved = false;
        }

        private void showMenu(Card card, Point viewPoint, Point modelPoint) {
            JPopupMenu menu = new JPopupMenu();
            Card only = single();
            if (card == null) {
                menu.add(item("カードを追加", () -> startEditing(addCard(modelPoint, false))));
                menu.add(item("グループ枠を追加", () -> startEditing(addCard(modelPoint, true))));
                menu.add(item("貼り付け（1行につき1枚）", BoardPane.this::paste));
                menu.addSeparator();
                menu.add(item("すべてのカードを整列", BoardPane.this::tidy));
                menu.add(item("色ごとに並べる", BoardPane.this::arrangeByColor));
                menu.add(item("全体を表示", BoardPane.this::fitAll));
            } else {
                if (only != null) {
                    menu.add(item("文字を編集", () -> startEditing(only)));
                }
                JMenu colors = new JMenu("色");
                for (int i = 0; i < PALETTE.length; i++) {
                    int index = i;
                    colors.add(item(PALETTE_NAMES[i], () -> {
                        String before = snapshot();
                        for (Card selected : selection) {
                            selected.color = index;
                        }
                        commit(before);
                    }));
                }
                menu.add(colors);
                if (!selectedNotes().isEmpty()) {
                    menu.add(item("投票する（+1）", () -> vote(1)));
                    menu.add(item("投票を取り消す（−1）", () -> vote(-1)));
                }
                if (only != null && !only.group) {
                    menu.add(item("矢印でつなぐ…", () -> {
                        linkSource = only;
                        repaint();
                        fireStatusChanged();
                    }));
                    menu.add(item("このカードの矢印を消す", () -> {
                        String before = snapshot();
                        if (links.removeIf(link -> link.from() == only.id || link.to() == only.id)) {
                            commit(before);
                        }
                    }));
                }
                menu.addSeparator();
                if (!selectedNotes().isEmpty()) {
                    menu.add(item("グループ枠で囲む", BoardPane.this::groupSelected));
                }
                if (selectedNotes().size() >= 2) {
                    menu.add(item("選んだカードを整列", BoardPane.this::tidy));
                    menu.add(item("選んだカードを色ごとに並べる", BoardPane.this::arrangeByColor));
                }
                menu.add(item("複製", BoardPane.this::duplicateSelected));
                menu.add(item("削除", BoardPane.this::deleteSelected));
            }
            menu.show(this, viewPoint.x, viewPoint.y);
        }

        private JMenuItem item(String label, Runnable action) {
            JMenuItem item = new JMenuItem(label);
            item.addActionListener(e -> action.run());
            return item;
        }

        @Override
        public Dimension getPreferredSize() {
            Rectangle bounds = contentBounds(cards);
            int width = bounds == null ? 0 : bounds.x + bounds.width;
            int height = bounds == null ? 0 : bounds.y + bounds.height;
            Dimension viewport = scrollPane.getViewport().getExtentSize();
            // カードの外側にも余白を取り、いつでも右や下へ広げられるようにする
            return new Dimension(Math.max((int) ((width + 600) * scale), viewport.width),
                    Math.max((int) ((height + 600) * scale), viewport.height));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.scale(scale, scale);     // ここから先は、ボード上の座標でそのまま描ける
            paintBoard(g2, true);
            g2.dispose();
        }

        /** ボードを描く。decorations が false のときは、選択の枠などを描かない（画像の書き出し用）。 */
        void paintBoard(Graphics2D g2, boolean decorations) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle clip = g2.getClipBounds();
            g2.setColor(Theme.BG);
            g2.fillRect(clip.x, clip.y, clip.width, clip.height);

            // 背景の点（位置の目安）
            g2.setColor(Theme.BOARD_DOT);
            for (int x = clip.x - Math.floorMod(clip.x, 32); x < clip.x + clip.width; x += 32) {
                for (int y = clip.y - Math.floorMod(clip.y, 32); y < clip.y + clip.height; y += 32) {
                    g2.fillRect(x, y, 2, 2);
                }
            }

            if (cards.isEmpty() && decorations) {
                g2.setFont(Theme.ui());
                g2.setColor(Theme.FG_DIM);
                g2.drawString("ダブルクリックでカードを追加　／　右クリックでメニュー　／　Ctrl+V で一括追加", 40, 60);
            }

            // 奥から順に：グループ枠 → 矢印 → カード
            for (Card card : cards) {
                if (card.group) {
                    paintGroup(g2, card, decorations);
                }
            }
            for (Link link : links) {
                Card from = find(link.from());
                Card to = find(link.to());
                if (from != null && to != null) {
                    paintArrow(g2, from, to);
                }
            }
            for (Card card : cards) {
                if (!card.group && card != editingCard) {
                    paintCard(g2, card, decorations);
                }
            }
            if (rubberBand != null && decorations) {
                g2.setColor(new Color(0, 122, 204, 50));
                g2.fill(rubberBand);
                g2.setStroke(new BasicStroke(1.5f));
                g2.setColor(Theme.ACCENT);
                g2.draw(rubberBand);
            }
        }

        private void paintGroup(Graphics2D g2, Card card, boolean decorations) {
            boolean selected = decorations && selection.contains(card);
            Color base = PALETTE[card.color];
            g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 28));
            g2.fillRoundRect(card.x, card.y, card.w, card.h, 18, 18);
            g2.setStroke(new BasicStroke(selected ? 3f : 2f));
            g2.setColor(selected ? Theme.ACCENT : base);
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
            if (selected) {
                paintHandle(g2, card);
            }
        }

        private void paintCard(Graphics2D g2, Card card, boolean decorations) {
            g2.setColor(new Color(0, 0, 0, 90));                   // 影
            g2.fillRoundRect(card.x + 3, card.y + 5, card.w, card.h, 12, 12);
            g2.setColor(PALETTE[card.color]);
            g2.fillRoundRect(card.x, card.y, card.w, card.h, 12, 12);
            boolean selected = decorations && selection.contains(card);
            if (selected || (decorations && card == linkSource)) {
                g2.setStroke(new BasicStroke(3f));
                g2.setColor(card == linkSource ? LINK_COLOR : Theme.ACCENT);
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

            // 投票の点（多いときは、点1つと数字で表す）
            if (card.votes > 0) {
                g2.setColor(new Color(0xC0392B));
                int dotY = card.y + card.h - 20;
                if (card.votes <= 8) {
                    for (int i = 0; i < card.votes; i++) {
                        g2.fillOval(card.x + 10 + i * 18, dotY, 13, 13);
                    }
                } else {
                    g2.fillOval(card.x + 10, dotY, 13, 13);
                    g2.setFont(Theme.small().deriveFont(Font.BOLD, 15f));
                    g2.drawString("× " + card.votes, card.x + 30, dotY + 12);
                }
            }
            if (selected && single() == card) {
                paintHandle(g2, card);
            }
        }

        /** from から to へ、カードのふちで止まる矢印を描く。 */
        private void paintArrow(Graphics2D g2, Card from, Card to) {
            double fromX = from.x + from.w / 2.0;
            double fromY = from.y + from.h / 2.0;
            double toX = to.x + to.w / 2.0;
            double toY = to.y + to.h / 2.0;
            double dx = toX - fromX;
            double dy = toY - fromY;
            double length = Math.hypot(dx, dy);
            if (length < 1) {
                return;
            }
            // 相手のカードの中心から、どれだけ手前がふちになるかを求める
            double tx = Math.abs(dx) < 1e-6 ? Double.MAX_VALUE : (to.w / 2.0 + 6) / Math.abs(dx);
            double ty = Math.abs(dy) < 1e-6 ? Double.MAX_VALUE : (to.h / 2.0 + 6) / Math.abs(dy);
            double t = Math.min(1, Math.min(tx, ty));
            double tipX = toX - dx * t;
            double tipY = toY - dy * t;

            g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(LINK_COLOR);
            g2.drawLine((int) fromX, (int) fromY, (int) tipX, (int) tipY);

            // 矢じり（進む向きの単位ベクトル ux,uy と、それに直角な向きを使う）
            double ux = dx / length;
            double uy = dy / length;
            int size = 16;
            int[] xs = {(int) tipX, (int) (tipX - ux * size - uy * size * 0.5), (int) (tipX - ux * size + uy * size * 0.5)};
            int[] ys = {(int) tipY, (int) (tipY - uy * size + ux * size * 0.5), (int) (tipY - uy * size - ux * size * 0.5)};
            g2.fillPolygon(xs, ys, 3);
        }

        /** 右下に、大きさを変えられることを示す目印（三角）を描く。実際は、どのふちでもつかめる。 */
        private void paintHandle(Graphics2D g2, Card card) {
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
