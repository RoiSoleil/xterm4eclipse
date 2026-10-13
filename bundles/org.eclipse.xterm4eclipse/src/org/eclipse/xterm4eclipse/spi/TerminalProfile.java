package org.eclipse.xterm4eclipse.spi;

/**
 * A program offered in the menus of the terminal.
 *
 * @param name
 *            shown in the menus, for example {@code Claude}
 * @param commandLine
 *            what the terminal runs; double quotes group an argument with spaces
 */
public record TerminalProfile(String name, String commandLine) {
}
