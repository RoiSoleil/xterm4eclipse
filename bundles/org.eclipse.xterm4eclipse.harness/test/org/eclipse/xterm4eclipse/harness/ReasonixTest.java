package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Reasonix, the agent for the models of DeepSeek: the last conversation of the folder. */
class ReasonixTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.REASONIX;
	}

	@Test
	void isRecognizedAndOffered() throws Exception {
		assertRecognized("reasonix", "icons/reasonix.png");
		assertOffered("reasonix");
	}

	@Test
	void continuesTheLastConversationOfTheFolderOnceTheUserHasTalked() {
		assertArrayEquals(line("reasonix"), new Run("reasonix").start());
		assertArrayEquals(line("reasonix"), new Run("reasonix").resume(), "nothing said: nothing to resume");
		assertArrayEquals(line("reasonix", "--continue"), new Run("reasonix").talked().resume());
		assertArrayEquals(line("reasonix", "--model", "deepseek-v4-pro", "--continue"),
				new Run("reasonix", "--model", "deepseek-v4-pro").talked().resume());
		assertArrayEquals(line("/bin/zsh", "-l", "-i", "-c", "reasonix --continue"),
				new Run("/bin/zsh", "-l", "-i", "-c", "reasonix").talked().resume());
		assertArrayEquals(line("cmd.exe", "/c", "C:\\npm\\reasonix.cmd", "--continue"),
				new Run("cmd.exe", "/c", "C:\\npm\\reasonix.cmd").talked().resume());
		assertLeftAlone(line("reasonix", "-c"), line("reasonix", "--continue"), line("reasonix", "--resume", "abc"),
				line("reasonix", "-r"), line("reasonix", "-p", "summarize"), line("reasonix", "run", "fix the bug"),
				line("reasonix", "serve"), line("reasonix", "mcp", "list"), line("reasonix", "session", "list", "--json"));
	}

	@Test
	void addsTheServerOfEclipseForTheUser() throws Exception {
		Harness.Mcp mcp = Harnesses.REASONIX.mcp();
		assertEquals(List.of("mcp", "add", NAME, "--http", "http://127.0.0.1:8642/mcp", "--header",
				"Authorization=Bearer " + TOKEN), mcp.adding(new EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", TOKEN)));
		assertEquals(List.of("mcp", "remove", NAME), mcp.removing());
		assertFalse(mcp.tokenInEnvironment());
		String server = "[[plugins]]\nname = \"e\"\ntype = \"http\"\nurl = \"{url}\"\n";
		assertKnownFrom(home.resolve(".reasonix/config.toml").toString(), server);
		assertKnownFrom("reasonix.toml", server);
		assertKnownFrom(".mcp.json", "{\"mcpServers\": {\"e\": {\"url\": \"{url}\"}}}");
		environment.put("REASONIX_HOME", temp.resolve("moved").toString());
		assertKnownFrom(temp.resolve("moved/config.toml").toString(), server);
	}

	@Test
	void comesBackToTheLastConversationInItsTerminal() throws Exception {
		assertComesBackToTheLastConversation("reasonix", "--continue");
	}

	@Test
	void isOfferedTheMcpServerOfEclipse() throws Exception {
		// Reasonix refuses a server whose name it knows: the one of an earlier Eclipse is removed first.
		Start start = assertOfferedTheMcpServerOfEclipse("reasonix",
				"mcp add " + NAME + " --http {url} --header Authorization=Bearer " + TOKEN,
				"MCP server \"" + NAME + "\" is already configured; remove it first or choose another name",
				"mcp remove " + NAME, home.resolve(".reasonix/config.toml"));
		assertEquals("", start.token());
	}
}
