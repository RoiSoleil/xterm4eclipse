package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Crush of Charm: the last conversation of the folder, and no command for the MCP servers. */
class CrushTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.CRUSH;
	}

	@Test
	void isRecognizedAndOffered() throws Exception {
		assertRecognized("crush", "icons/crush.png");
		assertOffered("crush");
	}

	@Test
	void continuesTheLastConversationOfTheFolderOnceTheUserHasTalked() {
		assertArrayEquals(line("crush"), new Run("crush").start());
		assertArrayEquals(line("crush"), new Run("crush").resume());
		assertArrayEquals(line("crush", "--continue"), new Run("crush").talked().resume());
		assertArrayEquals(line("crush", "--yolo", "--continue"), new Run("crush", "--yolo").talked().resume());
		assertLeftAlone(line("crush", "-C"), line("crush", "--continue"), line("crush", "-s", "abc"),
				line("crush", "--session", "abc"), line("crush", "run", "fix the bug"), line("crush", "session", "list"),
				line("crush", "--host", "unix:///run/crush.sock"));
	}

	@Test
	void comesBackToTheLastConversationInItsTerminal() throws Exception {
		assertComesBackToTheLastConversation("crush", "--continue");
	}

	@Test
	void isNotOfferedTheMcpServerOfEclipse() throws Exception {
		assertNull(Harnesses.CRUSH.mcp(), "its servers are only in its files");
		eclipseMcpRuns();
		assertEquals(new Start("", ""), started(open(program("crush")), 1));
		assertTrue(asked.isEmpty());
	}
}
