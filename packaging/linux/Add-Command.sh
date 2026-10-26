#!/bin/sh
# Makes the "datum" command (type "datum" or "datum file.txt" in a terminal) by putting a small script in ~/.local/bin.
# Run it again after moving this folder. To undo, delete ~/.local/bin/datum.
APP_DIR=$(cd "$(dirname "$0")" && pwd)
BIN="$HOME/.local/bin"
mkdir -p "$BIN"
printf '#!/bin/sh\nexec "%s/bin/DATUM-Editor" "$@" >/dev/null 2>&1 &\n' "$APP_DIR" > "$BIN/datum"
chmod +x "$BIN/datum"
echo "Created: $BIN/datum"
case ":$PATH:" in
  *":$BIN:"*) echo "Type: datum   (or: datum file.txt)" ;;
  *) echo "$BIN is not in your PATH yet. Log out and in again (most distributions add it automatically), or add it to PATH." ;;
esac
