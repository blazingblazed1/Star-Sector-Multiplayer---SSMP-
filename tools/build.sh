#!/bin/sh
# build.sh : compile the Java mod, check it against Starsector's mod sandbox, and make StarMP.jar
# Usage: STARSECTOR=/path/to/starsector-core sh tools/build.sh
#   (Windows: the starsector-core folder; Linux: the game folder that holds starfarer.api.jar)
set -e
cd "$(dirname "$0")/.."
G=${STARSECTOR:?set STARSECTOR to the folder containing starfarer.api.jar}
CP="$G/fs.common_obf.jar:$G/starfarer.api.jar:$G/lwjgl.jar:$G/lwjgl_util.jar:$G/json.jar:$G/log4j-1.2.9.jar:$G/xstream-1.4.10.jar"
rm -rf build && mkdir build
javac -nowarn -proc:none --release 17 -cp "$CP" -d build java/*.java java/combat/*.java java/campaign/*.java java/ui/*.java java/diag/*.java java/rulecmd/*.java
python3 tools/sandbox_check.py build
(cd build && jar cf ../StarMP.jar starmp/*.class starmp/combat/*.class starmp/campaign/*.class starmp/ui/*.class starmp/diag/*.class starmp/rulecmd/*.class)
echo "built StarMP.jar"
