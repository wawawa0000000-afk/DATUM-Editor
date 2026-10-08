#!/bin/sh
# アプリのメニューとデスクトップに「DATUM-Editor」のアイコンを登録する。
# このフォルダを別の場所に移したときは、もう一度実行すること。
APP_DIR=$(cd "$(dirname "$0")" && pwd)
ENTRY="[Desktop Entry]
Version=1.0
Type=Application
Name=DATUM-Editor
Comment=原稿用紙・縦書き・ルビ対応のテキストエディタ
Exec=\"$APP_DIR/bin/DATUM-Editor\"
Icon=$APP_DIR/lib/DATUM-Editor.png
Terminal=false
Categories=Utility;TextEditor;
"
MENU_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$MENU_DIR"
printf '%s' "$ENTRY" > "$MENU_DIR/datum-editor.desktop"
chmod +x "$MENU_DIR/datum-editor.desktop"
echo "メニューに登録しました: $MENU_DIR/datum-editor.desktop"

DESKTOP_DIR=$(xdg-user-dir DESKTOP 2>/dev/null || echo "$HOME/Desktop")
if [ -d "$DESKTOP_DIR" ]; then
  printf '%s' "$ENTRY" > "$DESKTOP_DIR/DATUM-Editor.desktop"
  chmod +x "$DESKTOP_DIR/DATUM-Editor.desktop"
  # デスクトップ環境によっては「信頼する」という印が必要なので、付けられる場合は付ける
  gio set "$DESKTOP_DIR/DATUM-Editor.desktop" metadata::trusted true 2>/dev/null || true
  echo "デスクトップにアイコンを作りました: $DESKTOP_DIR/DATUM-Editor.desktop"
fi
echo "消すときは、上の2つのファイルと、このフォルダを削除してください。"
