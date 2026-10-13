package org.eclipse.xterm4eclipse.harness;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.swt.widgets.Display;
import org.eclipse.xterm4eclipse.harness.Harness.Run;
import org.eclipse.xterm4eclipse.harness.Harness.Sessions;
import org.eclipse.xterm4eclipse.spi.Terminal;
import org.eclipse.xterm4eclipse.spi.TerminalCompanion;

/**
 * A coding agent in a terminal: it is offered the MCP server of Eclipse when the user opens the
 * terminal, and it comes back to the conversation of the terminal when Eclipse reopens it, or when
 * the user starts it again after an error. Restarted by the user, it starts a new conversation.
 */
final class HarnessCompanion implements TerminalCompanion {

	/** An agent that fails this soon after it was asked to resume could not resume. */
	static final long FAILED_RESUME_MILLIS = 10_000;
	/** How many times an agent is asked for its conversations, to find the one of the terminal. */
	private static final int MAX_SEARCHES = 5;
	private static final String YES = "true"; //$NON-NLS-1$

	private final Terminal terminal;
	private final Harness harness;
	/** When the agent was started last. */
	private long started;
	/** The agent was started last to resume a conversation that it may not have kept. */
	private boolean resumed;
	/** It could not: the next start is a new conversation. */
	private boolean resumeFailed;
	private boolean searching;
	private int searches;

	HarnessCompanion(Terminal terminal, Harness harness) {
		this.terminal = terminal;
		this.harness = harness;
		String id = terminal.state(harness.sessionKey());
		if (harness.sessions().list() != null && harness.sessions().isId(id)) {
			SessionFinder.take(id);
		}
	}

	@Override
	public boolean beforeStart(Runnable start) {
		Harnesses.Found found = Harnesses.find(terminal.commandLine());
		Display display = Display.getCurrent();
		return found != null && display != null && McpOffer.offer(terminal, harness, found.invocation(), display, start);
	}

	@Override
	public String[] arguments(String[] arguments, boolean resume) {
		started = System.currentTimeMillis();
		resumed = false;
		Harnesses.Found found = Harnesses.find(arguments);
		if (found == null || found.harness() != harness) {
			return arguments;
		}
		List<String> own = found.invocation().arguments();
		List<String> added = conversation(own, resume);
		if (added == null) {
			resumed = false;
			return arguments;
		}
		String[] changed = found.invocation().appended(harness.conversation(own, added));
		return changed == null ? arguments : changed;
	}

	/**
	 * @param own
	 *            the arguments that the command line gives to the agent
	 * @return the arguments to add for the conversation of the terminal, or {@code null} for none
	 */
	private List<String> conversation(List<String> own, boolean resume) {
		Sessions sessions = harness.sessions();
		Run run = harness.run(own);
		if (run == Run.OTHER) {
			return null;
		}
		boolean back = resume && !resumeFailed;
		if (resume && resumeFailed) {
			// The conversation could not be resumed: forgotten.
			renewed();
		}
		String id = terminal.state(harness.sessionKey());
		if (!sessions.isId(id)) {
			// Checked: it goes into the command line, which a shell may run.
			id = null;
		}
		boolean conversed = YES.equals(terminal.state(harness.conversedKey()));
		List<String> arguments;
		if (sessions.fresh() != null) {
			// The terminal chooses the id: the same conversation each time, also with several
			// terminals of the agent in the same folder.
			if (id == null) {
				id = UUID.randomUUID().toString();
				terminal.setState(harness.sessionKey(), id);
			}
			// Resumed only if there is a conversation: an agent keeps none before the first message.
			boolean kept = sessions.store() != null ? sessions.store().exists(id) : conversed;
			arguments = Sessions.with(back && kept ? sessions.resume() : sessions.fresh(), id);
			resumed = back && kept && sessions.store() == null;
		} else if (back && id != null && sessions.resume() != null) {
			arguments = Sessions.with(sessions.resume(), id);
			resumed = true;
		} else if (back && conversed && sessions.last() != null) {
			// The agent chooses its ids itself: the last conversation of the folder.
			arguments = sessions.last();
			resumed = true;
		} else {
			return null;
		}
		// A subcommand comes after options only, not after a prompt.
		boolean subcommand = harness.interactive() != null || !arguments.get(0).startsWith("-"); //$NON-NLS-1$
		return subcommand && run != Run.CONVERSATION ? null : arguments;
	}

	@Override
	public Map<String, String> environment() {
		if (harness.mcp() != null && harness.mcp().tokenInEnvironment()) {
			// The agent reads the token of the server here, not in its files.
			EclipseMcp.Endpoint endpoint = EclipseMcp.running();
			if (endpoint != null) {
				return Map.of(EclipseMcp.TOKEN_VARIABLE, endpoint.token());
			}
		}
		return Map.of();
	}

	@Override
	public void lineEntered() {
		Sessions sessions = harness.sessions();
		if (sessions.store() != null || sessions.fresh() == null && sessions.last() == null && sessions.list() == null) {
			// What the agent keeps tells if there is a conversation, or it resumes none.
			return;
		}
		if (!YES.equals(terminal.state(harness.conversedKey()))) {
			terminal.setState(harness.conversedKey(), YES);
		}
		if (sessions.list() != null && !searching && searches < MAX_SEARCHES
				&& !sessions.isId(terminal.state(harness.sessionKey()))) {
			search(System.currentTimeMillis());
		}
	}

	/** Asks the agent for its conversations, a moment after the line: the one of the terminal is new. */
	private void search(long entered) {
		Harnesses.Found found = Harnesses.find(terminal.commandLine());
		String[] list = found == null ? null : found.invocation().replaced(harness.sessions().list());
		Display display = Display.getCurrent();
		if (list == null || display == null) {
			return;
		}
		searching = true;
		searches++;
		long since = started;
		File directory = terminal.directory();
		String[] environment = terminal.environment();
		Thread worker = new Thread(() -> {
			try {
				Thread.sleep(SessionFinder.delayMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			Commands.Result result = Commands.run(list, directory, environment);
			String id = result.succeeded()
					? SessionFinder.choose(SessionFinder.parse(result.output(), harness.sessions()), since, entered)
					: null;
			HarnessPlugin.later(display, () -> {
				searching = false;
				if (id == null) {
					return;
				}
				if (terminal.isDisposed() || since != started) {
					// The agent has been started again meanwhile: not its conversation.
					SessionFinder.release(id);
				} else {
					terminal.setState(harness.sessionKey(), id);
				}
			});
		}, "Xterm conversations of " + harness.product()); //$NON-NLS-1$
		worker.setDaemon(true);
		worker.start();
	}

	@Override
	public void exited(int exitCode) {
		resumeFailed = resumed && exitCode != 0 && System.currentTimeMillis() - started < FAILED_RESUME_MILLIS;
		resumed = false;
	}

	@Override
	public void renewed() {
		if (harness.sessions().list() != null) {
			SessionFinder.release(terminal.state(harness.sessionKey()));
		}
		terminal.setState(harness.sessionKey(), null);
		terminal.setState(harness.conversedKey(), null);
		resumeFailed = false;
		searches = 0;
	}
}
