package org.eclipse.xterm4eclipse.claude;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The command line of Claude Code for a chat: it reads the messages of the user and writes its own
 * as JSON, one per line, and asks the chat for the permissions of its tools.
 */
final class ClaudeCommand {

	/** The permission modes that a chat offers, with their names in the chat. */
	static final Map<String, String> PERMISSION_MODES = new LinkedHashMap<>();
	static {
		PERMISSION_MODES.put("default", "Ask before edits"); //$NON-NLS-1$ //$NON-NLS-2$
		PERMISSION_MODES.put("acceptEdits", "Accept edits"); //$NON-NLS-1$ //$NON-NLS-2$
		PERMISSION_MODES.put("plan", "Plan mode"); //$NON-NLS-1$ //$NON-NLS-2$
	}
	static final String DEFAULT_MODE = "default"; //$NON-NLS-1$

	/** The models that a chat offers: the aliases of Claude Code for the latest ones. */
	static final List<String> MODELS = List.of("opus", "sonnet", "haiku"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	/** A model as Claude Code names it: an alias or a full name, nothing that a shell interprets. */
	private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\[\\]-]{0,99}"); //$NON-NLS-1$
	static final Pattern SESSION_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); //$NON-NLS-1$
	/** A more argument of the preferences: nothing that cmd.exe or a shell would interpret. */
	private static final Pattern ARGUMENT = Pattern.compile("[A-Za-z0-9_@+=:,./\\\\-]+"); //$NON-NLS-1$
	/** The options of the chat itself, which the preferences cannot change. */
	private static final Set<String> OWN = Set.of("-p", "--print", "--input-format", "--output-format", "--verbose", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			"--include-partial-messages", "--permission-prompt-tool", "--permission-prompts", "-r", "--resume", "-c", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
			"--continue", "--session-id", "--fork-session", "--model", "--permission-mode", "--replay-user-messages"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	/** The shells whose {@code -c} takes the program and its arguments as {@code $0 $@}. */
	private static final Set<String> POSIX_SHELLS = Set.of("bash", "zsh", "sh", "ksh", "dash", "mksh"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

	/** The environment of Eclipse; a test may change it. */
	static UnaryOperator<String> environment = System::getenv;
	/** The program that starts another in a session of its own, on Linux; a test may change it. */
	static String setsid = new File("/usr/bin/setsid").canExecute() ? "/usr/bin/setsid" //$NON-NLS-1$ //$NON-NLS-2$
			: new File("/bin/setsid").canExecute() ? "/bin/setsid" : null; //$NON-NLS-1$ //$NON-NLS-2$
	/** The system; a test may change it. */
	static boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	private ClaudeCommand() {
	}

	/**
	 * @param resume
	 *            resumes the conversation of the id rather than starting it
	 * @param model
	 *            the model, or {@code null} or empty for the one of Claude Code
	 * @param mode
	 *            the permission mode, or {@code null} for the default one
	 * @param more
	 *            more arguments, separated by blanks: those that are not plain are left out
	 * @return the arguments of the program
	 */
	static List<String> arguments(String sessionId, boolean resume, String model, String mode, String more) {
		if (!SESSION_ID.matcher(sessionId).matches()) {
			throw new IllegalArgumentException("Not an id of conversation: " + sessionId); //$NON-NLS-1$
		}
		List<String> arguments = new ArrayList<>(List.of("-p", "--input-format", "stream-json", "--output-format", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				"stream-json", "--verbose", "--include-partial-messages", "--permission-prompt-tool", "stdio")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		arguments.add(resume ? "--resume" : "--session-id"); //$NON-NLS-1$ //$NON-NLS-2$
		arguments.add(sessionId);
		if (isModel(model)) {
			arguments.add("--model"); //$NON-NLS-1$
			arguments.add(model);
		}
		if (mode != null && PERMISSION_MODES.containsKey(mode) && !mode.equals(DEFAULT_MODE)) {
			arguments.add("--permission-mode"); //$NON-NLS-1$
			arguments.add(mode);
		}
		arguments.addAll(more(more));
		return arguments;
	}

	static boolean isModel(String model) {
		return model != null && MODEL.matcher(model).matches();
	}

	/** @return the plain arguments of the text, without the options of the chat and their values */
	static List<String> more(String text) {
		List<String> arguments = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return arguments;
		}
		String[] words = text.trim().split("\\s+"); //$NON-NLS-1$
		for (int i = 0; i < words.length; i++) {
			String word = words[i];
			String option = word.startsWith("--") && word.contains("=") ? word.substring(0, word.indexOf('=')) : word; //$NON-NLS-1$ //$NON-NLS-2$
			if (OWN.contains(option)) {
				// Its value too, if it is one.
				if (option.equals(word) && i + 1 < words.length && !words[i + 1].startsWith("-")) { //$NON-NLS-1$
					i++;
				}
				continue;
			}
			if (ARGUMENT.matcher(word).matches()) {
				arguments.add(word);
			} else {
				ChatPlugin.log("Left out the argument " + word + " of Claude Code: not a plain one", null); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return arguments;
	}

	/**
	 * @param configured
	 *            the program chosen in the preferences, or empty
	 * @return the program of Claude Code, or {@code null} if it is not found
	 */
	static File program(String configured) {
		if (configured != null && !configured.isBlank()) {
			File file = new File(configured.trim());
			return file.isAbsolute() && file.isFile() ? file : null;
		}
		List<String> names = windows ? List.of("claude.exe", "claude.cmd") : List.of("claude"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		String path = env("PATH"); //$NON-NLS-1$
		List<File> folders = new ArrayList<>();
		if (path != null) {
			for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
				// An empty or relative entry is the folder of Eclipse, where anybody could have left a
				// program of the same name.
				if (!entry.isBlank() && new File(entry).isAbsolute()) {
					folders.add(new File(entry));
				}
			}
		}
		// Where the installer of Claude Code puts it, which the PATH of Eclipse may not have when it
		// was started from the desktop.
		String home = windows ? env("USERPROFILE") : env("HOME"); //$NON-NLS-1$ //$NON-NLS-2$
		if (home != null && new File(home).isAbsolute()) {
			folders.add(new File(home, ".local/bin")); //$NON-NLS-1$
			folders.add(new File(home, ".claude/local")); //$NON-NLS-1$
		}
		for (File folder : folders) {
			for (String name : names) {
				File file = new File(folder, name);
				if (file.isFile() && (windows || file.canExecute())) {
					return file;
				}
			}
		}
		return null;
	}

	/**
	 * The command line that runs the program: through the login shell of the user on Unix, so that
	 * it finds the same environment (node, nvm...) as in a terminal; through cmd.exe for a launcher
	 * of npm on Windows. The arguments are never written in a script.
	 */
	static List<String> commandLine(File program, List<String> arguments) {
		List<String> command = new ArrayList<>();
		if (windows) {
			if (program.getName().toLowerCase().endsWith(".cmd")) { //$NON-NLS-1$
				command.add("cmd.exe"); //$NON-NLS-1$
				command.add("/c"); //$NON-NLS-1$
			}
			command.add(program.getPath());
			command.addAll(arguments);
			return command;
		}
		String shell = env("SHELL"); //$NON-NLS-1$
		String name = shell == null ? "" : new File(shell).getName(); //$NON-NLS-1$
		boolean known = shell != null && new File(shell).isAbsolute() && (POSIX_SHELLS.contains(name) || name.equals("fish")); //$NON-NLS-1$
		if (known && setsid != null) {
			// An interactive shell takes the terminal that Eclipse was started from, if any: in a session
			// of its own it has none.
			command.add(setsid);
		}
		if (known && POSIX_SHELLS.contains(name)) {
			command.addAll(List.of(shell, "-l", "-i", "-c", "exec \"$0\" \"$@\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		} else if (known) {
			command.addAll(List.of(shell, "-l", "-i", "-c", "exec $argv")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		}
		command.add(program.getPath());
		command.addAll(arguments);
		return command;
	}

	private static String env(String name) {
		String value = environment.apply(name);
		return value == null || value.isBlank() ? null : value;
	}
}
