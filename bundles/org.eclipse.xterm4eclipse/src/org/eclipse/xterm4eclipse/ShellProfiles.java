package org.eclipse.xterm4eclipse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The shells that can be started in a terminal, similar to the terminal profiles of VS Code.
 */
final class ShellProfiles {

	/** A named shell command line, for example {@code zsh} / {@code /usr/bin/zsh}. */
	record Profile(String name, String commandLine) {

		/** The icon shown in front of the shell in the menus, as a resource of the plug-in. */
		String icon() {
			return name.equals("Git Bash") ? "icons/shells/gitbash.png" : iconOf(commandLine); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/**
	 * The icon of the shell or program a command line runs, as a resource of the plug-in: shown in the
	 * menus and in the tab of the terminal.
	 */
	static String iconOf(String commandLine) {
		String[] arguments = parse(commandLine);
		String kind;
		// Git for Windows: its bash.exe is in Git/bin or Git/usr/bin.
		if (arguments.length > 0 && arguments[0].replace('\\', '/').toLowerCase().matches(".*/git/(usr/)?bin/bash\\.exe")) { //$NON-NLS-1$
			kind = "gitbash"; //$NON-NLS-1$
		} else {
			String program = displayName(commandLine).toLowerCase();
			kind = switch (program) {
			case "bash", "zsh", "fish", "cmd", "claude" -> program; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
			case "pwsh", "powershell" -> "powershell"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			case "wsl" -> "linux"; //$NON-NLS-1$ //$NON-NLS-2$
			default -> "shell"; //$NON-NLS-1$
			};
		}
		return "icons/shells/" + kind + ".png"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** The machine the shells are looked up on; a parameter so that every platform can be tested. */
	record Host(String os, UnaryOperator<String> environment, Path shellsFile) {

		static Host current() {
			return new Host(System.getProperty("os.name", ""), System::getenv, Path.of("/etc/shells")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}

		boolean isWindows() {
			return os.toLowerCase().contains("win"); //$NON-NLS-1$
		}

		boolean isMac() {
			return os.toLowerCase().contains("mac"); //$NON-NLS-1$
		}

		String env(String name) {
			return environment.apply(name);
		}
	}

	private ShellProfiles() {
	}

	static List<Profile> detect() {
		return detect(Host.current());
	}

	static List<Profile> detect(Host host) {
		Map<String, Profile> profiles = new LinkedHashMap<>();
		if (host.isWindows()) {
			addIfOnPath(host, profiles, "PowerShell", "pwsh.exe"); //$NON-NLS-1$ //$NON-NLS-2$
			addIfOnPath(host, profiles, "Windows PowerShell", "powershell.exe"); //$NON-NLS-1$ //$NON-NLS-2$
			addIfOnPath(host, profiles, "Command Prompt", "cmd.exe"); //$NON-NLS-1$ //$NON-NLS-2$
			addGitBash(host, profiles);
			addIfOnPath(host, profiles, "WSL", "wsl.exe"); //$NON-NLS-1$ //$NON-NLS-2$
		} else {
			List<String> candidates = new ArrayList<>();
			String userShell = host.env("SHELL"); //$NON-NLS-1$
			if (userShell != null && !userShell.isBlank()) {
				candidates.add(userShell);
			}
			try {
				for (String line : Files.readAllLines(host.shellsFile())) {
					if (line.startsWith("/")) { //$NON-NLS-1$
						candidates.add(line.trim());
					}
				}
			} catch (IOException e) {
				// No /etc/shells, only the user shell and bash are offered.
			}
			candidates.add("/bin/bash"); //$NON-NLS-1$
			for (String candidate : candidates) {
				File file = new File(candidate);
				// /etc/shells lists the same shell under /bin and /usr/bin: keep one entry per name.
				if (file.canExecute()) {
					profiles.putIfAbsent(file.getName(),
							new Profile(file.getName(), withLoginFlag(host, quote(candidate))));
				}
			}
		}
		addClaude(host, profiles);
		return new ArrayList<>(profiles.values());
	}

	/**
	 * Claude Code, when it is on the PATH: started through the login shell of the user on Unix so that
	 * it finds the same environment (node, nvm...) as in a terminal, and the view closes when it ends.
	 */
	private static void addClaude(Host host, Map<String, Profile> profiles) {
		if (host.isWindows()) {
			File claude = findOnPath(host, "claude.exe", "claude.cmd"); //$NON-NLS-1$ //$NON-NLS-2$
			if (claude != null) {
				// A .cmd launcher, as installed by npm, needs cmd.exe to run.
				profiles.put(CLAUDE, new Profile(CLAUDE, claude.getName().toLowerCase().endsWith(".cmd") //$NON-NLS-1$
						? "cmd.exe /c " + quote(claude.getPath()) //$NON-NLS-1$
						: quote(claude.getPath())));
			}
		} else if (findOnPath(host, "claude") != null) { //$NON-NLS-1$
			String shell = host.env("SHELL"); //$NON-NLS-1$
			if (shell == null || shell.isBlank()) {
				shell = "/bin/bash"; //$NON-NLS-1$
			}
			profiles.put(CLAUDE, new Profile(CLAUDE, quote(shell) + " -l -i -c claude")); //$NON-NLS-1$
		}
	}

	private static final String CLAUDE = "Claude"; //$NON-NLS-1$

	private static File findOnPath(Host host, String... executables) {
		for (String directory : pathEntries(host)) {
			for (String executable : executables) {
				File file = new File(directory, executable);
				if (file.isFile()) {
					return file;
				}
			}
		}
		return null;
	}

	private static List<String> pathEntries(Host host) {
		String path = host.env("PATH"); //$NON-NLS-1$
		// Not File.pathSeparator: the host may be a Windows machine simulated in a test.
		List<String> entries = new ArrayList<>();
		if (path != null) {
			for (String entry : path.split(host.isWindows() ? ";" : ":")) { //$NON-NLS-1$ //$NON-NLS-2$
				// An empty or relative entry is the current directory of Eclipse, where anybody could
				// have left a program of the same name: the shells are only looked up in fixed places.
				if (isAbsolute(host, entry)) {
					entries.add(entry);
				}
			}
		}
		return entries;
	}

	static boolean isAbsolute(Host host, String directory) {
		if (host.isWindows()) {
			return directory.matches("[A-Za-z]:[\\\\/].*|[\\\\/].*"); //$NON-NLS-1$
		}
		return directory.startsWith("/"); //$NON-NLS-1$
	}

	/** The command line used for new terminals when no profile is chosen explicitly. */
	static String defaultCommandLine() {
		return defaultCommandLine(Host.current());
	}

	static String defaultCommandLine(Host host) {
		String stored = XtermPlugin.preference(XtermPlugin.PREF_DEFAULT_SHELL);
		if (!stored.isBlank()) {
			return stored;
		}
		if (host.isWindows()) {
			String comspec = host.env("COMSPEC"); //$NON-NLS-1$
			return quote(comspec != null ? comspec : "cmd.exe"); //$NON-NLS-1$
		}
		String shell = host.env("SHELL"); //$NON-NLS-1$
		return withLoginFlag(host, quote(shell == null || shell.isBlank() ? "/bin/bash" : shell)); //$NON-NLS-1$
	}

	static void setDefaultCommandLine(String commandLine) {
		XtermPlugin.setPreference(XtermPlugin.PREF_DEFAULT_SHELL, commandLine.trim());
	}

	/** Splits a command line on whitespace, double quotes group an argument containing spaces. */
	static String[] parse(String commandLine) {
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

	/** A short label for a command line: the executable name without directory or extension. */
	static String displayName(String commandLine) {
		String[] arguments = parse(commandLine);
		if (arguments.length == 0) {
			return "Xterm"; //$NON-NLS-1$
		}
		// A shell running a single program, such as Claude Code, is named after the program.
		for (int i = 1; i < arguments.length - 1; i++) {
			if (arguments[i].equals("-c") || arguments[i].equalsIgnoreCase("/c")) { //$NON-NLS-1$ //$NON-NLS-2$
				String program = arguments[i + 1].trim();
				program = program.startsWith("'") ? program.substring(1, Math.max(1, program.indexOf('\'', 1))) //$NON-NLS-1$
						: program.split("\\s+")[0]; //$NON-NLS-1$
				if (!program.isEmpty()) {
					return displayName('"' + program + '"');
				}
			}
		}
		String name = arguments[0].substring(Math.max(arguments[0].lastIndexOf('/'), arguments[0].lastIndexOf('\\')) + 1);
		return name.toLowerCase().matches(".*\\.(exe|cmd|bat)") ? name.substring(0, name.length() - 4) : name; //$NON-NLS-1$
	}

	private static final Set<String> SHELLS = Set.of("bash", "zsh", "fish", "sh", "dash", "ksh", "mksh", "ash", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
			"csh", "tcsh", "nu", "xonsh", "elvish", "cmd", "powershell", "pwsh", "wsl"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$

	/**
	 * @return {@code true} if the command line starts an interactive shell, {@code false} if it runs a
	 *         program of its own such as Claude Code (directly or through {@code shell -c})
	 */
	static boolean isShell(String commandLine) {
		return SHELLS.contains(displayName(commandLine).toLowerCase());
	}

	/**
	 * Git for Windows is found through the {@code git.exe} of the PATH, whatever the installation
	 * directory, then in the usual installation directories.
	 */
	private static void addGitBash(Host host, Map<String, Profile> profiles) {
		List<File> roots = new ArrayList<>();
		for (String directory : pathEntries(host)) {
			if (new File(directory, "git.exe").isFile()) { //$NON-NLS-1$
				// The PATH holds Git\cmd, Git\bin or Git\mingw64\bin.
				File parent = new File(directory).getAbsoluteFile().getParentFile();
				if (parent != null) {
					roots.add(parent);
					if (parent.getParentFile() != null) {
						roots.add(parent.getParentFile());
					}
				}
			}
		}
		for (String variable : new String[] {"ProgramFiles", "ProgramW6432", "ProgramFiles(x86)", "LocalAppData"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			String root = host.env(variable);
			if (root != null) {
				roots.add(new File(root, "Git")); //$NON-NLS-1$
				roots.add(new File(root, "Programs/Git")); //$NON-NLS-1$
			}
		}
		for (File root : roots) {
			File bash = new File(root, "bin/bash.exe"); //$NON-NLS-1$
			if (bash.isFile()) {
				profiles.putIfAbsent("Git Bash", new Profile("Git Bash", quote(bash.getPath()) + " --login -i")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				return;
			}
		}
	}

	private static void addIfOnPath(Host host, Map<String, Profile> profiles, String name, String executable) {
		File file = findOnPath(host, executable);
		if (file != null) {
			profiles.putIfAbsent(name, new Profile(name, quote(file.getPath())));
		}
	}

	private static String withLoginFlag(Host host, String commandLine) {
		// macOS GUI applications do not inherit the user's shell environment.
		return host.isMac() ? commandLine + " -l" : commandLine; //$NON-NLS-1$
	}

	private static String quote(String path) {
		return path.indexOf(' ') >= 0 ? '"' + path + '"' : path;
	}
}
