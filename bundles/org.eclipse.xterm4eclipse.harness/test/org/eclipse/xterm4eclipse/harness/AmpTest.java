package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.eclipse.xterm4eclipse.XtermView;
import org.junit.jupiter.api.Test;

/** Amp: the MCP server of Eclipse; its threads are kept on its server. */
class AmpTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.AMP;
	}

	@Test
	void isRecognizedAndOffered() throws Exception {
		assertRecognized("amp", "icons/amp.png");
		assertOffered("amp");
	}

	@Test
	void startsAsTheUserWroteIt() throws Exception {
		assertArrayEquals(line("amp"), new Run("amp").start());
		assertArrayEquals(line("amp"), new Run("amp").talked().resume(), "its threads are not chosen by the terminal");
		assertLeftAlone(line("amp", "threads", "continue"), line("amp", "last"), line("amp", "--execute", "explain"));
		XtermView view = open(program("amp"));
		assertEquals("", started(view, 1).arguments());
		say(view, "hello");
		assertNull(kept(view, "ampConversed"));
		assertEquals("", started(reopen(view), 1).arguments());
	}

	@Test
	void addsTheServerOfEclipseForTheUser() throws Exception {
		Harness.Mcp mcp = Harnesses.AMP.mcp();
		assertEquals(List.of("mcp", "add", NAME, "--header", "Authorization=Bearer " + TOKEN, "http://127.0.0.1:8642/mcp"),
				mcp.adding(new EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", TOKEN)));
		assertEquals(List.of("mcp", "remove", NAME), mcp.removing());
		String server = "{\"amp.mcpServers\": {\"e\": {\"url\": \"{url}\"}}}";
		assertKnownFrom(home.resolve(".config/amp/settings.json").toString(), server);
		assertKnownFrom(".amp/settings.json", server);
	}

	@Test
	void isOfferedTheMcpServerOfEclipse() throws Exception {
		assertOfferedTheMcpServerOfEclipse("amp", "mcp add " + NAME + " --header Authorization=Bearer " + TOKEN + " {url}",
				"MCP server \"" + NAME + "\" already exists", "mcp remove " + NAME,
				home.resolve(".config/amp/settings.json"));
	}
}
