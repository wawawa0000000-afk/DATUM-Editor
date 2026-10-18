#!/bin/sh
# Linux 用のインストーラー（.deb）を作る。
# Java を同梱するので、入れる人の PC に Java がなくても動く。メニューにも登録される。
# 必要なもの: JDK 17 以上（jpackage）、dpkg-deb、fakeroot。先に「mvn package」をしておくこと。
set -e
cd "$(dirname "$0")/.."
VERSION=1.1.2
[ -f target/minieditor.jar ] || { echo "target/minieditor.jar がありません。先に mvn package を実行してください。"; exit 1; }
WORK=$(mktemp -d)
mkdir -p "$WORK/in" dist
cp target/minieditor.jar "$WORK/in/"
jpackage --type deb \
  --name DATUM-Editor --linux-package-name datum-editor \
  --app-version "$VERSION" --vendor wazaseisakujo \
  --description "Text editor with manuscript paper, vertical writing and ruby" \
  --input "$WORK/in" --main-jar minieditor.jar --main-class com.example.minieditor.Main \
  --add-modules java.desktop,jdk.httpserver,java.net.http \
  --icon src/main/resources/icon.png \
  --linux-shortcut --linux-menu-group "Utility;TextEditor;" --linux-app-category text \
  --license-file LICENSE \
  --dest dist
rm -rf "$WORK"
# jpackage が付ける名前（datum-editor_版_機種.deb）を、ほかの配布物とそろえた名前に変える
for f in dist/datum-editor_"$VERSION"_*.deb; do
  mv "$f" "dist/DATUM-Editor-$VERSION-linux-${f##*_}"
done
ls -l dist/DATUM-Editor-"$VERSION"-linux-*.deb
