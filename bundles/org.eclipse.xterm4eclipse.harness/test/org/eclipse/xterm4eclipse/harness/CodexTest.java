package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Codex of OpenAI: the last conversation of the folder, and the token of the MCP server in the environment. */
class CodexTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.CODEX;
	}

	@Test
	void isRecognizedAndOffered() throws Exception {
		assertRecognized("codex", "icons/codex.png");
		assertOffered("codex");
		assertEquals("Codex", Harnesses.CODEX.label());
	}

	@Test
	void resumesTheLastConversationOfTheFolderOnceTheUserHasTalked() {
		assertArrayEquals(line("codex"), new Run("codex").start(), "Codex chooses its ids itself");
		assertArrayEquals(line("codex"), new Run("codex").resume(), "nothing said: nothing to resume");
		assertArrayEquals(line("codex", "resume", "--last"), new Run("codex").talked().resume());
		assertArrayEquals(line("codex"), new Run("codex").talked().start(), "not asked to resume");
		// The subcommand comes after the options and their values.
		assertArrayEquals(line("/usr/bin/codex", "--model", "gpt-5", "-s", "read-only", "--search", "resume", "--last"),
				new Run("/usr/bin/codex", "--model", "gpt-5", "-s", "read-only", "--search").talked().resume());
		assertArrayEquals(line("/bin/zsh", "-l", "-i", "-c", "codex resume --last"),
				new Run("/bin/zsh", "-l", "-i", "-c", "codex").talked().resume());
		assertArrayEquals(line("cmd.exe", "/c", "C:\\npm\\codex.cmd", "resume", "--last"),
				new Run("cmd.exe", "/c", "C:\\npm\\codex.cmd").talked().resume());
	}

	@Test
	void otherCommandLinesAreLeftAsTheyAre() {
		assertLeftAlone(line("codex", "exec", "fix the bug"), line("codex", "resume"), line("codex", "resume", "--last"),
				line("codex", "fork", "--last"), line("codex", "mcp", "list"), line("codex", "--remote", "ws://host:1"),
				// A prompt: a subcommand cannot follow it.
				line("codex", "fix the bug"), line("codex", "-i", "a.png", "b.png"),
				line("/bin/zsh", "-c", "codex --model gpt-5 review"));
	}

	@Test
	void conversationThatCannotBeResumedIsNotAskedAgain() {
		Run run = new Run("codex").talked();
		assertArrayEquals(line("codex", "resume", "--last"), run.resume());
		run.companion.exited(1);
		assertArrayEquals(line("codex"), run.resume(), "failed at once: a new conversation");
		assertNull(run.terminal.state.get("codexConversed"), "forgotten");
		// Ended well, or restarted by the user.
		run.talked();
		assertArrayEquals(line("codex", "resume", "--last"), run.resume());
		run.companion.exited(0);
		assertArrayEquals(line("codex", "resume", "--last"), run.resume());
		run.companion.renewed();
		assertArrayEquals(line("codex"), run.resume());
	}

	@Test
	void readsTheTokenOfTheServerOfEclipseInItsEnvironment() throws Exception {
		Harness.Mcp mcp = Harnesses.CODEX.mcp();
		assertEquals(List.of("mcp", "add", NAME, "--url", "http://127.0.0.1:8642/mcp", "--bearer-token-env-var",
				"XTERM4ECLIPSE_MCP_TOKEN"), mcp.adding(new EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", TOKEN)));
		assertNull(mcp.removing(), "Codex replaces a server of the same name");
		assertTrue(mcp.tokenInEnvironment());
		assertTrue(new Run("codex").companion.environment().isEmpty(), "no server of Eclipse");
		eclipseMcpRuns();
		assertEquals(Map.of("XTERM4ECLIPSE_MCP_TOKEN", TOKEN), new Run("codex").companion.environment());
	}

	@Test
	void mayKnowTheServerOfEclipseAlready() throws Exception {
		String server = "[mcp_servers." + NAME + "]\nurl = \"{url}\"\nbearer_token_env_var = \"XTERM4ECLIPSE_MCP_TOKEN\"\n";
		assertKnownFrom(home.resolve(".codex/config.toml").toString(), server);
		assertKnownFrom(".codex/config.toml", server);
		environment.put("CODEX_HOME", temp.resolve("moved").toString());
		assertKnownFrom(temp.resolve("moved/config.toml").toString(), server);
	}

	@Test
	void comesBackToTheLastConversationInItsTerminal() throws Exception {
		assertComesBackToTheLastConversation("codex", "resume --last");
	}

	@Test
	void isOfferedTheMcpServerOfEclipse() throws Exception {
		Start start = assertOfferedTheMcpServerOfEclipse("codex",
				"mcp add " + NAME + " --url {url} --bearer-token-env-var XTERM4ECLIPSE_MCP_TOKEN", null, null,
				home.resolve(".codex/config.toml"));
		assertEquals(TOKEN, start.token(), "the terminal gives the token to Codex");
	}
}
