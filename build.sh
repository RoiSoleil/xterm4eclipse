#!/bin/bash
# Quick local build without Maven. Usage: ECLIPSE_HOME=/path/to/eclipse ./build.sh [--install]
# The official build is "mvn clean install" (Tycho), which also produces the update site.
set -euo pipefail
cd "$(dirname "$0")"
BUNDLE=bundles/org.eclipse.xterm4eclipse
: "${ECLIPSE_HOME:?Set ECLIPSE_HOME to your Eclipse installation directory}"
# A new qualifier for every build: with an unchanged version Eclipse keeps serving the web
# resources it extracted from the previous jar.
VERSION=$(sed -n 's/^Bundle-Version: //p' $BUNDLE/META-INF/MANIFEST.MF | sed "s/qualifier/$(date +%Y%m%d%H%M%S)/")
JAR="dist/org.eclipse.xterm4eclipse_${VERSION}.jar"
rm -rf bin dist && mkdir -p bin dist
javac --release 21 -nowarn -cp "$ECLIPSE_HOME/plugins/*" -d bin $(find $BUNDLE/src -name '*.java')
sed "s/^Bundle-Version: .*/Bundle-Version: ${VERSION}/" $BUNDLE/META-INF/MANIFEST.MF > bin/MANIFEST.MF
jar --create --file "$JAR" --manifest bin/MANIFEST.MF -C bin org -C "$BUNDLE" plugin.xml -C "$BUNDLE" web -C "$BUNDLE" icons
echo "Built $JAR"
if [ "${1:-}" = "--install" ]; then
	mkdir -p "$ECLIPSE_HOME/dropins"
	rm -f "$ECLIPSE_HOME"/dropins/org.eclipse.xterm4eclipse_*.jar "$ECLIPSE_HOME"/dropins/io.github.xtermview_*.jar
	cp "$JAR" "$ECLIPSE_HOME/dropins/"
	echo "Installed into $ECLIPSE_HOME/dropins (restart Eclipse with -clean)"
fi
