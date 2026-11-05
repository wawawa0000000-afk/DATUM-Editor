package com.example.minieditor;

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
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.font.TextHitInfo;
import java.awt.im.InputMethodRequests;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.text.CharacterIterator;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.AttributeSet;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.undo.CompoundEdit;
import javax.swing.undo.UndoManager;

/**
 * 縦書きの文書。
 *
 * 文字は右上から下へ並び、行が変わると左へ進む。
 * JTextArea は横書き専用なので、文字の配置・カーソル・キー操作をすべて自分で作っている。
 * 文章そのものは DefaultStyledDocument に入れてあり、「元に戻す」は横書きと同じ UndoManager が使える。
 *
 * ルビ（ふりがな）は、文字に付ける「属性」として持っている（文章の中に記号は入れない）。
 * 属性は文字と一緒に動くので、前に文字を足しても、ルビは同じ文字に付いたままになる。
 * ファイルに保存するときだけ、「小説家になろう」の記法（｜親文字《ルビ》）に直して書き出し、
 * 開くときは、その記法を読み取って属性に戻す。
 *
 * 表示は2通り：
 *   原稿用紙 … 20字×20行のマス目。1枚ごとに区切って、右から左へ並べる。マスの右の細い欄がルビ欄。
 *   ふつう   … マス目なし。1列の文字数は、ウィンドウの高さで決まる。
 */
public class VerticalTextPane extends EditorPane implements Searchable {

    /** 文字に付ける属性の名前。値は RubyMark。 */
    private static final Object RUBY = "ruby";

    /**
     * ルビ1つぶんの印。equals を作らないので、「同じ物かどうか」だけで区別される。
     * そのため、となり合う2文字に同じ「・」を振っても、1つにまとまらず、別々のルビとして残る。
     */
    private static final class RubyMark {
        final String ruby;

        RubyMark(String ruby) {
            this.ruby = ruby;
        }
    }

    /** 同じ文書を2つのタブで映すときに、共有するもの（文章・ルビと「元に戻す」の履歴）。 */
    private static final class Shared {
        final DefaultStyledDocument doc = new DefaultStyledDocument();
        final UndoManager undoManager = new UndoManager();
        boolean loading;
        CompoundEdit compound;
        VerticalTextPane active;        // 最後に打っていたタブ（カーソルを、書き換えた所へ動かすのはこのタブだけ）

        Shared() {
            doc.addUndoableEditListener(e -> {
                if (loading) {
                    return;
                }
                if (compound != null) {
                    compound.addEdit(e.getEdit());
                } else {
                    undoManager.addEdit(e.getEdit());
                }
            });
        }
    }

    private final Shared shared;
    private final DefaultStyledDocument doc;
    private final UndoManager undoManager;
    private final View view = new View();
    private final JScrollPane scrollPane = new JScrollPane(view,
            JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

    private String text = "";           // 文書の中身の写し（描くたびに取り出すと遅いので持っておく）
    private int[] lineStarts;           // 各行の始まりの位置（ステータスバーの「行」を、毎回先頭から数えないため）。null なら作り直す
    private int caret = 0;              // カーソルの位置（何文字目の前か）
    private int anchor = 0;             // 範囲選択の起点（選択していないときは caret と同じ）
    private List<Highlight> highlights = List.of();

    /** 原稿用紙1枚の、1行の文字数と行数（400字詰め）。 */
    public static final int SHEET_ROWS = 20;
    public static final int SHEET_COLUMNS = 20;
    private boolean manuscript = true;
    private List<VerticalLayout.RubyRun> runs = List.of();  // ルビの一覧（文書の属性から作った写し）

    // ルビを入力している間だけ出す、小さな入力欄
    private JTextField rubyField;
    private int rubyEditStart;
    private int rubyEditEnd;

    // 日本語入力（IME）で、まだ確定していない変換中の文字。
    // 文書には入れず、カーソルの位置に「はさんで」表示だけする。
    private String composed = "";
    private int composedCaret = 0;      // 変換中の文字の中での、カーソルの位置

    // 文字ごとの置き場所。colOf[i] が「右から何列目か」、rowOf[i] が「上から何文字目か」
    private int[] colOf = {0};
    private int[] rowOf = {0};
    private int totalCols = 1;
    private int usedCols = 1;           // 文字が入っている列の数（文末のカーソルのためだけの列を除く。PDF の枚数と同じ数え方）
    private VerticalLayout layout = new VerticalLayout("", 1, List.of());
    private int layoutHeight = -1;
    private boolean layoutDirty = true;
    private int rowsPerColumn = 1;      // 1列に入る文字数
    // 文字のない所（行の終わりより下や、文書の終わりより先の列）をクリックしたときの、マス（x=列、y=行）。
    // 打ち始めたときに、そこまでを全角の空白と改行で埋める（打たずにほかへ移れば、何も足さない）
    private Point freeCell;

    private boolean caretVisible = true;
    private final Timer blinkTimer = new Timer(530, e -> {
        caretVisible = !caretVisible;
        view.repaint();
    });

    public VerticalTextPane(Font font) {
        this(font, new Shared());
    }

    // 文書の見張り（同じ文書を映していたタブを閉じたときに外せるよう、名前を付けて持つ）
    private final DocumentListener documentListener = new DocumentListener() {
        public void insertUpdate(DocumentEvent e) {
            if (isActiveView()) {
                documentChanged(e.getOffset() + e.getLength());
            } else {
                // ほかのタブで書き換えられたときは、カーソルと選択を文字が増えたぶんだけずらし、スクロールもしない
                // （右で別の場所を読みながら、左で書けるように）
                otherViewChanged(e.getOffset(), e.getLength());
            }
        }

        public void removeUpdate(DocumentEvent e) {
            if (isActiveView()) {
                documentChanged(e.getOffset());
            } else {
                otherViewChanged(e.getOffset(), -e.getLength());
            }
        }

        public void changedUpdate(DocumentEvent e) {
            attributesChanged();    // ルビ（属性）が付いた・外れた
        }
    };

    private VerticalTextPane(Font font, Shared shared) {
        this.shared = shared;
        doc = shared.doc;
        undoManager = shared.undoManager;
        if (shared.active == null) {
            shared.active = this;
        }
        view.setFont(font);

        doc.addDocumentListener(documentListener);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(Theme.BG);
        // 縦書きは左へ読み進めるので、ホイール（タッチパッドの上下）を下に回したら左へ動かす
        WheelScroll.install(scrollPane, true);
        // 枠の大きさが変わると、1列に入る文字数が変わるので、並べ直す
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutDirty = true;
                view.revalidate();
                view.repaint();
                SwingUtilities.invokeLater(VerticalTextPane.this::scrollToCaret);
            }
        });
        add(scrollPane, BorderLayout.CENTER);
    }

    public static VerticalTextPane fromJson(Map<String, Object> json) {
        Font font = new Font(Json.str(json, "fontName", "Monospaced"), Font.PLAIN,
                Theme.zoomed(Json.num(json, "fontSize", Theme.unzoomed(Theme.EDITOR_SIZE))));
        VerticalTextPane pane = new VerticalTextPane(font);
        pane.manuscript = Json.bool(json, "manuscript", true);
        pane.loadText(Json.str(json, "text", ""));
        return pane;
    }

    @Override
    public EditorPane newView() {
        VerticalTextPane other = new VerticalTextPane(view.getFont(), shared);
        other.manuscript = manuscript;
        boolean wasLoading = shared.loading;
        shared.loading = true;          // 中身は変わっていないので、未保存の印は付けない
        other.documentChanged(Math.min(caret, text.length()));     // 今の中身を、新しいタブの写しにも入れる
        shared.loading = wasLoading;
        return linkView(other);
    }

    /** 書き換えた所へカーソルを動かすタブか（最後に打っていたタブ。そのタブが閉じられたあとは、残ったタブ）。 */
    private boolean isActiveView() {
        return shared.active == this || shared.active == null;
    }

    @Override
    protected void detachShared() {
        doc.removeDocumentListener(documentListener);
        if (shared.active == this) {
            shared.active = null;
        }
    }

    /** offset の所で文字が delta 個増えた（負なら減った）ときの、position の新しい位置。 */
    private static int shifted(int position, int offset, int delta) {
        if (position < offset) {
            return position;
        }
        return Math.max(offset, position + delta);
    }

    // ---------- 文書の操作 ----------

    private void documentChanged(int newCaret) {
        try {
            text = doc.getText(0, doc.getLength());
            lineStarts = null;          // 行の始まりの表は、次に要るときに作り直す
        } catch (BadLocationException ex) {
            text = "";
        }
        rebuildRuns();
        composed = "";                  // 文書が変わったら、変換中の表示は取り消す（位置がずれるため）
        caret = anchor = Math.max(0, Math.min(newCaret, text.length()));
        layoutDirty = true;
        caretVisible = true;
        view.revalidate();
        view.repaint();
        if (!shared.loading) {
            fireContentChanged();
        }
        SwingUtilities.invokeLater(this::scrollToCaret);
    }

    /** 同じ文書を映すほかのタブで書き換えられたとき。カーソル・選択・スクロールの位置は、そのまま保つ。 */
    private void otherViewChanged(int offset, int delta) {
        int newCaret = shifted(caret, offset, delta);
        int newAnchor = shifted(anchor, offset, delta);
        try {
            text = doc.getText(0, doc.getLength());
            lineStarts = null;          // 行の始まりの表は、次に要るときに作り直す
        } catch (BadLocationException ex) {
            text = "";
        }
        rebuildRuns();
        caret = Math.max(0, Math.min(newCaret, text.length()));
        anchor = Math.max(0, Math.min(newAnchor, text.length()));
        layoutDirty = true;
        view.revalidate();
        view.repaint();
        if (!shared.loading) {
            fireContentChanged();
        }
    }

    /** ルビだけが変わったとき（文字は変わっていない）。 */
    private void attributesChanged() {
        rebuildRuns();
        layoutDirty = true;
        view.revalidate();
        view.repaint();
        if (!shared.loading) {
            fireContentChanged();
        }
    }

    /** 文書の属性を先頭から見ていき、ルビの一覧を作り直す。 */
    private void rebuildRuns() {
        List<VerticalLayout.RubyRun> found = new ArrayList<>();
        int length = doc.getLength();
        RubyMark current = null;
        int start = 0;
        int position = 0;
        // 一度ルビにした印。親文字の途中にルビの外の文字が入って（11文字目など）、同じ印が2か所に分かれたときは、
        // 前のほうだけをルビにする（両方に同じふりがなが付かないように。間の文字を消せば、また1つにつながる）
        java.util.Set<RubyMark> used = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (position < length) {
            Element element = doc.getCharacterElement(position);
            Object mark = element.getAttributes().getAttribute(RUBY);
            if (mark != null && mark != current && used.contains(mark)) {
                mark = null;
            }
            if (mark != current) {      // 印が変わった所が、ルビの切れ目
                if (current != null) {
                    found.add(new VerticalLayout.RubyRun(start, position, current.ruby));
                    used.add(current);
                }
                current = (RubyMark) mark;
                start = position;
            }
            position = Math.max(position + 1, Math.min(element.getEndOffset(), length));
        }
        if (current != null) {
            found.add(new VerticalLayout.RubyRun(start, length, current.ruby));
        }
        runs = found;
    }

    private static AttributeSet rubyAttribute(String ruby) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        attributes.addAttribute(RUBY, new RubyMark(ruby));
        return attributes;
    }

    /**
     * ファイルの中身を入れる。「小説家になろう」の記法が入っていれば、ルビとして読み取る。
     */
    public void loadText(String notation) {
        NarouRuby.Parsed parsed = NarouRuby.read(notation.replace("\r", ""));
        closeRubyField(false);
        shared.loading = true;
        try {
            doc.remove(0, doc.getLength());
            doc.insertString(0, parsed.text(), SimpleAttributeSet.EMPTY);
            for (VerticalLayout.RubyRun run : parsed.rubies()) {
                doc.setCharacterAttributes(run.baseStart(), run.baseEnd() - run.baseStart(),
                        rubyAttribute(run.ruby()), true);
            }
        } catch (BadLocationException ex) {
            // 0 文字目から全体を入れ替えているので、通常は起きない
        }
        shared.loading = false;
        dropComposition();
        caret = anchor = 0;
        caretMoved();
        undoManager.discardAllEdits();
        setDirty(false);
    }

    /** 複数の変更を、「元に戻す」1回ぶんにまとめて実行する。 */
    private void edit(DocumentChange change) {
        if (shared.compound != null) {
            try {
                change.run();           // すでにまとめている最中なら、その中に入れる
            } catch (BadLocationException ex) {
                // 範囲は呼び出し側で文書の中に収めているので、通常は起きない
            }
            return;
        }
        shared.compound = new CompoundEdit();
        try {
            change.run();
        } catch (BadLocationException ex) {
            // 範囲は呼び出し側で文書の中に収めているので、通常は起きない
        } finally {
            // 途中で思わぬ失敗があっても、まとめるのをやめる（「元に戻す」が効かなくならないように）
            shared.compound.end();
            undoManager.addEdit(shared.compound);
            shared.compound = null;
        }
    }

    private interface DocumentChange {
        void run() throws BadLocationException;
    }

    /** start から end までを replacement に置き換える（「元に戻す」1回ぶんにまとめる）。 */
    private void replace(int start, int end, String replacement) {
        freeCell = null;
        int[] newCaret = {start + replacement.length()};
        edit(() -> {
            if (end > start) {
                doc.remove(start, end - start);
            }
            if (!replacement.isEmpty()) {
                doc.insertString(start, replacement, attributesForInsert(start, replacement));
            }
            newCaret[0] = absorbNotation(newCaret[0]);
        });
        caret = anchor = Math.max(0, Math.min(newCaret[0], text.length()));
        caretMoved();
        view.repaint();
    }

    /**
     * 入れる文字に付ける属性を決める。
     * ルビの付いた文字の「あいだ」に入れるときは、同じルビの続きにする（親文字が伸びる）。
     * ルビの前後や、ふつうの文字の所では、何も付けない。
     */
    private AttributeSet attributesForInsert(int position, String inserted) {
        if (inserted.indexOf('\n') >= 0 || position <= 0 || position >= doc.getLength()) {
            return SimpleAttributeSet.EMPTY;
        }
        Object before = doc.getCharacterElement(position - 1).getAttributes().getAttribute(RUBY);
        Object after = doc.getCharacterElement(position).getAttributes().getAttribute(RUBY);
        if (before != null && before == after) {
            VerticalLayout.RubyRun run = runAt(position, false);
            if (run != null && !NarouRuby.fits(text.substring(run.baseStart(), run.baseEnd()) + inserted, run.ruby())) {
                return SimpleAttributeSet.EMPTY;    // 伸ばすと保存できない長さや文字になるときは、ルビの外の文字にする
            }
            SimpleAttributeSet attributes = new SimpleAttributeSet();
            attributes.addAttribute(RUBY, before);
            return attributes;
        }
        return SimpleAttributeSet.EMPTY;
    }

    /**
     * 打ち込んだ文章の中に「｜親文字《ルビ》」の形ができていたら、記号を消して、ルビに変える。
     * これで、キーボードから記法を打つだけでも、ルビを振れる。
     * 記号を消すと位置がずれるので、ずれたあとのカーソル位置を返す。
     */
    private int absorbNotation(int caretPosition) throws BadLocationException {
        String original = text;
        List<NarouRuby.Span> spans = NarouRuby.parse(original);
        // 後ろから順に直す（前から直すと、後ろにあるルビの位置がずれてしまうため）
        for (int i = spans.size() - 1; i >= 0; i--) {
            NarouRuby.Span span = spans.get(i);
            String ruby = original.substring(span.rubyStart(), span.rubyEnd());
            int removedAfter = span.end() - span.baseEnd();         // 《ルビ》 の文字数
            int removedBefore = span.baseStart() - span.start();    // ｜ の文字数（省略されていれば 0）
            int baseLength = span.baseEnd() - span.baseStart();
            // 先に ｜ を消し、あとで《ルビ》を消す。最後に消した場所にカーソルが来るので、
            // 「やり直し」をしたときも、カーソルが親文字のすぐ後ろに残る
            if (removedBefore > 0) {
                doc.remove(span.start(), removedBefore);
            }
            doc.remove(span.baseEnd() - removedBefore, removedAfter);
            doc.setCharacterAttributes(span.start(), baseLength, rubyAttribute(ruby), true);

            if (caretPosition >= span.end()) {
                caretPosition -= removedAfter + removedBefore;
            } else if (caretPosition > span.start()) {
                caretPosition = span.start() + baseLength;
            }
        }
        return caretPosition;
    }

    /** 選択範囲があればそれを消してから、カーソルの位置に文字を入れる。 */
    private void insert(String s) {
        String padding = takePadding();
        replace(selectionStart(), selectionEnd(), padding + s.replace("\r", ""));
    }

    // ---------- 好きなマスから書く ----------

    /**
     * 点 p が文字のないマスなら、そのマス（x=列、y=行）を返す。文字のある所なら null。
     * 文字のないマスは、改行のある列の行の終わりより下か、文書の終わりより先（左）の列。
     */
    private Point freeCellAt(Point p) {
        ensureLayout();
        if (!composed.isEmpty()) {
            return null;
        }
        int col = -1;
        for (int c = 0; columnLeft(c) + columnWidth() > 0; c++) {
            int left = columnLeft(c);
            if (p.x >= left && p.x < left + columnWidth()) {
                col = c;
                break;
            }
        }
        if (col < 0) {
            return null;
        }
        int row = Math.max(0, Math.min(rowsPerColumn - 1, (p.y - margin()) / cellHeight()));
        return isFreeCell(col, row) ? new Point(col, row) : null;
    }

    /** col 列目・row 行目が、文字のないマスか。 */
    private boolean isFreeCell(int col, int row) {
        int end = text.length();
        if (col > colOf[end]) {
            return true;
        }
        int last = -1;      // その列の、行の終わり（改行の位置か、文書の終わり）
        for (int i = 0; i <= end; i++) {
            if (colOf[i] == col && (i == end || text.charAt(i) == '\n')) {
                last = i;
            }
        }
        return last >= 0 && row > rowOf[last];
    }

    /** 矢印キーで行ける列か。文書の先（左）の列も、画面に描ける所までは行ける。 */
    private boolean canReachColumn(int col) {
        return col >= 0 && (col < displayColumns() || columnLeft(col) >= 0);
    }

    /** col 列目・row 行目のマスへカーソルを動かす。文字のないマスなら、そのマスにカーソルを出す。 */
    private void moveToCell(int col, int row) {
        ensureLayout();
        if (!canReachColumn(col) || row < 0 || row >= rowsPerColumn || !composed.isEmpty()) {
            return;
        }
        for (int i = 0; i < colOf.length; i++) {
            if (colOf[i] == col && rowOf[i] == row) {
                moveCaret(i, false);
                return;
            }
        }
        if (isFreeCell(col, row)) {
            Point cell = new Point(col, row);
            moveCaret(freeCellPosition(cell), false);
            freeCell = cell;
            scrollToCaret();
            view.repaint();
            return;
        }
        moveCaret(indexAt(col, row), false);
    }

    /** 文字のないマスをクリックしたとき、カーソルを置く文書の中の位置（その列の行の終わり）。 */
    private int freeCellPosition(Point cell) {
        int end = text.length();
        if (cell.x > colOf[end]) {
            return end;
        }
        for (int i = 0; i <= end; i++) {
            if (colOf[i] == cell.x && (i == end || text.charAt(i) == '\n')) {
                return i;
            }
        }
        return end;
    }

    /** クリックした空きマスまでを埋める空白と改行。使ったら、空きマスは忘れる。 */
    private String takePadding() {
        Point cell = freeCell;
        freeCell = null;
        if (cell == null || selectionEnd() > selectionStart()) {
            return "";
        }
        ensureLayout();
        int end = text.length();
        if (cell.x > colOf[end]) {
            return "\n".repeat(cell.x - colOf[end]) + "\u3000".repeat(cell.y);
        }
        return "\u3000".repeat(Math.max(0, cell.y - rowOf[caret]));
    }

    /** カーソルを描くマス。空きマスをクリックしていれば、そのマス。 */
    private Rectangle caretCell() {
        if (freeCell != null) {
            return new Rectangle(columnLeft(freeCell.x), margin() + freeCell.y * cellHeight(), cellWidth(), cellHeight());
        }
        return shownCellRect(Math.min(shownCaret(), colOf.length - 1));
    }

    private int selectionStart() {
        return Math.min(caret, anchor);
    }

    private int selectionEnd() {
        return Math.max(caret, anchor);
    }

    /** 変換中の文字を捨てる。カーソルを動かす前に呼ぶ（はさむ位置が変わってしまうため）。 */
    private void dropComposition() {
        if (!composed.isEmpty()) {
            composed = "";
            composedCaret = 0;
            layoutDirty = true;
        }
    }

    /**
     * カーソルが動いたとき。並べ直しが要るのは、変換中の文字があるときだけ（カーソルの位置にはさんで並べるため）。
     * 変換中の文字がなければ、配置はカーソルに関係ないので、長い原稿でも矢印キーのたびに全文を並べ直さない。
     */
    private void caretMoved() {
        if (!composed.isEmpty()) {
            layoutDirty = true;
        }
        view.revalidate();
    }

    private void moveCaret(int position, boolean extendSelection) {
        dropComposition();
        freeCell = null;
        int p = Math.max(0, Math.min(position, text.length()));
        // ひとまとまりの文字（サロゲートペア・異体字セレクタ付きの字・結合文字など）の途中には止まらない
        int start = VerticalLayout.clusterStart(text, p);
        if (start != p) {
            p = (p > caret) ? VerticalLayout.clusterEnd(text, start) : start;
        }
        caret = p;
        if (!extendSelection) {
            anchor = p;
        }
        caretMoved();
        caretVisible = true;
        view.repaint();
        scrollToCaret();
        fireStatusChanged();
    }

    // ---------- 文字の配置 ----------

    /** 文字の大きさ。原稿用紙では、マスの大きさに合わせる。 */
    private int fontSize() {
        return manuscript ? Math.round(sheetCell() * 0.78f) : view.getFont().getSize();
    }

    /**
     * 原稿用紙のマス1つの大きさ。20マスと、その下の余白（何枚目かを書く所など）が、
     * ちょうど画面の高さに収まるように決める（ウィンドウを大きくすると、マスも大きくなる）。
     */
    private int sheetCell() {
        return Math.max(14, (int) ((viewportHeight() - 2 * margin()) / (SHEET_ROWS + 1.6)));
    }

    /** 1文字ぶんの高さ（下へ進む量）。 */
    private int cellHeight() {
        return manuscript ? sheetCell() : Math.round(fontSize() * 1.12f);
    }

    /** 文字を置くマスの幅。 */
    private int cellWidth() {
        return manuscript ? sheetCell() : columnWidth();
    }

    /** 原稿用紙で、マスの右にあるルビ欄の幅。 */
    private int rubyStrip() {
        return Math.round(sheetCell() * 0.5f);
    }

    /** 1列ぶんの幅（左へ進む量）。行間やルビ欄を含む。 */
    private int columnWidth() {
        return manuscript ? sheetCell() + rubyStrip() : Math.round(fontSize() * 1.85f);
    }

    private int margin() {
        return manuscript ? 34 : Math.max(16, view.getFont().getSize() / 2);
    }

    /** 原稿用紙の中央（10行目と11行目のあいだ）に空ける幅。 */
    private int foldGap() {
        return manuscript ? Math.round(sheetCell() * 0.7f) : 0;
    }

    /** 原稿用紙の、1枚と次の1枚のあいだに空ける幅。 */
    private int sheetGap() {
        return manuscript ? Math.round(sheetCell() * 1.4f) : 0;
    }

    /** 画面に並べる列の数。原稿用紙では、最後の1枚も 20 行ぶんのマスを描くので、20 の倍数に切り上げる。 */
    private int displayColumns() {
        if (!manuscript) {
            return totalCols;
        }
        return Math.max(1, (totalCols + SHEET_COLUMNS - 1) / SHEET_COLUMNS) * SHEET_COLUMNS;
    }

    /** col 列目より右にある「すき間」（中央の空きと、紙と紙のあいだ）の合計。 */
    private int gapsBefore(int col) {
        if (!manuscript) {
            return 0;
        }
        return (col / SHEET_COLUMNS) * (sheetGap() + foldGap())
                + (col % SHEET_COLUMNS >= SHEET_COLUMNS / 2 ? foldGap() : 0);
    }

    /** col 列目の左端の位置。列は右から数えるので、右端から引いていく。 */
    private int columnLeft(int col) {
        return view.getWidth() - margin() - (col + 1) * columnWidth() - gapsBefore(col);
    }

    /** 横の位置 x にある列。どの列の上でもなければ、いちばん近い列。 */
    private int columnAt(int x) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int col = 0; col < displayColumns(); col++) {
            int left = columnLeft(col);
            if (x >= left && x < left + columnWidth()) {
                return col;
            }
            int distance = Math.min(Math.abs(x - left), Math.abs(x - (left + columnWidth())));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = col;
            }
        }
        return best;
    }

    /** すべての列を並べるのに必要な幅。 */
    private int neededWidth() {
        int columns = displayColumns();
        return 2 * margin() + columns * columnWidth() + gapsBefore(columns - 1);
    }

    public boolean isManuscript() {
        return manuscript;
    }

    /** 原稿用紙の表示と、ふつうの表示を切り替える。 */
    public void setManuscript(boolean manuscript) {
        closeRubyField(false);
        this.manuscript = manuscript;
        layoutDirty = true;
        view.revalidate();
        view.repaint();
        fireStatusChanged();
        SwingUtilities.invokeLater(this::scrollToCaret);
    }

    private int viewportHeight() {
        int h = scrollPane.getViewport().getExtentSize().height;
        return h > 0 ? h : 600;
    }

    /** 全部の文字について、何列目・何文字目に置くかを計算する。 */
    private void ensureLayout() {
        int height = viewportHeight();
        if (!layoutDirty && height == layoutHeight) {
            return;
        }
        layoutHeight = height;
        layoutDirty = false;

        // 配置は「画面に出す文章」（変換中の文字をはさんだもの）で計算する。
        // 一番下の1文字ぶんは、余白として空けておく。
        rowsPerColumn = manuscript ? SHEET_ROWS
                : Math.max(1, (height - 2 * margin() - cellHeight()) / cellHeight());
        layout = new VerticalLayout(shownText(), rowsPerColumn, shownRuns());
        colOf = layout.col;
        rowOf = layout.row;
        totalCols = layout.totalColumns;
        usedCols = layout.usedColumns();    // 並べ直したときだけ数える（ステータスバーの枚数を PDF とそろえるため）
    }

    /** column 列目（またはそれより後ろの列）にある最初の文字。文字は列の順に並んでいるので、半分ずつ探す。 */
    private int firstIndexInColumn(int column, int length) {
        int low = 0;
        int high = Math.min(length, colOf.length);
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (colOf[middle] < column) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /** 画面に出す文章。変換中の文字があれば、カーソルの位置にはさむ。 */
    private String shownText() {
        if (composed.isEmpty()) {
            return text;
        }
        return text.substring(0, caret) + composed + text.substring(caret);
    }

    /** ルビの位置を、画面に出す文章の中の位置に直す（変換中の文字をはさんだぶん、ずらす）。 */
    private List<VerticalLayout.RubyRun> shownRuns() {
        if (composed.isEmpty()) {
            return runs;
        }
        int shift = composed.length();
        List<VerticalLayout.RubyRun> shifted = new ArrayList<>();
        for (VerticalLayout.RubyRun run : runs) {
            shifted.add(new VerticalLayout.RubyRun(
                    run.baseStart() >= caret ? run.baseStart() + shift : run.baseStart(),
                    run.baseEnd() > caret ? run.baseEnd() + shift : run.baseEnd(), run.ruby()));
        }
        return shifted;
    }

    /** 文書の中の位置を、画面に出す文章の中の位置に直す（変換中の文字のぶん、後ろへずれる）。 */
    private int toShown(int index) {
        return index > caret ? index + composed.length() : index;
    }

    /** 画面に出す文章の中の位置を、文書の中の位置に戻す。 */
    private int fromShown(int shownIndex) {
        if (shownIndex <= caret) {
            return shownIndex;
        }
        return Math.max(caret, shownIndex - composed.length());
    }

    /** 画面に出す文章の i 文字目のマス。列は右から数えるので、右端から引き算する。 */
    private Rectangle shownCellRect(int shownIndex) {
        return new Rectangle(columnLeft(colOf[shownIndex]),
                margin() + rowOf[shownIndex] * cellHeight(), cellWidth(), cellHeight());
    }

    /** 文書の i 文字目のマス（画面上の四角）。 */
    private Rectangle cellRect(int i) {
        return shownCellRect(toShown(i));
    }

    /** カーソルを描く位置（変換中は、変換中の文字の中のカーソル位置）。 */
    private int shownCaret() {
        return caret + Math.min(composedCaret, composed.length());
    }

    /** 変換中の文字を入れ替えて、並べ直す。 */
    private void setComposed(String newComposed, int newCaret) {
        composed = newComposed;
        composedCaret = newCaret;
        layoutDirty = true;
        caretVisible = true;
        view.revalidate();
        view.repaint();
        SwingUtilities.invokeLater(this::scrollToCaret);
    }

    /** 変換の途中なら、そこで確定させる（クリックや、ほかの場所へ移るとき）。 */
    private void finishComposition() {
        if (!composed.isEmpty() && view.getInputContext() != null) {
            view.getInputContext().endComposition();
        }
        if (!composed.isEmpty()) {
            setComposed("", 0);         // 入力方式が確定を返さなかったときは、変換中の文字を捨てる
        }
    }

    /** 指定した列・行にいちばん近い文字の位置を返す。 */
    private int indexAt(int col, int row) {
        ensureLayout();
        int lastInColumn = -1;
        for (int i = 0; i < colOf.length; i++) {
            if (colOf[i] == col) {
                if (rowOf[i] >= row) {
                    return fromShown(i);
                }
                lastInColumn = i;
            }
        }
        return lastInColumn >= 0 ? fromShown(lastInColumn) : text.length();
    }

    private int indexAt(Point p) {
        ensureLayout();
        int col = Math.min(columnAt(p.x), totalCols - 1);
        int row = Math.max(0, (p.y - margin() + cellHeight() / 2) / cellHeight());
        return indexAt(col, row);
    }

    private void scrollToCaret() {
        ensureLayout();
        if (caret > text.length()) {
            return;
        }
        Rectangle r = caretCell();
        r.grow(columnWidth(), 0);               // 左右の列も少し見えるようにする
        view.scrollRectToVisible(r);
    }

    // ---------- EditorPane ----------

    @Override
    public void exportPdf(PdfWriter writer) throws java.io.IOException {
        if (manuscript) {
            PagePrinter.printManuscript(text, runs, view.getFont().getFamily(), writer);
        } else {
            PagePrinter.printVertical(text, runs, view.getFont().getFamily(), writer);
        }
    }

    @Override
    public Mode getMode() {
        return Mode.VERTICAL;
    }

    @Override
    public boolean isEmpty() {
        return text.isEmpty();
    }

    @Override
    public Map<String, Object> toJson() {
        Map<String, Object> json = baseJson();
        json.put("fontName", view.getFont().getFamily());
        json.put("fontSize", Theme.unzoomed(view.getFont().getSize()));   // 等倍のときの大きさで保存する
        json.put("manuscript", manuscript);
        json.put("text", toPlainText());    // ルビは、記法の形で本文に入れて保存する
        return json;
    }

    /** 保存用の文章。ルビを「小説家になろう」の記法（｜親文字《ルビ》）に直して返す。 */
    @Override
    public String toPlainText() {
        return NarouRuby.write(text, runs, 0, text.length());
    }

    @Override
    public String statusText() {
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        int line = lineNumber(lineStart);
        String status = "行 " + line + ", " + (caret - lineStart + 1) + " 文字目";
        if (manuscript && colOf.length > 0) {
            // 原稿用紙の何枚目にいるか（配置の計算が済んでいる範囲で求める）
            int column = colOf[Math.min(toShown(caret), colOf.length - 1)];
            int sheets = Math.max(column / SHEET_COLUMNS + 1, (usedCols + SHEET_COLUMNS - 1) / SHEET_COLUMNS);
            status = "原稿用紙 " + (column / SHEET_COLUMNS + 1) + " / " + sheets + " 枚    " + status;
        }
        return status;
    }

    /** position が何行目か（1 から）。行の始まりの表を作っておき、半分ずつ探す。 */
    private int lineNumber(int position) {
        if (lineStarts == null) {
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
            lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }
        int found = java.util.Arrays.binarySearch(lineStarts, position);
        return (found >= 0 ? found : -found - 2) + 1;
    }

    @Override
    public void focusEditor() {
        view.requestFocusInWindow();
    }

    @Override
    public Font getEditorFont() {
        return view.getFont();
    }

    @Override
    public void setEditorFont(Font font) {
        view.setFont(font);
        layoutDirty = true;
        view.revalidate();
        view.repaint();
        SwingUtilities.invokeLater(this::scrollToCaret);
    }

    @Override
    public void undo() {
        closeRubyField(false);
        if (undoManager.canUndo()) {
            undoManager.undo();
        }
    }

    @Override
    public void redo() {
        closeRubyField(false);
        if (undoManager.canRedo()) {
            undoManager.redo();
        }
    }

    @Override
    public void cut() {
        if (selectionEnd() > selectionStart()) {
            copy();
            insert("");
        }
    }

    @Override
    public void copy() {
        if (selectionEnd() > selectionStart()) {
            // ルビも一緒に写せるように、記法の形でコピーする（貼り付けると、ルビに戻る）
            String selected = NarouRuby.write(text, runs, selectionStart(), selectionEnd());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(selected), null);
        }
    }

    @Override
    public void paste() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (data instanceof String s && !s.isEmpty()) {
                insert(s);
            }
        } catch (Exception ex) {
            // クリップボードに文字が入っていないときは、何もしない
        }
    }

    @Override
    public void selectAll() {
        dropComposition();
        anchor = 0;
        caret = text.length();
        caretMoved();
        view.requestFocusInWindow();
        view.repaint();
        fireStatusChanged();
    }

    // ---------- Searchable ----------

    @Override
    public String getText() {
        return text;
    }

    @Override
    public void setHighlights(List<Highlight> newHighlights) {
        highlights = List.copyOf(newHighlights);
        view.repaint();
    }

    @Override
    public void reveal(int offset) {
        dropComposition();
        caret = anchor = Math.max(0, Math.min(offset, text.length()));
        caretMoved();
        view.repaint();
        scrollToCaret();
        fireStatusChanged();
    }

    @Override
    public void select(int start, int end) {
        reveal(end);
        anchor = Math.max(0, Math.min(start, text.length()));
        view.repaint();
        fireStatusChanged();
    }

    @Override
    public void replaceRange(String replacement, int start, int end) {
        replace(start, end, replacement);
    }

    @Override
    public int getSelectionStart() {
        return selectionStart();
    }

    @Override
    public int getSelectionEnd() {
        return selectionEnd();
    }

    @Override
    public void replaceRanges(List<Replacement> replacements) {
        // 後ろにあるものから直す（前から直すと、文字数が変わって、後ろの位置がずれてしまうため）
        List<Replacement> sorted = new ArrayList<>(replacements);
        sorted.sort(Comparator.comparingInt(Replacement::start).reversed());
        edit(() -> {
            for (Replacement r : sorted) {
                replace(r.start(), r.end(), r.text());
            }
        });
    }

    // ---------- ルビ（ふりがな） ----------

    /** position を含んでいるルビを探す。なければ null。 */
    private VerticalLayout.RubyRun runAt(int position, boolean includeEnd) {
        for (VerticalLayout.RubyRun run : runs) {
            if (run.baseStart() <= position && (position < run.baseEnd() || (includeEnd && position == run.baseEnd()))) {
                return run;
            }
        }
        return null;
    }

    /** start〜end にかかっているルビを、すべて外す。 */
    private void clearRuby(int start, int end) {
        for (VerticalLayout.RubyRun run : new ArrayList<>(runs)) {
            if (run.baseStart() < end && start < run.baseEnd()) {
                doc.setCharacterAttributes(run.baseStart(), run.baseEnd() - run.baseStart(),
                        SimpleAttributeSet.EMPTY, true);
            }
        }
    }

    /** start〜end の文字に、ルビを振る。ruby が空なら、外すだけ。 */
    private void setRuby(int start, int end, String ruby) {
        edit(() -> {
            clearRuby(start, end);
            if (!ruby.isEmpty()) {
                doc.setCharacterAttributes(start, end - start, rubyAttribute(ruby), true);
            }
        });
    }

    /**
     * ルビの入力を始める。文字を選んでいればその文字に、選んでいなければカーソルのあるルビに対して、
     * すぐ横に小さな入力欄を出す。Enter で決定、Esc で取り消し。
     */
    public void beginRubyEdit() {
        finishComposition();
        int start = selectionStart();
        int end = selectionEnd();
        String current = "";
        VerticalLayout.RubyRun existing = runAt(start == end ? caret : start, start == end);
        if (start == end) {
            if (existing == null) {
                fireMessage("ルビを振る文字を選んでから、もう一度どうぞ（Shift+上下の矢印、またはドラッグで選べます）");
                return;
            }
            start = existing.baseStart();
            end = existing.baseEnd();
        }
        if (existing != null && existing.baseStart() == start && existing.baseEnd() == end) {
            current = existing.ruby();
        }
        String base = text.substring(start, end);
        if (base.isBlank() || base.indexOf('\n') >= 0) {
            fireMessage("ルビは、1行の中の文字に振れます");
            return;
        }
        if (base.length() > NarouRuby.MAX_LENGTH) {
            fireMessage("ルビを振れるのは " + NarouRuby.MAX_LENGTH + " 文字までです（小説家になろうの決まり）");
            return;
        }
        if (!NarouRuby.fits(base, "あ")) {
            fireMessage("｜ 《 》 （ ( を含む文字には、ルビを振れません");
            return;
        }
        openRubyField(start, end, current);
    }

    private void openRubyField(int start, int end, String current) {
        closeRubyField(false);
        rubyEditStart = start;
        rubyEditEnd = end;
        JTextField field = new JTextField(current, 9);
        Theme.styleField(field);
        field.setFont(Theme.ui());
        field.setToolTipText("ふりがなを入力して Enter（空にすると外れます。Esc で取り消し）");
        rubyField = field;

        // 親文字のすぐ右に置く。画面からはみ出すときは、左に置く
        ensureLayout();
        Rectangle cell = cellRect(start);
        Dimension size = field.getPreferredSize();
        int x = cell.x + cell.width - 4;
        if (x + size.width > view.getWidth()) {
            x = Math.max(0, cell.x - size.width + 4);
        }
        field.setBounds(x, Math.max(0, cell.y), size.width, size.height);

        field.addActionListener(e -> {      // Enter で決定
            closeRubyField(true);
            view.requestFocusInWindow();
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    closeRubyField(false);
                    view.requestFocusInWindow();
                }
            }
        });
        field.addFocusListener(new FocusListener() {
            public void focusGained(FocusEvent e) {
            }

            public void focusLost(FocusEvent e) {
                if (!e.isTemporary() && rubyField == field) {
                    closeRubyField(true);   // ほかの場所をクリックしたら、そこで決定
                }
            }
        });

        view.add(field);
        view.scrollRectToVisible(field.getBounds().union(cell));
        field.requestFocusInWindow();
        field.selectAll();
        view.repaint();
    }

    /** 入力欄を閉じる。keep が true なら、入力された文字をルビとして付ける。 */
    private void closeRubyField(boolean keep) {
        if (rubyField == null) {
            return;
        }
        JTextField field = rubyField;
        rubyField = null;               // 先に null にする（focusLost から、もう一度呼ばれるため）
        view.remove(field);
        view.repaint();
        if (!keep || rubyEditEnd > text.length()) {
            return;
        }
        String ruby = field.getText().strip();
        if (ruby.length() > NarouRuby.MAX_LENGTH) {
            fireMessage("ふりがなは " + NarouRuby.MAX_LENGTH + " 文字までです（小説家になろうの決まり）。付けませんでした");
            return;
        }
        if (!ruby.isEmpty() && !NarouRuby.fits("字", ruby)) {
            fireMessage("ふりがなに ｜ 《 》 （ ( は使えません。付けませんでした");
            return;
        }
        VerticalLayout.RubyRun existing = runAt(rubyEditStart, false);
        boolean same = existing != null && existing.baseStart() == rubyEditStart
                && existing.baseEnd() == rubyEditEnd && existing.ruby().equals(ruby);
        if (!same && !(ruby.isEmpty() && existing == null)) {
            setRuby(rubyEditStart, rubyEditEnd, ruby);
        }
        caret = anchor = rubyEditEnd;
        caretMoved();
    }

    /** 選んだ文字に、1文字ずつ傍点（・）を付ける。 */
    public void emphasizeSelection() {
        int start = selectionStart();
        int end = selectionEnd();
        if (start == end) {
            fireMessage("傍点を付ける文字を選んでから、もう一度どうぞ");
            return;
        }
        edit(() -> {
            clearRuby(start, end);
            for (int i = start; i < end; ) {
                int codePoint = text.codePointAt(i);
                int length = Character.charCount(codePoint);
                if (!Character.isWhitespace(codePoint)) {
                    doc.setCharacterAttributes(i, length, rubyAttribute("・"), true);
                }
                i += length;
            }
        });
    }

    /** 選んだ範囲（選んでいなければ、カーソルのあるルビ）から、ルビと傍点を外す。 */
    public void removeRubyAtSelection() {
        int start = selectionStart();
        int end = selectionEnd();
        if (start == end) {
            VerticalLayout.RubyRun run = runAt(caret, true);
            if (run == null) {
                fireMessage("外すルビが、カーソルの位置にありません");
                return;
            }
            start = run.baseStart();
            end = run.baseEnd();
        }
        int from = start;
        int to = end;
        edit(() -> clearRuby(from, to));
    }

    // ---------- 画面（描画とキー・マウスの受け取り） ----------

    private final class View extends JComponent implements Scrollable {

        private boolean draggingFromStrip;

        View() {
            setFocusable(true);
            setOpaque(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    finishComposition();
                    // ルビ欄（文字の右どなり）をクリックしたら、その文字にルビを書き込む
                    if (SwingUtilities.isLeftMouseButton(e) && !e.isShiftDown() && inRubyStrip(e.getPoint())) {
                        int index = characterAt(e.getPoint());
                        if (index >= 0) {
                            draggingFromStrip = true;
                            beginRubyFor(index);
                            return;
                        }
                    }
                    draggingFromStrip = false;
                    requestFocusInWindow();
                    Point cell = SwingUtilities.isLeftMouseButton(e) && !e.isShiftDown() ? freeCellAt(e.getPoint()) : null;
                    if (cell != null) {
                        // 文字のないマス：カーソルはそのマスに出し、打ち始めたら、そこまでを空白で埋める
                        moveCaret(freeCellPosition(cell), false);
                        freeCell = cell;
                        repaint();
                        return;
                    }
                    moveCaret(indexAt(e.getPoint()), e.isShiftDown());
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    // ルビの付いた文字をダブルクリックすると、そのルビを書き直せる
                    if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && rubyField == null) {
                        int index = characterAt(e.getPoint());
                        VerticalLayout.RubyRun run = index < 0 ? null : runAt(index, false);
                        if (run != null) {
                            openRubyField(run.baseStart(), run.baseEnd(), run.ruby());
                        }
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (draggingFromStrip) {
                        return;         // ルビ欄から始めたドラッグでは、範囲選択をしない
                    }
                    moveCaret(indexAt(e.getPoint()), true);     // 押したままで動かすと範囲選択
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);

            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    handleKeyPressed(e);
                }

                @Override
                public void keyTyped(KeyEvent e) {
                    handleKeyTyped(e);
                }
            });

            addFocusListener(new FocusListener() {
                public void focusGained(FocusEvent e) {
                    shared.active = VerticalTextPane.this;
                    caretVisible = true;
                    blinkTimer.start();
                    repaint();
                }

                public void focusLost(FocusEvent e) {
                    if (!e.isTemporary()) {
                        finishComposition();
                    }
                    blinkTimer.stop();
                    repaint();
                }
            });

            // 日本語入力（IME）から、変換中の文字と確定した文字を受け取る
            enableInputMethods(true);
            addInputMethodListener(new InputMethodListener() {
                @Override
                public void inputMethodTextChanged(InputMethodEvent e) {
                    handleInputMethodText(e);
                }

                @Override
                public void caretPositionChanged(InputMethodEvent e) {
                    composedCaret = e.getCaret() != null ? e.getCaret().getInsertionIndex() : composed.length();
                    repaint();
                    e.consume();
                }
            });
        }

        /**
         * 日本語入力からの知らせ。届く文字は、前半が「確定した文字」、後半が「変換中の文字」。
         * 確定したぶんは文書に入れ、変換中のぶんは表示用に覚えておく。
         */
        private void handleInputMethodText(InputMethodEvent e) {
            StringBuilder committed = new StringBuilder();
            StringBuilder composing = new StringBuilder();
            AttributedCharacterIterator it = e.getText();
            if (it != null) {
                int count = 0;
                for (char c = it.first(); c != CharacterIterator.DONE; c = it.next()) {
                    (count++ < e.getCommittedCharacterCount() ? committed : composing).append(c);
                }
            }
            composed = "";              // 文書を書き換える前に、いったん空にする（位置の計算がずれないように）
            if (committed.length() > 0) {
                insert(committed.toString());
            } else if (composing.length() > 0 && (selectionEnd() > selectionStart() || freeCell != null)) {
                insert("");             // 範囲を選んだまま打ち始めたら、先にその範囲を消す（空きマスなら、そこまで埋める）
            }
            int caretInComposed = e.getCaret() != null ? e.getCaret().getInsertionIndex() : composing.length();
            setComposed(composing.toString(), Math.max(0, Math.min(caretInComposed, composing.length())));
            e.consume();
        }

        /** 入力方式に「自分で変換中の文字を表示する」と伝える。これがないと、確定するまで文字が出ない。 */
        @Override
        public InputMethodRequests getInputMethodRequests() {
            return inputMethodRequests;
        }

        private final InputMethodRequests inputMethodRequests = new InputMethodRequests() {
            /** 変換候補の窓を出す場所（カーソルのあるマスの、画面上の位置）を答える。 */
            @Override
            public Rectangle getTextLocation(TextHitInfo offset) {
                ensureLayout();
                Rectangle cell = caretCell();
                try {
                    Point origin = getLocationOnScreen();
                    cell.translate(origin.x, origin.y);
                } catch (java.awt.IllegalComponentStateException ex) {
                    // まだ画面に出ていないときは、部品の中の位置のまま返す
                }
                return cell;
            }

            @Override
            public TextHitInfo getLocationOffset(int x, int y) {
                return null;
            }

            @Override
            public int getInsertPositionOffset() {
                return caret;
            }

            @Override
            public AttributedCharacterIterator getCommittedText(int beginIndex, int endIndex,
                    AttributedCharacterIterator.Attribute[] attributes) {
                int begin = Math.max(0, Math.min(beginIndex, text.length()));
                int end = Math.max(begin, Math.min(endIndex, text.length()));
                return new AttributedString(text.substring(begin, end)).getIterator();
            }

            @Override
            public int getCommittedTextLength() {
                return text.length();
            }

            @Override
            public AttributedCharacterIterator cancelLatestCommittedText(AttributedCharacterIterator.Attribute[] attributes) {
                return null;
            }

            @Override
            public AttributedCharacterIterator getSelectedText(AttributedCharacterIterator.Attribute[] attributes) {
                return new AttributedString(text.substring(selectionStart(), selectionEnd())).getIterator();
            }
        };

        /** その点が、ルビ欄（文字の右どなり）の上かどうか。 */
        private boolean inRubyStrip(Point p) {
            ensureLayout();
            int left = columnLeft(columnAt(p.x));
            if (p.x < left || p.x >= left + columnWidth()) {
                return false;           // 列と列のすき間
            }
            return manuscript ? p.x >= left + cellWidth() : p.x >= left + columnWidth() / 2 + fontSize() / 2;
        }

        /** ルビ欄をクリックされたとき：そのとなりの文字に、ルビを入力する。 */
        private void beginRubyFor(int index) {
            VerticalLayout.RubyRun run = runAt(index, false);
            if (selectionEnd() > selectionStart() && index >= selectionStart() && index < selectionEnd()) {
                beginRubyEdit();        // 選んでいる範囲の横なら、その範囲全体に振る
            } else if (run != null) {
                openRubyField(run.baseStart(), run.baseEnd(), run.ruby());
            } else {
                openRubyField(index, index + Character.charCount(text.codePointAt(index)), "");
            }
        }

        /** その点のマスにある文字の位置を返す。文字がなければ -1。 */
        private int characterAt(Point p) {
            ensureLayout();
            int col = columnAt(p.x);
            int row = Math.floorDiv(p.y - margin(), cellHeight());
            for (int i = 0; i < text.length(); i++) {
                if (colOf[toShown(i)] == col && rowOf[toShown(i)] == row && text.charAt(i) != '\n') {
                    return i;
                }
            }
            return -1;
        }

        private void handleKeyPressed(KeyEvent e) {
            if (e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                return;                 // Ctrl+S などは、メニューのショートカットに任せる
            }
            boolean shift = e.isShiftDown();
            ensureLayout();
            switch (e.getKeyCode()) {
                // 画面の見た目どおりに動かす：上下で「文字」を、左右で「行」を移る
                // 範囲選択していないときは、行の終わりより下の、文字のないマスにも行ける
                case KeyEvent.VK_DOWN -> moveDown(shift);               // 下：次の文字へ
                case KeyEvent.VK_UP -> moveUp(shift);                   // 上：前の文字へ
                case KeyEvent.VK_LEFT -> moveColumn(1, shift);          // 左：次の行へ
                case KeyEvent.VK_RIGHT -> moveColumn(-1, shift);        // 右：前の行へ
                case KeyEvent.VK_HOME -> moveCaret(text.lastIndexOf('\n', caret - 1) + 1, shift);
                case KeyEvent.VK_END -> {
                    int end = text.indexOf('\n', caret);
                    moveCaret(end < 0 ? text.length() : end, shift);
                }
                case KeyEvent.VK_ENTER -> insert("\n" + InputAssist.paragraphIndent());   // 入力補助：段落頭の全角スペース（設定が入っているときだけ）
                case KeyEvent.VK_BACK_SPACE -> {
                    if (freeCell != null) {
                        freeCell = null;        // 空きマスにいるときは、何も消さずに、元の行の終わりへ戻るだけ
                        repaint();
                    } else if (selectionEnd() > selectionStart()) {
                        insert("");
                    } else if (caret > 0) {
                        replace(caret - charLengthBefore(caret), caret, "");
                    }
                }
                case KeyEvent.VK_DELETE -> {
                    if (selectionEnd() > selectionStart()) {
                        insert("");
                    } else if (caret < text.length()) {
                        replace(caret, caret + charLengthAt(caret), "");
                    }
                }
                default -> {
                    return;             // それ以外のキーは、ここでは扱わない
                }
            }
            e.consume();
        }

        private void handleKeyTyped(KeyEvent e) {
            char c = e.getKeyChar();
            // Enter・BackSpace などの制御文字は keyPressed で処理済みなので、ここでは文字だけを入れる。
            // 日本語入力（IME）で確定した文字も、ここに1文字ずつ届く。
            if (c == KeyEvent.CHAR_UNDEFINED || c < 0x20 || c == 0x7F
                    || e.isControlDown() || e.isMetaDown() || (e.isAltDown() && !e.isAltGraphDown())) {
                return;
            }
            // 入力補助（かっこの自動閉じ）。ルビの入力欄の中と、変換中は、規則を当てない。範囲を選んでいるときは、ふつうに置き換える
            if (InputAssist.pairs && rubyField == null && composed.isEmpty() && selectionEnd() == selectionStart()) {
                if (InputAssist.isClosing(c) && freeCell == null && caret < text.length() && text.charAt(caret) == c) {
                    moveCaret(caret + 1, false);        // 同じ閉じかっこが右にあるときは、入れずに右へ進むだけ
                    e.consume();
                    return;
                }
                String closing = InputAssist.closingFor(c);
                if (closing != null) {
                    insert(c + closing);                // 空きマスからでも、そこまで埋めてから入れる（1回の取り消しにまとまる）
                    moveCaret(caret - 1, false);        // 2字のあいだに、カーソルを置く
                    e.consume();
                    return;
                }
            }
            insert(String.valueOf(c));
            e.consume();
        }

        /** 下へ。行の終わりからは、その下の文字のないマスへ。列の一番下からは、次の列の頭へ。 */
        private void moveDown(boolean shift) {
            boolean lineEnd = caret == text.length() || text.charAt(caret) == '\n';
            if (shift || (freeCell == null && !lineEnd)) {
                moveCaret(caret + 1, shift);
                return;
            }
            Point here = freeCell != null ? freeCell : new Point(colOf[toShown(caret)], rowOf[toShown(caret)]);
            if (here.y + 1 < rowsPerColumn) {
                moveToCell(here.x, here.y + 1);
            } else {
                moveToCell(here.x + 1, 0);
            }
        }

        /** 上へ。文字のないマスからは、1マスずつ上へ（行の終わりまで来たら、文字の所に戻る）。 */
        private void moveUp(boolean shift) {
            if (shift || freeCell == null) {
                moveCaret(caret - 1, shift);
                return;
            }
            if (freeCell.y > 0) {
                moveToCell(freeCell.x, freeCell.y - 1);
            } else {
                moveToCell(freeCell.x - 1, rowsPerColumn - 1);
            }
        }

        /** 左右の列へ移る。同じ高さのマスへ（そこに文字がなければ、文字のないマスとして）動かす。 */
        private void moveColumn(int delta, boolean shift) {
            int here = toShown(caret);
            int row = freeCell != null ? freeCell.y : rowOf[here];
            int target = (freeCell != null ? freeCell.x : colOf[here]) + delta;
            if (shift) {
                if (target >= 0 && target < totalCols) {
                    moveCaret(indexAt(target, row), true);
                }
                return;
            }
            moveToCell(target, row);
        }

        /** position の直前の「ひとまとまりの文字」の長さ（BackSpace で、異体字セレクタ付きの字などを丸ごと消すため）。 */
        private int charLengthBefore(int position) {
            return Math.max(1, position - VerticalLayout.clusterStart(text, position - 1));
        }

        /** position から始まる「ひとまとまりの文字」の長さ（Delete で使う）。 */
        private int charLengthAt(int position) {
            return Math.max(1, VerticalLayout.clusterEnd(text, position) - position);
        }

        @Override
        public Dimension getPreferredSize() {
            ensureLayout();
            int needed = neededWidth();
            int viewportWidth = scrollPane.getViewport().getExtentSize().width;
            return new Dimension(Math.max(needed, viewportWidth), viewportHeight());
        }

        @Override
        protected void paintComponent(Graphics g) {
            ensureLayout();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle clip = g2.getClipBounds();
            g2.setColor(Theme.BG);
            g2.fillRect(clip.x, clip.y, clip.width, clip.height);

            int columnWidth = columnWidth();
            int caretColumn = freeCell != null ? freeCell.x : colOf[Math.min(shownCaret(), colOf.length - 1)];
            if (manuscript) {
                paintSheets(g2, clip, caretColumn);
            } else {
                // 1列おきの縞と、カーソルのある列の強調
                for (int col = 0; col < totalCols; col++) {
                    int x = columnLeft(col);
                    if (x > clip.x + clip.width || x + columnWidth < clip.x) {
                        continue;
                    }
                    if (col == caretColumn) {
                        g2.setColor(Theme.CURRENT_LINE);
                        g2.fillRect(x, 0, columnWidth, getHeight());
                    } else if (Theme.stripes && col % 2 == 1) {
                        g2.setColor(Theme.BG_STRIPE);
                        g2.fillRect(x, 0, columnWidth, getHeight());
                    }
                }
            }

            // 検索の色と、範囲選択の色
            for (Highlight h : highlights) {
                fillRange(g2, h.start(), h.end(), h.color());
            }
            fillRange(g2, selectionStart(), selectionEnd(), Theme.SELECTION);
            if (rubyField != null) {
                fillRange(g2, rubyEditStart, rubyEditEnd, Theme.MENU_SELECTION);    // ルビを入力している親文字
            }

            // 文字
            int size = fontSize();
            Font font = getFont().deriveFont((float) size);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            // 見えている列だけを描く（カーソルの点滅のたびに、長い原稿の全文をたどらないように）。
            // 列は右から数え、文字は列の順に並んでいるので、見えている最初の列の文字から始めて、最後の列を過ぎたらやめる
            int firstVisible = totalCols;
            int lastVisible = -1;
            for (int col = 0; col < totalCols; col++) {
                int x = columnLeft(col);
                if (x <= clip.x + clip.width && x + columnWidth >= clip.x) {
                    firstVisible = Math.min(firstVisible, col);
                    lastVisible = Math.max(lastVisible, col);
                }
            }
            int fromColumn = Math.max(0, firstVisible - 1);     // 前後1列は余分に（はみ出す句読点・ルビのため）
            int toColumn = lastVisible + 1;
            int length = shownText().length();
            for (int i = firstIndexInColumn(fromColumn, length); i < length && colOf[i] <= toColumn; i++) {
                if (!layout.isDrawn(i)) {
                    continue;
                }
                Rectangle cell = shownCellRect(i);
                if (!cell.intersects(clip)) {
                    continue;           // 見えていない文字は描かない
                }
                // 変換中の文字は白くする
                boolean composing = i >= caret && i < caret + composed.length();
                g2.setColor(composing ? Theme.FG_STRONG : Theme.FG);
                layout.drawCharacter(g2, fm, i, cell.x + cell.width / 2.0, cell.y + cell.height / 2.0, size);
                if (composing) {
                    // 変換中の文字の右側に線を引く（縦書きの下線にあたる）
                    g2.setColor(Theme.MATCH_CURRENT);
                    g2.fillRect(cell.x + cell.width - 8, cell.y + 2, 3, cell.height - 4);
                }
            }

            // ルビ（親文字の右に、小さく描く）
            g2.setColor(Theme.FG);
            // 原稿用紙ではルビ欄の中央に、ふつうの表示では文字のすぐ右に置く
            double rubyOffset = manuscript ? cellWidth() / 2.0 + rubyStrip() / 2.0 : size * 0.77;
            layout.drawRubies(g2, font, new VerticalLayout.Place() {
                public double centerX(int column) {
                    return columnLeft(column) + cellWidth() / 2.0;
                }

                public double centerY(int row) {
                    return margin() + (row + 0.5) * cellHeight();
                }
            }, cellHeight(), size, rubyOffset, fromColumn, toColumn);

            // カーソル（文字の上に引く横線）
            if (isFocusOwner() && caretVisible) {
                Rectangle cell = caretCell();
                g2.setColor(Theme.FG_STRONG);
                g2.fillRect(cell.x + 6, cell.y - 1, cell.width - 12, 3);
            }
            g2.dispose();
        }

        /** 原稿用紙を描く。20行ごとに1枚の紙にして、中央を少し空け、マス目とルビ欄を引く。 */
        private void paintSheets(Graphics2D g2, Rectangle clip, int caretColumn) {
            int cell = sheetCell();
            int strip = rubyStrip();
            int top = margin();
            int gridHeight = SHEET_ROWS * cell;
            int sheets = displayColumns() / SHEET_COLUMNS;
            for (int sheet = 0; sheet < sheets; sheet++) {
                int firstColumn = sheet * SHEET_COLUMNS;
                int lastColumn = firstColumn + SHEET_COLUMNS - 1;
                int paperLeft = columnLeft(lastColumn) - cell / 2;
                int paperRight = columnLeft(firstColumn) + columnWidth() + cell / 2;
                if (paperLeft > clip.x + clip.width || paperRight < clip.x) {
                    continue;
                }
                // 紙
                g2.setColor(Theme.PAPER);
                g2.fillRect(paperLeft, top - cell / 2, paperRight - paperLeft, gridHeight + cell * 2);
                for (int col = firstColumn; col <= lastColumn; col++) {
                    int x = columnLeft(col);
                    if (col == caretColumn) {
                        g2.setColor(Theme.PAPER_CURRENT);
                        g2.fillRect(x, top, columnWidth(), gridHeight);
                    }
                    g2.setColor(Theme.GRID);
                    for (int row = 0; row < SHEET_ROWS; row++) {
                        g2.drawRect(x, top + row * cell, cell, cell);       // 文字を書くマス
                    }
                    g2.drawRect(x + cell, top, strip, gridHeight);          // ルビ欄
                }
                // 紙の下に、何枚目かを小さく書く
                g2.setFont(Theme.small().deriveFont(Math.max(11f, cell * 0.4f)));
                g2.setColor(Theme.GRID);
                String label = (sheet + 1) + " 枚目";
                int labelWidth = g2.getFontMetrics().stringWidth(label);
                g2.drawString(label, (paperLeft + paperRight - labelWidth) / 2, top + gridHeight + cell + cell / 3);
            }
        }

        private void fillRange(Graphics2D g2, int start, int end, Color color) {
            g2.setColor(color);
            for (int i = Math.max(0, start); i < Math.min(end, text.length()); i++) {
                if (text.charAt(i) != '\n') {
                    Rectangle cell = cellRect(i);
                    g2.fillRect(cell.x + 4, cell.y, cell.width - 8, cell.height);
                }
            }
        }

        // --- Scrollable：スクロール枠に「高さは枠に合わせ、横だけスクロールする」と伝える ---

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return columnWidth();
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(columnWidth(), visibleRect.width - columnWidth());
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return false;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return true;
        }
    }
}
