package org.eclipse.xterm4eclipse.harness;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.service.datalocation.Location;

/**
 * The MCP server of Eclipse by vogella (https://github.com/vogellacompany/eclipse-mcp-server),
 * which gives a coding agent the projects, problems, debugger... of the running Eclipse. When it
 * runs, a new terminal of an agent offers to add it to the agent.
 */
public final class EclipseMcp {

	/** The name of the server in the agents: it tells who added it. Claude Code allows no space in it. */
	public static final String NAME = "eclipse-mcp-server-via-xterm4eclipse"; //$NON-NLS-1$
	/**
	 * The variable in which the terminals give the token of the server to the agents that do not
	 * keep it in their files (Codex).
	 */
	static final String TOKEN_VARIABLE = "XTERM4ECLIPSE_MCP_TOKEN"; //$NON-NLS-1$
	/** Where the server tells how to reach it, in the folder of the workspace. */
	static final String ENDPOINT_FILE = ".metadata/.plugins/com.vogella.eclipse.mcp.server/endpoint.json"; //$NON-NLS-1$
	/** Only a server of this computer: the address and the token go into a command line. */
	private static final Pattern URL = Pattern.compile("http://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):(\\d{1,5})/[A-Za-z0-9/_.-]*"); //$NON-NLS-1$
	private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._-]{8,256}"); //$NON-NLS-1$

	/** The running server: where it listens, and the token that it requires. */
	public record Endpoint(String url, String token) {
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
	public static Endpoint running() {
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
	 *            where Claude Code starts, for the servers of its project
	 * @return {@code true} if Claude Code already knows the server: of the user or of the project
	 */
	public static boolean claudeKnows(Endpoint endpoint, File directory) {
		return configured(Harnesses.CLAUDE.mcp(), endpoint, directory);
	}

	/**
	 * @param directory
	 *            where the agent starts, for the servers of its project
	 * @return {@code true} if the agent already knows the server: of the user or of the project
	 */
	static boolean configured(Harness.Mcp mcp, Endpoint endpoint, File directory) {
		String localhost = endpoint.url().replace("127.0.0.1", "localhost"); //$NON-NLS-1$ //$NON-NLS-2$
		List<Path> files = new ArrayList<>();
		for (Supplier<Path> file : mcp.userFiles()) {
			files.add(file.get());
		}
		if (directory != null) {
			for (String file : mcp.projectFiles()) {
				files.add(directory.toPath().resolve(file));
			}
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
}
