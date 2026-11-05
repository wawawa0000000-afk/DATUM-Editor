#!/bin/sh
# 配布用スクリプトの共通部分：どの種類（Edition）を作るかを決める。
# 種類は、スクリプトの1つ目の引数で選ぶ（省略すると全部入りの AI）。
# 種類ごとに名前と番号が決まっていて、基礎を直したり足したりしても変えない（全部の種類を作り直すだけ）。
#   Lite   1.1.0 … 基礎 ＋ 横書き
#   Writer 1.1.1 … ＋縦書き
#   Studio 1.1.2 … ＋縦書き・カードボード
#   AI     1.1.3 … ＋縦書き・カードボード・AI（全部入り）
EDITION=${1:-AI}
case "$EDITION" in
  Lite)   VERSION=1.1.0 ;;
  Writer) VERSION=1.1.1 ;;
  Studio) VERSION=1.1.2 ;;
  AI)     VERSION=1.1.3 ;;
  *) echo "種類は Lite / Writer / Studio / AI のどれかを指定してください（指定: $EDITION）"; exit 1 ;;
esac
PRODUCT="DATUM-Editor-$EDITION"     # 配布物のファイル名の頭（例：DATUM-Editor-Writer-1.1.1-windows.zip）
[ -f target/minieditor.jar ] || { echo "target/minieditor.jar がありません。先に mvn package を実行してください。"; exit 1; }

# 版を書き込んだ jar を、指定したフォルダに置く（jar の中の edition.properties で、出す機能が決まる）
edition_jar() {
  cp target/minieditor.jar "$1/minieditor.jar"
  EDITION_TMP=$(mktemp -d)
  printf 'edition=%s\n' "$EDITION" > "$EDITION_TMP/edition.properties"
  (cd "$EDITION_TMP" && zip -q "$1/minieditor.jar" edition.properties)
  rm -rf "$EDITION_TMP"
}

# jpackage で、フォルダのままの版（$1/DATUM-Editor/）を作る。同梱の Java の共有データ（基本の分）も入れて、起動を速くする。
# 先に edition_jar で jar を「$1/in」に置いておくこと。
make_app_image() {
  jpackage --type app-image --name DATUM-Editor --app-version "$VERSION" --vendor wazaseisakujo \
    --input "$1/in" --main-jar minieditor.jar --main-class com.example.minieditor.Main \
    --add-modules java.desktop,jdk.httpserver,java.net.http \
    --icon src/main/resources/icon.png \
    --jlink-options '--generate-cds-archive' \
    --java-options '-Xlog:disable' \
    --dest "$1"
  # 同梱の Java の共有データは、ふつうの設定の分（classes.jsa）だけ残す（配布を小さくするため。外した分の設定では、普通に起動する）
  rm -f "$1/DATUM-Editor/lib/runtime/lib/server/classes_nocoh.jsa" "$1/DATUM-Editor/lib/runtime/lib/server/classes_nocoops.jsa" \
    "$1/DATUM-Editor/lib/runtime/lib/server/classes_nocoops_nocoh.jsa"
}

# 起動を速くする AppCDS のアーカイブ（minieditor.jsa）を、その版の Java と jar から作り、起動の指定に加える（$1 は make_app_image の場所）。
# アーカイブは、配る中身と同じ Java・同じ jar から作る（別の Java や古い jar のものは使われない）。
# 作れなかったときも配布は止めない。JVM は使えないアーカイブを黙って捨てて、普通に起動する（-Xlog:disable で表示も消す）。
add_app_cds() {
  CFG="$1/DATUM-Editor/lib/app/DATUM-Editor.cfg"
  # 練習の起動で、アーカイブを作る（利用者の設定フォルダには入らないように、一時フォルダを使う）
  CDS_TMP=$(mktemp -d)
  cp "$CFG" "$CDS_TMP/cfg.orig"
  printf 'java-options=-XX:ArchiveClassesAtExit=$APPDIR/minieditor.jsa\n' >> "$CFG"
  HOME="$CDS_TMP" DATUM_EDITOR_CONFIG_DIR="$CDS_TMP/config" timeout -s TERM 5 "$1/DATUM-Editor/bin/DATUM-Editor" >/dev/null 2>&1 || true
  mv "$CDS_TMP/cfg.orig" "$CFG"
  rm -rf "$CDS_TMP"
  if [ -s "$1/DATUM-Editor/lib/app/minieditor.jsa" ]; then
    printf 'java-options=-XX:SharedArchiveFile=$APPDIR/minieditor.jsa\n' >> "$CFG"
  else
    echo "注意: AppCDS のアーカイブが作れませんでした（配布は作ります。起動は普通どおりになります）。"
  fi
}
