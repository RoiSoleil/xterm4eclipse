package org.eclipse.xterm4eclipse.harness;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IPreferencesService;
import org.eclipse.xterm4eclipse.spi.CommandLine;
import org.eclipse.xterm4eclipse.spi.Terminal;
import org.eclipse.xterm4eclipse.spi.TerminalCompanion;
import org.eclipse.xterm4eclipse.spi.TerminalExtension;
import org.eclipse.xterm4eclipse.spi.TerminalHost;
import org.eclipse.xterm4eclipse.spi.TerminalIcon;
import org.eclipse.xterm4eclipse.spi.TerminalProfile;

/**
 * Gives the terminals of Xterm4Eclipse what they know about the coding agents (Claude Code, Codex,
 * OpenCode, Reasonix...): their entries in the menus when they are installed, their icons, their
 * conversations and the MCP server of Eclipse.
 */
public final class HarnessExtension implements TerminalExtension {

	/**
	 * The agents that are on the PATH: started through the login shell of the user on Unix so that
	 * they find the same environment (node, nvm...) as in a terminal, and the view closes when they
	 * end.
	 */
	@Override
	public List<TerminalProfile> profiles(TerminalHost host) {
		List<TerminalProfile> profiles = new ArrayList<>();
		for (Harness harness : Harnesses.ALL) {
			String commandLine = commandLine(harness, host);
			if (commandLine != null) {
				profiles.add(new TerminalProfile(harness.label(), commandLine));
			}
		}
		return profiles;
	}

	/** The permission mode of the chats with Claude Code, in the preferences of its plug-in. */
	static Supplier<String> claudePermissionMode = () -> {
		try {
			IPreferencesService preferences = Platform.getPreferencesService();
			return preferences == null ? null
					: preferences.getString("org.eclipse.xterm4eclipse.claude", "permissionMode", null, null); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	};

	/** The permission modes of the chats that Claude Code starts in, all but the default one. */
	private static final Set<String> CLAUDE_PERMISSION_MODES = Set.of("acceptEdits", "auto", "plan"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	/**
	 * @param own
	 *            the arguments of the command line of the terminal
	 * @return the arguments that start Claude Code in the permission mode of the chats: none for the
	 *         default one, or when the command line chooses its permissions itself
	 */
	static List<String> claudePermissionArguments(List<String> own) {
		for (String argument : own) {
			if (argument.equals("--permission-mode") || argument.startsWith("--permission-mode=") //$NON-NLS-1$ //$NON-NLS-2$
					|| argument.equals("--dangerously-skip-permissions")) { //$NON-NLS-1$
				return List.of();
			}
		}
		String mode = claudePermissionMode.get();
		return mode != null && CLAUDE_PERMISSION_MODES.contains(mode) ? List.of("--permission-mode", mode) : List.of(); //$NON-NLS-1$
	}

	/** @return the command line that runs the agent on this machine, or {@code null} if it is not installed */
	static String commandLine(Harness harness, TerminalHost host) {
		if (host.isWindows()) {
			List<String> launchers = new ArrayList<>();
			for (String program : harness.programs()) {
				launchers.add(program + ".exe"); //$NON-NLS-1$
				launchers.add(program + ".cmd"); //$NON-NLS-1$
			}
			File found = host.findOnPath(launchers.toArray(String[]::new));
			if (found == null) {
				return null;
			}
			// A .cmd launcher, as installed by npm, needs cmd.exe to run.
			return found.getName().toLowerCase().endsWith(".cmd") ? "cmd.exe /c " + CommandLine.quote(found.getPath()) //$NON-NLS-1$ //$NON-NLS-2$
					: CommandLine.quote(found.getPath());
		}
		for (String program : harness.programs()) {
			if (host.findOnPath(program) != null) {
				String shell = host.env("SHELL"); //$NON-NLS-1$
				if (shell == null || shell.isBlank()) {
					shell = "/bin/bash"; //$NON-NLS-1$
				}
				return CommandLine.quote(shell) + " -l -i -c " + program; //$NON-NLS-1$
			}
		}
		return null;
	}

	@Override
	public TerminalIcon icon(String commandLine) {
		Harnesses.Found found = Harnesses.find(commandLine);
		return found == null ? null : new TerminalIcon(HarnessExtension.class, HarnessPlugin.ID, found.harness().icon());
	}

	@Override
	public TerminalCompanion accompany(Terminal terminal) {
		Harnesses.Found found = Harnesses.find(terminal.commandLine());
		return found == null ? null : new HarnessCompanion(terminal, found.harness());
	}

	/**
	 * The MCP server of Eclipse gives its port and its token to every terminal, so that the
	 * configuration of an agent can refer to them whatever its program.
	 */
	@Override
	public Map<String, String> environment() {
		return EclipseMcp.environment();
	}
}
