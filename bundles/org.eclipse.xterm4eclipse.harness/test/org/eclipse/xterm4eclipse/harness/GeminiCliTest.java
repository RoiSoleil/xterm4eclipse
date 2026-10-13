package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Gemini CLI of Google: a conversation of its own for each terminal. */
class GeminiCliTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.GEMINI;
	}

	@Test
	void isRecognizedAndOffered() throws Exception {
		assertRecognized("gemini", "icons/gemini.png");
		assertOffered("gemini");
	}

	@Test
	void getsTheSessionOfTheTerminalAndResumesItOnceKept() throws Exception {
		assertArrayEquals(line("gemini", "--session-id", ID), new Run("gemini").with(ID).start());
		assertArrayEquals(line("gemini", "--session-id", ID), new Run("gemini").with(ID).resume(),
				"no conversation before the first message: the same one starts");
		// tmp/<project>/chats/session-<time>-<first characters of the id>.jsonl
		write(home.resolve(".gemini/tmp/project/chats/session-2026-10-13T10-00-" + ID.substring(0, 8) + ".jsonl"), "{}\n");
		assertArrayEquals(line("gemini", "--resume", ID), new Run("gemini").with(ID).resume());
		assertArrayEquals(line("/bin/zsh", "-l", "-i", "-c", "gemini --model pro --resume " + ID),
				new Run("/bin/zsh", "-l", "-i", "-c", "gemini --model pro").with(ID).resume());
		assertArrayEquals(line("gemini", "--session-id", "1" + ID.substring(1)), new Run("gemini").with("1" + ID.substring(1)).resume(),
				"another conversation");
		String[] fresh = new Run("gemini").start();
		assertTrue(fresh[2].matches(Harness.Sessions.UUID), fresh[2]);
		assertLeftAlone(line("gemini", "-r", "latest"), line("gemini", "--resume", "5"), line("gemini", "--session-id", ID),
				line("gemini", "-p", "explain"), line("gemini", "--list-sessions"), line("gemini", "mcp", "list"),
				line("gemini", "extensions", "list"));
	}

	@Test
	void conversationsAreAlsoFoundWhereTheUserMovedThem() throws Exception {
		environment.put("GEMINI_CLI_HOME", temp.resolve("moved").toString());
		assertArrayEquals(line("gemini", "--session-id", ID), new Run("gemini").with(ID).resume());
		write(temp.resolve("moved/.gemini/tmp/p/chats/session-2026-10-13T10-00-" + ID.substring(0, 8) + ".json"), "{}");
		assertArrayEquals(line("gemini", "--resume", ID), new Run("gemini").with(ID).resume());
	}

	@Test
	void addsTheServerOfEclipseForTheUser() throws Exception {
		Harness.Mcp mcp = Harnesses.GEMINI.mcp();
		assertEquals(List.of("mcp", "add", "-s", "user", "-t", "http", NAME, "http://127.0.0.1:8642/mcp", "-H",
				"Authorization: Bearer " + TOKEN), mcp.adding(new EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", TOKEN)));
		assertNull(mcp.removing(), "Gemini CLI updates a server of the same name");
		String server = "{\"mcpServers\": {\"e\": {\"url\": \"{url}\", \"type\": \"http\"}}}";
		assertKnownFrom(home.resolve(".gemini/settings.json").toString(), server);
		assertKnownFrom(".gemini/settings.json", server);
	}

	@Test
	void comesBackToItsOwnConversationInItsTerminal() throws Exception {
		assertComesBackToItsOwnConversation("gemini", "--session-id {id}", "--resume {id}",
				".gemini/tmp/project/chats/session-2026-10-13T10-00-{id8}.jsonl");
	}

	@Test
	void isOfferedTheMcpServerOfEclipse() throws Exception {
		assertOfferedTheMcpServerOfEclipse("gemini",
				"mcp add -s user -t http " + NAME + " {url} -H Authorization: Bearer " + TOKEN, null, null,
				home.resolve(".gemini/settings.json"));
	}
}
