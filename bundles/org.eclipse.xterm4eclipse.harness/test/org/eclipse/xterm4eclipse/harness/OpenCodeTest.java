package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.eclipse.xterm4eclipse.TestWorkbench;
import org.eclipse.xterm4eclipse.XtermView;
import org.junit.jupiter.api.Test;

/** OpenCode: the conversation of a terminal is found among those that OpenCode lists. */
class OpenCodeTest extends HarnessTestCase {

	/** Ids as OpenCode makes them. */
	private static final String FIRST = "ses_f45ae02c9ffePxeWKv60gyuqM2";
	private static final String SECOND = "ses_f45afd710ffe3BWvf7pX7sYd6R";
	/** Not the program of the PATH: the tests without a view must not run a real OpenCode. */
	private static final String OPENCODE = "/opt/tools/opencode";

	@Override
	Harness harness() {
		return Harnesses.OPENCODE;
	}

	/** A conversation as {@code opencode session list --format json} prints it. */
	private static String listed(String id, long created) {
		return "  {\n    \"id\": \"" + id + "\",\n    \"title\": \"Fix {the} [bug] \\\"now\\\"\",\n    \"updated\": "
				+ (created + 5000) + ",\n    \"created\": " + created
				+ ",\n    \"projectId\": \"3e486acd17cfeae024b7b8994966e932013f42ed\",\n    \"directory\": \"/home/me/project\"\n  }";
	}

	@Test
	void isRecognizedAndOffered() throws Exception {
		assertRecognized("opencode", "icons/opencode.png");
		assertOffered("opencode");
	}

	@Test
	void resumesTheConversationFoundForItsTerminal() {
		assertArrayEquals(line(OPENCODE), new Run(OPENCODE).start(), "OpenCode chooses its ids itself");
		assertArrayEquals(line(OPENCODE), new Run(OPENCODE).resume(), "nothing said: nothing to resume");
		assertArrayEquals(line(OPENCODE, "--session", FIRST), new Run(OPENCODE).with(FIRST).resume());
		assertArrayEquals(line(OPENCODE), new Run(OPENCODE).with(FIRST).start());
		assertArrayEquals(line("/bin/zsh", "-l", "-i", "-c", "opencode --session " + FIRST),
				new Run("/bin/zsh", "-l", "-i", "-c", "opencode").with(FIRST).resume());
		// Not found: the last conversation of the folder, once the user has talked.
		assertArrayEquals(line(OPENCODE, "--continue"), new Run(OPENCODE).talked().resume());
		// Only an id of OpenCode, never anything that a shell would run.
		assertArrayEquals(line("/bin/zsh", "-c", "opencode"), new Run("/bin/zsh", "-c", "opencode").with("x; rm -rf ~").resume());
		assertLeftAlone(line(OPENCODE, "run", "fix the bug"), line(OPENCODE, "-c"), line(OPENCODE, "--session", SECOND),
				line(OPENCODE, "serve"), line(OPENCODE, "session", "list"));
	}

	@Test
	void conversationOfATerminalIsTheNewOneNearestToItsLine() {
		String output = "Last login: today\n[\n" + listed(FIRST, 2000) + ",\n" + listed(SECOND, 9000) + ",\n"
				+ "  {\"id\": \"not an id\", \"created\": 9500},\n  {\"id\": \"ses_withoutTime1234\"}\n]\n";
		List<SessionFinder.Listed> listed = SessionFinder.parse(output, Harnesses.OPENCODE.sessions());
		assertEquals(List.of(new SessionFinder.Listed(FIRST, 2000), new SessionFinder.Listed(SECOND, 9000)), listed);
		assertEquals(List.of(), SessionFinder.parse("no conversation", Harnesses.OPENCODE.sessions()));
		assertEquals(List.of(), SessionFinder.parse("[]", Harnesses.OPENCODE.sessions()));
		try {
			assertNull(SessionFinder.choose(listed, 9500, 9600), "both started before the program of the terminal");
			// Two terminals in the same folder: each one its conversation, the nearest to its line.
			assertEquals(SECOND, SessionFinder.choose(listed, 1000, 8900));
			assertEquals(FIRST, SessionFinder.choose(listed, 1000, 8900), "the other one is taken");
			assertNull(SessionFinder.choose(listed, 1000, 8900));
			SessionFinder.release(SECOND);
			assertEquals(SECOND, SessionFinder.choose(listed, 5000, 1000), "the first one started before the program");
		} finally {
			SessionFinder.release(FIRST);
			SessionFinder.release(SECOND);
			SessionFinder.release(null);
		}
	}

	@Test
	void findsItsConversationInItsTerminal() throws Exception {
		String commandLine = program("opencode");
		long before = System.currentTimeMillis() - 60_000;
		XtermView view = open(commandLine);
		assertEquals("", started(view, 1).arguments());
		assertNull(kept(view, "opencodeSession"));
		// OpenCode starts the conversation at the first message.
		conversationsListed("[\n" + listed(FIRST, System.currentTimeMillis()) + ",\n" + listed(SECOND, before) + "\n]\n");
		say(view, "hello");
		TestWorkbench.await("conversation found", () -> FIRST.equals(kept(view, "opencodeSession")));
		assertEquals(List.of("", "session list --format json -n 20"), calls());
		say(view, "more");
		TestWorkbench.pump(500);
		assertEquals(2, calls().size(), "found: not asked again");
		try {
			XtermView back = reopen(view);
			assertEquals("--session " + FIRST, started(back, 1).arguments());

			// Another terminal in the same folder does not take the conversation of the first one.
			XtermView other = open(commandLine);
			started(other, 1);
			say(other, "hello");
			TestWorkbench.pump(800);
			assertNull(kept(other, "opencodeSession"), "no new conversation in the list");
			assertEquals("--continue", started(reopen(other), 1).arguments(), "the last one of the folder");

			// Restarted by the user: a new conversation, and the first one is free again.
			TestWorkbench.restart(back);
			assertEquals("", started(back, 2).arguments());
			assertNull(kept(back, "opencodeSession"));
		} finally {
			SessionFinder.release(FIRST);
		}
	}

	@Test
	void addsTheServerOfEclipseForTheUser() throws Exception {
		Harness.Mcp mcp = Harnesses.OPENCODE.mcp();
		assertEquals(List.of("mcp", "add", NAME, "--url", "http://127.0.0.1:8642/mcp", "--header",
				"Authorization=Bearer " + TOKEN, "--global"), mcp.adding(new EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", TOKEN)));
		assertNull(mcp.removing(), "OpenCode replaces a server of the same name");
		assertFalse(mcp.tokenInEnvironment());
		String server = "{\"mcp\": {\"servers\": {\"e\": {\"type\": \"remote\", \"url\": \"{url}\"}}}}";
		assertKnownFrom(home.resolve(".config/opencode/opencode.json").toString(), server);
		assertKnownFrom(home.resolve(".config/opencode/opencode.jsonc").toString(), server);
		assertKnownFrom("opencode.json", server);
		assertKnownFrom(".opencode/opencode.jsonc", server);
		environment.put("XDG_CONFIG_HOME", temp.resolve("xdg").toString());
		assertKnownFrom(temp.resolve("xdg/opencode/opencode.json").toString(), server);
	}

	@Test
	void isOfferedTheMcpServerOfEclipse() throws Exception {
		Start start = assertOfferedTheMcpServerOfEclipse("opencode",
				"mcp add " + NAME + " --url {url} --header Authorization=Bearer " + TOKEN + " --global", null, null,
				home.resolve(".config/opencode/opencode.json"));
		assertEquals("", start.token(), "OpenCode keeps the token in its files");
	}
}
