package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.xterm4eclipse.XtermView;
import org.junit.jupiter.api.Test;

/** The CLI of Cursor: the last conversation, and no command for the MCP servers. */
class CursorCliTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.CURSOR;
	}

	@Test
	void isRecognizedUnderItsOwnNameOnly() throws Exception {
		assertRecognized("cursor-agent", "icons/cursor.png");
		assertOffered("cursor-agent");
		// Its other name could be any program.
		assertNotRecognized("agent", "/bin/zsh -l -i -c agent");
	}

	@Test
	void continuesTheLastConversationOnceTheUserHasTalked() {
		assertArrayEquals(line("cursor-agent"), new Run("cursor-agent").start());
		assertArrayEquals(line("cursor-agent"), new Run("cursor-agent").resume());
		assertArrayEquals(line("cursor-agent", "--continue"), new Run("cursor-agent").talked().resume());
		assertArrayEquals(line("/bin/zsh", "-l", "-i", "-c", "cursor-agent --continue"),
				new Run("/bin/zsh", "-l", "-i", "-c", "cursor-agent").talked().resume());
		assertLeftAlone(line("cursor-agent", "--resume", "abc"), line("cursor-agent", "--continue"),
				line("cursor-agent", "resume"), line("cursor-agent", "ls"), line("cursor-agent", "-p", "explain"),
				line("cursor-agent", "mcp", "list"), line("cursor-agent", "create-chat"));
	}

	@Test
	void comesBackToTheLastConversationInItsTerminal() throws Exception {
		assertComesBackToTheLastConversation("cursor-agent", "--continue");
	}

	@Test
	void isNotOfferedTheMcpServerOfEclipse() throws Exception {
		assertNull(Harnesses.CURSOR.mcp(), "its servers are only in its files");
		eclipseMcpRuns();
		XtermView view = open(program("cursor-agent"));
		assertEquals(new Start("", ""), started(view, 1));
		assertTrue(asked.isEmpty());
		assertEquals(List.of(""), calls());
	}
}
