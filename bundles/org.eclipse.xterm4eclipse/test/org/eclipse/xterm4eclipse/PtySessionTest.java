package org.eclipse.xterm4eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.eclipse.cdt.utils.pty.ConPTY;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

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
		session = new PtySession(command, TestWorkbench.FOLDER, 100, 30, this);
		return session;
	}

	/** The bash of the tests, without the settings of the user. */
	private PtySession startShell() throws Exception {
		return start(TestWorkbench.BASH, "--norc", "--noprofile");
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

	/**
	 * The pseudo console of Windows, which the pseudo terminals of CDT use there. Outside of Eclipse
	 * CDT cannot report why it does not start: this test does.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void pseudoConsoleOfWindowsStarts() throws Exception {
		new ConPTY().close();
	}

	@Test
	void shellRunsInATerminalOfTheRequestedSize() throws Exception {
		startShell();
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
		startShell();
		await("prompt", () -> output().contains("$"));
		assertFalse(session.isBusy());
		send("sleep 1\n");
		await("busy", session::isBusy);
		await("idle again", () -> !session.isBusy());
	}

	@Test
	void currentDirectoryFollowsTheShell() throws Exception {
		startShell();
		if (TestWorkbench.WINDOWS) {
			// Windows does not tell: the view follows what the shell announces.
			assertNull(session.currentDirectory());
			return;
		}
		assertEquals(TestWorkbench.FOLDER, session.currentDirectory().getCanonicalFile());
		send("cd '" + TestWorkbench.OTHER_FOLDER + "'\n");
		await("cd", () -> TestWorkbench.OTHER_FOLDER.equals(session.currentDirectory()));
	}

	@Test
	void exitIsReportedWithItsCodeAfterTheOutput() throws Exception {
		startShell();
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
		startShell();
		send("sleep 20 &\n");
		send("exit 4\n");
		await("exit", () -> exitCode.get() != null);
		assertEquals(4, exitCode.get());
	}

	@Test
	void disposeKillsTheShellWithoutReportingAnExit() throws Exception {
		startShell();
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
		assertTrue(unix.stream().noneMatch(entry -> entry.startsWith("CHERE_INVOKING=")));
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
		assertTrue(powershell.contains("CHERE_INVOKING=1"), "Git Bash stays in the directory it is started in");
	}

	@Test
	void bashOfWindowsAnnouncesItsDirectoryBeforeEachPrompt() {
		String function = PtySession.PROMPT_FUNCTION_VARIABLE
				+ "=() { local status=$?; printf '\\e]9;9;%s\\e\\\\' \"$PWD\"; return $status; }";
		List<String> bash = List.of(PtySession.environment(
				new String[] {"C:\\Program Files\\Git\\usr\\bin\\bash.exe", "--login"}, "Windows 11", Map.of()));
		assertTrue(bash.contains(function), bash.toString());
		assertTrue(bash.contains("PROMPT_COMMAND=__xterm4eclipse_prompt"));
		// Before the one of the user, which still runs.
		List<String> own = List.of(PtySession.environment(new String[] {"bash"}, "Windows 11",
				Map.of("PROMPT_COMMAND", "history -a")));
		assertTrue(own.contains("PROMPT_COMMAND=__xterm4eclipse_prompt; history -a"), own.toString());
		// Elsewhere the directory is read from the system.
		List<String> linux = List.of(PtySession.environment(new String[] {"/bin/bash"}, "Linux", Map.of()));
		assertTrue(linux.stream().noneMatch(entry -> entry.startsWith("PROMPT_COMMAND=") || entry.startsWith("BASH_FUNC_")));
	}
}
