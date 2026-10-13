package org.eclipse.xterm4eclipse.spi;

import java.util.List;

/**
 * What another plug-in adds to the terminals, through the extension point
 * {@code org.eclipse.xterm4eclipse.terminalExtensions}: programs offered with the shells, their
 * icons, and what it does in a terminal that runs one of them. The terminal works the same without
 * any extension.
 */
public interface TerminalExtension {

	/**
	 * @param host
	 *            the machine the programs are looked up on
	 * @return the programs offered in the menus after the shells
	 */
	default List<TerminalProfile> profiles(TerminalHost host) {
		return List.of();
	}

	/**
	 * @return the icon of the program that the command line runs, or {@code null} if it is not a
	 *         program of this extension
	 */
	default TerminalIcon icon(String commandLine) {
		return null;
	}

	/**
	 * @return what this extension does in the terminal, or {@code null} if it does not know the
	 *         program of the terminal
	 */
	default TerminalCompanion accompany(Terminal terminal) {
		return null;
	}
}
