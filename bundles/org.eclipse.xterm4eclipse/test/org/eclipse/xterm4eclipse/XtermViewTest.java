package org.eclipse.xterm4eclipse;

import static org.eclipse.xterm4eclipse.TestWorkbench.await;
import static org.eclipse.xterm4eclipse.TestWorkbench.pump;
import static org.eclipse.xterm4eclipse.TestWorkbench.screen;
import static org.eclipse.xterm4eclipse.TestWorkbench.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.CommandManager;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.keys.KeyBinding;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISelectionService;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartConstants;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.XMLMemento;
import org.eclipse.ui.services.IEvaluationService;
import org.eclipse.ui.services.IServiceLocator;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drives the real view: SWT browser, xterm.js and a shell in a PTY; only the workbench is faked. */
class XtermViewTest {

	private static final String SHELL = "/bin/bash --norc --noprofile";

	private TestWorkbench workbench;
	private final List<Image> titleImages = new ArrayList<>();

	@BeforeEach
	void setUp() {
		workbench = new TestWorkbench();
		ShellProfiles.setDefaultCommandLine(SHELL);
	}

	@AfterEach
	void tearDown() {
		workbench.close();
		XtermPreferencePageTest.resetPreferences();
	}

	private XtermView open(XtermView view) throws Exception {
		view.addPropertyListener((source, property) -> {
			if (property == IWorkbenchPartConstants.PROP_TITLE) {
				try {
					titleImages.add(view.getTitleImage());
				} catch (RuntimeException e) {
					// No image set yet: the default one needs a running workbench.
				}
			}
		});
		workbench.open(view, null, null);
		await("shell prompt", () -> screen(view).contains("$"));
		return view;
	}

	private XtermView open() throws Exception {
		return open(new XtermView());
	}

	private static void run(XtermView view, String command, String expected) {
		type(view, command + "\r");
		await("output of " + command, () -> screen(view).contains(expected));
	}

	@Test
	void typedCommandsRunInTheShell() throws Exception {
		XtermView view = open();
		assertEquals("bash", view.getPartName());
		run(view, "echo re$((40+2))sult-é", "re42sult-é");
		run(view, "echo $TERM_PROGRAM", "xterm4eclipse");
		view.browser.execute("javaBinary('echo binary-$((1+1))\\r')");
		await("binary input", () -> screen(view).contains("binary-2"));
		view.setFocus();
	}

	@Test
	void browserStaysHiddenUntilTheTerminalIsDrawn() throws Exception {
		// Far beyond the test timeout: only the page itself can reveal the browser.
		XtermView.revealTimeoutMillis = 60000;
		try {
			XtermView view = new XtermView();
			workbench.activePart = view;
			workbench.open(view, null, null);
			assertFalse(view.browser.getVisible(), "no white page while loading");
			await("terminal revealed", () -> view.browser.getVisible());
			assertNotEquals("", String.valueOf(view.browser.evaluate("return document.body.style.backgroundColor")),
					"themed before being shown");
			await("shell prompt", () -> screen(view).contains("$"));
		} finally {
			XtermView.revealTimeoutMillis = 1500;
		}
	}

	@Test
	void browserIsRevealedAnywayWhenThePageStaysSilent() throws Exception {
		XtermView.revealTimeoutMillis = 0;
		try {
			XtermView view = new XtermView();
			workbench.open(view, null, null);
			pump(50);
			assertTrue(view.browser.getVisible());
			await("shell prompt", () -> screen(view).contains("$"));
		} finally {
			XtermView.revealTimeoutMillis = 1500;
		}
	}

	@Test
	void shellFollowsTheSizeOfTheView() throws Exception {
		XtermView view = open();
		type(view, "stty size\r");
		await("size", () -> screen(view).matches("(?s).*\\n\\d+ \\d+\\s.*"));
		workbench.shells.get(0).setSize(1000, 600);
		pump(800);
		Object columns = view.browser.evaluate("return document.querySelector('.xterm-rows > div').parentElement.children.length");
		run(view, "echo rows=$(stty size | cut -d' ' -f1)", "rows=" + ((Number) columns).intValue());
	}

	@Test
	void shellStartsInTheSelectedProject() throws Exception {
		IProject project = new Fake().on("getLocation", args -> org.eclipse.core.runtime.Path.fromOSString("/usr/share"))
				.as(IProject.class);
		workbench.selection = new StructuredSelection(new Fake().on("getProject", args -> project).as(IResource.class));
		XtermView view = open();
		run(view, "echo in=$PWD", "in=/usr/share");
	}

	@Test
	void selectionWithoutResourceStartsInTheHomeDirectory() throws Exception {
		workbench.selection = new StructuredSelection("not a resource");
		XtermView view = open();
		run(view, "echo in=$PWD", "in=" + System.getProperty("user.home"));
	}

	@Test
	void exitClosesTheView() throws Exception {
		XtermView view = open();
		type(view, "exit\r");
		await("view closed", () -> workbench.page.count("hideView") == 1);
	}

	@Test
	void shellThatFailsImmediatelyKeepsTheViewOpenWithItsError() throws Exception {
		ShellProfiles.setDefaultCommandLine("/bin/sh -c \"echo broken; exit 3\"");
		XtermView view = new XtermView();
		workbench.open(view, null, null);
		await("exit message", () -> screen(view).contains("Process exited with code 3"));
		assertTrue(screen(view).contains("broken"));
		assertEquals(0, workbench.page.count("hideView"));
		type(view, "ignored\r");

		// Without a live shell the directory announced through OSC 7 is the one remembered.
		view.browser.execute("javaDirectory('file:///usr/lib'); javaDirectory('/does/not/exist'); javaDirectory('')");
		XMLMemento memento = XMLMemento.createWriteRoot("view");
		view.saveState(memento);
		assertEquals("/usr/lib", memento.getString("directory"));
	}

	@Test
	void unknownShellIsReportedInTheTerminal() throws Exception {
		ShellProfiles.setDefaultCommandLine("/nonexistent/xterm-shell");
		XtermView view = new XtermView();
		workbench.open(view, null, null);
		await("error message", () -> screen(view).contains("Could not start the shell"));
		assertEquals("xterm-shell", view.getPartName());
	}

	@Test
	void bellMarksTheTabUntilTheUserComesBack() throws Exception {
		XtermView view = open();
		run(view, "printf '\\a'; echo rang", "rang");
		await("done icon", () -> titleImages.size() == 1);
		Image done = titleImages.get(0);
		assertEquals(1, workbench.progress.count("warnOfContentChange"), "inactive tab is highlighted");
		assertEquals(0, workbench.page.count("activate"), "focus is not stolen by default");

		type(view, " ");
		await("idle icon after typing", () -> titleImages.size() == 2);
		Image idle = titleImages.get(1);
		assertNotSame(done, idle);

		// Desktop notification sequence, as sent by Claude Code; a progress report is not one.
		workbench.activePart = view;
		run(view, "printf '\\033]9;4;1;50\\007'; echo progress", "progress");
		pump(300);
		assertEquals(2, titleImages.size());
		run(view, "printf '\\033]9;finished\\007'; printf '\\033]777;notify;a;b\\007'; echo notified", "notified");
		await("done icon again", () -> titleImages.size() >= 3);
		assertSame(done, titleImages.get(2));
		assertEquals(1, workbench.progress.count("warnOfContentChange"), "active tab needs no highlight");

		workbench.partListeners.get(0).partActivated(workbench.reference(view));
		assertSame(idle, titleImages.get(titleImages.size() - 1));
	}

	@Test
	void finishedCommandTakesTheFocusWhenTheUserAskedForIt() throws Exception {
		XtermPlugin.preferences().setValue(XtermPlugin.PREF_FOCUS_ON_FINISH, true);
		XtermView view = open();
		run(view, "printf '\\a'; echo rang", "rang");
		await("view activated", () -> workbench.page.count("activate") == 1);
		assertEquals(0, workbench.progress.count("warnOfContentChange"));

		// Already in front: nothing to activate.
		workbench.activePart = view;
		run(view, "printf '\\a'; echo again", "again");
		pump(300);
		assertEquals(1, workbench.page.count("activate"));
	}

	@Test
	void programThatKeepsRunningTakesTheFocusWhenItsWorkGoesQuiet() throws Exception {
		// Like Claude Code answering a prompt: output flows for a few seconds, then the program waits.
		XtermPlugin.preferences().setValue(XtermPlugin.PREF_FOCUS_ON_FINISH, true);
		XtermView view = open();
		type(view, "sh -c 'for i in $(seq 45); do printf .; sleep 0.1; done; echo quiet; sleep 30'\r");
		await("work in progress", () -> screen(view).contains("....."));
		assertEquals(0, workbench.page.count("activate"));
		// The user switches to another view shortly before the end: the terminal reports the loss of
		// focus to the program, which is not typing.
		pump(2800);
		type(view, "\u001b[O");
		await("view activated once quiet", () -> workbench.page.count("activate") == 1);
		assertTrue(screen(view).contains("quiet"), "not before the output stopped");
		assertNotSame(titleImages.get(0), titleImages.get(titleImages.size() - 1), "done icon after the running one");

		// Coming back to a program that still runs shows it as running again.
		workbench.partListeners.get(0).partActivated(workbench.reference(view));
		assertSame(titleImages.get(0), titleImages.get(titleImages.size() - 1));
		type(view, "\u0003");
	}

	@Test
	void shortOutputAndTypingAreNotMistakenForFinishedWork() throws Exception {
		XtermPlugin.preferences().setValue(XtermPlugin.PREF_FOCUS_ON_FINISH, true);
		XtermView view = open();
		// A program that prints briefly, then echoes what the user types for a few seconds.
		type(view, "sh -c 'echo started; sleep 8'\r");
		await("started", () -> screen(view).contains("started\n"));
		for (int i = 0; i < 25; i++) {
			type(view, "x");
			pump(150);
		}
		pump(2500);
		assertEquals(0, workbench.page.count("activate"), "neither the short output nor the echo is work");
		await("end of the command", () -> workbench.page.count("activate") == 1);
	}

	@Test
	void historyIsNotRestoredWhenTheUserDisabledIt() throws Exception {
		XtermView view = open();
		run(view, "echo marker-$((6*7))", "marker-42");
		XMLMemento memento = XMLMemento.createWriteRoot("view");
		view.saveState(memento);
		Path saved = XtermPlugin.stateDirectory().resolve("main.screen");
		assertTrue(Files.isRegularFile(saved));

		// Disabled after a screen was saved: it is neither shown again nor kept on disk.
		XtermPlugin.preferences().setValue(XtermPlugin.PREF_RESTORE_HISTORY, false);
		workbench.closing = true;
		XtermView restored = new XtermView();
		workbench.open(restored, null, memento);
		await("shell prompt", () -> screen(restored).contains("$"));
		pump(300);
		assertFalse(screen(restored).contains("marker-42"));
		assertFalse(screen(restored).contains("History restored"));
		restored.saveState(XMLMemento.createWriteRoot("view"));
		assertFalse(Files.exists(saved));
		assertEquals(SHELL, memento.getString("shell"), "shell and directory are still restored");
	}

	@Test
	void endOfALongCommandIsSignalled() throws Exception {
		XtermView view = open();
		type(view, "sleep 2\r");
		await("running then done icons", () -> titleImages.size() >= 2);
		assertNotSame(titleImages.get(0), titleImages.get(1));
		assertEquals(1, workbench.progress.count("warnOfContentChange"));
	}

	@Test
	void eclipseKeyBindingsAreSuspendedWhileTheTerminalIsActive() throws Exception {
		XtermView view = open();
		workbench.partListeners.get(0).partActivated(workbench.reference(null));
		workbench.partListeners.get(0).partDeactivated(workbench.reference(null));
		assertEquals(0, workbench.bindings.count("setKeyFilterEnabled"));

		workbench.partListeners.get(0).partActivated(workbench.reference(view));
		workbench.partListeners.get(0).partActivated(workbench.reference(view));
		assertEquals(1, workbench.bindings.count("setKeyFilterEnabled"));
		workbench.partListeners.get(0).partDeactivated(workbench.reference(view));
		assertEquals(2, workbench.bindings.count("setKeyFilterEnabled"));

		// Closing the active terminal gives the key bindings back.
		workbench.partListeners.get(0).partActivated(workbench.reference(view));
		view.dispose();
		workbench.views.clear();
		assertEquals(4, workbench.bindings.count("setKeyFilterEnabled"));
		assertTrue(workbench.partListeners.isEmpty());
		assertTrue(workbench.themeListeners.isEmpty());
	}

	@Test
	void titleSetByTheShellNamesTheTab() throws Exception {
		XtermView view = open();
		run(view, "printf '\\033]0;my title\\007'; echo ok1", "ok1");
		await("title", () -> view.getPartName().equals("my title"));
		assertEquals("my title", view.getTitleToolTip());
		String longTitle = "a-very-long-title-that-does-not-fit-in-a-tab-0123456789";
		run(view, "printf '\\033]0;" + longTitle + "\\007'; echo ok2", "ok2");
		await("long title", () -> view.getPartName().startsWith("\u2026"));
		assertEquals(31, view.getPartName().length());
		assertTrue(longTitle.endsWith(view.getPartName().substring(1)));
	}

	@Test
	void colorsFollowTheEclipseTheme() throws Exception {
		XtermView view = open();
		Shell parent = workbench.shells.get(0);
		Color darkBackground = new Color(30, 31, 32);
		Color lightText = new Color(220, 221, 222);
		parent.setBackground(darkBackground);
		parent.setForeground(lightText);
		workbench.themeListeners.get(0).propertyChange(null);
		await("dark background", () -> css(view, "document.body.style.backgroundColor").equals("rgb(30, 31, 32)"));
		assertEquals("#dcddde", view.browser.evaluate("return window.xtermTheme().foreground"));
		assertEquals("#f14c4c", view.browser.evaluate("return window.xtermTheme().brightRed"), "dark palette");

		// A light theme leaves views grey: the terminal uses the color of text areas instead, and an
		// unreadable foreground is ignored.
		parent.setBackground(new Color(240, 240, 240));
		parent.setForeground(new Color(238, 238, 238));
		workbench.themeListeners.get(0).propertyChange(null);
		RGB list = TestWorkbench.DISPLAY.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
		String expected = "rgb(" + list.red + ", " + list.green + ", " + list.blue + ")";
		await("list background", () -> css(view, "document.body.style.backgroundColor").equals(expected));
	}

	private static String css(XtermView view, String expression) {
		return String.valueOf(view.browser.evaluate("return " + expression));
	}

	@Test
	void copyAndPasteGoThroughTheSystemClipboard() throws Exception {
		Clipboard clipboard = new Clipboard(TestWorkbench.DISPLAY);
		Object previous = clipboard.getContents(TextTransfer.getInstance());
		try {
			XtermView view = open();
			view.browser.execute("javaCopy(''); javaCopy('copied-by-test')");
			assertEquals("copied-by-test", clipboard.getContents(TextTransfer.getInstance()));
			assertEquals("copied-by-test", view.browser.evaluate("return javaPaste()"));

			// Right click pastes, unless the program tracks the mouse and handles the click itself.
			String rightClick = "document.querySelector('#terminal').dispatchEvent(new MouseEvent('contextmenu', "
					+ "{bubbles: true, cancelable: true, button: 2, shiftKey: %s}))";
			type(view, "echo ");
			view.browser.execute(String.format(rightClick, false));
			type(view, "\r");
			await("pasted once", () -> screen(view).contains("\ncopied-by-test\n"));
			type(view, "printf '\\033[?1000h'; echo tracking\r");
			await("mouse tracking", () -> screen(view).contains("\ntracking"));
			type(view, "echo [");
			view.browser.execute(String.format(rightClick, false));
			pump(200);
			view.browser.execute(String.format(rightClick, true));
			type(view, "]\r");
			await("pasted once with Shift", () -> screen(view).contains("\n[copied-by-test]\n"));
		} finally {
			if (previous != null) {
				clipboard.setContents(new Object[] {previous}, new Transfer[] {TextTransfer.getInstance()});
			} else {
				clipboard.clearContents();
			}
			clipboard.dispose();
		}
	}

	@Test
	void restartOfEclipseBringsBackScreenShellAndDirectory() throws Exception {
		XtermView view = open();
		run(view, "cd /usr/share && echo marker-$((6*7))", "marker-42");
		XMLMemento memento = XMLMemento.createWriteRoot("view");
		view.saveState(memento);
		assertEquals(SHELL, memento.getString("shell"));
		assertEquals("/usr/share", memento.getString("directory"));
		Path saved = XtermPlugin.stateDirectory().resolve("main.screen");
		assertTrue(Files.isRegularFile(saved));

		// Eclipse shuts down: the saved screen must survive the disposal of the view.
		workbench.closing = true;
		view.dispose();
		workbench.views.clear();
		assertTrue(Files.isRegularFile(saved));
		workbench.closing = false;

		// Eclipse starts again, even with another default shell.
		ShellProfiles.setDefaultCommandLine("/bin/sh");
		XtermView restored = new XtermView();
		workbench.open(restored, null, memento);
		await("restored screen", () -> screen(restored).contains("History restored"));
		assertTrue(screen(restored).contains("marker-42"));
		assertEquals("bash", restored.getPartName());
		run(restored, "echo back-in=$PWD", "back-in=/usr/share");

		// Closed by the user: nothing to restore next time.
		restored.dispose();
		workbench.views.clear();
		assertFalse(Files.exists(saved));
	}

	@Test
	void savingBeforeTheViewIsCreatedOnlyRecordsTheShell() throws Exception {
		XtermView view = new XtermView();
		XMLMemento memento = XMLMemento.createWriteRoot("view");
		memento.putString("shell", "/bin/sh");
		memento.putString("directory", "/does/not/exist");
		view.init(workbench.site("second:id"), memento);
		XMLMemento saved = XMLMemento.createWriteRoot("view");
		view.saveState(saved);
		assertEquals("/bin/sh", saved.getString("shell"));
		assertNull(saved.getString("directory"));
	}

	@Test
	void newTerminalMenuOffersTheDetectedShells() throws Exception {
		List<XtermView> opened = new ArrayList<>();
		workbench.page.on("showView", args -> {
			assertEquals(XtermView.ID, args[0]);
			try {
				XtermView view = new XtermView();
				workbench.open(view, (String) args[1], null);
				opened.add(view);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
			return null;
		});
		String[] custom = {null};
		XtermView view = open(new XtermView() {
			@Override
			String askCustomShell(String current) {
				return custom[0];
			}
		});
		IAction newTerminal = ((ActionContributionItem) workbench.toolBar.getItems()[0]).getAction();
		IMenuCreator creator = newTerminal.getMenuCreator();
		List<ShellProfiles.Profile> profiles = ShellProfiles.detect();
		ShellProfiles.Profile first = profiles.get(0);

		// One entry per shell: choosing it opens a terminal running that shell.
		Menu menu = creator.getMenu(workbench.shells.get(0));
		assertEquals(profiles.size() + 2, menu.getItemCount());
		assertEquals(first.name(), menu.getItem(0).getText());
		assertSame(XtermPlugin.image(first.icon()), menu.getItem(0).getImage());
		menu.getItem(0).notifyListeners(SWT.Selection, new Event());
		assertEquals(1, opened.size());
		assertEquals(ShellProfiles.displayName(first.commandLine()), opened.get(0).getPartName());

		// The button itself uses the default shell.
		newTerminal.run();
		assertEquals(2, opened.size());
		assertEquals("bash", opened.get(1).getPartName());

		// The sub menu chooses the default shell.
		Menu defaults = menu.getItem(profiles.size() + 1).getMenu();
		MenuItem customItem = defaults.getItem(profiles.size());
		assertEquals("Custom: " + SHELL, customItem.getText());
		assertTrue(customItem.getSelection());
		MenuItem firstDefault = defaults.getItem(0);
		firstDefault.notifyListeners(SWT.Selection, new Event());
		assertEquals(SHELL, ShellProfiles.defaultCommandLine(), "deselected radio items are ignored");
		firstDefault.setSelection(true);
		firstDefault.notifyListeners(SWT.Selection, new Event());
		assertEquals(first.commandLine(), ShellProfiles.defaultCommandLine());

		menu = creator.getMenu(workbench.shells.get(0));
		assertEquals(first.name() + " (default)", menu.getItem(0).getText());
		defaults = menu.getItem(profiles.size() + 1).getMenu();
		customItem = defaults.getItem(profiles.size());
		assertEquals("Custom\u2026", customItem.getText());
		customItem.notifyListeners(SWT.Selection, new Event());
		customItem.setSelection(true);
		customItem.notifyListeners(SWT.Selection, new Event());
		assertEquals(first.commandLine(), ShellProfiles.defaultCommandLine(), "cancelled dialog changes nothing");
		custom[0] = "/bin/sh -e";
		customItem.notifyListeners(SWT.Selection, new Event());
		assertEquals("/bin/sh -e", ShellProfiles.defaultCommandLine());

		assertNull(creator.getMenu(menu));
		creator.dispose();
		assertTrue(menu.isDisposed());
		assertNotEquals(view, opened.get(0));
	}

	@Test
	void toolbarCommandOpensATerminalWithTheDefaultShell() throws Exception {
		List<Object[]> shown = new ArrayList<>();
		workbench.page.on("showView", args -> {
			shown.add(args);
			return null;
		});
		IWorkbenchWindow window = new Fake().on("getActivePage", args -> workbench.page.as(IWorkbenchPage.class))
				.as(IWorkbenchWindow.class);
		EvaluationContext context = new EvaluationContext(null, new Object());
		context.addVariable(ISources.ACTIVE_WORKBENCH_WINDOW_NAME, window);
		ExecutionEvent event = new ExecutionEvent(null, Map.of(), null, context);
		OpenTerminalHandler handler = new OpenTerminalHandler();

		// First terminal: the plain view. Next ones: copies with their own identifier.
		handler.execute(event);
		assertEquals(XtermView.ID, shown.get(0)[0]);
		assertNull(shown.get(0)[1]);
		workbench.page.on("findViewReference", args -> new Fake().as(IViewReference.class));
		handler.execute(event);
		assertTrue(((String) shown.get(1)[1]).startsWith("t"));

		workbench.page.on("showView", args -> {
			throw new IllegalStateException(new PartInitException("no more views"));
		});
		assertThrows(IllegalStateException.class, () -> handler.execute(event));

		// No page in the window (workbench starting or closing): nothing to do.
		IWorkbenchWindow empty = new Fake().as(IWorkbenchWindow.class);
		context.addVariable(ISources.ACTIVE_WORKBENCH_WINDOW_NAME, empty);
		assertNull(handler.execute(event));
		assertEquals(2, shown.size());
	}

	@Test
	void showInXtermOpensEachShellInTheDirectoryOfTheSelection() throws Exception {
		List<XtermView> opened = new ArrayList<>();
		workbench.page.on("showView", args -> {
			try {
				XtermView view = new XtermView();
				workbench.open(view, (String) args[1], null);
				opened.add(view);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
			return null;
		});
		// A file is selected: the terminal opens in the folder that holds it.
		IContainer folder = new Fake().on("getLocation", args -> org.eclipse.core.runtime.Path.fromOSString("/usr/lib"))
				.as(IContainer.class);
		IFile file = new Fake().on("getParent", args -> folder).as(IFile.class);
		ISelection[] selection = {new StructuredSelection(file)};
		IWorkbenchWindow window = new Fake().on("getActivePage", args -> workbench.page.as(IWorkbenchPage.class))
				.on("getSelectionService",
						args -> new Fake().on("getSelection", a -> selection[0]).as(ISelectionService.class))
				.as(IWorkbenchWindow.class);
		ShowInXtermMenu contribution = new ShowInXtermMenu();
		contribution.initialize(new Fake().on("getService", args -> window).as(IServiceLocator.class));

		Menu menu = new Menu(workbench.shells.isEmpty() ? new Shell(TestWorkbench.DISPLAY) : workbench.shells.get(0));
		contribution.fill(menu, 0);
		List<ShellProfiles.Profile> profiles = ShellProfiles.detect();
		assertEquals(profiles.stream().map(ShellProfiles.Profile::name).toList(),
				List.of(menu.getItems()).stream().map(MenuItem::getText).toList());

		int bash = profiles.stream().map(ShellProfiles.Profile::name).toList().indexOf("bash");
		assertSame(XtermPlugin.image("icons/shells/bash.png"), menu.getItem(bash).getImage(), "icon of the shell");
		menu.getItem(bash).notifyListeners(SWT.Selection, new Event());
		assertEquals(1, opened.size());
		assertEquals("bash", opened.get(0).getPartName());
		await("shell prompt", () -> screen(opened.get(0)).contains("$"));
		run(opened.get(0), "echo in=$PWD", "in=/usr/lib");

		// A folder is selected: the terminal opens in it.
		selection[0] = new StructuredSelection(folder);
		assertEquals(new File("/usr/lib"), XtermView.directoryOf(folder));
		assertNull(XtermView.directoryOf("not a resource"));
		assertNull(XtermView.directoryOf(new Fake().on("getParent", args -> new Fake().as(IContainer.class)).as(IFile.class)));

		// The selection may change while the menu is shown: the one the menu was opened on counts.
		menu.dispose();
		menu = new Menu(workbench.shells.get(0));
		contribution.fill(menu, 0);
		selection[0] = new StructuredSelection();
		menu.getItem(bash).notifyListeners(SWT.Selection, new Event());
		assertEquals(2, opened.size());
		await("shell prompt", () -> screen(opened.get(1)).contains("$"));
		run(opened.get(1), "echo second-in=$PWD", "second-in=/usr/lib");

		// A context menu tells which element it was opened on, whatever the active part selects.
		IEvaluationContext state = new Fake()
				.on("getVariable", args -> ISources.ACTIVE_MENU_SELECTION_NAME.equals(args[0])
						? new StructuredSelection(new Fake().on("getLocation",
								a -> org.eclipse.core.runtime.Path.fromOSString("/usr/share")).as(IContainer.class))
						: null)
				.as(IEvaluationContext.class);
		IEvaluationService evaluation = new Fake().on("getCurrentState", args -> state).as(IEvaluationService.class);
		ShowInXtermMenu fromMenu = new ShowInXtermMenu();
		fromMenu.initialize(new Fake()
				.on("getService", args -> args[0] == IEvaluationService.class ? evaluation : window)
				.as(IServiceLocator.class));
		menu.dispose();
		menu = new Menu(workbench.shells.get(0));
		fromMenu.fill(menu, 0);
		menu.getItem(bash).notifyListeners(SWT.Selection, new Event());
		assertEquals(3, opened.size());
		await("shell prompt", () -> screen(opened.get(2)).contains("$"));
		run(opened.get(2), "echo menu-in=$PWD", "menu-in=/usr/share");

		// Nothing usable selected, or no page: no directory is forced, nothing breaks.
		menu.dispose();
		menu = new Menu(workbench.shells.get(0));
		contribution.fill(menu, 0);
		menu.getItem(bash).notifyListeners(SWT.Selection, new Event());
		assertEquals(4, opened.size());
		workbench.page.on("showView", args -> {
			throw new IllegalStateException(new PartInitException("no more views"));
		});
		assertThrows(IllegalStateException.class, () -> XtermView.open(workbench.page.as(IWorkbenchPage.class), SHELL));
		ShowInXtermMenu detached = new ShowInXtermMenu();
		detached.initialize(new Fake().as(IServiceLocator.class));
		detached.fill(menu, 0);
		menu.getItem(0).notifyListeners(SWT.Selection, new Event());
		menu.dispose();
	}

	@Test
	void clearActionEmptiesTheScreen() throws Exception {
		XtermView view = open();
		run(view, "echo to-be-cleared", "to-be-cleared\n");
		((ActionContributionItem) workbench.toolBar.getItems()[1]).getAction().run();
		await("cleared", () -> !screen(view).contains("to-be-cleared"));
	}

	@Test
	void chosenEclipseShortcutsRunTheirCommandInsteadOfGoingToTheShell() throws Exception {
		CommandManager commands = new CommandManager();
		Command quickAccess = commands.getCommand("quickAccess");
		quickAccess.setHandler(new AbstractHandler() {
			@Override
			public Object execute(ExecutionEvent event) {
				return null;
			}
		});
		ParameterizedCommand command = new ParameterizedCommand(quickAccess, null);
		List<Object> executed = new ArrayList<>();
		List<String> looked = new ArrayList<>();
		workbench.bindings.on("getPerfectMatch", args -> {
			looked.add(args[0].toString());
			return args[0].toString().equals("CTRL+3") ? new KeyBinding((KeySequence) args[0], command,
					"scheme", "context", null, null, null, Binding.SYSTEM) : null;
		});
		workbench.handlers.on("executeCommand", args -> executed.add(args[0]));
		XtermView view = open();

		// Ctrl+3 is bound: Eclipse runs Quick Access and the shell does not see the key.
		type(view, "echo [");
		key(view, "Digit3", "3", true, false);
		await("command run", () -> executed.size() == 1);
		assertSame(command, executed.get(0));
		// Ctrl+Shift+R is in the list but not bound, Ctrl+Shift+Y is not in the list: both for the shell.
		key(view, "KeyR", "R", true, true);
		key(view, "KeyY", "Y", true, true);
		type(view, "]\r");
		await("line run", () -> screen(view).contains("\n[]\n"));
		assertEquals(List.of("CTRL+3", "CTRL+SHIFT+R"), looked);

		// The list follows the preference.
		XtermPlugin.preferences().setValue(XtermPlugin.PREF_ECLIPSE_SHORTCUTS, "CTRL+SHIFT+Y");
		await("new list", () -> {
			key(view, "KeyY", "Y", true, true);
			return looked.size() > 2;
		});
		assertEquals("CTRL+SHIFT+Y", looked.get(looked.size() - 1));
		assertFalse(view.runEclipseShortcut("not a key+++"));
		assertFalse(view.runEclipseShortcut(null));
	}

	private static void key(XtermView view, String code, String key, boolean ctrl, boolean shift) {
		view.browser.execute("document.querySelector('.xterm-helper-textarea').dispatchEvent(new KeyboardEvent('keydown', "
				+ "{key: '" + key + "', code: '" + code + "', ctrlKey: " + ctrl + ", shiftKey: " + shift
				+ ", bubbles: true, cancelable: true}))");
	}

	@Test
	void eclipseShortcutsAreNamedLikeInThePage() {
		assertEquals(List.of("CTRL+3", "CTRL+SHIFT+F7", "ALT+CTRL+SHIFT+X", "CTRL+PAGE_UP", "F11"),
				EclipseShortcuts.parse("M1+3, , M1+M2+F7, M1+M2+M3+X, CTRL+PAGE_UP, F11, CTRL+X CTRL+S, bad+++, CTRL+"));
		assertEquals("[\"CTRL+3\",\"a\\\"b\\\\\"]", EclipseShortcuts.toJson(List.of("CTRL+3", "a\"b\\")));
		assertNull(EclipseShortcuts.sequence("bad+++"));
	}

	@Test
	void selectedTextRunsInTheLastActiveTerminal() throws Exception {
		List<XtermView> opened = new ArrayList<>();
		workbench.page.on("showView", args -> {
			try {
				XtermView view = new XtermView();
				workbench.open(view, (String) args[1], null);
				opened.add(view);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
			return null;
		});
		IWorkbenchPage page = workbench.workbenchPage;

		// No terminal yet: one is opened and runs the text once its shell is ready.
		XtermView.run(page, "echo first-$((1+1))\n\n");
		assertEquals(1, opened.size());
		await("run in a new terminal", () -> screen(opened.get(0)).contains("first-2\n"));

		// A terminal was active: the text runs there, as one block even on several lines.
		XtermView view = open();
		workbench.partListeners.get(workbench.partListeners.size() - 1).partActivated(workbench.reference(view));
		XtermView.run(page, "echo second-é\necho third");
		await("run in the active terminal", () -> screen(view).contains("second-é\nthird\n"));
		assertEquals(1, opened.size());
		assertEquals(1, workbench.page.count("bringToTop"));

		// Through the command, from the selection of an editor.
		IWorkbenchWindow window = new Fake().on("getActivePage", args -> page).as(IWorkbenchWindow.class);
		EvaluationContext context = new EvaluationContext(null, new Object());
		context.addVariable(ISources.ACTIVE_WORKBENCH_WINDOW_NAME, window);
		Document document = new Document("echo line-one\necho line-$((1+1))\n");
		context.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, new TextSelection(document, 15, 0));
		ITextEditor editor = new Fake()
				.on("getDocumentProvider", args -> new Fake().on("getDocument", a -> document).as(IDocumentProvider.class))
				.as(ITextEditor.class);
		context.addVariable(ISources.ACTIVE_PART_NAME, editor);
		new RunSelectionHandler().execute(new ExecutionEvent(null, Map.of(), null, context));
		await("line of the cursor run", () -> screen(view).contains("\nline-2\n"));

		// Nothing to run: no text selection, a blank line, a part that is not a text editor.
		assertNull(RunSelectionHandler.textToRun(new StructuredSelection("x"), editor));
		assertEquals("echo line-one", RunSelectionHandler.textToRun(new TextSelection(document, 0, 13), null));
		assertNull(RunSelectionHandler.textToRun(new TextSelection(document, 0, 0), null));
		assertNull(RunSelectionHandler.textToRun(new TextSelection(document, 500, 0), editor));
		assertNull(RunSelectionHandler.textToRun(new TextSelection(document, 0, 0), new Fake().as(ITextEditor.class)));
		context.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, new TextSelection(document, document.getLength(), 0));
		assertNull(new RunSelectionHandler().execute(new ExecutionEvent(null, Map.of(), null, context)));

		// Closed: the next text goes to a new terminal again.
		view.dispose();
		workbench.views.remove(view);
		XtermView.run(page, "echo fourth");
		assertEquals(2, opened.size());
		workbench.page.on("showView", args -> {
			throw new IllegalStateException(new PartInitException("no more views"));
		});
		context.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, new TextSelection(document, 0, 4));
		assertThrows(IllegalStateException.class,
				() -> new RunSelectionHandler().execute(new ExecutionEvent(null, Map.of(), null, context)));
	}

	@Test
	void directoriesAnnouncedByTheShellAreParsed() {
		assertEquals(new File("/home/me/my project"), XtermView.parseDirectory("file://host/home/me/my%20project"));
		assertEquals(new File("C:/Users/me"), XtermView.parseDirectory("file:///C:/Users/me"));
		assertEquals(new File("C:\\Users\\me"), XtermView.parseDirectory(" C:\\Users\\me "));
		assertNull(XtermView.parseDirectory("file://host/bad path"));
		assertNull(XtermView.parseDirectory("file://host"));
		assertNull(XtermView.parseDirectory(" "));
		assertNull(XtermView.parseDirectory(null));
	}

	/** Drops data on the terminal as the browser does when the user releases a drag over it. */
	private static void drop(XtermView view, String uris, String text) {
		view.browser.execute("var data = new DataTransfer();" //
				+ (uris == null ? "" : "data.setData('text/uri-list', '" + uris + "');") //
				+ (text == null ? "" : "data.setData('text/plain', '" + text + "');") //
				+ "document.querySelector('.xterm').dispatchEvent(new DragEvent('drop', "
				+ "{dataTransfer: data, bubbles: true, cancelable: true}));");
	}

	@Test
	void droppedFilesAreTypedAsQuotedPaths() throws Exception {
		XtermView view = open();
		type(view, "printf '<%s>' ");
		drop(view, "# comment\\r\\nfile:///tmp/my%20dir/it%27s\\r\\nfile:///usr/share\\r\\nhttp://example.org/", null);
		type(view, "\r");
		await("dropped paths", () -> screen(view).contains("</tmp/my dir/it's></usr/share>"));

		// Resources dragged from an Eclipse view: the browser does not know their path.
		IResource resource = new Fake().on("getLocation", args -> IPath.fromOSString("/usr/lib")).as(IResource.class);
		LocalSelectionTransfer.getTransfer().setSelection(new StructuredSelection(new Object[] {resource, new File("/etc"), "other"}));
		try {
			type(view, "printf '[%s]' ");
			drop(view, "", "ignored");
			type(view, "\r");
			await("dragged resources", () -> screen(view).contains("[/usr/lib][/etc]"));
		} finally {
			LocalSelectionTransfer.getTransfer().setSelection(null);
		}

		// Plain text, from an editor for instance.
		type(view, "echo ");
		drop(view, null, "dropped-text");
		type(view, "\r");
		await("dropped text", () -> screen(view).contains("dropped-text\n"));
	}

	@Test
	void droppedPathsAreQuotedForTheShell() {
		assertEquals("/usr/share '/tmp/a b' ", XtermView.quotePaths(List.of("/usr/share", "/tmp/a b"), "/bin/bash"));
		assertEquals("'it'\\''s' ", XtermView.quotePaths(List.of("it's"), "zsh -l"));
		assertEquals("C:/Users/me/a.txt 'C:/Program Files/x' ",
				XtermView.quotePaths(List.of("C:\\Users\\me\\a.txt", "C:\\Program Files\\x"), "\"C:\\Git\\bin\\bash.exe\" --login -i"));
		assertEquals("C:\\Users\\me \"C:\\Program Files\\x\" \"a&b\" ",
				XtermView.quotePaths(List.of("C:\\Users\\me", "C:\\Program Files\\x", "a&b"), "cmd.exe"));
		assertEquals("C:\\Users\\me 'C:\\My Files\\it''s' ",
				XtermView.quotePaths(List.of("C:\\Users\\me", "C:\\My Files\\it's"), "powershell.exe -NoLogo"));
		assertEquals("'a b' ", XtermView.quotePaths(List.of("a b"), "pwsh"));
		assertEquals("x ", XtermView.quotePaths(List.of("x"), null));
		assertEquals(List.of(new File("C:/Users/me").getPath()), XtermView.droppedPaths("file:/C:/Users/me"));
		assertEquals(List.of(new File("//server/share/x").getPath()), XtermView.droppedPaths("file://server/share/x"));
		assertEquals(List.of(new File("/tmp").getPath()), XtermView.droppedPaths("file://localhost/tmp"));
		assertEquals(List.of(), XtermView.droppedPaths("file:bad path\nfile:"));
		assertEquals(List.of(), XtermView.droppedPaths(null));
	}

	@Test
	void closingAskForConfirmationWhileACommandRuns() throws Exception {
		List<Boolean> answers = new ArrayList<>(List.of(false, true));
		int[] asked = {0};
		List<Integer> properties = new ArrayList<>();
		XtermView view = open(new XtermView() {
			@Override
			boolean confirmClose() {
				asked[0]++;
				return answers.remove(0);
			}
		});
		view.addPropertyListener((source, property) -> properties.add(property));
		assertFalse(view.isDirty());
		assertTrue(view.isSaveOnCloseNeeded());
		assertFalse(view.isSaveAsAllowed());
		assertEquals(XtermView.NO, view.promptToSaveOnClose(), "nothing runs: closed without asking");
		assertEquals(0, asked[0]);

		type(view, "sleep 30\r");
		await("dirty while the command runs", () -> properties.contains(IWorkbenchPartConstants.PROP_DIRTY));
		assertTrue(view.isDirty());
		assertEquals(XtermView.CANCEL, view.promptToSaveOnClose(), "the user keeps the terminal");
		assertEquals(XtermView.NO, view.promptToSaveOnClose(), "the user closes it anyway");
		assertEquals(2, asked[0]);
		view.doSave(null);
		view.doSaveAs();
		assertTrue(view.isDirty(), "saving does not stop the command");

		properties.clear();
		type(view, "\u0003");
		await("clean once the command is over", () -> properties.contains(IWorkbenchPartConstants.PROP_DIRTY));
		assertFalse(view.isDirty());
		assertEquals(XtermView.NO, view.promptToSaveOnClose());
		assertEquals(2, asked[0]);
	}

	@Test
	void stateOfAnEarlierSessionDoesNotOverrideTheChosenDirectory() throws Exception {
		XMLMemento memento = XMLMemento.createWriteRoot("view");
		memento.putString("shell", "/bin/sh");
		memento.putString("directory", "/usr");
		List<XtermView> opened = new ArrayList<>();
		workbench.page.on("showView", args -> {
			try {
				XtermView view = new XtermView();
				workbench.open(view, (String) args[1], memento);
				opened.add(view);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
			return null;
		});
		XtermView.open(workbench.page.as(IWorkbenchPage.class), SHELL, new File("/usr/lib"));
		XtermView view = opened.get(0);
		assertEquals("bash", view.getPartName());
		await("shell prompt", () -> screen(view).contains("$"));
		run(view, "echo chosen=$PWD", "chosen=/usr/lib");
	}
}
