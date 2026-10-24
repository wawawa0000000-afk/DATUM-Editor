#!/bin/sh
# どの OS でも使える、小さい配布 ZIP を作る（Java は同梱しない。使う人が JDK 17 以上を入れておく）。
# 必要なもの: zip。先に「mvn package」をしておくこと。
set -e
cd "$(dirname "$0")/.."
VERSION=1.1.3
[ -f target/minieditor.jar ] || { echo "target/minieditor.jar がありません。先に mvn package を実行してください。"; exit 1; }
WORK=$(mktemp -d)
mkdir -p "$WORK/DATUM-Editor" dist
cp target/minieditor.jar DATUM-Editor.bat DATUM-Editor.sh START-HERE.txt LICENSE README.md "$WORK/DATUM-Editor/"
OUT="$(pwd)/dist/DATUM-Editor-$VERSION.zip"
rm -f "$OUT"
(cd "$WORK" && zip -q -r "$OUT" DATUM-Editor)
rm -rf "$WORK"
ls -l "$OUT"
