package org.eclipse.xterm4eclipse.spi;

import java.io.File;

import org.eclipse.swt.widgets.Shell;

/** A terminal, as an extension sees it. To use on the UI thread. */
public interface Terminal {

	/** The command line of the shell or program of the terminal. */
	String commandLine();

	/** The directory in which the program starts, or {@code null} for the one of Eclipse. */
	File directory();

	/**
	 * @return a value that an extension keeps with the terminal, also after a restart of Eclipse, or
	 *         {@code null}. It comes back from a file of the workspace: to be checked before it goes
	 *         into a command line.
	 */
	String state(String key);

	/**
	 * @param value
	 *            the value to keep with the terminal, or {@code null} to forget it
	 */
	void setState(String key, String value);

	/** Shows a message of Eclipse in the terminal, on a line of its own. */
	void print(String message);

	/** Shows an error of Eclipse in the terminal, on a line of its own. */
	void printError(String message);

	/** The window of the terminal, for a dialog; may be {@code null}. */
	Shell shell();

	/** The environment of the program of the terminal, as {@code NAME=value}. */
	String[] environment();

	/** @return {@code true} once the terminal is closed */
	boolean isDisposed();
}
