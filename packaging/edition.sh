#!/bin/sh
# 配布用スクリプトの共通部分：どの種類（Edition）を作るかを決める。
# 種類は、スクリプトの1つ目の引数で選ぶ（省略すると全部入りの AI）。番号（VERSION）は全部の種類で同じ。
#   Lite   … 基礎 ＋ 横書き
#   Writer … ＋縦書き
#   Studio … ＋縦書き・カードボード
#   AI     … ＋縦書き・カードボード・AI（全部入り）
# 基礎の部分はどの種類も同じプログラムなので、直したら全部の種類を作り直せば、全部に入る。
VERSION=1.1.3
EDITION=${1:-AI}
case "$EDITION" in
  Lite|Writer|Studio|AI) ;;
  *) echo "種類は Lite / Writer / Studio / AI のどれかを指定してください（指定: $EDITION）"; exit 1 ;;
esac
PRODUCT="DATUM-Editor-$EDITION"     # 配布物のファイル名の頭（例：DATUM-Editor-Writer-1.1.3-windows.zip）
[ -f target/minieditor.jar ] || { echo "target/minieditor.jar がありません。先に mvn package を実行してください。"; exit 1; }

# 版を書き込んだ jar を、指定したフォルダに置く（jar の中の edition.properties で、出す機能が決まる）
edition_jar() {
  cp target/minieditor.jar "$1/minieditor.jar"
  EDITION_TMP=$(mktemp -d)
  printf 'edition=%s\n' "$EDITION" > "$EDITION_TMP/edition.properties"
  (cd "$EDITION_TMP" && zip -q "$1/minieditor.jar" edition.properties)
  rm -rf "$EDITION_TMP"
}
