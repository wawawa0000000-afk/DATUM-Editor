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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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

import com.example.minieditor.apple.AppleTheme;
import com.example.minieditor.apple.BoardLook;

/**
 * カードボード。付箋（カード）を並べ、グループの枠で囲み、矢印でつなぐ。
 * Miro や FigJam などのオンラインのボードにある機能を参考にしている。
 * グループ枠の扱いと、マウスの手触りは、UE5 の Blueprint のコメント枠に合わせている。
 *
 * 操作：
 *   何もない所をダブルクリック … カードを追加（グループ枠の中でも同じ）
 *   カードをダブルクリック・F2・Enter … 文字を編集（Enter か外をクリックで確定、改行は Shift+Enter。Esc で取り消し）
 *   ドラッグ                   … 移動（16 ずつの格子にそろう）
 *   グループ枠の見出しをドラッグ … 枠ごと動かす（中のカードと、中の小さい枠も一緒に動く）
 *   何もない所からドラッグ     … 囲んだカードと枠をまとめて選択（枠の中の空いた所からでもよい）
 *   Shift+クリック／Ctrl+クリック … 選択に足す／選択を切り替える（範囲選択では Shift で足し、Ctrl で外す）
 *   ふちや角をドラッグ         … 大きさを変える（ふちに触れると、矢印の形が変わる）
 *   Ctrl+ホイール              … 拡大・縮小　／　右ボタン・中ボタン（ホイール）でドラッグ … 表示位置を動かす
 *   右クリック                 … 色・投票・矢印・整列・グループ化など
 *   C … 枠で囲む　Home … 全体を表示　Shift+W/A/S/D … 上・左・下・右にそろえる
 *   矢印キー … 選んだものを 8 動かす（Shift で 32）　Tab／Shift+Tab … 次／前のカードを選ぶ（端まで来たらボードの外へ）
 *
 * 見た目の値（色・角丸・影・線の太さ）は apple.AppleTheme と apple.BoardLook に集めている。
 *
 * 矢印でつなぐ（どこから引いてもよい。決まった接続口は置かない）：
 *   カードを長押ししてから引く … 糸が伸びる。相手のカードで離すとつながる。何もない所で離すと、新しいカードを作ってつなぐ
 *   Alt を押しながらカードを引く … 長押しを待たずに、すぐ糸が伸びる（Alt を押している間は、引けるカードのふちが光る）
 *   カードを選んだ順に L     … 1枚目 → 2枚目 → … と、順につなぐ
 *
 * グループ枠は入れ子にできる。小さい枠ほど手前に描き、先にクリックが当たる。
 * 枠の中にあるか（親子）は、そのつど位置から求める（中心が枠の内側にあり、枠より小さいもの）。
 *
 * ボードに端はない（UE5 のグラフと同じ）。カードはマイナスの位置にも置け、表示はどこまででも動かせる。
 * Board の部品は、表示している所の周りだけ（canvas）を受け持ち、足りなくなったら四方へ広げ直す。
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

    /** つないだ直後に、矢印の上を一度だけ流れる光（向きを伝える）。 */
    private record Flash(Card from, Card to, long start) {
    }

    // 色・形の値は apple.BoardLook / AppleTheme に集めている（ここには書かない）
    private static final Color[] PALETTE = {
        BoardLook.fill(0), BoardLook.fill(1), BoardLook.fill(2),
        BoardLook.fill(3), BoardLook.fill(4), BoardLook.fill(5)
    };
    private static final String[] PALETTE_NAMES = {"黄", "緑", "青", "赤", "紫", "灰"};
    /** 今の配色（地が暗いか明るいか）に合う、Apple的デザインの値。 */
    private static AppleTheme look() {
        return BoardLook.themeFor(Theme.BG);
    }
    private static final int HANDLE = 18;       // 選んだカードの右下に出す、目印の三角の大きさ
    private static final int GAP = 24;          // 整列するときの、カードとカードのすき間
    private static final int GRID = 16;         // ドラッグで動かすときに、そろえる格子の幅
    // align で、そろえるふち
    public static final int ALIGN_TOP = 0;
    public static final int ALIGN_LEFT = 1;
    public static final int ALIGN_BOTTOM = 2;
    public static final int ALIGN_RIGHT = 3;
    private static final int UNDO_LIMIT = 100;
    private static final double MIN_SCALE = 0.2;
    private static final double MAX_SCALE = 3.0;
    private static final double MAX_IMAGE_PIXELS = 64_000_000;  // 書き出す画像の画素数の上限（8000×8000、約 256MB。これを超えると細かさを下げる）

    private final List<Card> cards = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private final Set<Card> selection = new LinkedHashSet<>();     // 選んでいるカード（複数可）
    private int nextId = 1;
    private Card linkSource;                    // 「矢印でつなぐ」を選んだあと、相手を待っている元のカード
    private Font cardFont = new Font("Dialog", Font.PLAIN, Theme.UI_SIZE + 2);
    private double scale = 1.0;                 // 表示の倍率（保存はしない）

    // 「元に戻す」は、変更前のボード全体を JSON の文字列で覚えておく方式（同じボードを映すタブとは共有する）
    private Deque<String> undoStack = new ArrayDeque<>();
    private Deque<String> redoStack = new ArrayDeque<>();

    private final Board board = new Board();
    private final JScrollPane scrollPane = new JScrollPane(board);
    // Board の部品が受け持っている範囲（ボード上の座標）。部品の (0, 0) が、ボード上の (canvas.x, canvas.y)
    private final Rectangle canvas = new Rectangle();
    private boolean canvasReady;                // 一度でも表示して、canvas を決めたか
    private final double[] wheelRest = new double[2];   // ホイールの、1ピクセルに満たない残り（[0] 縦、[1] 横）
    private JTextArea editor;                   // 文字を編集している間だけ出す入力欄
    private Card editingCard;

    public BoardPane() {
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(Theme.BG);
        scrollPane.setWheelScrollingEnabled(false);     // ホイールは Board 側で受け取る（拡大・縮小のため）
        // 端のないボードなので、スクロールバーは出さない（ホイール・ドラッグで動かす）
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                refreshCanvas();        // 初めて表示したとき・画面の大きさが変わったとき
            }
        });
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
        board.resetThread();
        nextId = 1;
        cardFont = cardFont.deriveFont((float) Theme.zoomed(Json.num(json, "fontSize", Theme.unzoomed(cardFont.getSize()))));
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

    /**
     * zoom 倍の細かさで、ボード全体を画像にする（PDF では、拡大しても粗くならないように 2 倍にする）。
     * カードが遠く離れていて画像が大きくなりすぎるときは、細かさを下げる（メモリが足りずに書き出しが落ちないように）。
     */
    private BufferedImage renderImage(double zoom) {
        Rectangle bounds = contentBounds(cards);
        if (bounds == null) {
            return null;
        }
        bounds.grow(40, 40);
        zoom = Math.min(zoom, Math.sqrt(MAX_IMAGE_PIXELS / ((double) bounds.width * bounds.height)));
        BufferedImage image = new BufferedImage(Math.max(1, (int) Math.ceil(bounds.width * zoom)),
                Math.max(1, (int) Math.ceil(bounds.height * zoom)), BufferedImage.TYPE_INT_RGB);
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
        // 画面の 1 ピクセルを 0.75 ポイント（= 1/96 インチ）として、紙の大きさを決める。
        // 画像の細かさは大きいボードでは下がるので、紙の大きさは画像ではなくボードの広さから求める
        Rectangle bounds = contentBounds(cards);
        bounds.grow(40, 40);
        writer.addPage(image, bounds.width * 0.75, bounds.height * 0.75);
    }

    // ---------- 変更と「元に戻す」 ----------

    /** 今のボード全体を、1行の JSON にする（字下げを入れないぶん、「元に戻す」の 100 回ぶんの控えが 4 割ほど小さくなる）。 */
    private String snapshot() {
        return Json.writeCompact(toJson());
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
        refreshCanvas();                // カードが受け持ちの範囲の外へ出たら、広げる
        board.revalidate();
        board.repaint();
        if (!otherViews().isEmpty()) {
            Map<String, Object> json = toJson();
            for (EditorPane other : otherViews()) {
                ((BoardPane) other).mirror(json);
            }
        }
        fireContentChanged();
    }

    /** 同じボードを映すほかのタブで変わった中身を、こちらにも写す。選んでいたカードは、そのまま選んでおく。 */
    private void mirror(Map<String, Object> json) {
        stopEditing(false);
        List<Integer> selected = new ArrayList<>();
        for (Card card : selection) {
            selected.add(card.id);
        }
        load(json);
        for (int id : selected) {
            Card card = find(id);
            if (card != null) {
                selection.add(card);
            }
        }
        refreshCanvas();
        board.repaint();
    }

    @Override
    public EditorPane newView() {
        BoardPane other = new BoardPane();
        other.load(toJson());
        other.scale = scale;
        other.undoStack = undoStack;
        other.redoStack = redoStack;
        return linkView(other);
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

    /**
     * その点にあるカードを返す。ふつうのカードを先に調べ、なければグループ枠の見出しを調べる
     * （小さい枠が先）。枠の中の空いた所は、何もない所として扱う（範囲選択やカードの追加ができる）。
     */
    private Card cardAt(Point p) {
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card card = cards.get(i);
            if (!card.group && card.bounds().contains(p)) {
                return card;
            }
        }
        List<Card> groups = groupsBackToFront();
        for (int i = groups.size() - 1; i >= 0; i--) {
            if (titleBand(groups.get(i)).contains(p)) {
                return groups.get(i);
            }
        }
        return null;
    }

    // ---------- グループ枠の入れ子 ----------

    private static long area(Card card) {
        return (long) card.w * card.h;
    }

    /** グループ枠の見出しの帯の高さ。この帯をつかむと、枠ごと動かせる。 */
    private int titleHeight() {
        return cardFont.getSize() * 2 + 12;
    }

    private Rectangle titleBand(Card group) {
        return new Rectangle(group.x, group.y, group.w, Math.min(group.h, titleHeight()));
    }

    /** item が group の中に入っているか（中心が枠の内側にあり、枠より小さい）。 */
    private static boolean inside(Card item, Card group) {
        return item != group && group.group && area(item) < area(group)
                && group.bounds().contains(item.x + item.w / 2, item.y + item.h / 2);
    }

    /** item を囲む、いちばん小さいグループ枠（親）。どの枠にも入っていなければ null。 */
    private Card parentOf(Card item) {
        Card parent = null;
        for (Card group : cards) {
            if (inside(item, group) && (parent == null || area(group) < area(parent))) {
                parent = group;
            }
        }
        return parent;
    }

    /** 親ごとの、すぐ下の子の一覧。どの枠にも入っていないものは、null をキーにする。 */
    private Map<Card, List<Card>> childrenMap() {
        Map<Card, List<Card>> children = new HashMap<>();
        for (Card card : cards) {
            children.computeIfAbsent(parentOf(card), key -> new ArrayList<>()).add(card);
        }
        return children;
    }

    /** グループ枠を、大きい順（奥から手前の順）に並べたもの。 */
    private List<Card> groupsBackToFront() {
        List<Card> groups = new ArrayList<>();
        for (Card card : cards) {
            if (card.group) {
                groups.add(card);
            }
        }
        groups.sort(Comparator.comparingLong(BoardPane::area).reversed());
        return groups;
    }

    /** 枠の中に入っているものを、入れ子の奥まで全部返す。 */
    private List<Card> descendants(Card group) {
        List<Card> found = new ArrayList<>();
        List<Card> queue = new ArrayList<>(List.of(group));
        while (!queue.isEmpty()) {
            Card outer = queue.remove(queue.size() - 1);
            for (Card card : cards) {
                if (inside(card, outer) && card != group && !found.contains(card)) {
                    found.add(card);
                    if (card.group) {
                        queue.add(card);
                    }
                }
            }
        }
        return found;
    }

    /** items から、ほかの item の枠の中に入っているものを除く（枠と一緒に動くので、二重に動かさない）。 */
    private List<Card> topLevel(Collection<Card> items) {
        List<Card> result = new ArrayList<>();
        for (Card item : items) {
            boolean nested = false;
            for (Card parent = parentOf(item); parent != null; parent = parentOf(parent)) {
                if (items.contains(parent)) {
                    nested = true;
                    break;
                }
            }
            if (!nested) {
                result.add(item);
            }
        }
        return result;
    }

    /** item を動かす。グループ枠なら、中に入っているもの（children でわかる子と孫）も一緒に動かす。 */
    private static void moveTree(Card item, int dx, int dy, Map<Card, List<Card>> children) {
        item.x += dx;
        item.y += dy;
        if (item.group) {
            for (Card child : children.getOrDefault(item, List.of())) {
                moveTree(child, dx, dy, children);
            }
        }
    }

    /** 枠が、中に入っているもの全部を囲むように広げる（縮めはしない）。外側の枠も順に広げる。 */
    private void growToFit(Card group, Map<Card, List<Card>> children, Map<Card, Card> parents) {
        for (Card g = group; g != null; g = parents.get(g)) {
            for (Card child : children.getOrDefault(g, List.of())) {
                g.w = Math.max(g.w, child.x + child.w + GAP - g.x);
                g.h = Math.max(g.h, child.y + child.h + GAP - g.y);
            }
        }
    }

    private Map<Card, Card> parentsMap(Map<Card, List<Card>> children) {
        Map<Card, Card> parents = new HashMap<>();
        for (Map.Entry<Card, List<Card>> entry : children.entrySet()) {
            for (Card child : entry.getValue()) {
                parents.put(child, entry.getKey());
            }
        }
        return parents;
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

    /** カードの内側の余白（左右・上下）。カードの字の大きさに合わせる（15px の字で 14・12）。 */
    private int padX() {
        return Math.round(cardFont.getSize() * AppleTheme.CARD_PAD_X / AppleTheme.CARD_FONT);
    }

    private int padY() {
        return Math.round(cardFont.getSize() * AppleTheme.CARD_PAD_Y / AppleTheme.CARD_FONT);
    }

    /** 矢印の大きさの倍率（カードの字の大きさに合わせる。字を大きくすると、矢印も同じだけ大きくなる）。 */
    private double arrowScale() {
        return BoardLook.arrowScale(cardFont.getSize2D());
    }

    private static double lineHeight(FontMetrics fm) {
        return fm.getHeight() * 1.1;
    }

    /** 選んでいるもの（枠は中身ごと）を、dx・dy だけ動かす（矢印キー）。 */
    public void nudge(int dx, int dy) {
        List<Card> items = topLevel(selection);
        if (items.isEmpty()) {
            return;
        }
        String before = snapshot();
        Map<Card, List<Card>> children = childrenMap();
        for (Card item : items) {
            moveTree(item, dx, dy, children);
        }
        commit(before);
        board.repaint();
    }

    /**
     * Tab：次のカードを選ぶ（Shift+Tab は前）。端まで来たら選択を外し、焦点をボードの外へ渡す
     * （キーボードだけで使う人が、ボードから出られなくならないように）。
     */
    void step(int dir) {
        List<Card> notes = allNotes();
        Card current = single();
        int i = current == null || !notes.contains(current) ? (dir > 0 ? -1 : notes.size()) : notes.indexOf(current);
        int n = i + dir;
        if (n < 0 || n >= notes.size()) {
            selection.clear();
            board.repaint();
            fireStatusChanged();
            if (dir > 0) {
                board.transferFocus();
            } else {
                board.transferFocusBackward();
            }
            return;
        }
        select(notes.get(n));
        board.repaint();
    }

    private Card newCard(int x, int y, boolean group) {
        int size = cardFont.getSize();
        Card card = new Card();
        card.id = nextId++;
        card.group = group;
        card.w = group ? size * 22 : size * 10;
        card.h = group ? size * 14 : size * 6;
        card.x = x;
        card.y = y;
        card.color = group ? 2 : 0;
        card.text = group ? "グループ" : "";
        cards.add(group ? 0 : cards.size(), card);  // グループ枠は奥に、カードは手前に置く
        return card;
    }

    private Card addCard(Point p, boolean group) {
        String before = snapshot();
        Card card = newCard(p.x, p.y, group);
        if (!group) {
            card.x = p.x - card.w / 2;
            card.y = p.y - card.h / 2;
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
        if (linkSource != null && selection.contains(linkSource)) {
            linkSource = null;          // 「矢印でつなぐ…」の元のカードを消したら、つなぐのもやめる
        }
        selection.clear();
        commit(before);
    }

    private void connect(Card from, Card to) {
        // 消えたカードからは引かない（ないカードを指す矢印が残り、矢印の数もずれるため）
        if (!cards.contains(from) || !cards.contains(to) || linkProblem(from, to) != null) {
            return;
        }
        String before = snapshot();
        links.add(new Link(from.id, to.id));
        commit(before);
        board.flash(from, to);
    }

    /** from から to へ矢印を引けない理由。引けるなら null。 */
    private String linkProblem(Card from, Card to) {
        if (to == from) {
            return "同じカードにはつなげません";
        }
        if (from.group || to.group) {
            return "グループ枠にはつなげません";
        }
        for (Link link : links) {
            if ((link.from() == from.id && link.to() == to.id) || (link.from() == to.id && link.to() == from.id)) {
                return "もうつながっています";
            }
        }
        return null;
    }

    /** 選んだカードを、選んだ順に矢印でつなぐ（1枚目 → 2枚目 → …）。もうつながっている組は飛ばす。 */
    public void linkSelected() {
        List<Card> notes = selectedNotes();
        if (editor != null || notes.size() < 2) {
            return;
        }
        String before = snapshot();
        List<Card[]> made = new ArrayList<>();
        for (int i = 0; i + 1 < notes.size(); i++) {
            if (linkProblem(notes.get(i), notes.get(i + 1)) == null) {
                links.add(new Link(notes.get(i).id, notes.get(i + 1).id));
                made.add(new Card[]{notes.get(i), notes.get(i + 1)});
            }
        }
        if (made.isEmpty()) {
            return;
        }
        commit(before);
        for (Card[] pair : made) {
            board.flash(pair[0], pair[1]);
        }
    }

    /**
     * 伸ばした糸を、end で離した。相手のカードがあればつなぎ、何もない所なら新しいカードを作ってつなぐ。
     * つないだら true。つなげない相手のときは、何もせず false。
     */
    private boolean dropThread(Card source, Point end) {
        if (!cards.contains(source)) {
            return false;
        }
        Card target = cardAt(end);
        String before = snapshot();
        if (target == null) {
            Card card = newCard(0, 0, false);
            card.x = end.x - card.w / 2;
            card.y = end.y - card.h / 2;
            links.add(new Link(source.id, card.id));
            select(card);
            commit(before);
            board.flash(source, card);
            startEditing(card);         // そのまま、新しいカードの文字を打ってもらう
            return true;
        }
        if (linkProblem(source, target) != null) {
            return false;
        }
        links.add(new Link(source.id, target.id));
        select(target);
        commit(before);
        board.flash(source, target);
        return true;
    }

    /** 文字が全部見えるように、カードの高さを広げる。 */
    private void fitHeight(Card card) {
        if (card.group) {
            return;
        }
        FontMetrics fm = board.getFontMetrics(cardFont.deriveFont(Font.BOLD));
        int needed = (int) Math.ceil(wrap(card.text, fm, card.w - padX() * 2).size() * lineHeight(fm))
                + padY() * 2 + voteHeight(card);
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

    /**
     * 選んでいるカードと枠を囲む、グループ枠を作る（オンラインのボードの「クラスタ」にあたる）。
     * 大きい枠の中で作れば、その中の小さい枠になる。枠を選んで囲めば、外側の大きい枠になる。
     */
    public void groupSelected() {
        List<Card> items = topLevel(selection);
        if (editor != null || items.isEmpty()) {
            return;
        }
        String before = snapshot();
        Rectangle bounds = contentBounds(items);
        int titleHeight = titleHeight();
        Card group = newCard(bounds.x - GAP, bounds.y - titleHeight, true);
        group.w = bounds.width + 2 * GAP;
        group.h = bounds.height + titleHeight + GAP;
        select(group);
        commit(before);
        startEditing(group);            // そのまま、グループの名前を入力してもらう
    }

    /**
     * カードを、すき間をそろえて並べ直す。グループ枠の中のカードは、その枠の中で並べ、
     * 枠は中身に合わせた大きさにして、ひとかたまりとして並べる（入れ子の枠も同じ）。
     * 2つ以上選んでいれば（または枠を1つ選んでいれば）、選んだものだけを、それぞれの枠の中で並べる。
     */
    public void tidy() {
        if (editor != null || cards.isEmpty()) {
            return;
        }
        Map<Card, List<Card>> children = childrenMap();
        Map<Card, Card> parents = parentsMap(children);
        List<Card> chosen = topLevel(selection);
        String before = snapshot();
        if (chosen.size() == 1 && chosen.get(0).group) {
            arrangeInside(chosen.get(0), children);
            growToFit(parents.get(chosen.get(0)), children, parents);
        } else if (chosen.size() >= 2) {
            // 同じ枠に入っているものどうしで、その枠の中で並べる
            for (Map.Entry<Card, List<Card>> part : partitionByParent(chosen, parents).entrySet()) {
                Rectangle origin = contentBounds(part.getValue());
                arrangeItems(part.getValue(), new Point(origin.x, origin.y), children);
                growToFit(part.getKey(), children, parents);
            }
        } else {
            List<Card> roots = children.getOrDefault(null, List.of());
            Rectangle origin = contentBounds(roots);
            arrangeItems(new ArrayList<>(roots), new Point(origin.x, origin.y), children);
        }
        commit(before);
    }

    /** 枠の中身を並べ直し、枠を中身に合わせた大きさにする。中身がなければ何もしない。 */
    private void arrangeInside(Card group, Map<Card, List<Card>> children) {
        List<Card> kids = children.getOrDefault(group, List.of());
        if (kids.isEmpty()) {
            return;
        }
        Rectangle area = arrangeItems(new ArrayList<>(kids), new Point(group.x + GAP, group.y + titleHeight()), children);
        FontMetrics fm = board.getFontMetrics(cardFont.deriveFont(Font.BOLD));
        int titleWidth = fm.stringWidth(group.text.replace("\n", " ")) + 32;
        group.w = Math.max(titleWidth, area.x + area.width + GAP - group.x);
        group.h = area.y + area.height + GAP - group.y;
    }

    /**
     * items を origin から、左から右へ、行を折り返して並べる。カードは同じ大きさのマスに入れ、
     * 枠は中身を先に並べてから、その大きさのまま1つのかたまりとして置く。並べたあとの範囲を返す。
     */
    private Rectangle arrangeItems(List<Card> items, Point origin, Map<Card, List<Card>> children) {
        for (Card item : items) {
            if (item.group) {
                arrangeInside(item, children);
            }
        }
        int cellW = 0;
        int cellH = 0;
        int notes = 0;
        for (Card item : items) {
            if (!item.group) {
                cellW = Math.max(cellW, item.w);
                cellH = Math.max(cellH, item.h);
                notes++;
            }
        }
        // 今の位置に近い順（上から下、左から右）に並べるので、並びが大きく入れ替わらない
        // （マイナスの位置でも同じ行に分けるよう、0 に向けて切り捨てる / ではなく floorDiv で割る）
        int rowHeight = Math.max(1, notes > 0 ? cellH : items.stream().mapToInt(item -> item.h).min().orElse(1));
        items.sort(Comparator.comparingInt((Card card) -> Math.floorDiv(card.y, rowHeight)).thenComparingInt(card -> card.x));

        // 折り返す幅：カードだけなら、今までどおり √枚数 の列。枠があれば、全体がおよそ正方形になる幅
        double total = 0;
        int widest = 0;
        for (Card item : items) {
            int w = item.group ? item.w : cellW;
            int h = item.group ? item.h : cellH;
            total += (double) (w + GAP) * (h + GAP);
            widest = Math.max(widest, w);
        }
        int limit;
        if (notes > 0) {
            double aspect = (double) (cellW + GAP) / (cellH + GAP);
            int columns = (int) Math.ceil(Math.sqrt(total * aspect) / (cellW + GAP) - 1e-9);
            limit = Math.max(widest, columns * (cellW + GAP) - GAP);
        } else {
            limit = Math.max(widest, (int) Math.ceil(Math.sqrt(total)));
        }

        int x = origin.x;
        int y = origin.y;
        int lineHeight = 0;
        for (Card item : items) {
            int w = item.group ? item.w : cellW;
            int h = item.group ? item.h : cellH;
            if (x > origin.x && x - origin.x + w > limit) {
                x = origin.x;
                y += lineHeight + GAP;
                lineHeight = 0;
            }
            moveTree(item, x - item.x, y - item.y, children);
            x += w + GAP;
            lineHeight = Math.max(lineHeight, h);
        }
        return contentBounds(items);
    }

    /** items を、入っている枠（親）ごとに分ける。並びは items の順を保つ。 */
    private static Map<Card, List<Card>> partitionByParent(List<Card> items, Map<Card, Card> parents) {
        Map<Card, List<Card>> parts = new LinkedHashMap<>();
        for (Card item : items) {
            parts.computeIfAbsent(parents.get(item), key -> new ArrayList<>()).add(item);
        }
        return parts;
    }

    /** 同じ色のカードを、縦の列にまとめて並べる。グループ枠の中のカードは、その枠の中で並べる。 */
    public void arrangeByColor() {
        List<Card> notes = selectedNotes().size() >= 2 ? selectedNotes() : allNotes();
        if (editor != null || notes.size() < 2) {
            return;
        }
        String before = snapshot();
        Map<Card, List<Card>> children = childrenMap();
        Map<Card, Card> parents = parentsMap(children);
        for (Map.Entry<Card, List<Card>> part : partitionByParent(notes, parents).entrySet()) {
            List<Card> members = part.getValue();
            Rectangle origin = contentBounds(members);
            if (part.getKey() != null) {
                // 枠の中では、見出しの下から並べる
                origin.x = Math.max(origin.x, part.getKey().x + GAP);
                origin.y = Math.max(origin.y, part.getKey().y + titleHeight());
            }
            int cellW = 0;
            for (Card card : members) {
                cellW = Math.max(cellW, card.w);
            }
            int column = 0;
            for (int color = 0; color < PALETTE.length; color++) {
                int y = origin.y;
                boolean used = false;
                for (Card card : members) {
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
            growToFit(part.getKey(), children, parents);
        }
        commit(before);
    }

    /** 選んでいるものの、上・左・下・右のふちをそろえる（UE5 の Shift+W/A/S/D にあたる）。枠は中身ごと動く。 */
    public void align(int edge) {
        List<Card> items = topLevel(selection);
        if (editor != null || items.size() < 2) {
            return;
        }
        String before = snapshot();
        Map<Card, List<Card>> children = childrenMap();
        Rectangle bounds = contentBounds(items);
        for (Card item : items) {
            switch (edge) {
                case ALIGN_TOP -> moveTree(item, 0, bounds.y - item.y, children);
                case ALIGN_BOTTOM -> moveTree(item, 0, bounds.y + bounds.height - (item.y + item.h), children);
                case ALIGN_LEFT -> moveTree(item, bounds.x - item.x, 0, children);
                default -> moveTree(item, bounds.x + bounds.width - (item.x + item.w), 0, children);
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
        return new Point((int) Math.round(view.x / scale) + canvas.x, (int) Math.round(view.y / scale) + canvas.y);
    }

    /** ボード上の点を、Board の部品の上の点に直す（テストからも使う）。 */
    Point toView(int x, int y) {
        return new Point((int) Math.round((x - canvas.x) * scale), (int) Math.round((y - canvas.y) * scale));
    }

    private Rectangle visibleModelRect() {
        Rectangle v = board.getVisibleRect();
        return new Rectangle((int) (v.x / scale) + canvas.x, (int) (v.y / scale) + canvas.y,
                (int) (v.width / scale), (int) (v.height / scale));
    }

    /** 表示の左上が、ボード上のどこにあるか。 */
    private Point2D.Double viewOrigin() {
        Point p = scrollPane.getViewport().getViewPosition();
        return new Point2D.Double(p.x / scale + canvas.x, p.y / scale + canvas.y);
    }

    /**
     * 表示の左上を、ボード上の (left, top) に合わせる。ボードに端はないので、どこへでも動ける。
     * 部品が受け持つ範囲（canvas）が足りなければ、そこを中心に四方へ広げ直す。
     */
    private void showFrom(double left, double top) {
        JViewport viewport = scrollPane.getViewport();
        Dimension extent = viewport.getExtentSize();
        if (extent.width <= 0 || extent.height <= 0) {
            return;                     // まだ表示されていない
        }
        double width = extent.width / scale;
        double height = extent.height / scale;
        Rectangle need = new Rectangle((int) Math.floor(left), (int) Math.floor(top),
                (int) Math.ceil(width) + 2, (int) Math.ceil(height) + 2);
        Rectangle content = contentBounds(cards);
        if (content != null) {
            need = need.union(content);
        }
        if (!canvasReady || !canvas.contains(need)) {
            // どちらへも、1画面ぶん以上は続けて動けるだけの余白を取る
            int margin = (int) Math.ceil(Math.max(600, Math.max(width, height)));
            need.grow(margin, margin);
            canvas.setBounds(need);
            canvasReady = true;
        }
        Dimension size = board.getPreferredSize();
        if (!board.getSize().equals(size)) {
            board.setSize(size);        // すぐに大きさを変える（revalidate を待つと、位置がずれる）
            board.revalidate();
        }
        if (editor != null) {
            placeEditor();
        }
        viewport.setViewPosition(new Point((int) Math.round((left - canvas.x) * scale),
                (int) Math.round((top - canvas.y) * scale)));
        board.repaint();
    }

    /** 今の表示の位置のまま、受け持つ範囲を合わせ直す。初めてのときは、今までと同じく (0, 0) から見せる。 */
    private void refreshCanvas() {
        if (canvasReady) {
            Point2D.Double origin = viewOrigin();
            showFrom(origin.x, origin.y);
        } else {
            Rectangle content = contentBounds(cards);
            showFrom(content == null ? 0 : Math.min(0, content.x - 40), content == null ? 0 : Math.min(0, content.y - 40));
        }
    }

    /** 倍率を変える。anchor（Board の部品の上の点）にある物が、動かないようにする。 */
    private void setScale(double newScale, Point anchor) {
        newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
        if (newScale == scale) {
            return;
        }
        stopEditing(true);
        Point position = scrollPane.getViewport().getViewPosition();
        double modelX = anchor.x / scale + canvas.x;
        double modelY = anchor.y / scale + canvas.y;
        int offsetX = anchor.x - position.x;
        int offsetY = anchor.y - position.y;
        scale = newScale;
        showFrom(modelX - offsetX / scale, modelY - offsetY / scale);
        fireStatusChanged();
    }

    public void zoomBy(double factor) {
        Rectangle v = board.getVisibleRect();
        setScale(scale * factor, new Point(v.x + v.width / 2, v.y + v.height / 2));
    }

    /** すべてのカードが画面に入るように、倍率と位置を合わせる（真ん中に出す）。 */
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
        showFrom(bounds.getCenterX() - extent.width / scale / 2, bounds.getCenterY() - extent.height / scale / 2);
        fireStatusChanged();
    }

    /** ホイールで、表示を縦（Shift なら横）に動かす。動く量は、ほかの画面（WheelScroll）と同じ。 */
    private void scrollByWheel(MouseWheelEvent e) {
        boolean horizontal = e.isShiftDown();
        Dimension extent = scrollPane.getViewport().getExtentSize();
        double amount;
        if (e.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL) {
            amount = e.getPreciseWheelRotation() * (horizontal ? extent.width : extent.height);
        } else {
            amount = e.getPreciseWheelRotation() * e.getScrollAmount() * WheelScroll.LINE * Theme.zoom() / 100.0;
        }
        int i = horizontal ? 1 : 0;
        wheelRest[i] += amount;
        int move = (int) wheelRest[i];
        wheelRest[i] -= move;
        if (move == 0) {
            return;
        }
        Point2D.Double origin = viewOrigin();
        if (horizontal) {
            showFrom(origin.x + move / scale, origin.y);
        } else {
            showFrom(origin.x, origin.y + move / scale);
        }
    }

    // ---------- 文字の編集 ----------

    private void startEditing(Card card) {
        stopEditing(true);
        editingCard = card;
        editor = new JTextArea(card.text);
        Font base = cardFont.deriveFont(Font.BOLD);
        editor.setFont(base.deriveFont((float) (base.getSize() * scale)));
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setBackground(card.group ? Theme.INPUT : PALETTE[card.color]);
        editor.setForeground(card.group ? Theme.FG_STRONG : BoardLook.text(card.color));
        editor.setCaretColor(card.group ? Theme.FG_STRONG : BoardLook.text(card.color));
        int padX = (int) Math.round(padX() * scale);
        int padY = (int) Math.round(padY() * scale);
        editor.setBorder(BorderFactory.createEmptyBorder(padY, padX, padY, padX));
        placeEditor();

        // Esc で取り消し、Enter（と Ctrl+Enter）で確定。改行は Shift+Enter
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelEdit");
        editor.getActionMap().put("cancelEdit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopEditing(false);
                board.requestFocusInWindow();
            }
        });
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "commitEdit");
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "commitEdit");
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");
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

    /** 入力欄は拡大・縮小の外にあるので、位置と大きさに倍率を掛けて、カードの上に合わせる。 */
    private void placeEditor() {
        Card card = editingCard;
        Rectangle r = card.group
                ? new Rectangle(card.x + 6, card.y + 6, card.w - 12, cardFont.getSize() * 2)
                : card.bounds();
        Point at = toView(r.x, r.y);
        editor.setBounds(at.x, at.y, (int) (r.width * scale), (int) (r.height * scale));
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
        } else if (board.threadSource != null) {
            status = "つなぐ相手のカードで離してください。何もない所で離すと、新しいカードを作ってつなぎます（Esc でやめる）    " + status;
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
        private Point2D.Double panStart;        // 表示を動かし始めたときの、表示の左上（ボード上の座標）
        private Rectangle startBounds;
        private Rectangle rubberBand;           // 範囲選択の四角
        private boolean rubberRemoves;          // Ctrl を押して始めた範囲選択は、囲んだものを選択から外す
        private boolean rightPending;           // 右ボタンを押している（動かせば表示を動かし、動かさずに離せばメニュー）
        private Card menuCard;                  // 右ボタンを離したときに出すメニューの、対象と位置
        private Point menuView;
        private Point menuModel;
        private Card dragAnchor;                // 格子にそろえる基準（つかんだカード）
        private Point anchorStart;

        // 矢印を引く（糸）。長押しか Alt+ドラッグで、カードから糸が伸びる
        private static final int HOLD_MS = 300;         // 長押しとみなすまでの時間
        private static final int NEAR = 160;            // 糸の先がこれより近いカードは、ふちがにじんで光る（つながる先の予告）
        private static final int FLASH_MS = 450;        // つないだ直後に、光が矢印を流れる時間
        private static final int RETRACT_MS = 200;      // やめたときに、糸が縮んで消える時間
        private static final int PAINT_MARGIN = 48;     // 描く範囲の外でも、これだけ近ければ描く（持ち上げた影・曲線の矢印のふくらみ・矢じりのはみ出し）
        private Card threadSource;              // 糸を伸ばしているカード
        private Point threadEnd;                // 糸の先（ボード上の座標）
        private boolean altHeld;                // Alt を押している（引けるカードのふちを光らせる）
        private Card holdCard;                  // 長押しを待っているカード
        private Point lastModel;                // 最後にマウスがあった所（ボード上の座標）
        private final javax.swing.Timer holdTimer = new javax.swing.Timer(HOLD_MS, e -> beginHoldThread());
        private final javax.swing.Timer animation = new javax.swing.Timer(16, e -> animate());
        private final List<Flash> flashes = new ArrayList<>();
        private Card retractSource;             // 縮んで消えている途中の糸
        private Point retractEnd;
        private long retractStart;
        private final List<Card> dragMembers = new ArrayList<>();
        private final List<Point> dragStarts = new ArrayList<>();

        Board() {
            setLayout(null);            // 入力欄を、カードの位置にそのまま置くため
            holdTimer.setRepeats(false);
            setFocusable(true);
            setFocusTraversalKeysEnabled(false);    // Tab はカードを順に選ぶのに使う（端まで来たら外へ出す）
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
                    updateCursor(e.getPoint());
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    setAltHeld(e.isAltDown());      // キーの離しを取りこぼしても、マウスを動かせば合う
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
                    if (e.getKeyCode() == KeyEvent.VK_ALT) {
                        setAltHeld(true);
                        return;
                    }
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE && threadSource != null) {
                        cancelThread();
                        return;
                    }
                    if (e.isControlDown() || e.isAltDown()) {
                        return;         // Ctrl+D などは、メニューのショートカットに任せる
                    }
                    int step = e.isShiftDown() ? 32 : 8;     // 矢印キーで 8、Shift で 32 動かす
                    if (e.getKeyCode() == KeyEvent.VK_TAB) {
                        step(e.isShiftDown() ? -1 : 1);
                    } else if (e.getKeyCode() == KeyEvent.VK_LEFT) {
                        nudge(-step, 0);
                    } else if (e.getKeyCode() == KeyEvent.VK_RIGHT) {
                        nudge(step, 0);
                    } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                        nudge(0, -step);
                    } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                        nudge(0, step);
                    } else if (e.getKeyCode() == KeyEvent.VK_F2 && single() != null) {
                        startEditing(single());
                    } else if (e.getKeyCode() == KeyEvent.VK_DELETE) {
                        deleteSelected();
                    } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        linkSource = null;
                        selection.clear();
                        repaint();
                        fireStatusChanged();
                    } else if (e.getKeyCode() == KeyEvent.VK_ENTER && single() != null) {
                        startEditing(single());
                    } else if (e.getKeyCode() == KeyEvent.VK_HOME) {
                        fitAll();
                    } else if (e.isShiftDown()) {
                        // UE5 と同じく、Shift+W/A/S/D で上・左・下・右にそろえる
                        switch (e.getKeyCode()) {
                            case KeyEvent.VK_W -> align(ALIGN_TOP);
                            case KeyEvent.VK_A -> align(ALIGN_LEFT);
                            case KeyEvent.VK_S -> align(ALIGN_BOTTOM);
                            case KeyEvent.VK_D -> align(ALIGN_RIGHT);
                            default -> { }
                        }
                    } else if (e.getKeyCode() == KeyEvent.VK_C) {
                        groupSelected();        // UE5 の、C でコメント枠を作るのにあたる
                    } else if (e.getKeyCode() == KeyEvent.VK_L) {
                        linkSelected();         // 選んだ順に、矢印でつなぐ
                    }
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ALT) {
                        setAltHeld(false);
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

        // ---------- 糸（矢印を引く操作） ----------

        private void startThread(Card source, Point p) {
            threadSource = source;      // 選択は変えない（ゆっくりクリックしただけのときに、選び直しにならないように）
            threadEnd = p;
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            repaint();
            fireStatusChanged();
        }

        /** 長押しの時間がたった：押していたカードから糸を伸ばす。少し動いていたら、元の位置に戻す。 */
        private void beginHoldThread() {
            Card source = holdCard;
            holdCard = null;
            if (source == null || pressModel == null || !cards.contains(source)) {
                return;
            }
            for (int i = 0; i < dragMembers.size(); i++) {
                dragMembers.get(i).x = dragStarts.get(i).x;
                dragMembers.get(i).y = dragStarts.get(i).y;
            }
            dragMembers.clear();
            dragStarts.clear();
            moved = false;
            dragAnchor = null;
            startThread(source, lastModel);
        }

        private void finishThread() {
            Card source = threadSource;
            Point end = threadEnd;
            threadSource = null;
            threadEnd = null;
            if (!dropThread(source, end)) {
                retract(source, end);   // つなげなかった：糸が縮んで消える
            }
            setCursor(Cursor.getDefaultCursor());
            repaint();
            fireStatusChanged();
        }

        private void cancelThread() {
            retract(threadSource, threadEnd);
            threadSource = null;
            threadEnd = null;
            pressModel = null;
            stateAtPress = null;
            setCursor(Cursor.getDefaultCursor());
            repaint();
            fireStatusChanged();
        }

        /**
         * 文書を読み直したとき：引いている途中の糸と、動いている光を捨てる（古いカードを指しているため）。
         * ドラッグの途中（マウスを押したまま Ctrl+Z など）なら、そのドラッグもやめる。続けると、古いカードを動かし、
         * 離したときに押す前の状態を「元に戻す」に積んでしまう（次の Ctrl+Z が、やり直しのように働く）。
         */
        void resetThread() {
            holdTimer.stop();
            holdCard = null;
            threadSource = null;
            threadEnd = null;
            retractSource = null;
            flashes.clear();
            pressModel = null;
            stateAtPress = null;
            moved = false;
            resizing = false;
            resizeCard = null;
            rubberBand = null;
            dragAnchor = null;
            dragMembers.clear();
            dragStarts.clear();
        }

        private void retract(Card source, Point end) {
            retractSource = source;
            retractEnd = end;
            retractStart = System.currentTimeMillis();
            animation.start();
        }

        /** つないだ直後に、矢印の上を光が一度だけ流れるようにする。 */
        void flash(Card from, Card to) {
            flashes.add(new Flash(from, to, System.currentTimeMillis()));
            animation.start();
        }

        private void animate() {
            long now = System.currentTimeMillis();
            flashes.removeIf(f -> now - f.start() > FLASH_MS);
            if (retractSource != null && now - retractStart > RETRACT_MS) {
                retractSource = null;
            }
            if (flashes.isEmpty() && retractSource == null) {
                animation.stop();
            }
            repaint();
        }

        private void setAltHeld(boolean held) {
            if (altHeld != held) {
                altHeld = held;
                repaint();
            }
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
            List<Card> groups = groupsBackToFront();
            for (int i = groups.size() - 1; i >= 0; i--) {
                if (edgesAt(groups.get(i), p) != 0) {
                    return groups.get(i);           // 小さい（手前の）枠のふちを先につかむ
                }
            }
            return null;
        }

        /** ふちの向きに合った、矢印の形にする（ウィンドウのふちと同じ）。 */
        private void updateCursor(Point viewPoint) {
            int type = Cursor.DEFAULT_CURSOR;
            Card under = cardAt(toModel(viewPoint));
            if (threadSource != null || (altHeld && editor == null && under != null && !under.group)) {
                type = Cursor.CROSSHAIR_CURSOR;     // Alt を押している間は、カードの上で「引ける」形にする
            } else if (editor == null && linkSource == null) {
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
                left = Math.min(left + dx, right - minWidth);
            }
            if ((resizeEdges & EAST) != 0) {
                right = Math.max(right + dx, left + minWidth);
            }
            if ((resizeEdges & NORTH) != 0) {
                top = Math.min(top + dy, bottom - minHeight);
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
            scrollByWheel(e);
        }

        private void press(MouseEvent e) {
            stopEditing(true);
            requestFocusInWindow();
            Point p = toModel(e.getPoint());
            Card card = cardAt(p);
            pressView = e.getPoint();

            if (SwingUtilities.isMiddleMouseButton(e)) {
                panning = true;         // 中ボタン（ホイールを押す）：表示位置を動かす
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                panStart = viewOrigin();
                pressView = SwingUtilities.convertPoint(this, e.getPoint(), scrollPane);
                return;
            }
            if (SwingUtilities.isRightMouseButton(e)) {
                // 右ボタン：ドラッグすれば表示位置を動かし、動かさずに離せばメニューを出す（UE5 と同じ）
                if (card == null || !selection.contains(card)) {
                    select(card);       // 選択の外を右クリックしたら、そのカードだけを選び直す
                }
                rightPending = true;
                panning = false;
                menuCard = card;
                menuView = e.getPoint();
                menuModel = p;
                panStart = viewOrigin();
                pressView = SwingUtilities.convertPoint(this, e.getPoint(), scrollPane);
                repaint();
                fireStatusChanged();
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
            lastModel = p;
            stateAtPress = snapshot();
            moved = false;
            resizing = false;
            rubberBand = null;
            dragMembers.clear();
            dragStarts.clear();

            Card note = card != null && !card.group ? card : null;
            if (note != null && e.isAltDown()) {
                startThread(note, p);   // Alt+ドラッグ：長押しを待たずに、すぐ糸を伸ばす
                return;
            }

            boolean adding = e.isShiftDown();
            boolean toggling = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
            dragAnchor = null;
            Card edgeCard = adding || toggling ? null : resizableAt(p);
            // カードの中を押したら、長押しを待つ（そのまま動かさずにいると、糸を伸ばす操作になる）
            holdCard = edgeCard == null && note != null && !adding && !toggling ? note : null;
            if (holdCard != null) {
                holdTimer.restart();
            }
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
                // 何もない所（枠の中の空いた所も）から：範囲選択を始める
                if (!adding && !toggling) {
                    selection.clear();
                }
                rubberRemoves = toggling;
                rubberBand = new Rectangle(p);
            } else {
                if (adding) {
                    selection.add(card);                // Shift+クリック：選択に足す
                } else if (toggling) {
                    if (!selection.remove(card)) {       // Ctrl+クリック：選択に足す／外す
                        selection.add(card);
                    }
                } else if (!selection.contains(card)) {
                    select(card);
                }
                if (selection.contains(card)) {
                    dragMembers.addAll(selection);
                    // グループ枠を動かすときは、中に入っているもの（小さい枠と、その中身も）を一緒に動かす
                    for (Card selected : new ArrayList<>(selection)) {
                        if (selected.group) {
                            for (Card inner : descendants(selected)) {
                                if (!dragMembers.contains(inner)) {
                                    dragMembers.add(inner);
                                }
                            }
                        }
                    }
                    dragAnchor = card;
                    anchorStart = new Point(card.x, card.y);
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
            if (rightPending && !panning) {
                Point now = SwingUtilities.convertPoint(this, e.getPoint(), scrollPane);
                if (now.distance(pressView) < 5) {
                    return;             // 手ぶれは、まだクリックとみなす
                }
                panning = true;
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }
            if (panning) {
                // 押した所が、マウスについてくるように動かす（スクロール枠から見た位置で測る）
                Point now = SwingUtilities.convertPoint(this, e.getPoint(), scrollPane);
                showFrom(panStart.x - (now.x - pressView.x) / scale, panStart.y - (now.y - pressView.y) / scale);
                return;
            }
            if (pressModel == null) {
                return;
            }
            Point p = toModel(e.getPoint());
            lastModel = p;
            if (holdCard != null && e.getPoint().distance(pressView) > 4) {
                holdTimer.stop();       // 長押しの前に動かした：ふつうの移動
                holdCard = null;
            }
            if (threadSource != null) {
                threadEnd = p;
                repaint();
                return;
            }
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
            if (!resizing && dragAnchor != null) {
                // つかんだカードの左上が、格子にそろうようにずらす（ほかのカードも同じだけ動く）
                dx = Math.round((anchorStart.x + dx) / (float) GRID) * GRID - anchorStart.x;
                dy = Math.round((anchorStart.y + dy) / (float) GRID) * GRID - anchorStart.y;
            }
            if (resizing) {
                resizeTo(dx, dy);
            } else {
                for (int i = 0; i < dragMembers.size(); i++) {
                    Card member = dragMembers.get(i);
                    member.x = dragStarts.get(i).x + dx;
                    member.y = dragStarts.get(i).y + dy;
                }
            }
            moved = true;
            revalidate();
            repaint();
        }

        private void release(MouseEvent e) {
            if (rightPending) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    rightPending = false;
                    boolean wasPanning = panning;
                    panning = false;
                    if (!wasPanning) {
                        showMenu(menuCard, menuView, menuModel);
                    }
                }
                return;
            }
            holdTimer.stop();
            holdCard = null;
            if (threadSource != null) {
                finishThread();
                pressModel = null;
                stateAtPress = null;
                moved = false;
                dragAnchor = null;
                return;
            }
            if (rubberBand != null) {
                // 囲んだ四角に少しでも重なったカードと、すっぽり入った枠を、選択に加える（Ctrl なら外す）
                for (Card card : cards) {
                    boolean hit = card.group ? rubberBand.contains(card.bounds()) : rubberBand.intersects(card.bounds());
                    if (hit) {
                        if (rubberRemoves) {
                            selection.remove(card);
                        } else {
                            selection.add(card);
                        }
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
            dragAnchor = null;
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
                menu.add(item("グループ枠で囲む", BoardPane.this::groupSelected));
                if (only != null && only.group) {
                    menu.add(item("枠の中を整列", BoardPane.this::tidy));
                }
                if (topLevel(selection).size() >= 2) {
                    menu.add(item("選んだものを整列", BoardPane.this::tidy));
                }
                if (selectedNotes().size() >= 2) {
                    menu.add(item("選んだ順に矢印でつなぐ（L）", BoardPane.this::linkSelected));
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
            Dimension viewport = scrollPane.getViewport().getExtentSize();
            if (!canvasReady) {
                // まだ表示していない：(0, 0) からカードの右下までに、余白を足した大きさ
                Rectangle bounds = contentBounds(cards);
                int width = bounds == null ? 0 : bounds.x + bounds.width;
                int height = bounds == null ? 0 : bounds.y + bounds.height;
                return new Dimension(Math.max((int) ((width + 600) * scale), viewport.width),
                        Math.max((int) ((height + 600) * scale), viewport.height));
            }
            return new Dimension(Math.max((int) Math.ceil(canvas.width * scale), viewport.width),
                    Math.max((int) Math.ceil(canvas.height * scale), viewport.height));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.scale(scale, scale);
            g2.translate(-canvas.x, -canvas.y);     // ここから先は、ボード上の座標でそのまま描ける
            paintBoard(g2, true);
            g2.dispose();
        }

        /** ボードを描く。decorations が false のときは、選択の枠などを描かない（画像の書き出し用）。 */
        void paintBoard(Graphics2D g2, boolean decorations) {
            AppleTheme.quality(g2);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle clip = g2.getClipBounds();
            g2.setColor(Theme.BG);
            g2.fillRect(clip.x, clip.y, clip.width, clip.height);

            // 背景の点（位置の目安。控えめに）
            int dot = AppleTheme.DOT_STEP;
            g2.setColor(look().dot);
            for (int x = clip.x - Math.floorMod(clip.x, dot); x < clip.x + clip.width; x += dot) {
                for (int y = clip.y - Math.floorMod(clip.y, dot); y < clip.y + clip.height; y += dot) {
                    g2.fillRect(x, y, 2, 2);
                }
            }

            if (cards.isEmpty() && decorations) {
                g2.setFont(Theme.ui());
                g2.setColor(Theme.FG_DIM);
                g2.drawString("ダブルクリックでカードを追加　／　右クリックでメニュー　／　Ctrl+V で一括追加", 40, 60);
                g2.drawString("カードを長押し（または Alt を押しながら）して引くと、矢印でつなげます", 40, 60 + g2.getFontMetrics().getHeight() + 8);
            }

            // 描く範囲（clip）の外にあるものは飛ばす。カードが数百枚あると、見えない物の折り返しや塗りで、
            // ドラッグのたびの描き直しが重くなるため。影・枠の線・矢じりがはみ出すぶん、少し広く見る
            Rectangle near = new Rectangle(clip);
            near.grow(PAINT_MARGIN, PAINT_MARGIN);

            // 奥から順に：グループ枠 → 矢印 → カード
            for (Card group : groupsBackToFront()) {
                if (near.intersects(group.bounds())) {
                    paintGroup(g2, group, decorations);   // 大きい枠から描くので、小さい枠が手前に出る
                }
            }
            boolean threading = decorations && threadSource != null;
            Map<Integer, Card> byId = new HashMap<>();
            for (Card card : cards) {
                byId.putIfAbsent(card.id, card);         // find と同じく、同じ番号なら先のカード
            }
            for (Link link : links) {
                Card from = byId.get(link.from());
                Card to = byId.get(link.to());
                if (from != null && to != null && near.intersects(BoardLook.arrowBounds(from.bounds(), to.bounds(), arrowScale()))) {
                    paintArrow(g2, from, to, threading ? 90 : 255);     // 糸を引いている間は、ほかの矢印を控えめに
                }
            }
            for (Card card : cards) {
                if (!card.group && card != editingCard && near.intersects(card.bounds())) {
                    paintCard(g2, card, decorations);
                }
            }
            if (decorations) {
                paintThread(g2);
            }
            if (rubberBand != null && decorations) {
                g2.setColor(BoardLook.withAlpha(look().accent, 40));
                g2.fill(rubberBand);
                g2.setStroke(new BasicStroke(1f));
                g2.setColor(look().accent);
                g2.draw(rubberBand);
            }
        }

        private void paintGroup(Graphics2D g2, Card card, boolean decorations) {
            boolean selected = decorations && selection.contains(card);
            boolean nested = parentOf(card) != null;
            AppleTheme t = look();
            // 薄い地と 1px の線。見出しの帯（ここをつかむと枠ごと動く）だけ、少し濃くする
            BoardLook.paintGroup(g2, t, card.x, card.y, card.w, card.h, card.color, nested, titleHeight());
            if (selected) {
                BoardLook.paintRing(g2, t, card.x, card.y, card.w, card.h, BoardLook.groupRadius(nested));
            }
            if (card != editingCard) {
                g2.setFont(cardFont.deriveFont(Font.BOLD));
                g2.setColor(BoardLook.groupTitle(t));
                FontMetrics fm = g2.getFontMetrics();
                Shape oldClip = g2.getClip();
                g2.clipRect(card.x, card.y, card.w, card.h);
                g2.drawString(card.text.replace("\n", " "), card.x + padX() + 2, card.y + padY() + fm.getAscent());
                g2.setClip(oldClip);
            }
            if (selected) {
                paintHandle(g2, card);
            }
        }

        private void paintCard(Graphics2D g0, Card card, boolean decorations) {
            // 引きずっている間と、糸の元のカードは、少し大きくして影を深くする（持ち上げて見せる）
            boolean lifted = decorations && (card == threadSource || (moved && dragMembers.contains(card)));
            Graphics2D g2 = (Graphics2D) g0.create();
            if (lifted) {
                double cx = card.x + card.w / 2.0;
                double cy = card.y + card.h / 2.0;
                g2.translate(cx, cy);
                g2.scale(AppleTheme.LIFT_SCALE, AppleTheme.LIFT_SCALE);
                g2.translate(-cx, -cy);
            }
            BoardLook.paintCard(g2, card.x, card.y, card.w, card.h, card.color, lifted);
            boolean selected = decorations && selection.contains(card);
            if (selected || (decorations && card == linkSource)) {
                BoardLook.paintRing(g2, look(), card.x, card.y, card.w, card.h, AppleTheme.RADIUS_CARD);
            }

            g2.setFont(cardFont.deriveFont(Font.BOLD));
            FontMetrics fm = g2.getFontMetrics();
            int x = card.x + padX();
            double y = card.y + padY();
            if (card.text.isEmpty()) {
                g2.setColor(BoardLook.placeholder(card.color));         // 空のカードは「無題」と薄く
                g2.drawString("無題", x, (float) (y + fm.getAscent()));
            } else {
                // 入りきらない行は出さず、最後の行の末尾を「…」にする
                g2.setColor(BoardLook.text(card.color));
                List<String> lines = wrap(card.text, fm, card.w - padX() * 2);
                int room = (int) Math.max(1, (card.h - padY() * 2 - voteHeight(card)) / lineHeight(fm) + 0.01);
                if (lines.size() > room) {
                    lines = new ArrayList<>(lines.subList(0, room));
                    String last = lines.get(room - 1);
                    while (!last.isEmpty() && fm.stringWidth(last + "…") > card.w - padX() * 2) {
                        last = last.substring(0, last.length() - 1);
                    }
                    lines.set(room - 1, last + "…");
                }
                Shape oldClip = g2.getClip();
                g2.clipRect(card.x, card.y, card.w, card.h);
                for (String line : lines) {
                    g2.drawString(line, x, (float) (y + fm.getAscent()));
                    y += lineHeight(fm);
                }
                g2.setClip(oldClip);
            }

            // 投票の点（多いときは、点1つと数字で表す）
            if (card.votes > 0) {
                g2.setColor(new Color(0xC0392B));
                int dotY = card.y + card.h - 20;
                if (card.votes <= 8) {
                    for (int i = 0; i < card.votes; i++) {
                        g2.fillOval(x + i * 18, dotY, 13, 13);
                    }
                } else {
                    g2.fillOval(x, dotY, 13, 13);
                    g2.setFont(Theme.small().deriveFont(Font.BOLD, 15f));
                    g2.drawString("× " + card.votes, x + 20, dotY + 12);
                }
            }
            if (selected && single() == card) {
                paintHandle(g2, card);
            }
            g2.dispose();
        }

        /** from から to へ矢印を描く（横に並べば左右の辺の中央、重なれば上下の辺の中央を、曲線で結ぶ）。 */
        private void paintArrow(Graphics2D g2, Card from, Card to, int alpha) {
            BoardLook.paintArrow(g2, look(), from.bounds(), to.bounds(), alpha, arrowScale());
        }

        /** カード c の少し外のふちの上で、c の中心から (x, y) へ向かう線が通る点。 */
        private Point2D.Double borderPoint(Card c, double x, double y) {
            double cx = c.x + c.w / 2.0;
            double cy = c.y + c.h / 2.0;
            double dx = x - cx;
            double dy = y - cy;
            double tx = Math.abs(dx) < 1e-6 ? Double.MAX_VALUE : (c.w / 2.0 + 6) / Math.abs(dx);
            double ty = Math.abs(dy) < 1e-6 ? Double.MAX_VALUE : (c.h / 2.0 + 6) / Math.abs(dy);
            double t = Math.min(1, Math.min(tx, ty));
            return new Point2D.Double(cx + dx * t, cy + dy * t);
        }

        /** (x1, y1) から (x2, y2) へ線を引く。dashed なら点線、head なら先に矢じりを付ける。 */
        private void drawArrow(Graphics2D g2, double x1, double y1, double x2, double y2,
                Color color, boolean dashed, boolean head) {
            BoardLook.paintLine(g2, color, x1, y1, x2, y2, dashed, head, arrowScale());
        }

        /** カードのふちを、にじむように光らせる（alpha が大きいほど強く）。 */
        private void paintGlow(Graphics2D g2, Card card, Color color, int alpha) {
            for (int i = 3; i >= 1; i--) {
                g2.setStroke(new BasicStroke(i * 3f));
                g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha / i))));
                int r = AppleTheme.RADIUS_CARD + 4;
                g2.drawRoundRect(card.x - 2, card.y - 2, card.w + 4, card.h + 4, r, r);
            }
        }

        /**
         * 糸まわりの見せ方。
         *   Alt を押している間 … 引けるカードのふちを淡く光らせる（どこからでも引けることを示す）
         *   糸を引いている間   … 元のカードを光らせ、近いカードほどふちをにじませる（つながる先の予告）。
         *                        つなげる相手の上では、糸が相手のふちに吸い付いて実線になり、相手が少し大きくなる。
         *                        つなげない相手の上では、点線のまま相手を暗くし、理由をカーソルの横に出す。
         *                        何もない所では、新しいカードの影を出す
         *   そのあと           … つないだら光が矢印を流れ、やめたら糸が縮んで消える
         */
        private void paintThread(Graphics2D g2) {
            if (altHeld && threadSource == null && editor == null) {
                for (Card card : cards) {
                    if (!card.group) {
                        paintGlow(g2, card, look().arrow, 70);
                    }
                }
            }
            long now = System.currentTimeMillis();
            if (retractSource != null && retractEnd != null && cards.contains(retractSource)) {
                double t = Math.min(1, (now - retractStart) / (double) RETRACT_MS);
                double cx = retractSource.x + retractSource.w / 2.0;
                double cy = retractSource.y + retractSource.h / 2.0;
                double ex = retractEnd.x + (cx - retractEnd.x) * t;
                double ey = retractEnd.y + (cy - retractEnd.y) * t;
                Point2D.Double start = borderPoint(retractSource, ex, ey);
                if (!retractSource.bounds().contains(ex, ey)) {
                    drawArrow(g2, start.x, start.y, ex, ey, new Color(0x9A9A9A, false), true, false);
                }
            }
            for (Flash f : flashes) {
                if (!cards.contains(f.from()) || !cards.contains(f.to())) {
                    continue;
                }
                double t = Math.min(1, (now - f.start()) / (double) FLASH_MS);
                Point2D.Double a = borderPoint(f.from(), f.to().x + f.to().w / 2.0, f.to().y + f.to().h / 2.0);
                Point2D.Double b = borderPoint(f.to(), f.from().x + f.from().w / 2.0, f.from().y + f.from().h / 2.0);
                double x = a.x + (b.x - a.x) * t;
                double y = a.y + (b.y - a.y) * t;
                g2.setColor(new Color(look().arrow.getRed(), look().arrow.getGreen(), look().arrow.getBlue(), 110));
                g2.fill(new java.awt.geom.Ellipse2D.Double(x - 11, y - 11, 22, 22));
                g2.setColor(Color.WHITE);
                g2.fill(new java.awt.geom.Ellipse2D.Double(x - 5, y - 5, 10, 10));
            }
            if (threadSource == null || threadEnd == null) {
                return;
            }

            Card source = threadSource;
            Card target = cardAt(threadEnd);
            String problem = target == null ? null : linkProblem(source, target);
            if (target == source) {
                target = null;          // 自分の上：まだ引き出していないだけなので、何も言わない
                problem = "";
            }
            // 近いカードほど、ふちをにじませる
            for (Card card : cards) {
                if (card.group || card == source || card == target || linkProblem(source, card) != null) {
                    continue;
                }
                Rectangle r = card.bounds();
                double ddx = Math.max(0, Math.max(r.x - threadEnd.x, threadEnd.x - (r.x + r.width)));
                double ddy = Math.max(0, Math.max(r.y - threadEnd.y, threadEnd.y - (r.y + r.height)));
                double distance = Math.hypot(ddx, ddy);
                if (distance < NEAR) {
                    paintGlow(g2, card, look().arrow, (int) (150 * (1 - distance / NEAR)));
                }
            }
            paintGlow(g2, source, Theme.ACCENT, 220);           // 元のカード

            double sx = source.x + source.w / 2.0;
            double sy = source.y + source.h / 2.0;
            if (target != null && problem == null) {
                // つなげる：糸が相手のふちに吸い付いて実線になり、相手が少し大きくなって受け止める
                Graphics2D lifted = (Graphics2D) g2.create();
                double cx = target.x + target.w / 2.0;
                double cy = target.y + target.h / 2.0;
                lifted.translate(cx, cy);
                lifted.scale(1.04, 1.04);
                lifted.translate(-cx, -cy);
                paintCard(lifted, target, false);
                paintGlow(lifted, target, look().arrow, 255);
                lifted.dispose();
                Point2D.Double tip = borderPoint(target, sx, sy);
                Point2D.Double start = borderPoint(source, tip.x, tip.y);
                drawArrow(g2, start.x, start.y, tip.x, tip.y, look().arrow, false, true);
            } else if (target != null) {
                // つなげない：相手を暗くし、点線のまま。理由をカーソルの横に出す
                g2.setColor(new Color(0, 0, 0, 120));
                g2.fillRoundRect(target.x, target.y, target.w, target.h, AppleTheme.RADIUS_CARD, AppleTheme.RADIUS_CARD);
                Point2D.Double stop = borderPoint(target, sx, sy);        // 糸は相手のふちで止める（中には入らない）
                Point2D.Double start = borderPoint(source, stop.x, stop.y);
                drawArrow(g2, start.x, start.y, stop.x, stop.y, new Color(0x9A9A9A), true, false);
                paintTip(g2, problem, threadEnd);
            } else if (problem == null) {
                // 何もない所：離すと、ここに新しいカードができる
                int size = cardFont.getSize();
                int w = size * 10;
                int h = size * 6;
                int gx = threadEnd.x - w / 2;
                int gy = threadEnd.y - h / 2;
                g2.setColor(new Color(255, 255, 255, 22));
                g2.fillRoundRect(gx, gy, w, h, AppleTheme.RADIUS_CARD, AppleTheme.RADIUS_CARD);
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 6f}, 0f));
                g2.setColor(new Color(look().arrow.getRed(), look().arrow.getGreen(), look().arrow.getBlue(), 160));
                g2.drawRoundRect(gx, gy, w, h, AppleTheme.RADIUS_CARD, AppleTheme.RADIUS_CARD);
                g2.setFont(cardFont.deriveFont(Font.BOLD, cardFont.getSize() * 1.6f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString("＋", threadEnd.x - fm.stringWidth("＋") / 2, threadEnd.y + fm.getAscent() / 2 - 2);
                Point2D.Double tip = new Point2D.Double(gx, threadEnd.y);
                if (Math.abs(threadEnd.x - sx) < Math.abs(threadEnd.y - sy)) {
                    tip = new Point2D.Double(threadEnd.x, threadEnd.y > sy ? gy : gy + h);
                } else if (threadEnd.x < sx) {
                    tip = new Point2D.Double(gx + w, threadEnd.y);
                }
                Point2D.Double start = borderPoint(source, tip.x, tip.y);
                drawArrow(g2, start.x, start.y, tip.x, tip.y,
                        new Color(look().arrow.getRed(), look().arrow.getGreen(), look().arrow.getBlue(), 200), true, true);
            }
        }

        /** カーソルの横に、短い説明を出す。 */
        private void paintTip(Graphics2D g2, String text, Point at) {
            g2.setFont(Theme.small());
            FontMetrics fm = g2.getFontMetrics();
            int x = at.x + 18;
            int y = at.y + 18;
            int w = fm.stringWidth(text) + 16;
            int h = fm.getHeight() + 8;
            g2.setColor(new Color(20, 20, 20, 220));
            g2.fillRoundRect(x, y, w, h, 8, 8);
            g2.setColor(Color.WHITE);
            g2.drawString(text, x + 8, y + 4 + fm.getAscent());
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
