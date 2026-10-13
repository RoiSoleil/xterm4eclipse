package org.eclipse.xterm4eclipse;

import static org.eclipse.xterm4eclipse.TestWorkbench.FOLDER;
import static org.eclipse.xterm4eclipse.TestWorkbench.await;
import static org.eclipse.xterm4eclipse.TestWorkbench.pump;
import static org.eclipse.xterm4eclipse.TestWorkbench.screen;
import static org.eclipse.xterm4eclipse.TestWorkbench.text;
import static org.eclipse.xterm4eclipse.TestWorkbench.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.ui.XMLMemento;
import org.eclipse.xterm4eclipse.spi.CommandLine;
import org.eclipse.xterm4eclipse.spi.Terminal;
import org.eclipse.xterm4eclipse.spi.TerminalCompanion;
import org.eclipse.xterm4eclipse.spi.TerminalExtension;
import org.eclipse.xterm4eclipse.spi.TerminalHost;
import org.eclipse.xterm4eclipse.spi.TerminalIcon;
import org.eclipse.xterm4eclipse.spi.TerminalProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What another plug-in adds to the terminals: here an extension for a program named "agent". */
class TerminalExtensionsTest {

	private static final TerminalIcon ICON = new TerminalIcon(XtermPlugin.class, "org.example.agent", "icons/xterm.png");

	@TempDir
	Path temp;
	private TestWorkbench workbench;
	/** What the extension was told, in order. */
	private final List<String> events = new ArrayList<>();
	private final List<Terminal> terminals = new ArrayList<>();
	/** Starts the program that the extension holds back. */
	private Runnable start;
	private boolean holdBack;

	private final TerminalExtension extension = new TerminalExtension() {
		@Override
		public List<TerminalProfile> profiles(TerminalHost host) {
			File agent = host.findOnPath(host.isWindows() ? "agent.exe" : "agent");
			return agent == null ? List.of() : List.of(new TerminalProfile("Agent", CommandLine.quote(agent.getPath())));
		}

		@Override
		public TerminalIcon icon(String commandLine) {
			return commandLine.contains("agent") ? ICON : null;
		}

		@Override
		public TerminalCompanion accompany(Terminal terminal) {
			if (!terminal.commandLine().contains("agent")) {
				return null;
			}
			terminals.add(terminal);
			events.add("accompany " + terminal.state("agentState"));
			return new TerminalCompanion() {
				@Override
				public boolean beforeStart(Runnable starter) {
					events.add("beforeStart");
					start = starter;
					if (holdBack) {
						terminal.print("Asking\nthe user");
						terminal.printError("Answered");
					}
					return holdBack;
				}

				@Override
				public String[] arguments(String[] arguments, boolean resume) {
					events.add("arguments " + resume);
					terminal.setState("agentState", "kept");
					String[] changed = java.util.Arrays.copyOf(arguments, arguments.length + 1);
					changed[arguments.length] = resume ? "--resumed" : "--new";
					return changed;
				}

				@Override
				public Map<String, String> environment() {
					return Map.of("AGENT_VAR", "of the extension", "TERM_PROGRAM", "agent-terminal");
				}

				@Override
				public void lineEntered() {
					events.add("lineEntered");
				}

				@Override
				public void exited(int exitCode) {
					events.add("exited " + exitCode);
				}

				@Override
				public void renewed() {
					events.add("renewed");
					terminal.setState("agentState", null);
				}

				@Override
				public void dispose() {
					events.add("dispose");
				}
			};
		}
	};

	@BeforeEach
	void setUp() {
		TerminalExtensions.set(List.of(extension));
		workbench = new TestWorkbench();
	}

	@AfterEach
	void tearDown() {
		workbench.close();
		TerminalExtensions.set(null);
		XtermPreferencePageTest.resetPreferences();
	}

	/** A program that shows how it was started, repeats its lines and fails on "quit". */
	private String agent() throws Exception {
		Path script = Files.writeString(Files.createTempDirectory(FOLDER.toPath(), "ext").resolve("agent.sh"),
				"echo \"args: $* var: $AGENT_VAR term: $TERM_PROGRAM\"\nwhile read line; do\n"
						+ "  [ \"$line\" = quit ] && exit 3\n  echo \"got $line\"\ndone\n");
		return '"' + TestWorkbench.BASH + "\" \"" + script + '"';
	}

	@Test
	void programsOfAnExtensionAreOfferedAfterTheShellsWithItsIcon() throws Exception {
		boolean windows = File.separatorChar == '\\';
		Path agent = Files.createFile(Files.createDirectories(temp.resolve("bin")).resolve(windows ? "agent.exe" : "agent"));
		ShellProfiles.Host host = new ShellProfiles.Host(windows ? "Windows 11" : "Linux",
				Map.of("PATH", agent.getParent().toString())::get, temp.resolve("none"));
		List<ShellProfiles.Profile> profiles = ShellProfiles.detect(host);
		ShellProfiles.Profile last = profiles.get(profiles.size() - 1);
		assertEquals("Agent", last.name());
		assertEquals(CommandLine.quote(agent.toString()), last.commandLine());
		assertEquals(ICON, last.terminalIcon());
		assertEquals("platform:/plugin/org.example.agent/icons/xterm.png", ShellProfiles.icon(last.commandLine()).uri());
		assertTrue(XtermPlugin.image(last.terminalIcon()) == XtermPlugin.image(ICON), "one image for an icon");

		// The shells keep the icons of the terminal itself.
		assertEquals("platform:/plugin/org.eclipse.xterm4eclipse/icons/shells/bash.png", ShellProfiles.icon("/bin/bash").uri());
		assertEquals(new TerminalIcon(XtermPlugin.class, XtermPlugin.ID, "icons/shells/gitbash.png"),
				new ShellProfiles.Profile("Git Bash", "C:\\Git\\bin\\bash.exe --login -i").terminalIcon());
		assertTrue(ShellProfiles.detect(new ShellProfiles.Host("Linux", Map.of("PATH", temp.toString())::get, temp.resolve("none")))
				.stream().noneMatch(profile -> profile.name().equals("Agent")), "not installed: not offered");
	}

	@Test
	void extensionAccompaniesItsProgramFromItsStartToItsEnd() throws Exception {
		String commandLine = agent();
		holdBack = true;
		XtermView view = workbench.openByUser(commandLine, FOLDER);
		await("asked before the start", () -> start != null && text(view).contains("[Answered]"));
		assertEquals(List.of("accompany null", "beforeStart"), events);
		assertTrue(text(view).contains("[Asking the user]"), text(view));
		pump(300);
		assertFalse(screen(view).contains("args:"), "held back until the extension starts it");
		Terminal terminal = terminals.get(0);
		assertEquals(commandLine, terminal.commandLine());
		assertEquals(FOLDER, terminal.directory());
		assertFalse(terminal.isDisposed());
		assertTrue(List.of(terminal.environment()).contains("TERM=xterm-256color"));
		assertNull(terminal.shell(), "the window of the view, which the fake site does not give");
		assertThrows(IllegalArgumentException.class, () -> terminal.setState("shell", "/bin/other"),
				"not a value of the view itself");

		start.run();
		await("started with the arguments and the variables of the extension",
				() -> screen(view).contains("args: --new var: of the extension term: agent-terminal"));
		start.run();
		pump(300);
		assertEquals(1, text(view).split("args:", -1).length - 1, "started once");
		type(view, "hello\r");
		await("line", () -> screen(view).contains("got hello"));
		assertEquals(List.of("accompany null", "beforeStart", "arguments false", "lineEntered"), events);

		// What the extension keeps comes back with the terminal after a restart of Eclipse.
		XMLMemento memento = XMLMemento.createWriteRoot("view");
		view.saveState(memento);
		assertEquals("kept", memento.getString("agentState"));
		assertEquals(commandLine, memento.getString("shell"), "the command line of the user is kept");
		events.clear();
		XtermView restored = workbench.open(new XtermView(), "restored", memento);
		await("resumed", () -> screen(restored).contains("args: --resumed"));
		assertEquals(List.of("accompany kept", "arguments true"), events, "a terminal that Eclipse restores asks nothing");

		// A program that fails comes back on Enter; restarted by the user, it starts anew.
		events.clear();
		type(restored, "quit\r");
		await("failed", () -> text(restored).contains("Process exited with code 3"));
		await("told", () -> events.contains("exited 3"));
		type(restored, "\r");
		await("back", () -> text(restored).split("args: --resumed", -1).length == 3);
		assertEquals(List.of("lineEntered", "exited 3", "arguments true"), events);
		events.clear();
		TestWorkbench.restart(restored);
		await("anew", () -> text(restored).contains("args: --new"));
		assertEquals(List.of("renewed", "arguments false"), events.subList(0, 2));
		XMLMemento again = XMLMemento.createWriteRoot("view");
		restored.saveState(again);
		assertEquals("kept", again.getString("agentState"), "set again by the new start");

		events.clear();
		workbench.close();
		workbench = new TestWorkbench();
		assertEquals(2, events.stream().filter("dispose"::equals).count());
		assertTrue(terminal.isDisposed());
		terminal.print("ignored");
	}

	@Test
	void programStartsAtOnceWhenTheExtensionAsksNothing() throws Exception {
		XtermView view = workbench.openByUser(agent(), FOLDER);
		await("started", () -> screen(view).contains("args: --new"));
		assertEquals(List.of("accompany null", "beforeStart", "arguments false"), events);

		// Another program: no extension, the terminal is itself.
		events.clear();
		TestWorkbench.setDefaultCommandLine(TestWorkbench.SHELL);
		XtermView shell = workbench.open(new XtermView(), "shell", null);
		await("shell prompt", () -> screen(shell).contains("$"));
		type(shell, "echo plain\r");
		await("output", () -> screen(shell).contains("plain\n"));
		assertTrue(events.isEmpty(), events.toString());
	}

	@Test
	void extensionThatFailsIsLeftOut() throws Exception {
		TerminalExtension broken = new TerminalExtension() {
			@Override
			public List<TerminalProfile> profiles(TerminalHost host) {
				throw new IllegalStateException("profiles");
			}

			@Override
			public TerminalIcon icon(String commandLine) {
				throw new IllegalStateException("icon");
			}

			@Override
			public TerminalCompanion accompany(Terminal terminal) {
				if (!terminal.commandLine().contains("agent")) {
					throw new IllegalStateException("accompany");
				}
				return new TerminalCompanion() {
					@Override
					public boolean beforeStart(Runnable starter) {
						throw new IllegalStateException("beforeStart");
					}

					@Override
					public String[] arguments(String[] arguments, boolean resume) {
						throw new IllegalStateException("arguments");
					}

					@Override
					public void lineEntered() {
						throw new IllegalStateException("lineEntered");
					}

					@Override
					public void exited(int exitCode) {
						throw new IllegalStateException("exited");
					}

					@Override
					public void renewed() {
						throw new IllegalStateException("renewed");
					}

					@Override
					public void dispose() {
						throw new IllegalStateException("dispose");
					}
				};
			}
		};
		TerminalExtensions.set(List.of(broken, extension));
		assertFalse(ShellProfiles.detect().isEmpty());
		assertEquals(ICON, ShellProfiles.icon("agent"), "the next extension is asked");
		assertEquals("icons/shells/bash.png", ShellProfiles.icon("/bin/bash").path());

		// Its program runs as the user wrote it.
		TerminalExtensions.set(List.of(broken));
		XtermView view = workbench.openByUser(agent(), FOLDER);
		await("started", () -> screen(view).contains("args:  var:  term: xterm4eclipse"));
		type(view, "hello\r");
		await("line", () -> screen(view).contains("got hello"));
		TestWorkbench.restart(view);
		await("restarted", () -> text(view).split("args:", -1).length == 3);
		type(view, "quit\r");
		await("failed", () -> text(view).contains("Process exited with code 3"));

		TestWorkbench.setDefaultCommandLine(TestWorkbench.SHELL);
		XtermView shell = workbench.open(new XtermView(), "shell", null);
		await("shell prompt", () -> screen(shell).contains("$"));
	}

	@Test
	void extensionsOfTheInstallationAreLookedUpOnce() {
		TerminalExtensions.set(null);
		List<TerminalExtension> installed = TerminalExtensions.all();
		assertTrue(installed == TerminalExtensions.all());
		// The terminal works the same with none.
		TerminalExtensions.set(List.of());
		assertNull(TerminalExtensions.icon("agent"));
		assertTrue(TerminalExtensions.profiles(ShellProfiles.Host.current()).isEmpty());
		assertEquals("icons/shells/shell.png", ShellProfiles.icon("agent").path());
	}
}
