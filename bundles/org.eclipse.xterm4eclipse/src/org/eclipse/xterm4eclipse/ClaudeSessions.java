package org.eclipse.xterm4eclipse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The conversation of Claude Code in a terminal: started with its own session id, so that the
 * terminal that Eclipse reopens after a restart resumes that very conversation ({@code --resume}),
 * not the last one of the folder ({@code --continue}), which may be the one of another terminal.
 */
final class ClaudeSessions {

	/** A session id of Claude Code: a UUID, and nothing that a shell would interpret. */
	private static final Pattern ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); //$NON-NLS-1$
	/** Options with which the command line already chooses the conversation, or has none. */
	private static final Set<String> CHOSEN = Set.of("-r", "--resume", "-c", "--continue", "--session-id", "-p", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
			"--print", "--from-pr", "--teleport", "--fork-session"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	/** A script run by a shell with -c that is a plain run of a program: no operator, no expansion. */
	private static final Pattern PLAIN_SCRIPT = Pattern.compile("('[^']*'|[^\\s'\"]+)(\\s+[^;&|<>`$()'\"\\\\\\n]*)?"); //$NON-NLS-1$

	/** Where Claude Code keeps its conversations; a test may change it. */
	static Path home = defaultHome();

	private ClaudeSessions() {
	}

	private static Path defaultHome() {
		String configured = System.getenv("CLAUDE_CONFIG_DIR"); //$NON-NLS-1$
		return configured != null && !configured.isBlank() ? Path.of(configured)
				: Path.of(System.getProperty("user.home", ""), ".claude"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** @return {@code true} if the value is a session id, safe to give to a shell */
	static boolean isId(String value) {
		return value != null && ID.matcher(value).matches();
	}

	/** @return {@code true} if Claude Code has kept a conversation of this id */
	static boolean exists(String id) {
		if (!isId(id)) {
			return false;
		}
		String name = id + ".jsonl"; //$NON-NLS-1$
		// projects/<folder of the conversation>/<id>.jsonl
		try (Stream<Path> files = Files.walk(home.resolve("projects"), 2)) { //$NON-NLS-1$
			return files.anyMatch(file -> file.getFileName().toString().equals(name));
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * @param arguments
	 *            the command line of a terminal, parsed
	 * @param id
	 *            the session id of the terminal
	 * @param resume
	 *            {@code true} to resume the conversation, {@code false} to start it
	 * @return the command line with {@code --resume id} or {@code --session-id id} for Claude Code,
	 *         or the same arguments if they do not run Claude Code, or choose a conversation already
	 */
	static String[] withSession(String[] arguments, String id, boolean resume) {
		if (!isId(id) || arguments.length == 0) {
			return arguments;
		}
		String[] options = {resume ? "--resume" : "--session-id", id}; //$NON-NLS-1$ //$NON-NLS-2$
		if (isClaude(arguments[0])) {
			return chooses(Arrays.asList(arguments).subList(1, arguments.length).stream()) ? arguments
					: append(arguments, options);
		}
		for (int i = 1; i < arguments.length - 1; i++) {
			if (arguments[i].equalsIgnoreCase("/c") && i + 1 < arguments.length && isClaude(arguments[i + 1])) { //$NON-NLS-1$
				// cmd.exe /c claude.cmd ...: the arguments that follow go to Claude Code.
				return chooses(Arrays.asList(arguments).subList(i + 2, arguments.length).stream()) ? arguments
						: append(arguments, options);
			}
			if (arguments[i].equals("-c") && i == arguments.length - 2) { //$NON-NLS-1$
				// A shell runs the script: only a plain run of Claude Code is changed.
				String script = arguments[i + 1].strip();
				if (!PLAIN_SCRIPT.matcher(script).matches()) {
					return arguments;
				}
				String program = script.startsWith("'") ? script.substring(1, script.indexOf('\'', 1)) //$NON-NLS-1$
						: script.split("\\s+")[0]; //$NON-NLS-1$
				if (!isClaude(program) || chooses(Arrays.stream(script.split("\\s+")).skip(1))) { //$NON-NLS-1$
					return arguments;
				}
				String[] result = arguments.clone();
				result[i + 1] = script + ' ' + options[0] + ' ' + options[1];
				return result;
			}
		}
		return arguments;
	}

	/** @return {@code true} if the command line runs Claude Code */
	static boolean runsClaude(String commandLine) {
		return "claude".equalsIgnoreCase(ShellProfiles.displayName(commandLine)); //$NON-NLS-1$
	}

	private static boolean isClaude(String program) {
		String name = program.substring(Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\')) + 1).toLowerCase();
		return name.equals("claude") || name.equals("claude.exe") || name.equals("claude.cmd"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private static boolean chooses(Stream<String> options) {
		return options.anyMatch(option -> CHOSEN.contains(option) || option.startsWith("--resume=") //$NON-NLS-1$
				|| option.startsWith("--session-id=")); //$NON-NLS-1$
	}

	private static String[] append(String[] arguments, String[] options) {
		String[] result = Arrays.copyOf(arguments, arguments.length + options.length);
		System.arraycopy(options, 0, result, arguments.length, options.length);
		return result;
	}
}
