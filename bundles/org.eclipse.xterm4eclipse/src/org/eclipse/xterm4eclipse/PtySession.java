package org.eclipse.xterm4eclipse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.cdt.utils.pty.PTY;
import org.eclipse.cdt.utils.spawner.ProcessFactory;

/**
 * A shell process attached to a pseudo terminal.
 */
final class PtySession {

	interface Listener {
		/** Called on the reader thread; may block to apply back pressure. */
		void output(PtySession session, byte[] data, int length);

		/** Called on the reader thread once all output has been delivered. */
		void exited(PtySession session, int exitCode);
	}

	private final PTY pty;
	private final Process process;
	private final OutputStream stdin;
	private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> daemon(r, "Xterm PTY writer")); //$NON-NLS-1$
	private volatile boolean alive = true;

	PtySession(String[] command, File workingDirectory, int cols, int rows, Listener listener) throws IOException {
		pty = new PTY(PTY.Mode.TERMINAL);
		pty.setTerminalSize(cols, rows);
		process = ProcessFactory.getFactory().exec(command, environment(command), workingDirectory, pty);
		// The size set before exec is not always honoured, set it again once the child exists.
		pty.setTerminalSize(cols, rows);
		stdin = pty.getOutputStream();
		daemon(() -> pump(listener), "Xterm PTY reader").start(); //$NON-NLS-1$
	}

	boolean isAlive() {
		return alive;
	}

	void write(byte[] data) {
		if (!alive) {
			return;
		}
		writer.execute(() -> {
			try {
				stdin.write(data);
				stdin.flush();
			} catch (IOException e) {
				// The process is gone, the reader thread reports the exit.
			}
		});
	}

	/**
	 * @return the current working directory of the shell, or {@code null} if it cannot be determined
	 *         (always the case on Windows)
	 */
	File currentDirectory() {
		if (!alive) {
			return null;
		}
		try {
			long pid = process.pid();
			String os = System.getProperty("os.name", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
			if (os.contains("linux")) { //$NON-NLS-1$
				return Files.readSymbolicLink(Path.of("/proc", Long.toString(pid), "cwd")).toFile(); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (os.contains("mac")) { //$NON-NLS-1$
				Process lsof = new ProcessBuilder("lsof", "-a", "-d", "cwd", "-p", Long.toString(pid), "-Fn") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
						.redirectErrorStream(true).start();
				String output = new String(lsof.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				for (String line : output.split("\n")) { //$NON-NLS-1$
					if (line.startsWith("n/")) { //$NON-NLS-1$
						return new File(line.substring(1));
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// Fall through: the caller keeps the directory the shell was started in.
		}
		return null;
	}

	/**
	 * @return {@code true} while a command runs in the foreground of the terminal, {@code false} when
	 *         the shell is waiting at its prompt
	 */
	boolean isBusy() {
		if (!alive) {
			return false;
		}
		try {
			long pid = process.pid();
			Path stat = Path.of("/proc", Long.toString(pid), "stat"); //$NON-NLS-1$ //$NON-NLS-2$
			if (Files.isReadable(stat)) {
				// Linux: the shell is busy when another process group owns the terminal.
				// Fields after the command name: state ppid pgrp session tty_nr tpgid
				String content = Files.readString(stat);
				String[] fields = content.substring(content.lastIndexOf(')') + 2).split(" "); //$NON-NLS-1$
				return !fields[5].equals(fields[2]) && !fields[5].startsWith("-") && !fields[5].equals("0"); //$NON-NLS-1$ //$NON-NLS-2$
			}
			// Windows and macOS: the shell is busy while it has child processes.
			return ProcessHandle.of(pid).map(handle -> handle.children().findAny().isPresent()).orElse(false);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	void resize(int cols, int rows) {
		if (alive && cols > 0 && rows > 0) {
			pty.setTerminalSize(cols, rows);
		}
	}

	void dispose() {
		alive = false;
		writer.shutdownNow();
		// Spawner.destroy() waits for the process to die: never do that on the UI thread.
		daemon(process::destroy, "Xterm PTY terminator").start(); //$NON-NLS-1$
	}

	private void pump(Listener listener) {
		byte[] buffer = new byte[16 * 1024];
		try (InputStream in = pty.getInputStream()) {
			int n;
			while ((n = in.read(buffer)) != -1) {
				if (n > 0) {
					listener.output(this, buffer, n);
				}
			}
		} catch (IOException e) {
			// Reading from a closed PTY fails with EIO on Linux: this is the normal end of stream.
		}
		int exitCode = -1;
		try {
			exitCode = process.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		boolean wasAlive = alive;
		alive = false;
		writer.shutdownNow();
		if (wasAlive) {
			listener.exited(this, exitCode);
		}
	}

	private static String[] environment(String[] command) {
		return environment(command, System.getProperty("os.name", ""), System.getenv()); //$NON-NLS-1$ //$NON-NLS-2$
	}

	static String[] environment(String[] command, String os, Map<String, String> inherited) {
		Map<String, String> env = new HashMap<>(inherited);
		env.put("TERM", "xterm-256color"); //$NON-NLS-1$ //$NON-NLS-2$
		env.put("COLORTERM", "truecolor"); //$NON-NLS-1$ //$NON-NLS-2$
		env.put("TERM_PROGRAM", "xterm4eclipse"); //$NON-NLS-1$ //$NON-NLS-2$
		if (os.toLowerCase().contains("win")) { //$NON-NLS-1$
			if (command[0].toLowerCase().endsWith("cmd.exe")) { //$NON-NLS-1$
				// Make cmd.exe announce its directory (OSC 9;9) in front of the usual prompt, so that
				// the view can reopen in the same place: there is no way to ask Windows for it.
				env.putIfAbsent("PROMPT", "$E]9;9;$P$E\\$P$G"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		} else {
			env.putIfAbsent("LANG", "en_US.UTF-8"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		List<String> result = new ArrayList<>(env.size());
		env.forEach((key, value) -> result.add(key + '=' + value));
		return result.toArray(String[]::new);
	}

	private static Thread daemon(Runnable runnable, String name) {
		Thread thread = new Thread(runnable, name);
		thread.setDaemon(true);
		return thread;
	}
}
