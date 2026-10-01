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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuCreator;
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
import org.eclipse.ui.IWorkbenchPartConstants;
import org.eclipse.ui.XMLMemento;
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
	void clearActionEmptiesTheScreen() throws Exception {
		XtermView view = open();
		run(view, "echo to-be-cleared", "to-be-cleared\n");
		((ActionContributionItem) workbench.toolBar.getItems()[1]).getAction().run();
		await("cleared", () -> !screen(view).contains("to-be-cleared"));
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
}
