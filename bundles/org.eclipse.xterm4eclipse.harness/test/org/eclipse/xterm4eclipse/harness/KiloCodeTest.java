package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Kilo Code CLI: the last conversation of the folder. */
class KiloCodeTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.KILO;
	}

	@Test
	void isRecognizedAndOfferedUnderBothNames() throws Exception {
		assertRecognized("kilo", "icons/kilo.png");
		assertRecognized("kilocode", "icons/kilo.png");
		assertOffered("kilo");
		assertOffered("kilocode");
	}

	@Test
	void continuesTheLastConversationOfTheFolderOnceTheUserHasTalked() {
		assertArrayEquals(line("kilo"), new Run("kilo").start());
		assertArrayEquals(line("kilo"), new Run("kilo").resume());
		assertArrayEquals(line("kilo", "--continue"), new Run("kilo").talked().resume());
		assertArrayEquals(line("/bin/zsh", "-l", "-i", "-c", "kilocode --continue"),
				new Run("/bin/zsh", "-l", "-i", "-c", "kilocode").talked().resume());
		assertLeftAlone(line("kilo", "-c"), line("kilo", "--continue"), line("kilo", "-s", "ses_abc"),
				line("kilo", "--session", "ses_abc"), line("kilo", "run", "fix the bug"), line("kilo", "serve"),
				line("kilo", "mcp", "list"), line("kilo", "session", "list"));
	}

	@Test
	void addsTheServerOfEclipseForTheUser() throws Exception {
		Harness.Mcp mcp = Harnesses.KILO.mcp();
		assertEquals(List.of("mcp", "add", NAME, "--url", "http://127.0.0.1:8642/mcp", "--header",
				"Authorization=Bearer " + TOKEN), mcp.adding(new EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", TOKEN)));
		assertNull(mcp.removing(), "Kilo Code replaces a server of the same name");
		String server = "{\"mcp\": {\"e\": {\"type\": \"remote\", \"url\": \"{url}\"}}}";
		assertKnownFrom(home.resolve(".config/kilo/kilo.jsonc").toString(), server);
		assertKnownFrom(home.resolve(".config/kilo/kilo.json").toString(), server);
		assertKnownFrom("kilo.jsonc", server);
		assertKnownFrom(".kilo/kilo.json", server);
	}

	@Test
	void comesBackToTheLastConversationInItsTerminal() throws Exception {
		assertComesBackToTheLastConversation("kilo", "--continue");
	}

	@Test
	void isOfferedTheMcpServerOfEclipse() throws Exception {
		assertOfferedTheMcpServerOfEclipse("kilo",
				"mcp add " + NAME + " --url {url} --header Authorization=Bearer " + TOKEN, null, null,
				home.resolve(".config/kilo/kilo.jsonc"));
	}
}
