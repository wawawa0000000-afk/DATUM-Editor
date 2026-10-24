#!/bin/sh
# Linux 用の「展開するだけ」の配布物（.tar.gz）を作る。Java を同梱する。
# .deb が使えない Linux（Fedora、Arch など）でも使える。
# 必要なもの: JDK 17 以上（jpackage）。先に「mvn package」をしておくこと。
set -e
cd "$(dirname "$0")/.."
. packaging/edition.sh      # 版（引数）と VERSION を決める
WORK=$(mktemp -d)
mkdir -p "$WORK/in" dist
edition_jar "$WORK/in"
jpackage --type app-image \
  --name DATUM-Editor --app-version "$VERSION" --vendor wazaseisakujo \
  --input "$WORK/in" --main-jar minieditor.jar --main-class com.example.minieditor.Main \
  --add-modules java.desktop,jdk.httpserver,java.net.http \
  --icon src/main/resources/icon.png \
  --dest "$WORK"
cp LICENSE README.md packaging/linux/Add-To-Menu.sh packaging/linux/START-HERE.txt "$WORK/DATUM-Editor/"
chmod +x "$WORK/DATUM-Editor/Add-To-Menu.sh"
OUT="$(pwd)/dist/DATUM-Editor-$VERSION-linux-x64.tar.gz"
# tar.gz にするのは、「実行できる」という印（パーミッション）を保ったまま配るため
tar -C "$WORK" -czf "$OUT" DATUM-Editor
rm -rf "$WORK"
ls -l "$OUT"
