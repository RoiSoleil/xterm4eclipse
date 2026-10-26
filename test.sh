#!/bin/bash
# Runs the tests with coverage. Usage: ECLIPSE_HOME=/path/to/eclipse ./test.sh
# The tests drive the real view, browser and shell: windows open on the current display.
# Runs on Linux, macOS and Windows (Git Bash).
set -euo pipefail
cd "$(dirname "$0")"
BUNDLE=bundles/org.eclipse.xterm4eclipse
# The optional plug-in of the coding agents: the terminal is tested without it first.
HARNESS=bundles/org.eclipse.xterm4eclipse.harness
# The chat with Claude Code, in the same feature.
CLAUDE=bundles/org.eclipse.xterm4eclipse.claude
: "${ECLIPSE_HOME:?Set ECLIPSE_HOME to your Eclipse installation directory}"
MIN_COVERAGE=85
JACOCO=0.8.15
JUNIT=6.1.3
MAVEN=https://repo1.maven.org/maven2

# Class path separator and JVM options of the system. The tests run the bash of the PATH, which
# must be 4.4 or later (bracketed paste): on macOS, the one of Homebrew.
SEP=:
# The system as the Eclipse launcher gives it (Platform.getOS()): CDT picks its pseudo terminals with it.
JAVA_OPTS=(-Dosgi.os=linux)
TEST_BASH=$(command -v bash)
case "$(uname -s)" in
MINGW* | MSYS* | CYGWIN*)
	SEP=';'
	# A runner may take more than the 5 seconds that SWT gives Edge to create a browser.
	JAVA_OPTS=(-Dosgi.os=win32 -Dorg.eclipse.swt.internal.win32.Edge.timeout=60000)
	# Java does not understand the /c/... paths of Git Bash.
	ECLIPSE_HOME=$(cygpath -m "$ECLIPSE_HOME")
	TEST_BASH=$(cygpath -w "$TEST_BASH")
	;;
Darwin)
	# SWT runs on the main thread of macOS.
	JAVA_OPTS=(-Dosgi.os=macosx -XstartOnFirstThread)
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

rm -rf build && mkdir -p build/classes build/test-classes build/harness-classes build/harness-test-classes \
	build/claude-classes build/claude-test-classes build/natives
# Eclipse ships its own JUnit bundles: keep them off the class path, the console brings JUnit.
# JNA, which the PTY of CDT uses on Windows, is a bundle folder.
ECLIPSE_CP=$( (find "$ECLIPSE_HOME/plugins" -maxdepth 1 -name '*.jar'; find "$ECLIPSE_HOME/plugins" -maxdepth 1 -type d -name 'com.sun.jna*') |
	grep -v -E 'junit|opentest4j|apiguardian|\.source_' | tr '\n' "$SEP")
# The PTY of CDT is native code, normally loaded by OSGi from a platform fragment: the libraries
# of the processor only (a fragment of macOS has those of several).
ARCH=$(uname -m)
[ "$ARCH" = arm64 ] && ARCH=aarch64
# A fragment is a jar, or a folder when Eclipse unpacks it (Windows); jars are extracted with the jar
# tool of the JDK, as Git Bash has no unzip.
mkdir -p build/fragments
for fragment in "$ECLIPSE_HOME"/plugins/org.eclipse.cdt.core.{linux,macosx,win32}*; do
	if [ -d "$fragment/os" ]; then
		cp -R "$fragment/os" build/fragments/
	elif [ -f "$fragment" ] && [ "${fragment%.jar}" != "$fragment" ]; then
		(cd build/fragments && jar xf "$fragment" os)
	fi
done
cp build/fragments/os/*/"$ARCH"/* build/natives/ || {
	echo "No PTY library for $ARCH in:" "$ECLIPSE_HOME"/plugins/org.eclipse.cdt.core*
	exit 1
}
ls build/natives

# In argument files: the class path is longer than a command line of Windows.
classpath() {
	echo "-cp \"$1\""
}
classpath "$ECLIPSE_CP" > build/main.args
find $BUNDLE/src -name '*.java' >> build/main.args
classpath "build/classes$SEP$CONSOLE$SEP$ECLIPSE_CP" > build/test.args
find $BUNDLE/test -name '*.java' >> build/test.args
# The bundle folder gives the web/ and icons/ resources, as in the plug-in jar.
CORE_CP="build/classes${SEP}build/test-classes$SEP$BUNDLE$SEP$CONSOLE$SEP$ECLIPSE_CP"
classpath "$CORE_CP" > build/run.args
# The plug-in of the coding agents, and its tests, which use the test workbench of the terminal.
classpath "build/classes$SEP$ECLIPSE_CP" > build/harness-main.args
find $HARNESS/src -name '*.java' >> build/harness-main.args
classpath "build/harness-classes$SEP$CORE_CP" > build/harness-test.args
find $HARNESS/test -name '*.java' >> build/harness-test.args
# Its folder gives its icons and tells the terminal about it (META-INF/services), as plugin.xml does in Eclipse.
classpath "build/harness-classes${SEP}build/harness-test-classes$SEP$HARNESS$SEP$CORE_CP" > build/harness-run.args
# The chat, and its tests, which use the fakes of the tests of the terminal.
classpath "build/classes${SEP}build/harness-classes$SEP$ECLIPSE_CP" > build/claude-main.args
find $CLAUDE/src -name '*.java' >> build/claude-main.args
classpath "build/claude-classes${SEP}build/harness-classes$SEP$CORE_CP" > build/claude-test.args
find $CLAUDE/test -name '*.java' >> build/claude-test.args
# Its folder gives its page (web/), as in the plug-in jar.
classpath "build/claude-classes${SEP}build/claude-test-classes${SEP}build/harness-classes$SEP$CLAUDE$SEP$CORE_CP" > build/claude-run.args

javac --release 21 -nowarn -encoding UTF-8 -g -d build/classes @build/main.args
javac --release 21 -nowarn -encoding UTF-8 -d build/test-classes @build/test.args
javac --release 21 -nowarn -encoding UTF-8 -g -d build/harness-classes @build/harness-main.args
javac --release 21 -nowarn -encoding UTF-8 -d build/harness-test-classes @build/harness-test.args
javac --release 21 -nowarn -encoding UTF-8 -g -d build/claude-classes @build/claude-main.args
javac --release 21 -nowarn -encoding UTF-8 -d build/claude-test-classes @build/claude-test.args

STATUS=0
# ${@+"$@"}: no arguments is not an error for the bash 3 of macOS.
run_tests() {
	local arguments=$1 classes=$2
	shift 2
	java "${JAVA_OPTS[@]}" -Dfile.encoding=UTF-8 \
		-javaagent:".cache/org.jacoco.agent-$JACOCO-runtime.jar=destfile=build/jacoco.exec,includes=org.eclipse.xterm4eclipse.*" \
		-Djava.library.path=build/natives -Dxterm4eclipse.state=build/state "-Dxterm4eclipse.test.bash=$TEST_BASH" \
		"@$arguments" \
		org.junit.platform.console.ConsoleLauncher execute --scan-classpath "$classes" \
		--config 'junit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$ClassName' \
		--details=tree --disable-banner ${@+"$@"} || STATUS=$?
}
# TESTS=terminal, TESTS=harness or TESTS=claude runs the tests of one plug-in only.
case "${TESTS:-all}" in
all | terminal) run_tests build/run.args build/test-classes ${@+"$@"} ;;
esac
case "${TESTS:-all}" in
all | harness) run_tests build/harness-run.args build/harness-test-classes ${@+"$@"} ;;
esac
case "${TESTS:-all}" in
all | claude) run_tests build/claude-run.args build/claude-test-classes ${@+"$@"} ;;
esac

java -jar ".cache/org.jacoco.cli-$JACOCO-nodeps.jar" report build/jacoco.exec \
	--classfiles build/classes --classfiles build/harness-classes --classfiles build/claude-classes \
	--sourcefiles $BUNDLE/src --sourcefiles $HARNESS/src --sourcefiles $CLAUDE/src \
	--csv build/coverage.csv --xml build/coverage.xml --html build/coverage >/dev/null
awk -F, -v min="$MIN_COVERAGE" 'NR > 1 { missed += $8; covered += $9; printf "  %-28s %5.1f%%\n", $3, 100 * $9 / ($8 + $9) }
	END { total = 100 * covered / (missed + covered); printf "Line coverage: %.1f%% (minimum %d%%), report in build/coverage/index.html\n", total, min; exit total < min }' \
	build/coverage.csv || STATUS=1
exit $STATUS
