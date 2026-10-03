package org.eclipse.xterm4eclipse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeSessionsTest {

	private static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

	@TempDir
	Path temp;

	private static String[] with(boolean resume, String... arguments) {
		return ClaudeSessions.withSession(arguments, ID, resume);
	}

	@Test
	void claudeCodeGetsTheSessionOfTheTerminal() {
		assertArrayEquals(new String[] {"claude", "--session-id", ID}, with(false, "claude"));
		assertArrayEquals(new String[] {"/usr/bin/claude", "--model", "opus", "--resume", ID},
				with(true, "/usr/bin/claude", "--model", "opus"));
		assertArrayEquals(new String[] {"C:\\tools\\claude.exe", "--session-id", ID}, with(false, "C:\\tools\\claude.exe"));
		assertArrayEquals(new String[] {"cmd.exe", "/c", "C:\\npm\\claude.cmd", "--resume", ID},
				with(true, "cmd.exe", "/c", "C:\\npm\\claude.cmd"));
		// Through the login shell of the user: the script is changed.
		assertArrayEquals(new String[] {"/bin/zsh", "-l", "-i", "-c", "claude --session-id " + ID},
				with(false, "/bin/zsh", "-l", "-i", "-c", "claude"));
		assertArrayEquals(new String[] {"bash", "-c", "'/my dir/claude' --model opus --resume " + ID},
				with(true, "bash", "-c", "'/my dir/claude' --model opus"));
	}

	@Test
	void otherCommandLinesAreLeftAsTheyAre() {
		for (String[] arguments : List.of(new String[] {"/bin/bash"}, new String[] {"claude", "--continue"},
				new String[] {"claude", "-c"}, new String[] {"claude", "-r"}, new String[] {"claude", "--resume", "abc"},
				new String[] {"claude", "--session-id=" + ID}, new String[] {"claude", "-p", "hello"},
				new String[] {"/bin/zsh", "-c", "claude; rm x"}, new String[] {"/bin/zsh", "-c", "claude $(cat f)"},
				new String[] {"/bin/zsh", "-c", "claude --continue"}, new String[] {"/bin/zsh", "-c", "vim"},
				new String[] {"/bin/zsh", "-c", "claude \"quoted\""}, new String[] {"cmd.exe", "/c", "claude.cmd", "-c"},
				new String[] {"/usr/bin/claudette"}, new String[] {})) {
			assertArrayEquals(arguments, with(false, arguments), String.join(" ", arguments));
		}
		// Only a session id, never anything that a shell would run.
		String[] claude = {"/bin/zsh", "-c", "claude"};
		assertArrayEquals(claude, ClaudeSessions.withSession(claude, "x; rm -rf ~", false));
		assertFalse(ClaudeSessions.isId(null));
		assertFalse(ClaudeSessions.isId(ID + " "));
		assertFalse(ClaudeSessions.isId(ID.toUpperCase()));
		assertTrue(ClaudeSessions.isId(ID));
	}

	@Test
	void aConversationExistsOnceClaudeCodeHasKeptIt() throws Exception {
		Path previous = ClaudeSessions.home;
		ClaudeSessions.home = temp;
		try {
			assertFalse(ClaudeSessions.exists(ID), "no projects folder");
			Path folder = Files.createDirectories(temp.resolve("projects").resolve("-home-me-project"));
			assertFalse(ClaudeSessions.exists(ID));
			Files.writeString(folder.resolve(ID + ".jsonl"), "{}\n");
			assertTrue(ClaudeSessions.exists(ID));
			assertFalse(ClaudeSessions.exists("../" + ID), "not a session id");
		} finally {
			ClaudeSessions.home = previous;
		}
	}

	@Test
	void claudeCodeIsRecognized() {
		assertTrue(ClaudeSessions.runsClaude("/bin/zsh -l -i -c claude"));
		assertTrue(ClaudeSessions.runsClaude("\"C:\\Users\\me\\.local\\bin\\claude.exe\""));
		assertTrue(ClaudeSessions.runsClaude("cmd.exe /c C:\\npm\\claude.cmd"));
		assertFalse(ClaudeSessions.runsClaude("/bin/bash"));
	}
}
