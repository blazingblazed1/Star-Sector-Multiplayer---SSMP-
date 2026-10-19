#!/bin/bash
# package.sh <version> : build SSMP-<version>.zip (spaces/brackets in the version become "-" in the file name, e.g.
# "0.1.5.8 (Logger Disabled)" -> SSMP-0.1.5.8-Logger-Disabled.zip) (StarMP/ mod folder + one-click installers), same layout as the public releases
set -e
V=$1; R=/home/claude/starmp; H=/home/claude/ssmp/SSMP-0.1.4.3   # template: last release (installers, mod_info)
O=/tmp/claude-0/-home-claude/f7b4908b-77c3-55dd-981d-f68dd328e48d/scratchpad/out; F=$(echo "$V" | sed 's/[ ()]\+/-/g; s/-$//'); P=$O/SSMP-$F; rm -rf $P; mkdir -p $P/StarMP/jars $P/StarMP/bin
grep -q "VERSION = \"$V\"" $R/mod-java/StarMPPlugin.java || { echo "VERSION constant is not $V"; exit 1; }
cp $R/StarMP.jar $P/StarMP/jars/
mkdir -p "$P/StarMP/crash logs" "$P/StarMP/player logs"
cp $R/smnet/prebuilt/linux/smnet $P/StarMP/bin/smnet-linux
cp $R/smnet/prebuilt/windows/smnet.exe $P/StarMP/bin/smnet.exe
# the SSMP launcher ships only with LAUNCHER=1 (0.1.6.4: left out until it is approved)
if [ "${LAUNCHER:-0}" = 1 ]; then
  cp $R/launcher/prebuilt/linux/ssmp-launcher $P/StarMP/bin/ssmp-launcher
  cp $R/launcher/prebuilt/windows/ssmp-launcher.exe $P/StarMP/bin/ssmp-launcher.exe
fi
cp -r $R/moddata/data $R/moddata/graphics $P/StarMP/
if grep -q "BUILD_LOGGER = false" $R/mod-java/diag/Diag.java; then LOGOFF=1; DESC="SSMP (StarMP) $V - multiplayer for Starsector.";
else LOGOFF=0; DESC="SSMP (StarMP) $V - Desync \\& crash diagnostics build (full logging) - testers only, no public release."; fi
sed "s/@VERSION@/$V/; s/@DESC@/$DESC/" $R/moddata/mod_info.json > $P/StarMP/mod_info.json   # 0.9.1.3: template is in the source (moddata)
if [ "${LAUNCHER:-0}" = 1 ]; then sed '/^\[\[\/\?LAUNCHER\]\]$/d' $R/README-mod.txt > $P/StarMP/README.txt
else sed '/^\[\[LAUNCHER\]\]$/,/^\[\[\/LAUNCHER\]\]$/d' $R/README-mod.txt > $P/StarMP/README.txt; fi
if [ "$LOGOFF" = 1 ] && [ "${LAUNCHER:-0}" = 1 ]; then : > $P/StarMP/bin/logger.off; fi
if [ "$LOGOFF" = 1 ]; then rmdir "$P/StarMP/crash logs" "$P/StarMP/player logs" 2>/dev/null || true; fi  # tells the SSMP launcher not to start a crash report
cp "$R/moddata/extras/Multibox - make test copies.bat" $R/moddata/extras/multibox-copies.sh $P/StarMP/
cp $R/moddata/extras/multibox.ps1 $R/moddata/extras/bots.ps1 $P/StarMP/bin/
cp "$R/moddata/extras/SSMP bot test.bat" $P/StarMP/
cp $R/THIRD_PARTY_LICENSES.txt $P/StarMP/
cp $R/installers/single-click-install-windows.bat $R/installers/single-click-install-linux.sh $P/
(cd $O && rm -f SSMP-$F.zip && zip -q -r SSMP-$F.zip SSMP-$F)
echo $O/SSMP-$F.zip
