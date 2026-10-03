package org.eclipse.xterm4eclipse;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.service.datalocation.Location;

/**
 * The MCP server of Eclipse by vogella (https://github.com/vogellacompany/eclipse-mcp-server),
 * which gives Claude Code the projects, problems, debugger... of the running Eclipse. When it
 * runs, a new terminal of Claude Code offers to add it to Claude Code.
 */
final class EclipseMcp {

	/** The name of the server in Claude Code, as in the documentation of the server. */
	static final String NAME = "eclipse"; //$NON-NLS-1$
	/** Where the server tells how to reach it, in the folder of the workspace. */
	static final String ENDPOINT_FILE = ".metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json"; //$NON-NLS-1$
	/** Only a server of this computer: the address and the token go into a command line. */
	private static final Pattern URL = Pattern.compile("http://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):(\\d{1,5})/[A-Za-z0-9/_.-]*"); //$NON-NLS-1$
	private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._-]{8,256}"); //$NON-NLS-1$

	/** The running server: where it listens, and the token that it requires. */
	record Endpoint(String url, String token) {
	}

	/** The folder of the workspace; a test may change it. */
	static Path workspace;

	private EclipseMcp() {
	}

	private static Path workspace() {
		if (workspace != null) {
			return workspace;
		}
		try {
			Location location = Platform.getInstanceLocation();
			return location == null || location.getURL() == null ? null : Path.of(URI.create(
					location.getURL().toExternalForm().replace(" ", "%20"))); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	/** @return the server if it runs in this Eclipse and answers, or {@code null} */
	static Endpoint running() {
		Path folder = workspace();
		if (folder == null) {
			return null;
		}
		String json;
		try {
			json = Files.readString(folder.resolve(ENDPOINT_FILE), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			return null;
		}
		String state = field(json, "state"); //$NON-NLS-1$
		String url = field(json, "url"); //$NON-NLS-1$
		String token = field(json, "token"); //$NON-NLS-1$
		if (!"listening".equals(state) || url == null || token == null || !TOKEN.matcher(token).matches()) { //$NON-NLS-1$
			return null;
		}
		Matcher address = URL.matcher(url);
		if (!address.matches()) {
			return null;
		}
		// The file stays when Eclipse ends abruptly: the server must answer.
		try (Socket socket = new Socket()) {
			String host = url.contains("[::1]") ? "::1" : "127.0.0.1"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			socket.connect(new InetSocketAddress(host, Integer.parseInt(address.group(1))), 500);
		} catch (IOException | RuntimeException e) {
			return null;
		}
		return new Endpoint(url, token);
	}

	/** The string value of a field of a small JSON object, without escapes. */
	private static String field(String json, String name) {
		Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"\\\\]*)\"").matcher(json); //$NON-NLS-1$ //$NON-NLS-2$
		return matcher.find() ? matcher.group(1) : null;
	}

	/**
	 * @param directory
	 *            where Claude Code starts, for the servers of its project ({@code .mcp.json})
	 * @return {@code true} if Claude Code already knows the server: of the user, of the project or
	 *         of the folder
	 */
	static boolean configured(Endpoint endpoint, File directory) {
		String localhost = endpoint.url().replace("127.0.0.1", "localhost"); //$NON-NLS-1$ //$NON-NLS-2$
		List<Path> files = new ArrayList<>();
		files.add(claudeConfigFile());
		if (directory != null) {
			files.add(directory.toPath().resolve(".mcp.json")); //$NON-NLS-1$
		}
		for (Path file : files) {
			try {
				String content = Files.readString(file, StandardCharsets.UTF_8);
				if (content.contains(endpoint.url()) || content.contains(localhost)) {
					return true;
				}
			} catch (IOException | RuntimeException e) {
				// No such configuration.
			}
		}
		return false;
	}

	/**
	 * The configuration of Claude Code, with its servers of the user and of each folder: in
	 * CLAUDE_CONFIG_DIR if it is set, else ~/.claude.json next to the folder ~/.claude.
	 */
	private static Path claudeConfigFile() {
		String configured = System.getenv("CLAUDE_CONFIG_DIR"); //$NON-NLS-1$
		return configured != null && !configured.isBlank() ? Path.of(configured, ".claude.json") //$NON-NLS-1$
				: ClaudeSessions.home.resolveSibling(".claude.json"); //$NON-NLS-1$
	}

	/**
	 * @param claude
	 *            the command line of the terminal, parsed: the same Claude Code adds the server
	 * @param subcommand
	 *            the arguments of {@code claude mcp}
	 * @return the command line of {@code claude mcp ...}, or {@code null} if the terminal does not
	 *         run Claude Code in a way that allows it
	 */
	static String[] mcpCommand(String[] claude, String... subcommand) {
		if (claude.length == 0) {
			return null;
		}
		String[] mcp = new String[subcommand.length + 1];
		mcp[0] = "mcp"; //$NON-NLS-1$
		System.arraycopy(subcommand, 0, mcp, 1, subcommand.length);
		if (isClaude(claude[0])) {
			return concat(new String[] {claude[0]}, mcp);
		}
		for (int i = 1; i < claude.length - 1; i++) {
			if (claude[i].equalsIgnoreCase("/c") && isClaude(claude[i + 1])) { //$NON-NLS-1$
				return concat(Arrays.copyOf(claude, i + 2), mcp);
			}
			if (claude[i].equals("-c") && i == claude.length - 2) { //$NON-NLS-1$
				// Through the shell of the user, which finds Claude Code on its PATH.
				String script = claude[i + 1].strip();
				String program = script.startsWith("'") && script.indexOf('\'', 1) > 0 //$NON-NLS-1$
						? script.substring(0, script.indexOf('\'', 1) + 1)
						: script.split("\\s+")[0]; //$NON-NLS-1$
				if (!isClaude(program.replace("'", ""))) { //$NON-NLS-1$ //$NON-NLS-2$
					return null;
				}
				StringBuilder command = new StringBuilder(program);
				for (String argument : mcp) {
					// Only safe characters: the URL and the token are checked.
					command.append(" '").append(argument).append('\''); //$NON-NLS-1$
				}
				String[] result = Arrays.copyOf(claude, i + 2);
				result[i + 1] = command.toString();
				return result;
			}
		}
		return null;
	}

	/** The arguments of {@code claude mcp} that add the server for the user. */
	static String[] addArguments(Endpoint endpoint) {
		return new String[] {"add", "--transport", "http", "--scope", "user", NAME, endpoint.url(), "--header", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				"Authorization: Bearer " + endpoint.token()}; //$NON-NLS-1$
	}

	private static boolean isClaude(String program) {
		String name = program.substring(Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\')) + 1).toLowerCase();
		return name.equals("claude") || name.equals("claude.exe") || name.equals("claude.cmd"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private static String[] concat(String[] first, String[] second) {
		String[] result = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}
}
