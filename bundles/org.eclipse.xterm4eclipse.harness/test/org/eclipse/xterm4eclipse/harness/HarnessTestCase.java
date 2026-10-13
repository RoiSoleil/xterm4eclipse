package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.XMLMemento;
import org.eclipse.xterm4eclipse.TestWorkbench;
import org.eclipse.xterm4eclipse.XtermView;
import org.eclipse.xterm4eclipse.spi.CommandLine;
import org.eclipse.xterm4eclipse.spi.TerminalIcon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the tests of every coding agent share: a home of the user of their own, a stand-in for the
 * program of the agent that shows how it is started, and terminals that run it, with their real
 * view, browser and pseudo terminal.
 */
abstract class HarnessTestCase {

	/** An id of conversation, as the terminals choose them. */
	static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
	static final String TOKEN = "0f0f2a2e-1f9c-4c4a-9a0e-6d0f8f0f1e2b";
	static final String NAME = "eclipse-mcp-server-via-xterm4eclipse";
	static final boolean WINDOWS = TestWorkbench.WINDOWS;
	/** One display for all the tests, also those without a view. */
	static final Display DISPLAY = TestWorkbench.DISPLAY;
	private static final Pattern START = Pattern.compile("args: \\[(.*?)\\] token: \\[(.*?)\\]");

	@TempDir
	Path temp;
	/** The home of the user, where the agents keep their files. */
	Path home;
	/** The environment of Eclipse, as the agents read it. */
	final Map<String, String> environment = new HashMap<>();
	/** The questions about the MCP server of Eclipse, and the answer of the user. */
	final List<String> asked = new ArrayList<>();
	int answer = McpOffer.ADD;
	TestWorkbench workbench;

	private Path previousHome;
	private UnaryOperator<String> previousEnvironment;
	private McpOffer.Asker previousAsker;
	private long previousDelay;
	private ServerSocket server;
	/** The files of the stand-in for the program of the agent. */
	private Path stand;
	private int views;

	/** The agent under test. */
	abstract Harness harness();

	@BeforeEach
	void isolate() throws Exception {
		home = Files.createDirectories(temp.resolve("home"));
		previousHome = Places.userHome;
		previousEnvironment = Places.environment;
		previousAsker = McpOffer.asker;
		previousDelay = SessionFinder.delayMillis;
		Places.userHome = home;
		Places.environment = environment::get;
		EclipseMcp.workspace = temp.resolve("workspace");
		McpOffer.postponed = false;
		McpOffer.asker = (terminal, harness, url) -> {
			asked.add(harness.id() + ' ' + url);
			return answer;
		};
		SessionFinder.delayMillis = 200;
		HarnessPlugin.preferences().setToDefault(HarnessPlugin.PREF_OFFER_ECLIPSE_MCP);
	}

	@AfterEach
	void restore() throws Exception {
		if (workbench != null) {
			workbench.close();
		}
		if (server != null) {
			server.close();
		}
		Places.userHome = previousHome;
		Places.environment = previousEnvironment;
		McpOffer.asker = previousAsker;
		McpOffer.postponed = false;
		SessionFinder.delayMillis = previousDelay;
		EclipseMcp.workspace = null;
		HarnessPlugin.preferences().setToDefault(HarnessPlugin.PREF_OFFER_ECLIPSE_MCP);
		TestWorkbench.setDefaultCommandLine("");
	}

	// The command line of the agent, without a view.

	/** A terminal of the agent, without a view: what the agent makes of its command line. */
	final class Run {
		final FakeTerminal terminal;
		final HarnessCompanion companion;
		private final String[] commandLine;

		Run(String... commandLine) {
			this.commandLine = commandLine;
			StringBuilder line = new StringBuilder();
			for (String argument : commandLine) {
				line.append(line.isEmpty() ? "" : " ").append(CommandLine.quote(argument));
			}
			terminal = new FakeTerminal(line.toString(), temp.toFile());
			companion = (HarnessCompanion) new HarnessExtension().accompany(terminal);
			assertNotNull(companion, "a terminal of " + harness().product() + ": " + line);
		}

		/** @return the command line of a new start of the agent */
		String[] start() {
			return companion.arguments(commandLine.clone(), false);
		}

		/** @return the command line of the agent that comes back: Eclipse reopens its terminal */
		String[] resume() {
			return companion.arguments(commandLine.clone(), true);
		}

		/** The user has talked to the agent. */
		Run talked() {
			companion.lineEntered();
			return this;
		}

		/** The terminal comes back with the conversation of this id. */
		Run with(String id) {
			terminal.state.put(harness().id() + "Session", id);
			return this;
		}

		/** The id of the conversation that the terminal keeps. */
		String id() {
			return terminal.state.get(harness().id() + "Session");
		}
	}

	static String[] line(String... arguments) {
		return arguments;
	}

	/** The command lines that the agent runs as they are, new or back, even after a talk. */
	void assertLeftAlone(String[]... commandLines) {
		for (String[] commandLine : commandLines) {
			String told = String.join(" ", commandLine);
			assertArrayEquals(commandLine, new Run(commandLine).start(), told);
			assertArrayEquals(commandLine, new Run(commandLine).with(ID).talked().resume(), told);
		}
	}

	/** Command lines that do not run the agent: it does nothing in their terminals. */
	static void assertNotRecognized(String... commandLines) {
		for (String commandLine : commandLines) {
			assertNull(new HarnessExtension().accompany(new FakeTerminal(commandLine, null)), commandLine);
			assertNull(new HarnessExtension().icon(commandLine), commandLine);
		}
	}

	/** The agent is recognized however a terminal runs it, with its icon; a program of another name is not. */
	void assertRecognized(String program, String icon) {
		for (String commandLine : List.of(program, "/usr/local/bin/" + program, "/bin/zsh -l -i -c " + program,
				"cmd.exe /c C:\\npm\\" + program + ".cmd", "\"C:\\Program Files\\Agents\\" + program + ".exe\"",
				"bash -c \"'/my dir/" + program + "' --verbose\"")) {
			assertIcon(commandLine, icon);
			assertEquals(harness(), Harnesses.find(commandLine).harness(), commandLine);
		}
		assertNotRecognized(program + "x", "/usr/bin/my-" + program, "/bin/zsh -c \"" + program + "; rm x\"",
				"/bin/zsh -c \"" + program + " $(cat f)\"");
	}

	/**
	 * The menus offer the agent when its program is on the PATH: through the login shell of the user
	 * on Unix, through cmd.exe for a launcher of npm on Windows.
	 */
	void assertOffered(String program) throws Exception {
		if (!WINDOWS) {
			// The simulated Linux machine needs the files of a Unix system.
			assertEquals("/bin/zsh -l -i -c " + program, offered("Linux", Map.of("SHELL", "/bin/zsh"), program));
			assertEquals("/bin/bash -l -i -c " + program, offered("Linux", Map.of(), program));
			assertNull(offered("Linux", Map.of("SHELL", "/bin/zsh"), program + "-other"), "not on the PATH: not offered");
		}
		assertEquals(offered("Windows 11", Map.of(), program + ".exe"), bin().resolve(program + ".exe").toString());
		assertEquals(offered("Windows 11", Map.of(), program + ".cmd"), "cmd.exe /c " + bin().resolve(program + ".cmd"));
		assertNull(offered("Windows 11", Map.of(), program), "not a program of Windows");
	}

	/** The icon of the agent exists, with the one of the dense screens, and is the one of its terminals. */
	void assertIcon(String commandLine, String path) {
		TerminalIcon icon = new HarnessExtension().icon(commandLine);
		assertEquals(new TerminalIcon(HarnessExtension.class, "org.eclipse.xterm4eclipse.harness", path), icon);
		assertNotNull(HarnessExtension.class.getResource('/' + path), path);
		assertNotNull(HarnessExtension.class.getResource('/' + path.replace(".png", "@2x.png")), path);
		assertEquals(16, icon.descriptor().getImageData(100).width);
		// Through the terminal, which finds the plug-in of the agents.
		assertEquals("platform:/plugin/org.eclipse.xterm4eclipse.harness/" + path, TestWorkbench.iconUri(commandLine));
	}

	/**
	 * @param os
	 *            the system of a machine, with these programs on its PATH
	 * @return the command line that the menus of the terminal offer for the agent there, or
	 *         {@code null} if they do not offer it
	 */
	String offered(String os, Map<String, String> variables, String... programs) throws Exception {
		Path bin = Files.createDirectories(temp.resolve("bin" + temp.toFile().list().length));
		for (String program : programs) {
			Files.createFile(bin.resolve(program));
		}
		Map<String, String> machine = new HashMap<>(variables);
		machine.put("PATH", bin.toString());
		return TestWorkbench.profiles(os, machine).get(harness().label());
	}

	/** The folder of the programs of the last call of {@link #offered}. */
	Path bin() {
		return temp.resolve("bin" + (temp.toFile().list().length - 1));
	}

	// The MCP server of Eclipse.

	/**
	 * The MCP server of Eclipse runs: it tells where in the workspace, and answers there.
	 *
	 * @return its address
	 */
	String eclipseMcpRuns() throws Exception {
		server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
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
		String url = "http://127.0.0.1:" + server.getLocalPort() + "/mcp";
		Path endpoint = EclipseMcp.workspace.resolve(EclipseMcp.ENDPOINT_FILE);
		Files.createDirectories(endpoint.getParent());
		Files.writeString(endpoint, "{\"state\": \"listening\", \"url\": \"" + url + "\", \"token\": \"" + TOKEN + "\"}");
		return url;
	}

	/** @return {@code true} if the agent knows the server of this address, for a terminal in this folder */
	boolean knows(String url, File project) {
		return EclipseMcp.configured(harness().mcp(), new EclipseMcp.Endpoint(url, TOKEN), project);
	}

	/** Writes a file of the agent, with its folders. */
	static Path write(Path file, String content) throws Exception {
		Files.createDirectories(file.getParent());
		return Files.writeString(file, content);
	}

	/**
	 * The agent knows the server only once it is in this file: one of the user, or of the project
	 * when the path is relative.
	 */
	void assertKnownFrom(String file, String content) throws Exception {
		String url = "http://127.0.0.1:8642/mcp";
		File project = Files.createDirectories(temp.resolve("project")).toFile();
		assertFalse(knows(url, project), "not known yet");
		Path path = Path.of(file).isAbsolute() ? Path.of(file) : project.toPath().resolve(file);
		write(path, content.replace("{url}", "http://127.0.0.1:9000/mcp"));
		assertFalse(knows(url, project), "another server in " + file);
		write(path, content.replace("{url}", "http://localhost:8642/mcp"));
		assertTrue(knows(url, project), "known from " + file);
		if (!Path.of(file).isAbsolute()) {
			assertFalse(knows(url, null), "a file of the project");
		}
		Files.delete(path);
	}

	// The agent in a real terminal.

	/**
	 * Installs a stand-in for the program of the agent: it logs its command lines, answers the
	 * commands that add an MCP server or list the conversations, and else shows how it was started,
	 * repeats the lines it reads and fails on "quit".
	 *
	 * @return the command line of a terminal that runs it
	 */
	String program(String name) throws Exception {
		stand = Files.createTempDirectory(TestWorkbench.FOLDER.toPath(), "agent");
		Path log = stand.resolve("log");
		Path refusal = stand.resolve("refusal");
		Path list = stand.resolve("list");
		if (WINDOWS) {
			Path program = Files.writeString(stand.resolve(name + ".cmd"), "@echo off\r\n"
					// The redirection first: a digit that ends the arguments would name a stream.
					+ ">> \"" + log + "\" echo %*\r\n"
					+ "if \"%1\"==\"mcp\" goto mcp\r\n"
					+ "if \"%1\"==\"session\" if \"%2\"==\"list\" goto list\r\n"
					+ "echo args: [%*] token: [%XTERM4ECLIPSE_MCP_TOKEN%]\r\n"
					+ ":read\r\nset line=\r\nset /p line=\r\n"
					+ "if \"%line%\"==\"quit\" exit /b 3\r\necho got %line%\r\ngoto read\r\n"
					+ ":list\r\nif exist \"" + list + "\" type \"" + list + "\"\r\nexit /b 0\r\n"
					+ ":mcp\r\nif not \"%2\"==\"add\" exit /b 0\r\nif not exist \"" + refusal + "\" exit /b 0\r\n"
					+ "type \"" + refusal + "\"\r\ndel \"" + refusal + "\"\r\nexit /b 1\r\n");
			return "cmd.exe /c " + program;
		}
		Path program = Files.writeString(stand.resolve(name), "#!/bin/sh\n"
				+ "echo \"$*\" >> '" + log + "'\n"
				+ "if [ \"$1\" = mcp ]; then\n"
				+ "  if [ \"$2\" = add ] && [ -e '" + refusal + "' ]; then cat '" + refusal + "'; rm '" + refusal + "'; exit 1; fi\n"
				+ "  exit 0\nfi\n"
				+ "if [ \"$1\" = session ] && [ \"$2\" = list ]; then cat '" + list + "' 2> /dev/null; exit 0; fi\n"
				+ "echo \"args: [$*] token: [$XTERM4ECLIPSE_MCP_TOKEN]\"\n"
				+ "while read line; do\n  [ \"$line\" = quit ] && exit 3\n  echo \"got $line\"\ndone\n");
		assertTrue(program.toFile().setExecutable(true));
		return program.toString();
	}

	/** The agent refuses the first server that it is asked to add, with this message. */
	void refuseTheFirstAdd(String message) throws Exception {
		Files.writeString(stand.resolve("refusal"), message + System.lineSeparator());
	}

	/** What the agent prints when it is asked for its conversations. */
	void conversationsListed(String json) throws Exception {
		Files.writeString(stand.resolve("list"), json);
	}

	/** @return the command lines that the stand-in was run with, without the program */
	List<String> calls() throws Exception {
		Path log = stand.resolve("log");
		List<String> calls = new ArrayList<>();
		if (Files.exists(log)) {
			for (String line : Files.readAllLines(log)) {
				// cmd.exe keeps the quotes of the arguments, and says this when there is none.
				calls.add(line.replace("\"", "").replace("ECHO is off.", "").strip());
			}
		}
		return calls;
	}

	/** @return the commands about MCP servers that the stand-in was run with */
	List<String> mcpCalls() throws Exception {
		return calls().stream().filter(call -> call.startsWith("mcp ")).toList();
	}

	private TestWorkbench workbench() {
		if (workbench == null) {
			workbench = new TestWorkbench();
		}
		return workbench;
	}

	/** The user opens a terminal for this command line. */
	XtermView open(String commandLine) throws Exception {
		return workbench().openByUser(commandLine, TestWorkbench.FOLDER);
	}

	/** Eclipse restarts: it reopens the terminal of a view, as the view was. */
	XtermView reopen(XtermView view) throws Exception {
		XMLMemento memento = XMLMemento.createWriteRoot("view");
		view.saveState(memento);
		return reopen(memento);
	}

	XtermView reopen(XMLMemento memento) throws Exception {
		return workbench().open(new XtermView(), "reopened" + views++, memento);
	}

	/** @return what the view keeps of its terminal for the next start of Eclipse */
	static String kept(XtermView view, String key) {
		XMLMemento memento = XMLMemento.createWriteRoot("view");
		view.saveState(memento);
		return memento.getString(key);
	}

	/**
	 * How the agent was started in a terminal.
	 *
	 * @param arguments
	 *            its arguments
	 * @param token
	 *            the token of the MCP server of Eclipse in its environment, or nothing
	 */
	record Start(String arguments, String token) {
	}

	private static List<Start> starts(XtermView view) {
		List<Start> starts = new ArrayList<>();
		Matcher matcher = START.matcher(TestWorkbench.text(view).replace("\n", ""));
		while (matcher.find()) {
			starts.add(new Start(matcher.group(1).strip(), matcher.group(2)));
		}
		return starts;
	}

	/** Waits for the agent to start in the terminal for the nth time, and tells how it was started. */
	static Start started(XtermView view, int nth) {
		TestWorkbench.await("start " + nth + " of the agent", () -> starts(view).size() >= nth);
		TestWorkbench.pump(100);
		assertEquals(nth, starts(view).size(), TestWorkbench.text(view));
		return starts(view).get(nth - 1);
	}

	/**
	 * The life of an agent that chooses its ids itself: Eclipse can only ask it for the last
	 * conversation of the folder, which it does once the user has talked in the terminal.
	 *
	 * @param resume
	 *            the arguments that resume the last conversation
	 */
	void assertComesBackToTheLastConversation(String program, String resume) throws Exception {
		XtermView view = open(program(program));
		assertEquals("", started(view, 1).arguments(), "a new conversation");
		// Eclipse restarts before the user has said anything: there is nothing to resume.
		assertEquals("", started(reopen(view), 1).arguments());
		say(view, "hello");
		XtermView back = reopen(view);
		assertEquals(resume, started(back, 1).arguments());
		// The agent cannot resume and fails at once: Enter starts a new conversation, not the same
		// failure again.
		fail(back);
		enter(back);
		assertEquals("", started(back, 2).arguments());
		// An agent that the user has talked to and that fails goes on with the conversation on Enter.
		say(back, "again");
		fail(back);
		enter(back);
		assertEquals(resume, started(back, 3).arguments());
		// Restarted by the user, it starts a new one.
		TestWorkbench.restart(back);
		assertEquals("", started(back, 4).arguments());
		assertEquals("", started(reopen(back), 1).arguments(), "nothing kept of the conversation that the user left");
	}

	/**
	 * The life of an agent that takes the id of its conversations and keeps one file for each: every
	 * terminal has its own conversation, resumed once the agent has kept it.
	 *
	 * @param fresh
	 *            the arguments of a new conversation, {@code {id}} for its id
	 * @param resume
	 *            the arguments that resume it
	 * @param file
	 *            the file in which the agent keeps the conversation, {@code {id}} for its id
	 */
	void assertComesBackToItsOwnConversation(String program, String fresh, String resume, String file) throws Exception {
		XtermView view = open(program(program));
		String started = started(view, 1).arguments();
		String prefix = fresh.substring(0, fresh.indexOf("{id}"));
		assertTrue(started.matches(Pattern.quote(prefix) + Harness.Sessions.UUID), started);
		String id = started.substring(prefix.length());
		assertEquals(id, kept(view, harness().id() + "Session"));
		say(view, "hello");
		// Eclipse restarts before the agent has kept the conversation: the same one starts.
		assertEquals(fresh.replace("{id}", id), started(reopen(view), 1).arguments());
		write(home.resolve(file.replace("{id}", id).replace("{id8}", id.substring(0, 8))), "{}\n");
		XtermView back = reopen(view);
		assertEquals(resume.replace("{id}", id), started(back, 1).arguments());
		// Ended with an error, it goes on with its conversation on Enter; restarted by the user, it
		// starts a new one.
		fail(back);
		enter(back);
		assertEquals(resume.replace("{id}", id), started(back, 2).arguments());
		TestWorkbench.restart(back);
		String renewed = started(back, 3).arguments();
		assertTrue(renewed.matches(Pattern.quote(prefix) + Harness.Sessions.UUID), renewed);
		assertFalse(renewed.endsWith(id), "another conversation");
	}

	/**
	 * A new terminal of the agent, while the MCP server of Eclipse runs: the user is asked, the
	 * agent adds the server with its own command, then starts.
	 *
	 * @param add
	 *            the command of the agent that adds the server, {@code {url}} for its address
	 * @param refusal
	 *            what the agent answers when it knows a server of this name already, which is then
	 *            removed with {@code remove}; or {@code null} if it replaces it
	 * @param known
	 *            the file of the user in which the agent keeps its servers
	 * @return how the agent was started
	 */
	Start assertOfferedTheMcpServerOfEclipse(String program, String add, String refusal, String remove, Path known)
			throws Exception {
		String url = eclipseMcpRuns();
		String commandLine = program(program);
		if (refusal != null) {
			refuseTheFirstAdd(refusal);
		}
		XtermView view = open(commandLine);
		Start start = started(view, 1);
		assertEquals(List.of(harness().id() + ' ' + url), asked);
		String text = TestWorkbench.text(view).replace("\n", "");
		assertTrue(text.contains("[Adding the MCP server of Eclipse to " + harness().product() + "...]"), text);
		assertTrue(text.contains("[" + harness().product() + " can now use Eclipse.]"), text);
		assertTrue(text.indexOf("can now use Eclipse") < text.indexOf("args: ["), "added before the agent starts");
		String added = add.replace("{url}", url);
		assertEquals(refusal == null ? List.of(added) : List.of(added, remove, added), mcpCalls());

		// Known by the agent: not asked again.
		write(known, "\"" + url + "\"");
		started(open(commandLine), 1);
		assertEquals(1, asked.size());
		assertEquals(refusal == null ? 1 : 3, mcpCalls().size());
		return start;
	}

	/** The user talks to the agent. */
	static void say(XtermView view, String line) {
		TestWorkbench.type(view, line + "\r");
		TestWorkbench.await("answer to " + line, () -> TestWorkbench.text(view).contains("got " + line));
	}

	/** The agent ends with an error; the view stays, with its message. */
	static void fail(XtermView view) {
		int before = TestWorkbench.text(view).split("Process exited with code 3", -1).length;
		TestWorkbench.type(view, "quit\r");
		TestWorkbench.await("failure of the agent",
				() -> TestWorkbench.text(view).split("Process exited with code 3", -1).length > before);
		// The agent is told on the UI thread, a moment later.
		TestWorkbench.pump(200);
	}

	/** The user presses Enter in a terminal whose agent has ended. */
	static void enter(XtermView view) {
		TestWorkbench.type(view, "\r");
	}
}
