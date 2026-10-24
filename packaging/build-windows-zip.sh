#!/bin/sh
# Windows 用の配布 ZIP を作る（Linux の上で作れる）。
# Windows 向けの Java 実行環境（Eclipse Temurin の JRE）を同梱するので、
# 使う人の PC に Java がなくても、展開して DATUM-Editor.bat をダブルクリックするだけで動く。
# 必要なもの: curl、unzip、zip、sha256sum。先に「mvn package」をしておくこと。
set -e
cd "$(dirname "$0")/.."
. packaging/edition.sh      # 版（引数）と VERSION を決める
JRE_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip"
JRE_SHA256="d35f31e712f0fcf6ac5a093edc90204fbff22f720ba3950bd09d331d5e621636"

CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/datum-editor"
mkdir -p "$CACHE" dist
JRE_ZIP="$CACHE/temurin-jre-windows-x64.zip"
if ! echo "$JRE_SHA256  $JRE_ZIP" | sha256sum -c --status 2>/dev/null; then
  echo "Windows 用の Java 実行環境をダウンロードします…"
  curl -L --fail -o "$JRE_ZIP" "$JRE_URL"
  # 途中で壊れたり、すり替えられたりしていないかを、公開されている値と照らして確かめる
  echo "$JRE_SHA256  $JRE_ZIP" | sha256sum -c
fi

WORK=$(mktemp -d)
APP="$WORK/DATUM-Editor"
mkdir -p "$APP"
unzip -q "$JRE_ZIP" -d "$WORK/jre"
mv "$WORK"/jre/*/ "$APP/runtime"
edition_jar "$APP"
cp LICENSE README.md packaging/icon.ico "$APP/"
cp packaging/windows/DATUM-Editor.bat packaging/windows/Create-Desktop-Shortcut.bat packaging/windows/START-HERE.txt "$APP/"
OUT="$(pwd)/dist/DATUM-Editor-$VERSION-windows.zip"
rm -f "$OUT"
(cd "$WORK" && zip -q -r "$OUT" DATUM-Editor)
rm -rf "$WORK"
ls -l "$OUT"
