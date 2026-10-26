package org.eclipse.xterm4eclipse.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import org.eclipse.xterm4eclipse.harness.EclipseMcp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The MCP server of Eclipse, as a chat gives it to Claude Code. */
class EclipseMcpConfigTest {

	private static final EclipseMcp.Endpoint ENDPOINT = new EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", "token-1234567890");

	private Supplier<EclipseMcp.Endpoint> previousServer;
	private BiPredicate<EclipseMcp.Endpoint, File> previousKnown;

	@BeforeEach
	void setUp() {
		previousServer = EclipseMcpConfig.server;
		previousKnown = EclipseMcpConfig.known;
		EclipseMcpConfig.server = () -> ENDPOINT;
		EclipseMcpConfig.known = (endpoint, directory) -> false;
	}

	@AfterEach
	void tearDown() {
		EclipseMcpConfig.server = previousServer;
		EclipseMcpConfig.known = previousKnown;
		ChatPlugin.preferences().setToDefault(ChatPlugin.PREF_ECLIPSE_MCP);
	}

	@Test
	@SuppressWarnings("unchecked")
	void givesTheServerToTheConversationOnly() throws Exception {
		Path file = EclipseMcpConfig.write(new File("."));
		assertNotNull(file);
		try {
			Map<String, Object> servers = Json.object(Json.object(Files.readString(file)), "mcpServers");
			Map<String, Object> server = Json.object(servers, EclipseMcp.NAME);
			assertEquals("http", server.get("type"));
			assertEquals(ENDPOINT.url(), server.get("url"));
			assertEquals("Bearer " + ENDPOINT.token(), ((Map<String, Object>) server.get("headers")).get("Authorization"));
			if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
				assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), "it has the token");
			}
		} finally {
			EclipseMcpConfig.delete(file);
		}
		assertFalse(Files.exists(file), "deleted when the conversation is over");
		EclipseMcpConfig.delete(null);
	}

	@Test
	void givesNothingWithoutAServerOrWhenClaudeKnowsIt() {
		EclipseMcpConfig.server = () -> null;
		assertNull(EclipseMcpConfig.write(new File(".")), "the server does not run");
		EclipseMcpConfig.server = () -> ENDPOINT;
		EclipseMcpConfig.known = (endpoint, directory) -> true;
		assertNull(EclipseMcpConfig.write(new File(".")), "Claude Code has it in its configuration");
	}

	@Test
	void givesNothingWhenThePreferenceSaysSo() {
		ChatPlugin.preferences().setValue(ChatPlugin.PREF_ECLIPSE_MCP, false);
		assertNull(EclipseMcpConfig.write(new File(".")));
		assertTrue(ChatPlugin.preferences().getDefaultBoolean(ChatPlugin.PREF_ECLIPSE_MCP), "on by default");
	}
}
