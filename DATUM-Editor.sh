#!/bin/sh
# DATUM-Editor launcher for Linux / macOS. Files given as arguments are opened.
DIR=$(cd "$(dirname "$0")" && pwd) || exit 1
if [ -f "$DIR/target/minieditor.jar" ]; then JAR="$DIR/target/minieditor.jar"; else JAR="$DIR/minieditor.jar"; fi
if [ ! -f "$JAR" ]; then
  echo "minieditor.jar was not found. Run \"mvn package\" first, or put minieditor.jar next to this file."
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "Java was not found. Install JDK 17 or later, for example from https://adoptium.net/"
  exit 1
fi
exec java -jar "$JAR" "$@"
