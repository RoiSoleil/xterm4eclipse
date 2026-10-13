package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.xterm4eclipse.TestWorkbench;
import org.eclipse.xterm4eclipse.XtermView;
import org.junit.jupiter.api.Test;

/** goose of Block: the terminal names the conversation, of the command "session". */
class GooseTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.GOOSE;
	}

	@Test
	void isRecognizedAndOffered() throws Exception {
		assertRecognized("goose", "icons/goose.png");
		assertOffered("goose");
	}

	@Test
	void namesTheConversationOfTheTerminalAndResumesItOnceTheUserHasTalked() {
		// A bare goose runs a conversation: named, it is the one of its command "session".
		assertArrayEquals(line("goose", "session", "--name", ID), new Run("goose").with(ID).start());
		assertArrayEquals(line("goose", "session", "--name", ID), new Run("goose", "session").with(ID).start());
		assertArrayEquals(line("goose", "session", "--name", ID), new Run("goose").with(ID).resume(), "nothing said yet");
		assertArrayEquals(line("goose", "session", "--resume", "--name", ID), new Run("goose").with(ID).talked().resume());
		assertArrayEquals(line("goose", "session", "--max-turns", "5", "--resume", "--name", ID),
				new Run("goose", "session", "--max-turns", "5").with(ID).talked().resume());
		assertArrayEquals(line("/bin/zsh", "-l", "-i", "-c", "goose session --name " + ID),
				new Run("/bin/zsh", "-l", "-i", "-c", "goose").with(ID).start());
		String[] fresh = new Run("goose").start();
		assertTrue(fresh[3].matches(Harness.Sessions.UUID), fresh[3]);
		assertLeftAlone(line("goose", "session", "--name", "mine"), line("goose", "session", "-n", "mine"),
				line("goose", "session", "-r"), line("goose", "session", "--resume", "--session-id", "20260101_1"),
				line("goose", "session", "list"), line("goose", "run", "-i", "plan.md"), line("goose", "configure"),
				line("goose", "session", "--debug", "extra"));
	}

	@Test
	void conversationThatCannotBeResumedGetsANewName() {
		Run run = new Run("goose").with(ID).talked();
		assertArrayEquals(line("goose", "session", "--resume", "--name", ID), run.resume());
		run.companion.exited(1);
		String[] renewed = run.resume();
		assertEquals(List.of("goose", "session", "--name"), List.of(renewed).subList(0, 3));
		assertNotEquals(ID, renewed[3], "failed at once: a new conversation");
	}

	@Test
	void comesBackToItsOwnConversationInItsTerminal() throws Exception {
		XtermView view = open(program("goose"));
		String started = started(view, 1).arguments();
		assertTrue(started.matches("session --name " + Harness.Sessions.UUID), started);
		String id = started.substring("session --name ".length());
		assertEquals(started, started(reopen(view), 1).arguments(), "nothing said: the same conversation starts");
		say(view, "hello");
		XtermView back = reopen(view);
		assertEquals("session --resume --name " + id, started(back, 1).arguments());
		TestWorkbench.restart(back);
		String renewed = started(back, 2).arguments();
		assertTrue(renewed.matches("session --name " + Harness.Sessions.UUID), renewed);
		assertNotEquals(started, renewed);
	}

	@Test
	void isNotOfferedTheMcpServerOfEclipse() throws Exception {
		assertNull(Harnesses.GOOSE.mcp(), "its extensions are only in its files");
		eclipseMcpRuns();
		assertTrue(started(open(program("goose")), 1).arguments().startsWith("session --name "));
		assertTrue(asked.isEmpty());
	}
}
