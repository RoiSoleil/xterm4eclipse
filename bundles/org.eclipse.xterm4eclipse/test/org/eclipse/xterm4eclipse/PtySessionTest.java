package org.eclipse.xterm4eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PtySessionTest implements PtySession.Listener {

	private final ByteArrayOutputStream received = new ByteArrayOutputStream();
	private final AtomicReference<Integer> exitCode = new AtomicReference<>();
	private PtySession session;

	@Override
	public synchronized void output(PtySession source, byte[] data, int length) {
		received.write(data, 0, length);
	}

	@Override
	public void exited(PtySession source, int code) {
		exitCode.set(code);
	}

	private synchronized String output() {
		return received.toString(StandardCharsets.UTF_8);
	}

	private PtySession start(String... command) throws Exception {
		session = new PtySession(command, new File("/tmp"), 100, 30, this);
		return session;
	}

	private void send(String text) {
		session.write(text.getBytes(StandardCharsets.UTF_8));
	}

	private static void await(String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10000;
		while (!condition.getAsBoolean()) {
			assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for: " + what);
			Thread.sleep(20);
		}
	}

	@AfterEach
	void stop() {
		if (session != null) {
			session.dispose();
		}
	}

	@Test
	void shellRunsInATerminalOfTheRequestedSize() throws Exception {
		start("/bin/sh");
		send("stty size; echo term=$TERM color=$COLORTERM é\n");
		await("command output", () -> output().contains("term=xterm-256color color=truecolor é"));
		assertTrue(output().contains("30 100"), output());

		session.resize(120, 40);
		session.resize(0, 0);
		send("stty size\n");
		await("new size", () -> output().contains("40 120"));
	}

	@Test
	void busyWhileACommandRunsInTheForeground() throws Exception {
		start("/bin/bash", "--norc");
		await("prompt", () -> output().contains("$"));
		assertFalse(session.isBusy());
		send("sleep 1\n");
		await("busy", session::isBusy);
		await("idle again", () -> !session.isBusy());
	}

	@Test
	void currentDirectoryFollowsTheShell() throws Exception {
		start("/bin/sh");
		assertEquals(new File("/tmp").getCanonicalFile(), session.currentDirectory().getCanonicalFile());
		send("cd /usr\n");
		await("cd", () -> new File("/usr").equals(session.currentDirectory()));
	}

	@Test
	void exitIsReportedWithItsCodeAfterTheOutput() throws Exception {
		start("/bin/sh");
		send("echo bye; exit 7\n");
		await("exit", () -> exitCode.get() != null);
		assertEquals(7, exitCode.get());
		assertTrue(output().contains("bye"));
		assertFalse(session.isAlive());
		assertFalse(session.isBusy());
		assertNull(session.currentDirectory());
		send("ignored");
		session.resize(10, 10);
	}

	@Test
	void exitIsReportedEvenIfABackgroundJobKeepsTheTerminalOpen() throws Exception {
		// The same situation as on Windows, where the stream of the terminal outlives the shell.
		start("/bin/sh");
		send("sleep 20 &\n");
		send("exit 4\n");
		await("exit", () -> exitCode.get() != null);
		assertEquals(4, exitCode.get());
	}

	@Test
	void disposeKillsTheShellWithoutReportingAnExit() throws Exception {
		start("/bin/sh");
		await("alive", session::isAlive);
		session.dispose();
		assertFalse(session.isAlive());
		Thread.sleep(1500);
		assertNull(exitCode.get());
	}

	@Test
	void environmentDescribesTheTerminal() {
		List<String> unix = List.of(PtySession.environment(new String[] {"/bin/sh"}, "Linux", Map.of("HOME", "/h")));
		assertTrue(unix.containsAll(List.of("HOME=/h", "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=en_US.UTF-8")));
		List<String> keepsLang = List
				.of(PtySession.environment(new String[] {"/bin/sh"}, "Mac OS X", Map.of("LANG", "fr_FR.UTF-8")));
		assertTrue(keepsLang.contains("LANG=fr_FR.UTF-8"));
	}

	@Test
	void cmdAnnouncesItsDirectoryThroughThePrompt() {
		List<String> cmd = List
				.of(PtySession.environment(new String[] {"C:\\Windows\\System32\\cmd.exe"}, "Windows 11", Map.of()));
		assertTrue(cmd.contains("PROMPT=$E]9;9;$P$E\\$P$G"), cmd.toString());
		assertTrue(cmd.stream().noneMatch(entry -> entry.startsWith("LANG=")));

		List<String> custom = List.of(PtySession.environment(new String[] {"cmd.exe"}, "Windows 11", Map.of("PROMPT", "$G")));
		assertTrue(custom.contains("PROMPT=$G"));
		List<String> powershell = List.of(PtySession.environment(new String[] {"pwsh.exe"}, "Windows 11", Map.of()));
		assertTrue(powershell.stream().noneMatch(entry -> entry.startsWith("PROMPT=")));
	}
}
