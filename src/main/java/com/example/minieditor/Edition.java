package com.example.minieditor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 配る種類。基礎の部分はどの種類にも同じものが入り、主な機能を順に足していく。
 * 種類ごとに、名前と番号を1つずつ決めてある（主な機能が1つ増えるごとに、番号が1つ上がる）。
 * 基礎の部分を直したり足したりしても、名前と番号は変えない（全部の種類を作り直すだけ）。
 *
 *   Lite   1.1.0 … 基礎（横書き・タブ・分割・検索・配色・倍率・フォルダ・版の履歴・PDF など）
 *   Writer 1.1.1 … ＋縦書き（原稿用紙・ルビ・傍点）、校正、目標字数・執筆の記録
 *   Studio 1.1.2 … ＋カードボード（KJ法風のアイデア整理）
 *   AI     1.1.3 … ＋AI の会話欄、AI 連携（MCP）、ターミナル
 *
 * どの種類かは、jar の中の edition.properties で決める（配布用のスクリプトが書き込む）。
 * ないとき（開発中に mvn package しただけのとき）は、全部入りの AI として動く。
 * 定数の名前は「その種類で足した機能」（has(Edition.VERTICAL) ＝ 縦書きが入っているか）。
 */
enum Edition {
    HORIZONTAL("Lite", "1.1.0", "横書き"),
    VERTICAL("Writer", "1.1.1", "縦書き"),
    BOARD("Studio", "1.1.2", "カードボード"),
    AI("AI", "1.1.3", "AI");

    final String id;            // 種類の名前（ファイル名にも使う）
    final String version;       // 種類の番号（基礎を直しても変えない）
    final String label;         // その種類で足した主な機能

    Edition(String id, String version, String label) {
        this.id = id;
        this.version = version;
        this.label = label;
    }

    /** 今動いている種類。 */
    static final Edition CURRENT = load();

    private static Edition load() {
        String name = System.getProperty("datum.edition");      // テストで種類を切り替えるため
        if (name == null) {
            try (InputStream in = Edition.class.getResourceAsStream("/edition.properties")) {
                if (in != null) {
                    Properties properties = new Properties();
                    properties.load(in);
                    name = properties.getProperty("edition");
                }
            } catch (IOException ex) {
                // 読めなければ、全部入りとして動く
            }
        }
        if (name != null) {
            for (Edition edition : values()) {
                if (edition.id.equalsIgnoreCase(name.strip()) || edition.name().equalsIgnoreCase(name.strip())) {
                    return edition;
                }
            }
        }
        return AI;
    }

    /** 今の種類に、feature の種類で足した機能が入っているか。 */
    static boolean has(Edition feature) {
        return CURRENT.ordinal() >= feature.ordinal();
    }

    /** その種類の文書を、今の種類で作れる・開けるか。 */
    static boolean allows(EditorPane.Mode mode) {
        return switch (mode) {
            case TEXT -> true;
            case VERTICAL -> has(VERTICAL);
            case BOARD -> has(BOARD);
        };
    }

    /** 入っていない機能を使おうとしたときの案内。 */
    static String notice(Edition feature) {
        return feature.label + "は、DATUM-Editor " + feature.id + "（" + feature.version + "）" + (feature == AI ? "" : "以上") + "で使えます";
    }

    /** 画面に出す名前（例：Writer 1.1.1）。 */
    static String title() {
        return CURRENT.id + " " + CURRENT.version;
    }
}
