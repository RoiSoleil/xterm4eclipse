package org.eclipse.xterm4eclipse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.function.Consumer;

/**
 * A terminal on its way from one part to another, for example from the view to the editor area: the
 * same shell keeps running, the screen is shown again in the new part.
 * <p>
 * Between the moment the old part lets the shell go and the moment the new one takes it, the output
 * and the exit of the shell are kept here, then handed over in order. After the hand-over, late calls
 * go straight to the new part.
 */
final class TerminalTransfer implements PtySession.Listener {

	/** The running shell, or {@code null} if it had already ended: the new part starts a new one. */
	final PtySession session;
	/** The screen of the old part, as escape sequences that draw it again (see xtermSnapshot). */
	final String screen;
	final String commandLine;
	final File directory;
	/** The name the user gave to the terminal, or {@code null}. */
	final String name;
	final long sessionStart;
	/** The size of the old terminal: the screen is drawn again at this size, then fitted. */
	final int cols;
	final int rows;
	/** The tab of the old part told that a command had finished or a program asked for attention. */
	boolean attention;

	private static final int MAX_KEPT_BYTES = 1024 * 1024;

	private final ByteArrayOutputStream output = new ByteArrayOutputStream();
	private boolean exited;
	private int exitCode;
	private PtySession.Listener target;

	TerminalTransfer(PtySession session, String screen, int cols, int rows, String commandLine, File directory,
			String name, long sessionStart) {
		this.session = session;
		this.screen = screen == null ? "" : screen; //$NON-NLS-1$
		this.commandLine = commandLine;
		this.directory = directory;
		this.name = name;
		this.sessionStart = sessionStart;
		this.cols = Math.max(2, cols);
		this.rows = Math.max(1, rows);
		if (session != null) {
			session.setListener(this);
		}
	}

	@Override
	public void output(PtySession source, byte[] data, int length) {
		PtySession.Listener receiver;
		synchronized (this) {
			// Back pressure, as in the view: a program that prints without end must not fill the memory
			// while no part shows it.
			while (target == null && output.size() > MAX_KEPT_BYTES && source.isAlive()) {
				try {
					wait(1000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			receiver = target;
			if (receiver == null) {
				// The caller reuses its buffer: keep a copy.
				output.write(data, 0, length);
				return;
			}
		}
		receiver.output(source, data, length);
	}

	@Override
	public void exited(PtySession source, int code) {
		PtySession.Listener receiver;
		synchronized (this) {
			receiver = target;
			if (receiver == null) {
				exited = true;
				exitCode = code;
				return;
			}
		}
		receiver.exited(source, code);
	}

	/**
	 * Gives the shell to its new part: first what it printed in the meantime, then its exit if it has
	 * ended, and from then on everything directly.
	 *
	 * @param replay
	 *            shows the output kept meanwhile; called on the calling thread, it must not block
	 *            (the caller is the UI thread, which the back pressure of {@code receiver} waits for)
	 */
	void handOver(PtySession.Listener receiver, Consumer<byte[]> replay) {
		boolean ended;
		int code;
		synchronized (this) {
			if (target != null) {
				throw new IllegalStateException("Already handed over"); //$NON-NLS-1$
			}
			byte[] pending = output.toByteArray();
			output.reset();
			if (pending.length > 0) {
				replay.accept(pending);
			}
			ended = exited;
			code = exitCode;
			// The reader thread waits on this lock: what it delivers from now on comes after the
			// replayed output.
			target = receiver;
			notifyAll();
		}
		if (session != null) {
			session.setListener(receiver);
		}
		if (ended) {
			receiver.exited(session, code);
		}
	}
}
