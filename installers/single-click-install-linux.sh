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
# NixOS (and other packaged installs): the game itself is read-only (/nix/store) and mods live in
# ~/.local/share/starsector/mods - install there (0.9.1.3)
if [ -z "$GAME" ] && { [ -d "$HOME/.local/share/starsector/mods" ] || readlink -f "$(command -v starsector 2>/dev/null)" 2>/dev/null | grep -q '^/nix/store/'; }; then
  GAME="$HOME/.local/share/starsector"; PACKAGED=1
  echo "Found a packaged Starsector (NixOS / read-only game folder): installing into $GAME/mods"
fi
if [ -z "$GAME" ]; then
  read -r -p "Path to your Starsector folder (contains starsector.sh), or the folder that holds its mods folder: " GAME
fi
GAME="${GAME/#\~/$HOME}"
if [ -z "$PACKAGED" ] && [ ! -d "$GAME/starsector-core" ] && [ ! -f "$GAME/starsector.sh" ] && [ ! -d "$GAME/mods" ]; then echo "'$GAME' is not a Starsector folder (no starsector.sh or mods folder). Nothing installed."; exit 1; fi
mkdir -p "$GAME/mods"
rm -rf "$GAME/mods/StarMP.new"
cp -r "$HERE/StarMP" "$GAME/mods/StarMP.new"
rm -rf "$GAME/mods/StarMP"
mv "$GAME/mods/StarMP.new" "$GAME/mods/StarMP"
chmod +x "$GAME/mods/StarMP/bin/smnet-linux"
echo "Installed to $GAME/mods/StarMP"
# 0.9.1.3: make sure the network helper can run on this system (it prints one line and exits)
if timeout 15 "$GAME/mods/StarMP/bin/smnet-linux" < /dev/null 2>/dev/null | grep -q '"ev":"hello"'; then
  echo "Network helper check: OK"
else
  echo "WARNING: the SSMP network helper (mods/StarMP/bin/smnet-linux) could not start on this system,"
  echo "so hosting and joining will not work. Please send this message to the SSMP Discord."
  if [ -f /etc/NIXOS ]; then echo "NixOS: add  programs.nix-ld.enable = true;  to /etc/nixos/configuration.nix and run  sudo nixos-rebuild switch"; fi
fi
if [ ! -f "$GAME/mods/StarMP/bin/ssmp-launcher" ]; then echo "Open the Starsector launcher, tick StarMP under Mods, and start the game."; exit 0; fi
chmod +x "$GAME/mods/StarMP/bin/ssmp-launcher"
# desktop entry: "Starsector (SSMP)" opens the SSMP launcher (IROH or LAN)
DESK="[Desktop Entry]
Type=Application
Name=Starsector (SSMP)
Comment=Starsector - SSMP Multiplayer (IROH or LAN)
Exec=\"$GAME/mods/StarMP/bin/ssmp-launcher\"
Path=$GAME
Icon=$GAME/graphics/ui/s_icon64.png
Terminal=false"
mkdir -p "$HOME/.local/share/applications"
printf '%s\n' "$DESK" > "$HOME/.local/share/applications/starsector-ssmp.desktop"
if [ -d "$HOME/Desktop" ]; then printf '%s\n' "$DESK" > "$HOME/Desktop/starsector-ssmp.desktop"; chmod +x "$HOME/Desktop/starsector-ssmp.desktop"; fi
echo "Created the 'Starsector (SSMP)' shortcut (desktop / applications menu): IROH = online with a join code, LAN = direct / same network only."
if [ -f /etc/NIXOS ]; then
  echo "NixOS: the SSMP launcher is a normal Linux program and needs nix-ld to open:"
  echo "  add  programs.nix-ld.enable = true;  to /etc/nixos/configuration.nix and run  sudo nixos-rebuild switch"
fi
