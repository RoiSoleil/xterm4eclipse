#!/bin/bash
# Runs the tests with coverage. Usage: ECLIPSE_HOME=/path/to/eclipse ./test.sh
# The tests drive the real view, browser and shell: windows open on the current display.
# Runs on Linux, macOS and Windows (Git Bash).
set -euo pipefail
cd "$(dirname "$0")"
BUNDLE=bundles/org.eclipse.xterm4eclipse
: "${ECLIPSE_HOME:?Set ECLIPSE_HOME to your Eclipse installation directory}"
MIN_COVERAGE=85
JACOCO=0.8.15
JUNIT=6.1.3
MAVEN=https://repo1.maven.org/maven2

# Class path separator and JVM options of the system.
SEP=:
JAVA_OPTS=()
case "$(uname -s)" in
MINGW* | MSYS* | CYGWIN*)
	SEP=';'
	# Java does not understand the /c/... paths of Git Bash.
	ECLIPSE_HOME=$(cygpath -m "$ECLIPSE_HOME")
	;;
Darwin)
	# SWT runs on the main thread of macOS.
	JAVA_OPTS=(-XstartOnFirstThread)
	;;
esac

mkdir -p .cache
fetch() {
	[ -f ".cache/$(basename "$1")" ] || curl -sfL -o ".cache/$(basename "$1")" "$MAVEN/$1"
}
fetch "org/jacoco/org.jacoco.agent/$JACOCO/org.jacoco.agent-$JACOCO-runtime.jar"
fetch "org/jacoco/org.jacoco.cli/$JACOCO/org.jacoco.cli-$JACOCO-nodeps.jar"
fetch "org/junit/platform/junit-platform-console-standalone/$JUNIT/junit-platform-console-standalone-$JUNIT.jar"
CONSOLE=".cache/junit-platform-console-standalone-$JUNIT.jar"

rm -rf build && mkdir -p build/classes build/test-classes build/natives
# Eclipse ships its own JUnit bundles: keep them off the class path, the console brings JUnit.
# JNA, which the PTY of CDT uses on Windows, is a bundle folder.
ECLIPSE_CP=$( (find "$ECLIPSE_HOME/plugins" -maxdepth 1 -name '*.jar'; find "$ECLIPSE_HOME/plugins" -maxdepth 1 -type d -name 'com.sun.jna*') |
	grep -v -E 'junit|opentest4j|apiguardian|\.source_' | tr '\n' "$SEP")
# The PTY of CDT is native code, normally loaded by OSGi from a platform fragment.
for fragment in "$ECLIPSE_HOME"/plugins/org.eclipse.cdt.core.{linux,macosx,win32}*.jar; do
	[ -f "$fragment" ] && unzip -q -o -j "$fragment" 'os/*' -d build/natives 2>/dev/null || true
done

javac --release 21 -nowarn -encoding UTF-8 -g -cp "$ECLIPSE_CP" -d build/classes $(find $BUNDLE/src -name '*.java')
javac --release 21 -nowarn -encoding UTF-8 -cp "build/classes$SEP$CONSOLE$SEP$ECLIPSE_CP" -d build/test-classes \
	$(find $BUNDLE/test -name '*.java')

# The bundle folder gives the web/ and icons/ resources, as in the plug-in jar.
STATUS=0
# ${@+"$@"}: no arguments is not an error for the bash 3 of macOS.
java ${JAVA_OPTS[@]+"${JAVA_OPTS[@]}"} -Dfile.encoding=UTF-8 \
	-javaagent:".cache/org.jacoco.agent-$JACOCO-runtime.jar=destfile=build/jacoco.exec,includes=org.eclipse.xterm4eclipse.*" \
	-Djava.library.path=build/natives -Dxterm4eclipse.state=build/state \
	-cp "build/classes${SEP}build/test-classes$SEP$BUNDLE$SEP$CONSOLE$SEP$ECLIPSE_CP" \
	org.junit.platform.console.ConsoleLauncher execute --scan-classpath build/test-classes \
	--details=tree --disable-banner ${@+"$@"} || STATUS=$?

java -jar ".cache/org.jacoco.cli-$JACOCO-nodeps.jar" report build/jacoco.exec --classfiles build/classes \
	--sourcefiles $BUNDLE/src --csv build/coverage.csv --xml build/coverage.xml --html build/coverage >/dev/null
awk -F, -v min="$MIN_COVERAGE" 'NR > 1 { missed += $8; covered += $9; printf "  %-28s %5.1f%%\n", $3, 100 * $9 / ($8 + $9) }
	END { total = 100 * covered / (missed + covered); printf "Line coverage: %.1f%% (minimum %d%%), report in build/coverage/index.html\n", total, min; exit total < min }' \
	build/coverage.csv || STATUS=1
exit $STATUS
