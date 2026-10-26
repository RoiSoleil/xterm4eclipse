#!/bin/bash
# Quick local build without Maven. Usage: ECLIPSE_HOME=/path/to/eclipse ./build.sh [--install] [--no-harness]
# The official build is "mvn clean install" (Tycho), which also produces the update site.
set -euo pipefail
cd "$(dirname "$0")"
BUNDLE=bundles/org.eclipse.xterm4eclipse
# The optional plug-in of the coding agents (Claude Code, Codex...): left out with --no-harness.
HARNESS=bundles/org.eclipse.xterm4eclipse.harness
# The chat with Claude Code, in the same feature: left out with --no-harness too.
CLAUDE=bundles/org.eclipse.xterm4eclipse.claude
: "${ECLIPSE_HOME:?Set ECLIPSE_HOME to your Eclipse installation directory}"
INSTALL=
WITH_HARNESS=yes
for option in ${@+"$@"}; do
	case "$option" in
	--install) INSTALL=yes ;;
	--no-harness) WITH_HARNESS= ;;
	*)
		echo "Unknown option $option"
		exit 2
		;;
	esac
done
SEP=:
case "$(uname -s)" in
MINGW* | MSYS* | CYGWIN*)
	SEP=';'
	ECLIPSE_HOME=$(cygpath -m "$ECLIPSE_HOME")
	;;
esac
# JNA, which the PTY of CDT uses, is a bundle folder in some installations.
CP="$ECLIPSE_HOME/plugins/*$(find "$ECLIPSE_HOME/plugins" -maxdepth 1 -type d -name 'com.sun.jna*' | sed "s/^/$SEP/" | tr -d '\n')"
# A new qualifier for every build: with an unchanged version Eclipse keeps serving the web
# resources it extracted from the previous jar.
QUALIFIER=$(date +%Y%m%d%H%M%S)
version() {
	sed -n 's/^Bundle-Version: //p' "$1/META-INF/MANIFEST.MF" | sed "s/qualifier/$QUALIFIER/"
}
rm -rf bin dist && mkdir -p bin/terminal bin/harness bin/claude dist

VERSION=$(version $BUNDLE)
JAR="dist/org.eclipse.xterm4eclipse_${VERSION}.jar"
javac --release 21 -nowarn -cp "$CP" -d bin/terminal $(find $BUNDLE/src -name '*.java')
sed "s/^Bundle-Version: .*/Bundle-Version: ${VERSION}/" $BUNDLE/META-INF/MANIFEST.MF > bin/terminal.MF
jar --create --file "$JAR" --manifest bin/terminal.MF -C bin/terminal org -C "$BUNDLE" plugin.xml -C "$BUNDLE" schema \
	-C "$BUNDLE" web -C "$BUNDLE" icons
echo "Built $JAR"
JARS=("$JAR")
if [ -n "$WITH_HARNESS" ]; then
	VERSION=$(version $HARNESS)
	JAR="dist/org.eclipse.xterm4eclipse.harness_${VERSION}.jar"
	javac --release 21 -nowarn -cp "bin/terminal$SEP$CP" -d bin/harness $(find $HARNESS/src -name '*.java')
	sed "s/^Bundle-Version: .*/Bundle-Version: ${VERSION}/" $HARNESS/META-INF/MANIFEST.MF > bin/harness.MF
	jar --create --file "$JAR" --manifest bin/harness.MF -C bin/harness org -C "$HARNESS" plugin.xml -C "$HARNESS" icons \
		-C "$HARNESS" META-INF/services
	echo "Built $JAR"
	JARS+=("$JAR")
	VERSION=$(version $CLAUDE)
	JAR="dist/org.eclipse.xterm4eclipse.claude_${VERSION}.jar"
	javac --release 21 -nowarn -cp "$CP" -d bin/claude $(find $CLAUDE/src -name '*.java')
	sed "s/^Bundle-Version: .*/Bundle-Version: ${VERSION}/" $CLAUDE/META-INF/MANIFEST.MF > bin/claude.MF
	jar --create --file "$JAR" --manifest bin/claude.MF -C bin/claude org -C "$CLAUDE" plugin.xml -C "$CLAUDE" icons \
		-C "$CLAUDE" web
	echo "Built $JAR"
	JARS+=("$JAR")
fi
if [ -n "$INSTALL" ]; then
	mkdir -p "$ECLIPSE_HOME/dropins"
	# Both plug-ins: with --no-harness the terminal is installed without the coding agents.
	rm -f "$ECLIPSE_HOME"/dropins/org.eclipse.xterm4eclipse_*.jar "$ECLIPSE_HOME"/dropins/org.eclipse.xterm4eclipse.harness_*.jar \
		"$ECLIPSE_HOME"/dropins/org.eclipse.xterm4eclipse.claude_*.jar \
		"$ECLIPSE_HOME"/dropins/io.github.xtermview_*.jar
	cp "${JARS[@]}" "$ECLIPSE_HOME/dropins/"
	echo "Installed into $ECLIPSE_HOME/dropins (restart Eclipse with -clean)"
fi
