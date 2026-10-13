package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Aider: the history of the folder, read again when the terminal comes back. */
class AiderTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.AIDER;
	}

	@Test
	void isRecognizedAndOffered() throws Exception {
		assertRecognized("aider", "icons/aider.png");
		assertOffered("aider");
	}

	@Test
	void readsTheHistoryOfTheFolderAgainOnceTheUserHasTalked() {
		assertArrayEquals(line("aider"), new Run("aider").start());
		assertArrayEquals(line("aider"), new Run("aider").resume());
		assertArrayEquals(line("aider", "--restore-chat-history"), new Run("aider").talked().resume());
		// The files given to Aider stay where they are.
		assertArrayEquals(line("aider", "--model", "sonnet", "src/Foo.java", "--restore-chat-history"),
				new Run("aider", "--model", "sonnet", "src/Foo.java").talked().resume());
		assertArrayEquals(line("/bin/zsh", "-l", "-i", "-c", "aider --restore-chat-history"),
				new Run("/bin/zsh", "-l", "-i", "-c", "aider").talked().resume());
		assertLeftAlone(line("aider", "--restore-chat-history"), line("aider", "--no-restore-chat-history"),
				line("aider", "--message", "fix the bug"), line("aider", "-m", "fix"), line("aider", "-f", "plan.md"));
	}

	@Test
	void comesBackToTheHistoryOfTheFolderInItsTerminal() throws Exception {
		assertComesBackToTheLastConversation("aider", "--restore-chat-history");
	}

	@Test
	void isNotOfferedTheMcpServerOfEclipse() throws Exception {
		assertNull(Harnesses.AIDER.mcp(), "Aider knows no MCP server");
		eclipseMcpRuns();
		assertEquals(new Start("", ""), started(open(program("aider")), 1));
		assertTrue(asked.isEmpty());
	}
}
