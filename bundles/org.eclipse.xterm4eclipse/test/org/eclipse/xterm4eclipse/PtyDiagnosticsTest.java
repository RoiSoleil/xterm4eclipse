package org.eclipse.xterm4eclipse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Temporary: what the shell of the tests looks like on the system of the CI. */
@EnabledIfEnvironmentVariable(named = "XTERM_DIAGNOSE", matches = "1")
class PtyDiagnosticsTest implements PtySession.Listener {

	private final ByteArrayOutputStream received = new ByteArrayOutputStream();

	@Override
	public synchronized void output(PtySession source, byte[] data, int length) {
		received.write(data, 0, length);
	}

	@Override
	public void exited(PtySession source, int code) {
		System.out.println("EXIT " + code);
	}

	private synchronized String drain() {
		String text = received.toString(StandardCharsets.UTF_8).replace("\u001b", "\\e").replace("\u0007", "\\a")
				.replace("\r", "\\r").replace("\n", "\\n\n");
		received.reset();
		return text;
	}

	private static void tree(String label, long pid) {
		System.out.println("== " + label);
		ProcessHandle.of(pid).ifPresentOrElse(handle -> {
			System.out.println("shell " + pid + " " + handle.info().command().orElse("?"));
			handle.descendants().forEach(child -> System.out.println("  descendant " + child.pid() + " parent="
					+ child.parent().map(ProcessHandle::pid).orElse(-1L) + " " + child.info().command().orElse("?")));
		}, () -> System.out.println("no handle for " + pid));
	}

	@Test
	void describe() throws Exception {
		File directory = new File(System.getProperty("java.io.tmpdir")).getCanonicalFile();
		PtySession session = new PtySession(new String[] {TestWorkbench.BASH, "--norc", "--noprofile"}, directory, 100,
				30, this);
		Thread.sleep(3000);
		System.out.println("== start output\n" + drain());
		long pid = processPid(session);
		tree("idle, busy=" + session.isBusy(), pid);
		session.write("sleep 5\r".getBytes(StandardCharsets.UTF_8));
		Thread.sleep(1500);
		tree("sleep 5, busy=" + session.isBusy(), pid);
		Thread.sleep(5000);
		tree("after sleep, busy=" + session.isBusy(), pid);
		session.write("cd /usr; echo pwd=$PWD; echo W=$(pwd -W)\r".getBytes(StandardCharsets.UTF_8));
		Thread.sleep(1500);
		System.out.println("== cd output\n" + drain());
		session.dispose();
	}

	private static long processPid(PtySession session) throws Exception {
		java.lang.reflect.Field field = PtySession.class.getDeclaredField("process");
		field.setAccessible(true);
		return ((Process) field.get(session)).pid();
	}
}
