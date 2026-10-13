package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.xterm4eclipse.TestWorkbench;
import org.eclipse.xterm4eclipse.XtermView;
import org.junit.jupiter.api.Test;

/** GitHub Copilot CLI: one option starts or resumes the conversation of the terminal. */
class CopilotCliTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.COPILOT;
	}

	@Test
	void isRecognizedAndOffered() throws Exception {
		assertRecognized("copilot", "icons/copilot.png");
		assertOffered("copilot");
	}

	@Test
	void startsOrResumesTheSessionOfTheTerminalWithTheSameOption() {
		assertArrayEquals(line("copilot", "--session-id", ID), new Run("copilot").with(ID).start());
		assertArrayEquals(line("copilot", "--session-id", ID), new Run("copilot").with(ID).resume());
		assertArrayEquals(line("copilot", "--session-id", ID), new Run("copilot").with(ID).talked().resume());
		assertArrayEquals(line("/bin/zsh", "-l", "-i", "-c", "copilot --model auto --session-id " + ID),
				new Run("/bin/zsh", "-l", "-i", "-c", "copilot --model auto").with(ID).resume());
		Run fresh = new Run("copilot");
		String id = fresh.start()[2];
		assertTrue(id.matches(Harness.Sessions.UUID), id);
		fresh.companion.renewed();
		assertNotEquals(id, fresh.start()[2], "restarted by the user: a new conversation");
		assertLeftAlone(line("copilot", "--resume"), line("copilot", "-r", "abc"), line("copilot", "--continue"),
				line("copilot", "--session-id", ID), line("copilot", "-p", "explain"), line("copilot", "mcp", "list"),
				line("copilot", "login"));
	}

	@Test
	void addsTheServerOfEclipseForTheUser() throws Exception {
		Harness.Mcp mcp = Harnesses.COPILOT.mcp();
		assertEquals(List.of("mcp", "add", "--transport", "http", NAME, "http://127.0.0.1:8642/mcp", "--header",
				"Authorization: Bearer " + TOKEN), mcp.adding(new EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", TOKEN)));
		assertEquals(List.of("mcp", "remove", NAME), mcp.removing());
		String server = "{\"mcpServers\": {\"e\": {\"type\": \"http\", \"url\": \"{url}\"}}}";
		assertKnownFrom(home.resolve(".copilot/mcp-config.json").toString(), server);
		assertKnownFrom(".mcp.json", server);
		assertKnownFrom(".github/mcp.json", server);
		environment.put("COPILOT_HOME", temp.resolve("moved").toString());
		assertKnownFrom(temp.resolve("moved/mcp-config.json").toString(), server);
	}

	@Test
	void comesBackToItsOwnConversationInItsTerminal() throws Exception {
		XtermView view = open(program("copilot"));
		String started = started(view, 1).arguments();
		assertTrue(started.matches("--session-id " + Harness.Sessions.UUID), started);
		assertEquals(started.substring("--session-id ".length()), kept(view, "copilotSession"));
		XtermView back = reopen(view);
		assertEquals(started, started(back, 1).arguments(), "Copilot resumes the conversation of the id, or starts it");
		fail(back);
		enter(back);
		assertEquals(started, started(back, 2).arguments());
		TestWorkbench.restart(back);
		assertNotEquals(started, started(back, 3).arguments(), "restarted by the user: a new conversation");
	}

	@Test
	void isOfferedTheMcpServerOfEclipse() throws Exception {
		assertOfferedTheMcpServerOfEclipse("copilot",
				"mcp add --transport http " + NAME + " {url} --header Authorization: Bearer " + TOKEN,
				"Error: Server \"" + NAME + "\" already exists. To update it, remove it first:", "mcp remove " + NAME,
				home.resolve(".copilot/mcp-config.json"));
	}
}
