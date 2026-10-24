package com.example.minieditor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 配る種類（版）。基礎の部分はどの版にも同じものが入り、主な機能を順に足していく。
 *
 *   1.1.0 横書き       … 基礎（横書き・タブ・分割・検索・配色・倍率・フォルダ・版の履歴・PDF など）
 *   1.1.1 縦書き       … ＋縦書き（原稿用紙・ルビ・傍点）、校正、目標字数・執筆の記録
 *   1.1.2 カードボード … ＋カードボード（KJ法風のアイデア整理）
 *   1.1.3 AI           … ＋AI の会話欄、AI 連携（MCP）、ターミナル
 *
 * どの版かは、jar の中の edition.properties で決める（配布用のスクリプトが書き込む）。
 * ないとき（開発中に mvn package しただけのとき）は、全部入りの AI 版として動く。
 */
enum Edition {
    HORIZONTAL("1.1.0", "横書き"),
    VERTICAL("1.1.1", "縦書き"),
    BOARD("1.1.2", "カードボード"),
    AI("1.1.3", "AI");

    final String version;
    final String label;

    Edition(String version, String label) {
        this.version = version;
        this.label = label;
    }

    /** 今動いている版。 */
    static final Edition CURRENT = load();

    private static Edition load() {
        String name = System.getProperty("datum.edition");      // テストで版を切り替えるため
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
        try {
            return name == null ? AI : valueOf(name.strip().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return AI;
        }
    }

    /** 今の版に、feature の版で足した機能が入っているか。 */
    static boolean has(Edition feature) {
        return CURRENT.ordinal() >= feature.ordinal();
    }

    /** その種類の文書を、今の版で作れる・開けるか。 */
    static boolean allows(EditorPane.Mode mode) {
        return switch (mode) {
            case TEXT -> true;
            case VERTICAL -> has(VERTICAL);
            case BOARD -> has(BOARD);
        };
    }

    /** 入っていない機能を使おうとしたときの案内。 */
    static String notice(Edition feature) {
        return feature.label + "は、DATUM-Editor " + feature.version + " 以上で使えます";
    }

    /** 画面に出す名前（例：1.1.1（縦書き））。 */
    static String title() {
        return CURRENT.version + "（" + CURRENT.label + "）";
    }
}
