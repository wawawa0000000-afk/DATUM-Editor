#!/bin/sh
# DATUM-Editor launcher for Linux / macOS.
cd "$(dirname "$0")" || exit 1
if [ -f target/minieditor.jar ]; then JAR=target/minieditor.jar; else JAR=minieditor.jar; fi
if [ ! -f "$JAR" ]; then
  echo "minieditor.jar was not found. Run \"mvn package\" first, or put minieditor.jar next to this file."
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "Java was not found. Install JDK 17 or later, for example from https://adoptium.net/"
  exit 1
fi
exec java -jar "$JAR"
