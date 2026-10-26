package org.eclipse.xterm4eclipse.claude;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import org.eclipse.xterm4eclipse.harness.EclipseMcp;

/**
 * Gives a chat the MCP server of Eclipse (https://github.com/vogellacompany/eclipse-mcp-server)
 * when it runs: the projects, problems, launches... of this Eclipse. The terminals of the coding
 * agents add it to the configuration of the user, after asking; a chat gives it to its Claude Code
 * only, in a file of its own that is deleted when Claude Code ends, so that nothing is left in the
 * configuration of the user.
 */
final class EclipseMcpConfig {

	/** The running server; a test may change it. */
	static Supplier<EclipseMcp.Endpoint> server = EclipseMcp::running;
	/** Tells if Claude Code already knows the server, for a folder; a test may change it. */
	static BiPredicate<EclipseMcp.Endpoint, File> known = EclipseMcp::claudeKnows;

	private EclipseMcpConfig() {
	}

	/**
	 * @param directory
	 *            where Claude Code starts
	 * @return the file to give to {@code --mcp-config}, or {@code null} if the server does not run,
	 *         if Claude Code already knows it, or if the preferences do not want it
	 */
	static Path write(File directory) {
		if (!ChatPlugin.preferences().getBoolean(ChatPlugin.PREF_ECLIPSE_MCP)) {
			return null;
		}
		EclipseMcp.Endpoint endpoint = server.get();
		if (endpoint == null || known.test(endpoint, directory)) {
			return null;
		}
		String json = Json.write(Map.of("mcpServers", Map.of(EclipseMcp.NAME, Map.of("type", "http", "url", endpoint.url(), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				"headers", Map.of("Authorization", "Bearer " + endpoint.token()))))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		try {
			// The token is in it: for the user only where the system has permissions of files, else in the folder of temporary
			// files of the user.
			FileAttribute<?>[] attributes = FileSystems.getDefault().supportedFileAttributeViews().contains("posix") //$NON-NLS-1$
					? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))} //$NON-NLS-1$
					: new FileAttribute<?>[0];
			Path file = Files.createTempFile("xterm4eclipse-mcp-", ".json", attributes); //$NON-NLS-1$ //$NON-NLS-2$
			file.toFile().deleteOnExit();
			Files.writeString(file, json, StandardCharsets.UTF_8);
			return file;
		} catch (IOException e) {
			ChatPlugin.log("Could not give the MCP server of Eclipse to Claude Code", e); //$NON-NLS-1$
			return null;
		}
	}

	/** Deletes the file of a conversation that is over. */
	static void delete(Path file) {
		if (file != null) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				// Deleted when Eclipse ends.
			}
		}
	}
}
