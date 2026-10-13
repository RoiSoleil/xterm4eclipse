package org.eclipse.xterm4eclipse.harness;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
}
