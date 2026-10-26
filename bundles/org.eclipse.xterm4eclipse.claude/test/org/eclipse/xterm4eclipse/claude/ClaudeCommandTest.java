package org.eclipse.xterm4eclipse.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ClaudeCommandTest {

	static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
	private static final List<String> STREAM = List.of("-p", "--input-format", "stream-json", "--output-format",
			"stream-json", "--verbose", "--include-partial-messages", "--permission-prompt-tool", "stdio");

	@TempDir
	Path temp;
	private final Map<String, String> environment = new HashMap<>();
	private UnaryOperator<String> previousEnvironment;
	private boolean previousWindows;
	private String previousSetsid;

	@BeforeEach
	void isolate() {
		previousEnvironment = ClaudeCommand.environment;
		previousWindows = ClaudeCommand.windows;
		previousSetsid = ClaudeCommand.setsid;
		ClaudeCommand.environment = environment::get;
		ClaudeCommand.setsid = null;
	}

	@AfterEach
	void restore() {
		ClaudeCommand.environment = previousEnvironment;
		ClaudeCommand.windows = previousWindows;
		ClaudeCommand.setsid = previousSetsid;
	}

	private static List<String> with(String... more) {
		List<String> arguments = new java.util.ArrayList<>(STREAM);
		arguments.addAll(List.of(more));
		return arguments;
	}

	@Test
	void startsOrResumesTheConversationOfTheChat() {
		assertEquals(with("--session-id", ID), ClaudeCommand.arguments(ID, false, "", "default", ""));
		assertEquals(with("--resume", ID), ClaudeCommand.arguments(ID, true, null, null, null));
		assertThrows(IllegalArgumentException.class, () -> ClaudeCommand.arguments("x; rm -rf /", false, "", "", ""));
	}

	@Test
	void givesTheModelAndThePermissionMode() {
		assertEquals(with("--session-id", ID, "--model", "opus", "--permission-mode", "acceptEdits"),
				ClaudeCommand.arguments(ID, false, "opus", "acceptEdits", ""));
		assertEquals(with("--session-id", ID, "--model", "claude-opus-4-1[1m]", "--permission-mode", "plan"),
				ClaudeCommand.arguments(ID, false, "claude-opus-4-1[1m]", "plan", ""));
		assertEquals(with("--session-id", ID), ClaudeCommand.arguments(ID, false, "opus; rm x", "bypassPermissions", ""),
				"neither a model that is not one nor a mode that the chat does not offer");
		assertTrue(ClaudeCommand.isModel("sonnet"));
		assertFalse(ClaudeCommand.isModel("$(x)"));
		assertFalse(ClaudeCommand.isModel(""));
	}

	@Test
	void keepsOnlyThePlainMoreArgumentsThatAreNotThoseOfTheChat() {
		assertEquals(with("--resume", ID, "--add-dir", "../lib", "--effort=high"),
				ClaudeCommand.arguments(ID, true, "", "default", " --add-dir ../lib  --effort=high "));
		assertEquals(List.of("--debug"), ClaudeCommand.more("--resume other --output-format text --model=x --debug"));
		assertEquals(List.of("--x"), ClaudeCommand.more("--x a&b $(cat) \"q\" %PATH%"));
		assertEquals(List.of(), ClaudeCommand.more(null));
	}

	@Test
	void findsTheProgramInTheAbsoluteFoldersOfThePath() throws Exception {
		ClaudeCommand.windows = false;
		Path bin = Files.createDirectories(temp.resolve("bin"));
		Path relative = Files.createDirectories(temp.resolve("relative"));
		assertNull(ClaudeCommand.program(""), "nowhere");
		Path program = Files.createFile(bin.resolve("claude"));
		assertTrue(program.toFile().setExecutable(true));
		environment.put("PATH", "relative" + File.pathSeparator + bin);
		assertEquals(program.toFile(), ClaudeCommand.program(" "));
		environment.put("PATH", "relative");
		Files.createFile(relative.resolve("claude"));
		assertNull(ClaudeCommand.program(""), "never in a relative folder");
		assertEquals(program.toFile(), ClaudeCommand.program(program.toString()), "chosen in the preferences");
		assertNull(ClaudeCommand.program("claude"), "a chosen program has an absolute path");
		assertNull(ClaudeCommand.program(temp.resolve("missing").toString()));
	}

	@Test
	void findsTheProgramWhereItsInstallerPutsIt() throws Exception {
		ClaudeCommand.windows = false;
		Path program = Files.createFile(Files.createDirectories(temp.resolve(".local/bin")).resolve("claude"));
		assertTrue(program.toFile().setExecutable(true));
		environment.put("HOME", temp.toString());
		assertEquals(program.toFile(), ClaudeCommand.program(""));
	}

	@Test
	void findsTheLaunchersOfWindows() throws Exception {
		ClaudeCommand.windows = true;
		Path bin = Files.createDirectories(temp.resolve("bin"));
		environment.put("PATH", bin.toString());
		Files.createFile(bin.resolve("claude"));
		assertNull(ClaudeCommand.program(""), "not a program of Windows");
		Path npm = Files.createFile(bin.resolve("claude.cmd"));
		assertEquals(npm.toFile(), ClaudeCommand.program(""));
		Path installed = Files.createFile(bin.resolve("claude.exe"));
		assertEquals(installed.toFile(), ClaudeCommand.program(""), "the program before the launcher of npm");
	}

	@Test
	void runsALauncherOfNpmThroughCmd() {
		ClaudeCommand.windows = true;
		assertEquals(List.of("cmd.exe", "/c", "C:\\npm\\claude.cmd", "-p"),
				ClaudeCommand.commandLine(new File("C:\\npm\\claude.cmd"), List.of("-p")));
		assertEquals(List.of("C:\\bin\\claude.exe", "-p"), ClaudeCommand.commandLine(new File("C:\\bin\\claude.exe"), List.of("-p")));
	}

	@Test
	@DisabledOnOs(value = OS.WINDOWS, disabledReason = "paths of Unix")
	void runsTheProgramThroughTheLoginShellWithoutAScriptOfItsArguments() {
		ClaudeCommand.windows = false;
		File program = new File("/opt/claude/bin/claude");
		List<String> arguments = List.of("--model", "a b", "it's");
		assertEquals(List.of("/opt/claude/bin/claude", "--model", "a b", "it's"), ClaudeCommand.commandLine(program, arguments),
				"no shell: run as it is");
		environment.put("SHELL", "/bin/zsh");
		assertEquals(List.of("/bin/zsh", "-l", "-i", "-c", "exec \"$0\" \"$@\"", "/opt/claude/bin/claude", "--model", "a b",
				"it's"), ClaudeCommand.commandLine(program, arguments));
		environment.put("SHELL", "/usr/bin/fish");
		assertEquals(List.of("/usr/bin/fish", "-l", "-i", "-c", "exec $argv", "/opt/claude/bin/claude", "--model", "a b",
				"it's"), ClaudeCommand.commandLine(program, arguments));
		environment.put("SHELL", "/usr/bin/nu");
		assertEquals("/opt/claude/bin/claude", ClaudeCommand.commandLine(program, arguments).get(0), "a shell of another kind");
		environment.put("SHELL", "bash");
		assertEquals("/opt/claude/bin/claude", ClaudeCommand.commandLine(program, arguments).get(0), "not an absolute path");
	}

	@Test
	@DisabledOnOs(value = OS.WINDOWS, disabledReason = "paths of Unix")
	void runsTheLoginShellInASessionOfItsOwn() {
		ClaudeCommand.windows = false;
		ClaudeCommand.setsid = "/usr/bin/setsid";
		File program = new File("/opt/claude/bin/claude");
		environment.put("SHELL", "/bin/bash");
		assertEquals(List.of("/usr/bin/setsid", "/bin/bash", "-l", "-i", "-c", "exec \"$0\" \"$@\"", "/opt/claude/bin/claude", "-p"),
				ClaudeCommand.commandLine(program, List.of("-p")), "without the terminal that Eclipse may have been started from");
		environment.remove("SHELL");
		assertEquals(List.of("/opt/claude/bin/claude", "-p"), ClaudeCommand.commandLine(program, List.of("-p")),
				"no shell: nothing to keep from the terminal");
	}

	@Test
	void leavesOutWhatAnInteractiveShellSaysWithoutTerminal() {
		assertEquals("boom\n", ClaudeProcess.withoutShellNoise("bash: cannot set terminal process group (562): Inappropriate ioctl "
				+ "for device\nbash: no job control in this shell\nboom\n"));
		assertEquals("zsh: x\n", ClaudeProcess.withoutShellNoise("zsh: x\nsh: can't access tty; job control turned off"));
	}
}
