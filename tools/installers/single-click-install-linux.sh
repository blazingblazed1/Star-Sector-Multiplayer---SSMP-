#!/usr/bin/env bash
# StarMP one-click installer (Linux). Run: bash single-click-install-linux.sh [path/to/starsector]
set -e
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GAME="$1"
if [ -z "$GAME" ]; then
  for d in "$HOME/starsector" "$HOME/Starsector" "$HOME/games/starsector" "/opt/starsector" "$HOME/.local/share/starsector" "$PWD/starsector"; do
    [ -d "$d/starsector-core" ] && GAME="$d" && break
  done
fi
if [ -z "$GAME" ]; then
  read -r -p "Path to your Starsector folder (contains starsector-core): " GAME
fi
GAME="${GAME/#\~/$HOME}"
if [ ! -d "$GAME/starsector-core" ]; then echo "No starsector-core in '$GAME'. Nothing installed."; exit 1; fi
mkdir -p "$GAME/mods"
rm -rf "$GAME/mods/StarMP.new"
cp -r "$HERE/StarMP" "$GAME/mods/StarMP.new"
rm -rf "$GAME/mods/StarMP"
mv "$GAME/mods/StarMP.new" "$GAME/mods/StarMP"
chmod +x "$GAME/mods/StarMP/bin/smnet-linux"
echo "Installed to $GAME/mods/StarMP"
echo "Open the Starsector launcher, tick StarMP under Mods, and start the game."
