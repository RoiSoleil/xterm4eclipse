package org.eclipse.xterm4eclipse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EclipseMcpTest {

	private static final String TOKEN = "0f0f2a2e-1f9c-4c4a-9a0e-6d0f8f0f1e2b";

	@TempDir
	Path temp;
	private Path previousHome;

	@BeforeEach
	void setUp() {
		EclipseMcp.workspace = temp.resolve("workspace");
		previousHome = ClaudeSessions.home;
		ClaudeSessions.home = temp.resolve("home").resolve(".claude");
	}

	@AfterEach
	void tearDown() {
		EclipseMcp.workspace = null;
		ClaudeSessions.home = previousHome;
	}

	private void endpoint(String state, String url, String token) throws Exception {
		Path file = EclipseMcp.workspace.resolve(EclipseMcp.ENDPOINT_FILE);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\n  \"state\": \"" + state + "\",\n  \"url\": \"" + url + "\",\n  \"token\": \"" + token
				+ "\",\n  \"workspace\": \"/home/me/ws\",\n  \"startedAt\": 1787300000000\n}\n");
	}

	/** A server of this computer that accepts its connections, as the MCP server of Eclipse does. */
	static ServerSocket server() throws Exception {
		ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		Thread acceptor = new Thread(() -> {
			while (!server.isClosed()) {
				try {
					server.accept().close();
				} catch (Exception e) {
					// Closed.
				}
			}
		});
		acceptor.setDaemon(true);
		acceptor.start();
		return server;
	}

	@Test
	void theServerIsFoundWhenItAnswers() throws Exception {
		assertNull(EclipseMcp.running(), "no endpoint file");
		try (ServerSocket server = server()) {
			String url = "http://127.0.0.1:" + server.getLocalPort() + "/mcp";
			endpoint("listening", url, TOKEN);
			assertEquals(new EclipseMcp.Endpoint(url, TOKEN), EclipseMcp.running());

			endpoint("stopped", url, TOKEN);
			assertNull(EclipseMcp.running(), "not listening");
			endpoint("listening", "http://example.org:" + server.getLocalPort() + "/mcp", TOKEN);
			assertNull(EclipseMcp.running(), "only a server of this computer");
			endpoint("listening", url, "x' ; rm -rf ~ '");
			assertNull(EclipseMcp.running(), "the token goes into a command line");
			endpoint("listening", "http://127.0.0.1:" + server.getLocalPort() + "/mcp'x", TOKEN);
			assertNull(EclipseMcp.running());
		}
		ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		int port = closed.getLocalPort();
		closed.close();
		endpoint("listening", "http://127.0.0.1:" + port + "/mcp", TOKEN);
		assertNull(EclipseMcp.running(), "the file of an Eclipse that ended abruptly");
	}

	@Test
	void claudeCodeMayKnowTheServerAlready() throws Exception {
		EclipseMcp.Endpoint endpoint = new EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", TOKEN);
		File project = Files.createDirectories(temp.resolve("project")).toFile();
		assertFalse(EclipseMcp.configured(endpoint, project));

		Path config = Files.createDirectories(temp.resolve("home")).resolve(".claude.json");
		Files.writeString(config, "{\"mcpServers\": {\"other\": {\"url\": \"http://127.0.0.1:9000/mcp\"}}}");
		assertFalse(EclipseMcp.configured(endpoint, project), "another server");
		Files.writeString(config, "{\"mcpServers\": {\"eclipse\": {\"type\": \"http\", \"url\": \"http://localhost:8642/mcp\"}}}");
		assertTrue(EclipseMcp.configured(endpoint, project), "for the user");
		Files.delete(config);

		Files.writeString(project.toPath().resolve(".mcp.json"), "{\"mcpServers\": {\"e\": {\"url\": \"http://127.0.0.1:8642/mcp\"}}}");
		assertTrue(EclipseMcp.configured(endpoint, project), "for the project");
		assertFalse(EclipseMcp.configured(endpoint, null));
	}

	@Test
	void theSameClaudeCodeAddsTheServer() {
		EclipseMcp.Endpoint endpoint = new EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", TOKEN);
		String[] add = EclipseMcp.addArguments(endpoint);
		assertArrayEquals(new String[] {"add", "--transport", "http", "--scope", "user", "eclipse", "http://127.0.0.1:8642/mcp",
				"--header", "Authorization: Bearer " + TOKEN}, add);
		assertArrayEquals(new String[] {"/usr/bin/claude", "mcp", "add", "--transport", "http", "--scope", "user", "eclipse",
				"http://127.0.0.1:8642/mcp", "--header", "Authorization: Bearer " + TOKEN},
				EclipseMcp.mcpCommand(new String[] {"/usr/bin/claude", "--model", "opus"}, add));
		assertArrayEquals(new String[] {"cmd.exe", "/c", "C:\\npm\\claude.cmd", "mcp", "remove", "eclipse"},
				EclipseMcp.mcpCommand(new String[] {"cmd.exe", "/c", "C:\\npm\\claude.cmd", "--resume", "x"}, "remove", "eclipse"));
		// Through the login shell of the user, every argument quoted.
		assertArrayEquals(new String[] {"/bin/zsh", "-l", "-i", "-c",
				"claude 'mcp' 'add' '--transport' 'http' '--scope' 'user' 'eclipse' 'http://127.0.0.1:8642/mcp' '--header' "
						+ "'Authorization: Bearer " + TOKEN + "'"},
				EclipseMcp.mcpCommand(new String[] {"/bin/zsh", "-l", "-i", "-c", "claude --session-id x"}, add));
		assertArrayEquals(new String[] {"bash", "-c", "'/my dir/claude' 'mcp' 'list'"},
				EclipseMcp.mcpCommand(new String[] {"bash", "-c", "'/my dir/claude' --model opus"}, "list"));
		assertNull(EclipseMcp.mcpCommand(new String[] {"/bin/bash"}, "list"));
		assertNull(EclipseMcp.mcpCommand(new String[] {"/bin/zsh", "-c", "vim"}, "list"));
		assertNull(EclipseMcp.mcpCommand(new String[] {}, "list"));
	}
}
