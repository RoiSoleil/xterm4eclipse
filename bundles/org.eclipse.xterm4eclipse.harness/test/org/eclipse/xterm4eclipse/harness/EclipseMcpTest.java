package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The MCP server of Eclipse, as the agents are told about it. */
class EclipseMcpTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.CLAUDE;
	}

	private void endpoint(String state, String url, String token) throws Exception {
		Path file = EclipseMcp.workspace.resolve(EclipseMcp.ENDPOINT_FILE);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\n  \"state\": \"" + state + "\",\n  \"url\": \"" + url + "\",\n  \"token\": \"" + token
				+ "\",\n  \"workspace\": \"/home/me/ws\",\n  \"startedAt\": 1787300000000\n}\n");
	}

	@Test
	void theServerIsFoundWhenItAnswers() throws Exception {
		assertNull(EclipseMcp.running(), "no endpoint file");
		String url = eclipseMcpRuns();
		assertEquals(new EclipseMcp.Endpoint(url, TOKEN), EclipseMcp.running());
		String port = url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/'));

		endpoint("stopped", url, TOKEN);
		assertNull(EclipseMcp.running(), "not listening");
		endpoint("listening", "http://example.org:" + port + "/mcp", TOKEN);
		assertNull(EclipseMcp.running(), "only a server of this computer");
		endpoint("listening", url, "x' ; rm -rf ~ '");
		assertNull(EclipseMcp.running(), "the token goes into a command line");
		endpoint("listening", "http://127.0.0.1:" + port + "/mcp'x", TOKEN);
		assertNull(EclipseMcp.running());
		endpoint("listening", "http://localhost:" + port + "/mcp", TOKEN);
		assertEquals(new EclipseMcp.Endpoint("http://localhost:" + port + "/mcp", TOKEN), EclipseMcp.running());

		ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		int free = closed.getLocalPort();
		closed.close();
		endpoint("listening", "http://127.0.0.1:" + free + "/mcp", TOKEN);
		assertNull(EclipseMcp.running(), "the file of an Eclipse that ended abruptly");
	}

	@Test
	void withoutAWorkspaceThereIsNoServer() {
		EclipseMcp.workspace = null;
		assertNull(EclipseMcp.running(), "outside of Eclipse");
	}
}
