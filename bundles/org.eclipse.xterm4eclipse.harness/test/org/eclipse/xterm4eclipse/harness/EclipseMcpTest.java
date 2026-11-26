package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.xterm4eclipse.TestWorkbench;
import org.eclipse.xterm4eclipse.XtermView;
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

	@Test
	void everyTerminalKnowsThePortAndTheTokenOfTheServer() throws Exception {
		assertTrue(EclipseMcp.environment().isEmpty(), "no server of Eclipse");
		String url = eclipseMcpRuns();
		String port = url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/'));
		assertEquals(Map.of("ECLIPSE_MCP_PORT", port, "ECLIPSE_MCP_TOKEN", TOKEN), EclipseMcp.environment());

		// A terminal that runs a shell, not an agent, gets them too.
		Path script = Files.writeString(Files.createTempDirectory(TestWorkbench.FOLDER.toPath(), "variables")
				.resolve("variables.sh"), "echo \"mcp: $ECLIPSE_MCP_PORT $ECLIPSE_MCP_TOKEN\"\n"
						+ "while read line; do\n  [ \"$line\" = quit ] && exit 3\n  echo \"got $line\"\ndone\n");
		String commandLine = '"' + TestWorkbench.BASH + "\" \"" + script + '"';
		XtermView view = open(commandLine);
		TestWorkbench.await("variables of the server", () -> TestWorkbench.screen(view).contains("mcp: " + port + ' ' + TOKEN));
		TestWorkbench.type(view, "hello\r");
		TestWorkbench.await("line", () -> TestWorkbench.screen(view).contains("got hello"));
	}

	@Test
	void theServerMayStillStartOnlyIfInstalledAndEclipseHasJustStarted() throws Exception {
		// The plug-in of the server is installed.
		EclipseMcp.installed = () -> true;
		EclipseMcp.uptime = () -> 0;
		assertTrue(EclipseMcp.starting(), "no endpoint file yet");
		endpoint("stopped", "http://127.0.0.1:1/mcp", TOKEN);
		assertTrue(EclipseMcp.starting(), "the file of the last run of Eclipse");

		EclipseMcp.uptime = () -> EclipseMcp.startupMillis;
		assertFalse(EclipseMcp.starting(), "too late: the server does not start");
		EclipseMcp.uptime = () -> 0;
		EclipseMcp.installed = () -> false;
		assertFalse(EclipseMcp.starting(), "no server");
		EclipseMcp.installed = () -> true;
		eclipseMcpRuns();
		assertFalse(EclipseMcp.starting(), "it listens");
	}

	@Test
	void anAgentThatEclipseReopensWaitsForTheServer() throws Exception {
		EclipseMcp.installed = () -> true;
		EclipseMcp.uptime = () -> 0;
		endpoint("stopped", "http://127.0.0.1:1/mcp", TOKEN);
		// Already known by the agent: nothing to ask once the server listens.
		HarnessPlugin.preferences().setValue(HarnessPlugin.PREF_OFFER_ECLIPSE_MCP, false);
		XtermView view = open(program("claude"));
		TestWorkbench.await("waiting", () -> TestWorkbench.text(view).contains("Waiting for the MCP server of Eclipse"));
		TestWorkbench.pump(1000);
		assertFalse(TestWorkbench.text(view).contains("args: ["), "not started before the server listens");

		eclipseMcpRuns();
		assertEquals(TOKEN, started(view, 1).token(), "started with the token of the server");
	}

	@Test
	void anAgentDoesNotWaitForAServerThatDoesNotStart() throws Exception {
		EclipseMcp.installed = () -> true;
		long[] now = {0};
		EclipseMcp.uptime = () -> now[0];
		XtermView view = open(program("claude"));
		TestWorkbench.await("waiting", () -> TestWorkbench.text(view).contains("Waiting for the MCP server of Eclipse"));
		now[0] = EclipseMcp.startupMillis;
		assertEquals("", started(view, 1).token(), "started without the server");
	}
}
