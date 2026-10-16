#!/usr/bin/env bash
# StarMP one-click installer (Linux). Run: bash single-click-install-linux.sh [path/to/starsector]
set -e
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GAME="$1"
if [ -z "$GAME" ]; then
  for d in "$HOME/starsector" "$HOME/Starsector" "$HOME/games/starsector" "/opt/starsector" "$HOME/.local/share/starsector" "$PWD/starsector"; do
    { [ -d "$d/starsector-core" ] || [ -f "$d/starsector.sh" ]; } && GAME="$d" && break
  done
fi
if [ -z "$GAME" ]; then
  read -r -p "Path to your Starsector folder (contains starsector.sh): " GAME
fi
GAME="${GAME/#\~/$HOME}"
if [ ! -d "$GAME/starsector-core" ] && [ ! -f "$GAME/starsector.sh" ]; then echo "'$GAME' is not a Starsector folder (no starsector.sh). Nothing installed."; exit 1; fi
mkdir -p "$GAME/mods"
rm -rf "$GAME/mods/StarMP.new"
cp -r "$HERE/StarMP" "$GAME/mods/StarMP.new"
rm -rf "$GAME/mods/StarMP"
mv "$GAME/mods/StarMP.new" "$GAME/mods/StarMP"
chmod +x "$GAME/mods/StarMP/bin/smnet-linux"
echo "Installed to $GAME/mods/StarMP"
if [ ! -f "$GAME/mods/StarMP/bin/ssmp-launcher" ]; then echo "Open the Starsector launcher, tick StarMP under Mods, and start the game."; exit 0; fi
chmod +x "$GAME/mods/StarMP/bin/ssmp-launcher"
# desktop entry: "Starsector (SSMP)" opens the SSMP launcher (Single Player or Multiplayer)
DESK="[Desktop Entry]
Type=Application
Name=Starsector (SSMP)
Comment=Starsector - Single Player or SSMP Multiplayer
Exec=\"$GAME/mods/StarMP/bin/ssmp-launcher\"
Path=$GAME
Icon=$GAME/graphics/ui/s_icon64.png
Terminal=false"
mkdir -p "$HOME/.local/share/applications"
printf '%s\n' "$DESK" > "$HOME/.local/share/applications/starsector-ssmp.desktop"
if [ -d "$HOME/Desktop" ]; then printf '%s\n' "$DESK" > "$HOME/Desktop/starsector-ssmp.desktop"; chmod +x "$HOME/Desktop/starsector-ssmp.desktop"; fi
echo "Start the game with 'Starsector (SSMP)' (desktop / applications menu): Single Player = SSMP off, Multiplayer = SSMP on + crash logger."
