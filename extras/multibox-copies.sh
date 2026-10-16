#!/usr/bin/env bash
# SSMP multibox test copies (Linux). Run from <Starsector>/mods/StarMP: bash multibox-copies.sh [clients]
# Copies this install to "<parent>/Starsector Multibox/Copy 2..N", own names Tester2.., logs to THIS install's StarMP.
set -e
MOD="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GAME="$(cd "$MOD/../.." && pwd)"
[ -f "$GAME/starsector.sh" ] || { echo "Run this from <Starsector>/mods/StarMP. Nothing done."; exit 1; }
MB="$(dirname "$GAME")/Starsector Multibox"
N="${1:-}"; [ -n "$N" ] || read -r -p "How many game clients in total, including this main install? [6] " N; N="${N:-6}"
BASE=""; [ -f "$GAME/saves/common/starmp_settings.txt.data" ] && BASE="$(grep -v -E '^(name|logRoot)=' "$GAME/saves/common/starmp_settings.txt.data" || true)"
for k in $(seq 2 "$N"); do
  D="$MB/Copy $k"; echo "Copy $k ..."
  # the copy's own name: kept from an earlier run, otherwise Tester<k> (decided BEFORE copying anything)
  NAME="$(grep -m1 '^name=' "$D/saves/common/starmp_settings.txt.data" 2>/dev/null || true)"; [ -n "$NAME" ] || NAME="name=Tester$k"
  mkdir -p "$D"
  rsync -a --delete --exclude '/saves/' --exclude '/mods/StarMP/crash logs/' --exclude '/mods/StarMP/player logs/' --exclude 'starmp_host_identity.txt' "$GAME/" "$D/" 2>/dev/null \
    || { cp -a "$GAME/." "$D/"; rm -rf "$D/mods/StarMP/crash logs" "$D/mods/StarMP/player logs" "$D/mods/StarMP/bin/starmp_host_identity.txt"; }
  mkdir -p "$D/saves/common"
  for f in "$GAME/saves/common/"*; do b="$(basename "$f")"; case "$b" in starmp_*) ;; *) [ -e "$D/saves/common/$b" ] || cp "$f" "$D/saves/common/" 2>/dev/null || true;; esac; done
  S="$D/saves/common/starmp_settings.txt.data"
  { [ -n "$BASE" ] && printf '%s\n' "$BASE"; echo "$NAME"; echo "logRoot=$MOD"; } > "$S"
  if [ -d "$HOME/Desktop" ]; then
    EXE="$D/mods/StarMP/bin/ssmp-launcher"; [ -x "$EXE" ] || EXE="$D/starsector.sh"  # builds without the SSMP launcher: the copy's own Starsector
    printf '[Desktop Entry]\nType=Application\nName=Starsector (SSMP) #%s\nExec="%s"\nPath=%s\nIcon=%s/graphics/ui/s_icon64.png\nTerminal=false\n' "$k" "$EXE" "$D" "$D" > "$HOME/Desktop/starsector-ssmp-$k.desktop"
    chmod +x "$HOME/Desktop/starsector-ssmp-$k.desktop"
  fi
  echo "  ${NAME#name=}"
done
echo "Done. All copies write their logs to $MOD (crash logs / player logs, one folder per player name)."
