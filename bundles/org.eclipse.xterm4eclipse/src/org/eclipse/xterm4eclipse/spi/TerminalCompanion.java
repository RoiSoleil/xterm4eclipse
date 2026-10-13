package org.eclipse.xterm4eclipse.spi;

import java.util.Map;

/**
 * What an extension does in one terminal, whose program it knows. Every method is called on the UI
 * thread.
 */
public interface TerminalCompanion {

	/**
	 * Before the program starts in a terminal that the user has just opened: the moment to ask
	 * something. Called from within a call of the page of the terminal: a dialog must be opened
	 * later ({@code Display.asyncExec}).
	 *
	 * @param start
	 *            starts the program; called on the UI thread, once, if this method returns
	 *            {@code true}
	 * @return {@code true} if the program starts later, {@code false} to start it now
	 */
	default boolean beforeStart(Runnable start) {
		return false;
	}

	/**
	 * @param arguments
	 *            the command line of the terminal, parsed
	 * @param resume
	 *            {@code true} when the program comes back: Eclipse reopens the terminal, or the
	 *            program ended with an error and the user starts it again; {@code false} when it
	 *            starts anew
	 * @return the command line to run
	 */
	default String[] arguments(String[] arguments, boolean resume) {
		return arguments;
	}

	/** @return variables added to the environment of the program */
	default Map<String, String> environment() {
		return Map.of();
	}

	/** The user has entered a line in the program. */
	default void lineEntered() {
	}

	/** The program has ended. */
	default void exited(int exitCode) {
	}

	/** The user starts the program anew (Restart): nothing of its previous run goes on. */
	default void renewed() {
	}

	/** The terminal is closed. */
	default void dispose() {
	}
}
