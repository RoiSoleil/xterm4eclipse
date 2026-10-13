package org.eclipse.xterm4eclipse.harness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * How the command line of a terminal runs a program: directly, through {@code cmd.exe /c} (the
 * .cmd launcher that npm installs on Windows), or through a shell and its {@code -c} (the login
 * shell of the user, which finds the program on its PATH).
 */
final class Invocation {

	private enum Form {
		DIRECT, CMD, SCRIPT
	}

	/** A script run by a shell with -c that is a plain run of a program: no operator, no expansion. */
	private static final Pattern PLAIN_SCRIPT = Pattern.compile("('[^']*'|[^\\s'\"]+)(\\s+[^;&|<>`$()'\"\\\\\\n]*)?"); //$NON-NLS-1$
	/** An argument that a shell reads as it is written. */
	private static final Pattern PLAIN_ARGUMENT = Pattern.compile("[A-Za-z0-9_@%+=:,./-]+"); //$NON-NLS-1$
	/** An argument that a shell reads as it is written between single quotes. */
	private static final Pattern QUOTABLE_ARGUMENT = Pattern.compile("[^'\\p{Cntrl}]*"); //$NON-NLS-1$

	private final String[] commandLine;
	private final Form form;
	/** Where the program is in the command line, or the script that runs it. */
	private final int index;
	/** The program as it is written, with its quotes in a script. */
	private final String program;
	private final List<String> arguments;

	private Invocation(String[] commandLine, Form form, int index, String program, List<String> arguments) {
		this.commandLine = commandLine;
		this.form = form;
		this.index = index;
		this.program = program;
		this.arguments = List.copyOf(arguments);
	}

	/**
	 * @param commandLine
	 *            the command line of a terminal, parsed
	 * @param known
	 *            tells the programs that are looked for, by their {@link #name(String) name}
	 * @return how the command line runs one of these programs, or {@code null} if it does not, or not
	 *         plainly
	 */
	static Invocation of(String[] commandLine, Predicate<String> known) {
		if (commandLine.length == 0) {
			return null;
		}
		List<String> all = Arrays.asList(commandLine);
		if (known.test(name(commandLine[0]))) {
			return new Invocation(commandLine, Form.DIRECT, 0, commandLine[0], all.subList(1, all.size()));
		}
		for (int i = 1; i < commandLine.length - 1; i++) {
			if (commandLine[i].equalsIgnoreCase("/c") && known.test(name(commandLine[i + 1]))) { //$NON-NLS-1$
				// cmd.exe /c program.cmd ...: the arguments that follow go to the program.
				return new Invocation(commandLine, Form.CMD, i + 1, commandLine[i + 1], all.subList(i + 2, all.size()));
			}
			if (commandLine[i].equals("-c") && i == commandLine.length - 2) { //$NON-NLS-1$
				String script = commandLine[i + 1].strip();
				if (!PLAIN_SCRIPT.matcher(script).matches()) {
					return null;
				}
				String program = script.startsWith("'") ? script.substring(0, script.indexOf('\'', 1) + 1) //$NON-NLS-1$
						: script.split("\\s+")[0]; //$NON-NLS-1$
				if (!known.test(name(program))) {
					return null;
				}
				String rest = script.substring(program.length()).strip();
				return new Invocation(commandLine, Form.SCRIPT, i + 1, program,
						rest.isEmpty() ? List.of() : List.of(rest.split("\\s+"))); //$NON-NLS-1$
			}
		}
		return null;
	}

	/** The name of a program: without its folder, its quotes or the extension of a launcher of Windows. */
	static String name(String program) {
		String name = program.replace("'", ""); //$NON-NLS-1$ //$NON-NLS-2$
		name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).toLowerCase();
		return name.matches(".*\\.(exe|cmd|bat)") ? name.substring(0, name.length() - 4) : name; //$NON-NLS-1$
	}

	/** The name of the program that the command line runs. */
	String name() {
		return name(program);
	}

	/** The arguments that the command line gives to the program. */
	List<String> arguments() {
		return arguments;
	}

	/**
	 * @return the command line with more arguments for the program, after its own, or {@code null} if
	 *         they cannot be written in the script of a shell
	 */
	String[] appended(List<String> more) {
		if (form != Form.SCRIPT) {
			List<String> result = new ArrayList<>(Arrays.asList(commandLine));
			result.addAll(more);
			return result.toArray(String[]::new);
		}
		return script(new StringBuilder(commandLine[index].strip()), more);
	}

	/**
	 * @return the command line that runs the same program with other arguments, or {@code null} if
	 *         they cannot be written in the script of a shell
	 */
	String[] replaced(List<String> others) {
		if (form != Form.SCRIPT) {
			List<String> result = new ArrayList<>(Arrays.asList(commandLine).subList(0, index + 1));
			result.addAll(others);
			return result.toArray(String[]::new);
		}
		return script(new StringBuilder(program), others);
	}

	private String[] script(StringBuilder script, List<String> more) {
		for (String argument : more) {
			if (PLAIN_ARGUMENT.matcher(argument).matches()) {
				script.append(' ').append(argument);
			} else if (QUOTABLE_ARGUMENT.matcher(argument).matches()) {
				script.append(" '").append(argument).append('\''); //$NON-NLS-1$
			} else {
				return null;
			}
		}
		String[] result = Arrays.copyOf(commandLine, index + 1);
		result[index] = script.toString();
		return result;
	}
}
