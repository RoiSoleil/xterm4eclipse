package org.eclipse.xterm4eclipse.spi;

import java.util.ArrayList;
import java.util.List;

/** The command lines of the terminals, as the user writes them in the preferences. */
public final class CommandLine {

	private CommandLine() {
	}

	/** Splits a command line on whitespace, double quotes group an argument containing spaces. */
	public static String[] parse(String commandLine) {
		List<String> arguments = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		boolean pending = false;
		for (char c : commandLine.toCharArray()) {
			if (c == '"') {
				quoted = !quoted;
				pending = true;
			} else if (Character.isWhitespace(c) && !quoted) {
				if (pending) {
					arguments.add(current.toString());
					current.setLength(0);
					pending = false;
				}
			} else {
				current.append(c);
				pending = true;
			}
		}
		if (pending) {
			arguments.add(current.toString());
		}
		return arguments.toArray(String[]::new);
	}

	/** @return the argument as {@link #parse(String)} reads it back: in double quotes if it has a space */
	public static String quote(String argument) {
		return argument.indexOf(' ') >= 0 ? '"' + argument + '"' : argument;
	}
}
