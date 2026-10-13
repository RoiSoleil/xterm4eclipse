package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.ui.XMLMemento;
import org.eclipse.xterm4eclipse.TestWorkbench;
import org.eclipse.xterm4eclipse.XtermView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Claude Code: a conversation of its own for each terminal, and the MCP server of Eclipse. */
class ClaudeCodeTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.CLAUDE;
	}

	@Test
	void isRecognizedWithItsIcon() {
		for (String commandLine : List.of("claude", "/bin/zsh -l -i -c claude", "\"C:\\Users\\me\\.local\\bin\\claude.exe\"",
				"cmd.exe /c C:\\npm\\claude.cmd", "bash -c \"'/my dir/claude' --resume\"")) {
			assertIcon(commandLine, "icons/claude.png");
			assertEquals(Harnesses.CLAUDE, Harnesses.find(commandLine).harness(), commandLine);
		}
		assertNotRecognized("/bin/bash", "/usr/bin/claudette", "/bin/zsh -c vim", "/bin/zsh -c \"claude; rm x\"",
				"/bin/zsh -c \"claude $(cat f)\"", "");
	}

	@Test
	@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the simulated Linux machine needs the files of a Unix system")
	void isOfferedOnlyWhenItIsOnThePath() throws Exception {
		assertEquals("/bin/zsh -l -i -c claude", offered("Linux", Map.of("SHELL", "/bin/zsh"), "claude"),
				"through the login shell of the user");
		assertEquals("/bin/bash -l -i -c claude", offered("Linux", Map.of(), "claude"));
		assertNull(offered("Linux", Map.of("SHELL", "/bin/zsh"), "other"), "not on the PATH: not offered");
	}

	@Test
	void isOfferedOnWindowsWhenItIsOnThePath() throws Exception {
		String installed = offered("Windows 11", Map.of(), "claude.exe");
		assertEquals(bin().resolve("claude.exe").toString(), installed);
		// A .cmd launcher, as installed by npm, needs cmd.exe to run.
		String npm = offered("Windows 11", Map.of(), "claude.cmd");
		assertEquals("cmd.exe /c " + bin().resolve("claude.cmd"), npm);
		assertEquals(Harnesses.CLAUDE, Harnesses.find(npm).harness());
		assertNull(offered("Windows 11", Map.of(), "claude"), "not a program of Windows");
	}

	@Test
	void getsTheSessionOfTheTerminal() {
		assertArrayEquals(line("claude", "--session-id", ID), new Run("claude").with(ID).start());
		assertArrayEquals(line("C:\\tools\\claude.exe", "--session-id", ID), new Run("C:\\tools\\claude.exe").with(ID).start());
		// Through the login shell of the user: the script is changed.
		assertArrayEquals(line("/bin/zsh", "-l", "-i", "-c", "claude --session-id " + ID),
				new Run("/bin/zsh", "-l", "-i", "-c", "claude").with(ID).start());
		// A new terminal chooses its id, and keeps it.
		Run fresh = new Run("claude");
		String[] started = fresh.start();
		assertEquals("--session-id", started[1]);
		assertTrue(started[2].matches(Harness.Sessions.UUID), started[2]);
		assertEquals(started[2], fresh.id());
		assertArrayEquals(started, fresh.start(), "the same conversation until the user restarts the agent");
		fresh.companion.renewed();
		assertNotEquals(started[2], fresh.start()[2], "restarted by the user: a new conversation");
	}

	@Test
	void resumesItsConversationOnceItHasKeptIt() throws Exception {
		assertArrayEquals(line("claude", "--session-id", ID), new Run("claude").with(ID).resume(),
				"no conversation before the first message: the same one starts");
		Path project = Files.createDirectories(home.resolve(".claude/projects/-home-me-project"));
		assertArrayEquals(line("claude", "--session-id", ID), new Run("claude").with(ID).resume());
		Files.writeString(project.resolve(ID + ".jsonl"), "{}\n");
		assertArrayEquals(line("/usr/bin/claude", "--model", "opus", "--resume", ID),
				new Run("/usr/bin/claude", "--model", "opus").with(ID).resume());
		assertArrayEquals(line("cmd.exe", "/c", "C:\\npm\\claude.cmd", "--resume", ID),
				new Run("cmd.exe", "/c", "C:\\npm\\claude.cmd").with(ID).resume());
		assertArrayEquals(line("bash", "-c", "'/my dir/claude' --model opus --resume " + ID),
				new Run("bash", "-c", "'/my dir/claude' --model opus").with(ID).resume());
		assertArrayEquals(line("claude", "--session-id", ID), new Run("claude").with(ID).start(), "not asked to resume");

		// Where CLAUDE_CONFIG_DIR says.
		environment.put("CLAUDE_CONFIG_DIR", temp.resolve("moved").toString());
		assertArrayEquals(line("claude", "--session-id", ID), new Run("claude").with(ID).resume());
		Files.writeString(Files.createDirectories(temp.resolve("moved/projects/p")).resolve(ID + ".jsonl"), "{}\n");
		assertArrayEquals(line("claude", "--resume", ID), new Run("claude").with(ID).resume());
	}

	@Test
	void otherCommandLinesAreLeftAsTheyAre() {
		assertLeftAlone(line("claude", "--continue"), line("claude", "-c"), line("claude", "-r"),
				line("claude", "--resume", "abc"), line("claude", "--session-id=" + ID), line("claude", "-p", "hello"),
				line("/bin/zsh", "-c", "claude --continue"), line("/bin/zsh", "-c", "claude \"quoted\""),
				line("cmd.exe", "/c", "claude.cmd", "-c"), line("claude", "mcp", "list"), line("claude", "update"));
		// Only a session id, never anything that a shell would run.
		Run injected = new Run("/bin/zsh", "-c", "claude").with("x; rm -rf ~");
		String[] started = injected.start();
		assertTrue(started[2].matches("claude --session-id " + Harness.Sessions.UUID), started[2]);
		assertFalse(Harnesses.CLAUDE.sessions().isId(null));
		assertFalse(Harnesses.CLAUDE.sessions().isId(ID + " "));
		assertFalse(Harnesses.CLAUDE.sessions().isId(ID.toUpperCase()));
		assertFalse(Harnesses.CLAUDE.sessions().store().exists("../" + ID));
	}

	@Test
	void theSameClaudeCodeAddsTheServerOfEclipse() {
		Harness.Mcp mcp = Harnesses.CLAUDE.mcp();
		EclipseMcp.Endpoint endpoint = new EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", TOKEN);
		List<String> add = mcp.adding(endpoint);
		assertEquals(List.of("mcp", "add", "--transport", "http", "--scope", "user", NAME, "http://127.0.0.1:8642/mcp",
				"--header", "Authorization: Bearer " + TOKEN), add);
		assertEquals(List.of("mcp", "remove", "--scope", "user", NAME), mcp.removing());
		assertArrayEquals(line("/usr/bin/claude", "mcp", "add", "--transport", "http", "--scope", "user", NAME,
				"http://127.0.0.1:8642/mcp", "--header", "Authorization: Bearer " + TOKEN),
				Harnesses.find(line("/usr/bin/claude", "--model", "opus")).invocation().replaced(add));
		assertArrayEquals(line("cmd.exe", "/c", "C:\\npm\\claude.cmd", "mcp", "remove", "--scope", "user", NAME),
				Harnesses.find(line("cmd.exe", "/c", "C:\\npm\\claude.cmd", "--resume", "x")).invocation().replaced(mcp.removing()));
		// Through the login shell of the user, the header quoted.
		assertArrayEquals(
				line("/bin/zsh", "-l", "-i", "-c", "claude mcp add --transport http --scope user " + NAME
						+ " http://127.0.0.1:8642/mcp --header 'Authorization: Bearer " + TOKEN + "'"),
				Harnesses.find(line("/bin/zsh", "-l", "-i", "-c", "claude --session-id x")).invocation().replaced(add));
		assertArrayEquals(line("bash", "-c", "'/my dir/claude' mcp list"),
				Harnesses.find(line("bash", "-c", "'/my dir/claude' --model opus")).invocation().replaced(List.of("mcp", "list")));
		assertFalse(mcp.tokenInEnvironment());
		assertTrue(new Run("claude").companion.environment().isEmpty());
	}

	@Test
	void mayKnowTheServerOfEclipseAlready() throws Exception {
		assertKnownFrom(home.resolve(".claude.json").toString(), "{\"mcpServers\": {\"eclipse\": {\"type\": \"http\", \"url\": \"{url}\"}}}");
		assertKnownFrom(".mcp.json", "{\"mcpServers\": {\"e\": {\"url\": \"{url}\"}}}");
		environment.put("CLAUDE_CONFIG_DIR", temp.resolve("moved").toString());
		assertKnownFrom(temp.resolve("moved/.claude.json").toString(), "{\"mcpServers\": {\"e\": {\"url\": \"{url}\"}}}");
	}

	@Test
	void resumesItsConversationWhenTheTerminalComesBack() throws Exception {
		String commandLine = program("claude");
		XtermView view = open(commandLine);
		String started = started(view, 1).arguments();
		assertTrue(started.matches("--session-id " + Harness.Sessions.UUID), started);
		String id = started.substring("--session-id ".length());
		assertEquals(id, kept(view, "claudeSession"));
		assertEquals(commandLine, kept(view, "shell"), "the command line of the user is kept");
		assertTrue(asked.isEmpty(), "no MCP server of Eclipse: nothing to ask");

		// Eclipse restarts before the first message: no conversation yet, the same one starts.
		XtermView second = reopen(view);
		assertEquals("--session-id " + id, started(second, 1).arguments());

		// Once Claude Code has kept the conversation, the terminal resumes it.
		write(home.resolve(".claude/projects/-the-project/" + id + ".jsonl"), "{}\n");
		XtermView third = reopen(view);
		assertEquals("--resume " + id, started(third, 1).arguments());

		// Ended with an error, it goes on with its conversation on Enter; restarted by the user, it
		// starts a new one.
		fail(third);
		enter(third);
		assertEquals("--resume " + id, started(third, 2).arguments());
		TestWorkbench.restart(third);
		String renewed = started(third, 3).arguments();
		assertTrue(renewed.matches("--session-id " + Harness.Sessions.UUID), renewed);
		assertNotEquals("--session-id " + id, renewed);

		// A state that is not a session id never reaches the command line.
		XMLMemento memento = XMLMemento.createWriteRoot("view");
		memento.putString("shell", commandLine);
		memento.putString("claudeSession", "x; echo injected");
		XtermView fourth = reopen(memento);
		String safe = started(fourth, 1).arguments();
		assertTrue(safe.matches("--session-id " + Harness.Sessions.UUID), safe);
		assertFalse(TestWorkbench.text(fourth).contains("injected"));
	}

	@Test
	void isOfferedTheMcpServerOfEclipse() throws Exception {
		String url = eclipseMcpRuns();
		String commandLine = program("claude");
		// An older server of the same name, from another run of Eclipse: replaced.
		refuseTheFirstAdd("MCP server " + NAME + " already exists in user config");
		XtermView view = open(commandLine);
		assertTrue(started(view, 1).arguments().startsWith("--session-id"));
		assertEquals(List.of("claude " + url), asked);
		String text = TestWorkbench.text(view);
		assertTrue(text.contains("[Adding the MCP server of Eclipse to Claude Code...]"), text);
		assertTrue(text.contains("[Claude Code can now use Eclipse.]"), text);
		String add = "mcp add --transport http --scope user " + NAME + " " + url + " --header Authorization: Bearer " + TOKEN;
		assertEquals(List.of(add, "mcp remove --scope user " + NAME, add), mcpCalls());
		assertEquals("", started(view, 1).token(), "Claude Code keeps the token in its files");

		// Known by Claude Code: not asked again.
		write(home.resolve(".claude.json"), "{\"mcpServers\": {\"eclipse\": {\"url\": \"" + url + "\"}}}");
		started(open(commandLine), 1);
		assertEquals(1, asked.size());
		assertEquals(3, mcpCalls().size());
	}
}
