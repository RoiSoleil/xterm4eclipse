package org.eclipse.xterm4eclipse.harness;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.xterm4eclipse.spi.Terminal;

/**
 * When the MCP server of Eclipse runs and a coding agent does not know it yet, asks the user
 * whether to add it, before the agent starts so that it uses it at once.
 */
final class McpOffer {

	/** Answers of the user. */
	static final int ADD = 0;
	static final int NOT_NOW = 1;
	static final int NEVER = 2;
	/** Adds the server, and the next times without asking. */
	static final int ALWAYS = 3;

	/** The answer of each button of the dialog, in their order. */
	private static final int[] BUTTON_ANSWERS = {ADD, ALWAYS, NOT_NOW, NEVER};

	/** Asks the user. */
	@FunctionalInterface
	interface Asker {
		/** @return {@link #ADD}, {@link #ALWAYS}, {@link #NOT_NOW} or {@link #NEVER} */
		int ask(Terminal terminal, Harness harness, String url);
	}

	/** How the user is asked; a test may change it. */
	static Asker asker = McpOffer::ask;
	/** The user answered "Not Now": not asked again until Eclipse restarts. */
	static boolean postponed;

	private McpOffer() {
	}

	/**
	 * @param start
	 *            starts the agent, once the user has answered
	 * @return {@code true} if the agent starts later, {@code false} if there is nothing to ask
	 */
	static boolean offer(Terminal terminal, Harness harness, Invocation invocation, Display display, Runnable start) {
		Harness.Mcp mcp = harness.mcp();
		if (mcp == null || postponed || !HarnessPlugin.preferences().getBoolean(HarnessPlugin.PREF_OFFER_ECLIPSE_MCP)) {
			return false;
		}
		EclipseMcp.Endpoint endpoint = EclipseMcp.running();
		if (endpoint == null || EclipseMcp.configured(mcp, endpoint, terminal.directory())) {
			return false;
		}
		// The same program as the terminal adds the server.
		String[] add = invocation.replaced(mcp.adding(endpoint));
		List<String> removal = mcp.removing();
		String[] remove = removal == null ? null : invocation.replaced(removal);
		if (add == null || removal != null && remove == null) {
			return false;
		}
		// A dialog runs the event loop: not from within the call of the page.
		HarnessPlugin.later(display, () -> {
			if (terminal.isDisposed()) {
				return;
			}
			// Read now: another terminal may have been answered "Always Add" meanwhile.
			boolean withoutAsking = HarnessPlugin.preferences().getBoolean(HarnessPlugin.PREF_ADD_ECLIPSE_MCP_WITHOUT_ASKING);
			int answer = withoutAsking ? ADD : asker.ask(terminal, harness, endpoint.url());
			if (answer == ALWAYS) {
				remember(HarnessPlugin.PREF_ADD_ECLIPSE_MCP_WITHOUT_ASKING, true);
			}
			if (answer == ADD || answer == ALWAYS) {
				add(terminal, harness, add, remove, display, start);
				return;
			}
			if (answer == NEVER) {
				remember(HarnessPlugin.PREF_OFFER_ECLIPSE_MCP, false);
			} else {
				postponed = true;
			}
			start.run();
		});
		return true;
	}

	/** Keeps an answer of the user in the preferences. */
	private static void remember(String preference, boolean value) {
		IPreferenceStore preferences = HarnessPlugin.preferences();
		preferences.setValue(preference, value);
		if (preferences instanceof ScopedPreferenceStore scoped) {
			try {
				scoped.save();
			} catch (IOException e) {
				// The answer still applies until Eclipse exits.
			}
		}
	}

	private static int ask(Terminal terminal, Harness harness, String url) {
		String kept = harness.mcp().tokenInEnvironment()
				? "), and reads the token of the server in the environment of the terminals of Xterm (" //$NON-NLS-1$
						+ EclipseMcp.TOKEN_VARIABLE + ")." //$NON-NLS-1$
				: "), with the token of the server."; //$NON-NLS-1$
		MessageDialog dialog = new MessageDialog(terminal.shell(), "Eclipse MCP Server", null, //$NON-NLS-1$
				"The MCP server of Eclipse is running (" + url + ").\n\nAdd it to " + harness.product() //$NON-NLS-1$ //$NON-NLS-2$
						+ ", so that it can use this Eclipse: its projects, problems, launches...?\n\n" //$NON-NLS-1$
						+ harness.product() + " keeps it for your user (" + harness.program() + " mcp add " //$NON-NLS-1$ //$NON-NLS-2$
						+ EclipseMcp.NAME + kept,
				MessageDialog.QUESTION, new String[] {"&Add", "A&lways Add", "&Not Now", "Ne&ver Ask"}, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		int button = dialog.open();
		// closed without a button: as "Not Now"
		return button >= 0 && button < BUTTON_ANSWERS.length ? BUTTON_ANSWERS[button] : NOT_NOW;
	}

	/** Adds the server with the command of the agent, then starts the agent. */
	private static void add(Terminal terminal, Harness harness, String[] add, String[] remove, Display display,
			Runnable start) {
		terminal.print("Adding the MCP server of Eclipse to " + harness.product() + "..."); //$NON-NLS-1$ //$NON-NLS-2$
		File directory = terminal.directory();
		String[] environment = terminal.environment();
		Thread worker = new Thread(() -> {
			Commands.Result result = Commands.run(add, directory, environment);
			if (!result.succeeded() && remove != null && result.output().contains("already")) { //$NON-NLS-1$
				// A server of this name from an earlier run, on another port: replaced.
				Commands.run(remove, directory, environment);
				result = Commands.run(add, directory, environment);
			}
			Commands.Result last = result;
			if (!last.succeeded()) {
				HarnessPlugin.log("Could not add the MCP server of Eclipse to " + harness.product() + ": " //$NON-NLS-1$ //$NON-NLS-2$
						+ last.failure(), null);
			}
			HarnessPlugin.later(display, () -> {
				if (terminal.isDisposed()) {
					return;
				}
				if (last.succeeded()) {
					terminal.print(harness.product() + " can now use Eclipse."); //$NON-NLS-1$
				} else {
					terminal.printError("Could not add the MCP server of Eclipse: " + last.failure()); //$NON-NLS-1$
				}
				start.run();
			});
		}, "Xterm MCP of " + harness.product()); //$NON-NLS-1$
		worker.setDaemon(true);
		worker.start();
	}
}
