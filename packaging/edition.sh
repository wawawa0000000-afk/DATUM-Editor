#!/bin/sh
# 配布用スクリプトの共通部分：どの版（Edition）を作るかを決める。
# 版は、スクリプトの1つ目の引数で選ぶ（省略すると全部入りの AI 版）。
#   HORIZONTAL … 1.1.0 横書き（基礎）
#   VERTICAL   … 1.1.1 ＋縦書き
#   BOARD      … 1.1.2 ＋縦書き・カードボード
#   AI         … 1.1.3 ＋縦書き・カードボード・AI（全部入り）
# 基礎の部分はどの版も同じプログラムなので、直したら全部の版を作り直せば、全部に入る。
EDITION=${1:-AI}
case "$EDITION" in
  HORIZONTAL) VERSION=1.1.0 ;;
  VERTICAL)   VERSION=1.1.1 ;;
  BOARD)      VERSION=1.1.2 ;;
  AI)         VERSION=1.1.3 ;;
  *) echo "版は HORIZONTAL / VERTICAL / BOARD / AI のどれかを指定してください（指定: $EDITION）"; exit 1 ;;
esac
[ -f target/minieditor.jar ] || { echo "target/minieditor.jar がありません。先に mvn package を実行してください。"; exit 1; }

# 版を書き込んだ jar を、指定したフォルダに置く（jar の中の edition.properties で、出す機能が決まる）
edition_jar() {
  cp target/minieditor.jar "$1/minieditor.jar"
  EDITION_TMP=$(mktemp -d)
  printf 'edition=%s\n' "$EDITION" > "$EDITION_TMP/edition.properties"
  (cd "$EDITION_TMP" && zip -q "$1/minieditor.jar" edition.properties)
  rm -rf "$EDITION_TMP"
}
