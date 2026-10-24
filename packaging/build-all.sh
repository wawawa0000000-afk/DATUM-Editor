#!/bin/sh
# 4つの種類（Lite・Writer・Studio・AI）すべてを、4種類の配布物（Windows ZIP・どの OS でも使える ZIP・Linux .tar.gz・Linux .deb）で作る。
# 基礎の部分を直したときは、これを実行すれば、全部の種類に入る。先に「mvn package」をしておくこと。
set -e
cd "$(dirname "$0")/.."
for edition in Lite Writer Studio AI; do
  for script in build-windows-zip build-universal-zip build-linux-portable build-linux-deb; do
    sh "packaging/$script.sh" "$edition"
  done
done
