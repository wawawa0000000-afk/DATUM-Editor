#!/bin/sh
# Linux 用のインストーラー（.deb）を作る。
# Java を同梱するので、入れる人の PC に Java がなくても動く。メニューにも登録される。
# 必要なもの: JDK 17 以上（jpackage）、dpkg-deb、fakeroot。先に「mvn package」をしておくこと。
set -e
cd "$(dirname "$0")/.."
. packaging/edition.sh      # 種類（引数）・VERSION・PRODUCT を決める
WORK=$(mktemp -d)
mkdir -p "$WORK/in" dist
edition_jar "$WORK/in"
# フォルダのままの版を作り（同梱の Java の共有データを入れる）、それを .deb にする。AppCDS のアーカイブは、.deb の作る過程で時刻が変わって使えなくなるので入れない
make_app_image "$WORK"
jpackage --type deb --app-image "$WORK/DATUM-Editor" \
  --name DATUM-Editor --linux-package-name datum-editor \
  --app-version "$VERSION" --vendor wazaseisakujo \
  --description "Text editor with manuscript paper, vertical writing and ruby" \
  --linux-shortcut --linux-menu-group "Utility;TextEditor;" --linux-app-category text \
  --license-file LICENSE \
  --dest dist
rm -rf "$WORK"
# jpackage が付ける名前（datum-editor_版_機種.deb）を、ほかの配布物とそろえた名前に変える
for f in dist/datum-editor_"$VERSION"_*.deb; do
  mv "$f" "dist/$PRODUCT-$VERSION-linux-${f##*_}"
done
ls -l dist/"$PRODUCT"-"$VERSION"-linux-*.deb
