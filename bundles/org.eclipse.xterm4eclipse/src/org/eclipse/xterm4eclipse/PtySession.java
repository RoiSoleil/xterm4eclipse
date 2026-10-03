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
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.cdt.utils.pty.PTY;
import org.eclipse.cdt.utils.spawner.ProcessFactory;

import com.sun.jna.Library;
import com.sun.jna.Native;

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
	/**
	 * The terminal side of the pseudo terminal, held open on macOS until the shell has opened it:
	 * macOS clears the size of a terminal that nobody has open, when it is opened.
	 */
	private final AtomicInteger heldTerminal = new AtomicInteger(-1);
	private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows"); //$NON-NLS-1$ //$NON-NLS-2$
	/**
	 * Windows: the shell announces its prompts (its directory, OSC 9;9 or OSC 7). A command runs
	 * from the Enter that starts it to the next prompt: the processes of a command of Git Bash are
	 * not children of the shell for Windows.
	 */
	private volatile boolean announcesPrompts;
	private volatile boolean commandEntered;
	/** The end of the last output, where the start of an announcement may be. */
	private String outputTail = ""; //$NON-NLS-1$
	private final OutputStream stdin;
	private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> daemon(r, "Xterm PTY writer")); //$NON-NLS-1$
	private volatile boolean alive = true;
	private Listener listener;

	/** How long the output may keep coming after the shell has exited. */
	private static final long DRAIN_MILLIS = 500;

	PtySession(String[] command, File workingDirectory, int cols, int rows, Listener listener) throws IOException {
		pty = new PTY(PTY.Mode.TERMINAL);
		heldTerminal.set(MacTerminal.hold(pty.getSlaveName()));
		pty.setTerminalSize(cols, rows);
		try {
			process = ProcessFactory.getFactory().exec(command, environment(command), workingDirectory, pty);
		} catch (IOException | RuntimeException e) {
			releaseTerminal();
			throw e;
		}
		// The size set before exec is not always honoured, set it again once the child exists.
		pty.setTerminalSize(cols, rows);
		stdin = pty.getOutputStream();
		Thread reader = daemon(this::pump, "Xterm PTY reader"); //$NON-NLS-1$
		this.listener = listener;
		reader.start();
		daemon(() -> awaitExit(reader), "Xterm PTY exit watcher").start(); //$NON-NLS-1$
	}

	boolean isAlive() {
		return alive;
	}

	void write(byte[] data) {
		if (!alive) {
			return;
		}
		for (byte b : data) {
			if (b == '\r') {
				commandEntered = true;
				break;
			}
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
			// Windows and macOS: the shell is busy while it has child processes, or on Windows from the
			// Enter of a command to the next prompt when the shell announces them.
			return (WINDOWS && announcesPrompts && commandEntered)
					|| ProcessHandle.of(pid).map(handle -> handle.children().findAny().isPresent()).orElse(false);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/** Called on the reader thread with each output. */
	private void notePrompts(byte[] data, int length) {
		String text = outputTail + new String(data, 0, length, StandardCharsets.ISO_8859_1);
		if (text.contains("\u001b]9;9;") || text.contains("\u001b]7;")) { //$NON-NLS-1$ //$NON-NLS-2$
			announcesPrompts = true;
			commandEntered = false;
		}
		outputTail = text.substring(Math.max(0, text.length() - 5));
	}

	void resize(int cols, int rows) {
		if (alive && cols > 0 && rows > 0) {
			pty.setTerminalSize(cols, rows);
		}
	}

	void dispose() {
		alive = false;
		releaseTerminal();
		writer.shutdownNow();
		// Spawner.destroy() waits for the process to die: never do that on the UI thread.
		daemon(process::destroy, "Xterm PTY terminator").start(); //$NON-NLS-1$
	}

	private void pump() {
		byte[] buffer = new byte[16 * 1024];
		try (InputStream in = pty.getInputStream()) {
			int n;
			while ((n = in.read(buffer)) != -1) {
				if (n > 0) {
					// The shell writes to its terminal: it has it open.
					releaseTerminal();
					if (WINDOWS) {
						notePrompts(buffer, n);
					}
					listener.output(this, buffer, n);
				}
			}
		} catch (IOException e) {
			// Reading from a closed PTY fails with EIO on Linux: this is the normal end of stream.
		}
	}

	/**
	 * The session ends when the shell exits. The end of the output cannot be used for that: on
	 * Windows the stream of a pseudo console stays open after the process is gone, and on Unix a
	 * background job left by the shell keeps the terminal open.
	 */
	private void awaitExit(Thread reader) {
		int exitCode = -1;
		try {
			exitCode = process.waitFor();
			// Else the end of the output never comes.
			releaseTerminal();
			// Let the last output through before the exit is reported.
			reader.join(DRAIN_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		boolean wasAlive = alive;
		alive = false;
		writer.shutdownNow();
		if (reader.isAlive()) {
			try {
				pty.getInputStream().close();
			} catch (IOException e) {
				// Nothing more to read anyway.
			}
		}
		if (wasAlive) {
			listener.exited(this, exitCode);
		}
	}

	private void releaseTerminal() {
		MacTerminal.release(heldTerminal.getAndSet(-1));
	}

	/**
	 * Opens the terminal side of pseudo terminals on macOS, through the C library and JNA, which CDT
	 * brings for the pseudo consoles of Windows. Without JNA the size of a new shell may stay 0 x 0
	 * until the view is resized.
	 */
	static final class MacTerminal {

		private static final boolean MAC = System.getProperty("os.name", "").startsWith("Mac"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		/** O_RDONLY | O_NOCTTY of macOS: the terminal does not become the one of Eclipse. */
		private static final int READ_ONLY_NOT_CONTROLLING = 0x20000;

		private MacTerminal() {
		}

		/** @return the file descriptor of the opened terminal, or -1 */
		static int hold(String name) {
			if (!MAC || name == null) {
				return -1;
			}
			try {
				return Libc.INSTANCE.open(name, READ_ONLY_NOT_CONTROLLING);
			} catch (LinkageError | RuntimeException e) {
				return -1;
			}
		}

		static void release(int fd) {
			if (fd >= 0) {
				try {
					Libc.INSTANCE.close(fd);
				} catch (LinkageError | RuntimeException e) {
					// A library that does not work could not have opened it.
				}
			}
		}

		/** Loaded on first use only: JNA is optional. */
		private interface Libc extends Library {
			Libc INSTANCE = Native.load("c", Libc.class); //$NON-NLS-1$

			int open(String path, int flags);

			int close(int fd);
		}
	}

	private static String[] environment(String[] command) {
		return environment(command, System.getProperty("os.name", ""), System.getenv(), //$NON-NLS-1$ //$NON-NLS-2$
				XtermPlugin.preference(XtermPlugin.PREF_ENVIRONMENT));
	}

	static String[] environment(String[] command, String os, Map<String, String> inherited) {
		return environment(command, os, inherited, ""); //$NON-NLS-1$
	}

	/**
	 * @param userSetting
	 *            the variables the user gives to the shells, see {@link ShellEnvironment}
	 */
	/** The function that announces the directory of bash on Windows, exported as bash reads it. */
	private static final String PROMPT_FUNCTION = "__xterm4eclipse_prompt"; //$NON-NLS-1$
	static final String PROMPT_FUNCTION_VARIABLE = "BASH_FUNC_" + PROMPT_FUNCTION + "%%"; //$NON-NLS-1$ //$NON-NLS-2$

	static String[] environment(String[] command, String os, Map<String, String> inherited, String userSetting) {
		Map<String, String> env = new HashMap<>(inherited);
		env.put("TERM", "xterm-256color"); //$NON-NLS-1$ //$NON-NLS-2$
		env.put("COLORTERM", "truecolor"); //$NON-NLS-1$ //$NON-NLS-2$
		env.put("TERM_PROGRAM", "xterm4eclipse"); //$NON-NLS-1$ //$NON-NLS-2$
		if (os.toLowerCase().contains("win")) { //$NON-NLS-1$
			// Git Bash, MSYS2 and Cygwin login shells go to the home directory unless told to stay in
			// the one they are started in.
			env.putIfAbsent("CHERE_INVOKING", "1"); //$NON-NLS-1$ //$NON-NLS-2$
			String program = command[0].toLowerCase();
			if (program.endsWith("cmd.exe")) { //$NON-NLS-1$
				// Make cmd.exe announce its directory (OSC 9;9) in front of the usual prompt, so that
				// the view can reopen in the same place: there is no way to ask Windows for it.
				env.putIfAbsent("PROMPT", "$E]9;9;$P$E\\$P$G"); //$NON-NLS-1$ //$NON-NLS-2$
			} else if (program.endsWith("bash.exe") || program.endsWith("bash")) { //$NON-NLS-1$ //$NON-NLS-2$
				// The same for the bash of Git for Windows, MSYS2 or Cygwin, before each prompt and
				// without changing the status of the last command ($?), which a prompt may show.
				env.put(PROMPT_FUNCTION_VARIABLE,
						"() { local status=$?; printf '\\e]9;9;%s\\e\\\\' \"$PWD\"; return $status; }"); //$NON-NLS-1$
				String existing = env.get("PROMPT_COMMAND"); //$NON-NLS-1$
				env.put("PROMPT_COMMAND", existing == null || existing.isBlank() ? PROMPT_FUNCTION //$NON-NLS-1$
						: PROMPT_FUNCTION + "; " + existing); //$NON-NLS-1$
			}
		} else {
			env.putIfAbsent("LANG", "en_US.UTF-8"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		// Last: the user may also change what is set above.
		ShellEnvironment.apply(env, userSetting, os.toLowerCase().contains("win")); //$NON-NLS-1$
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
