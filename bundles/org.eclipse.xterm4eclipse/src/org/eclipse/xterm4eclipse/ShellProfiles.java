package org.eclipse.xterm4eclipse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The shells that can be started in a terminal, similar to the terminal profiles of VS Code.
 */
final class ShellProfiles {

	/** A named shell command line, for example {@code zsh} / {@code /usr/bin/zsh}. */
	record Profile(String name, String commandLine) {
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
			for (String root : new String[] {host.env("ProgramFiles"), host.env("ProgramFiles(x86)"), //$NON-NLS-1$ //$NON-NLS-2$
					host.env("LocalAppData")}) { //$NON-NLS-1$
				if (root == null) {
					continue;
				}
				for (String folder : new String[] {"Git", "Programs/Git"}) { //$NON-NLS-1$ //$NON-NLS-2$
					File gitBash = new File(new File(root, folder), "bin/bash.exe"); //$NON-NLS-1$
					if (gitBash.isFile()) {
						profiles.putIfAbsent("Git Bash", //$NON-NLS-1$
								new Profile("Git Bash", quote(gitBash.getPath()) + " --login -i")); //$NON-NLS-1$ //$NON-NLS-2$
					}
				}
			}
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
		return new ArrayList<>(profiles.values());
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
		String name = arguments[0].substring(Math.max(arguments[0].lastIndexOf('/'), arguments[0].lastIndexOf('\\')) + 1);
		return name.toLowerCase().endsWith(".exe") ? name.substring(0, name.length() - 4) : name; //$NON-NLS-1$
	}

	private static void addIfOnPath(Host host, Map<String, Profile> profiles, String name, String executable) {
		String path = host.env("PATH"); //$NON-NLS-1$
		if (path == null) {
			return;
		}
		// Not File.pathSeparator: the host may be a Windows machine simulated in a test.
		for (String directory : path.split(host.isWindows() ? ";" : File.pathSeparator)) { //$NON-NLS-1$
			File file = new File(directory, executable);
			if (file.isFile()) {
				profiles.putIfAbsent(name, new Profile(name, quote(file.getPath())));
				return;
			}
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
