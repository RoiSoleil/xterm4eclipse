package org.eclipse.xterm4eclipse;

import static org.eclipse.xterm4eclipse.TestWorkbench.await;
import static org.eclipse.xterm4eclipse.TestWorkbench.screen;
import static org.eclipse.xterm4eclipse.TestWorkbench.type;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.e4.ui.workbench.IPresentationEngine;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IWorkbenchPartConstants;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.XMLMemento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Moves real terminals between the view and the editor area; only the workbench is faked. */
class XtermEditorTest {

	private static final String SHELL = "/bin/bash --norc --noprofile";

	private TestWorkbench workbench;
	private final List<XtermView> views = new ArrayList<>();
	private final List<XtermEditor> editors = new ArrayList<>();

	@BeforeEach
	void setUp() {
		workbench = new TestWorkbench();
		ShellProfiles.setDefaultCommandLine(SHELL);
		// The page of the workbench: opens and closes the parts as Eclipse does.
		workbench.page.on("openEditor", args -> {
			try {
				XtermEditor editor = workbench.openEditor((IEditorInput) args[0]);
				editors.add(editor);
				return editor;
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		workbench.page.on("closeEditor", args -> {
			XtermEditor editor = (XtermEditor) args[0];
			editor.dispose();
			workbench.editors.remove(editor);
			return true;
		});
		workbench.page.on("showView", args -> {
			try {
				XtermView view = new XtermView();
				workbench.open(view, (String) args[1], null);
				views.add(view);
				return view;
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		workbench.page.on("hideView", args -> {
			XtermView view = (XtermView) args[0];
			view.dispose();
			workbench.views.remove(view);
			return null;
		});
	}

	@AfterEach
	void tearDown() {
		workbench.close();
		XtermPreferencePageTest.resetPreferences();
	}

	private XtermView openView() throws Exception {
		XtermView view = new XtermView();
		workbench.open(view, null, null);
		await("shell prompt", () -> screen(view).contains("$"));
		return view;
	}

	private static void run(XtermView view, String command, String expected) {
		type(view, command + "\r");
		await("output of " + command, () -> screen(view).contains(expected));
	}

	private static IAction action(IContributionManager manager, String label) {
		for (IContributionItem item : manager.getItems()) {
			if (item instanceof ActionContributionItem action && action.getAction().getText().startsWith(label)) {
				return action.getAction();
			}
		}
		throw new AssertionError("No " + label);
	}

	private static IAction editorAction(XtermEditor editor, String label) {
		return action(editor.terminal().getViewSite().getActionBars().getToolBarManager(), label);
	}

	@Test
	void terminalMovesToTheEditorAreaAndBackWithItsRunningShell() throws Exception {
		XtermView view = openView();
		run(view, "X=moved-$((6*7)); cd /usr/share && echo before-move", "before-move\n");

		action(workbench.toolBar, "Move to the Editor Area").run();
		assertEquals(1, editors.size());
		XtermEditor editor = editors.get(0);
		assertFalse(workbench.views.contains(view), "the view is closed");
		// The screen comes along, and it is the same shell: its variables and directory are there.
		await("screen moved", () -> screen(editor.terminal()).contains("before-move"));
		run(editor.terminal(), "echo $X in $PWD", "moved-42 in /usr/share");
		assertEquals("bash", editor.getPartName());
		assertSame(editor.terminal().activityImage(), editor.getTitleImage());

		// The editor shows a running command as dirty, so that closing it asks first.
		List<Integer> properties = new ArrayList<>();
		editor.addPropertyListener((source, property) -> properties.add(property));
		type(editor.terminal(), "sleep 30\r");
		await("dirty", () -> properties.contains(IWorkbenchPartConstants.PROP_DIRTY));
		assertTrue(editor.isDirty());
		type(editor.terminal(), "\u0003");
		await("clean", () -> !editor.isDirty());
		assertEquals(XtermView.NO, editor.promptToSaveOnClose());
		editor.doSave(null);
		editor.doSaveAs();
		assertFalse(editor.isSaveAsAllowed());

		// A title set by the program names the tab of the editor too.
		run(editor.terminal(), "printf '\\033]0;in the editor\\007'; echo titled", "titled\n");
		await("title", () -> editor.getPartName().equals("in the editor"));

		// And back to a view, still the same shell.
		editorAction(editor, "Move to the Terminal View").run();
		assertEquals(1, views.size());
		assertFalse(workbench.editors.contains(editor), "the editor is closed");
		XtermView back = views.get(0);
		await("screen moved back", () -> screen(back).contains("moved-42 in /usr/share"));
		run(back, "echo back-$X", "back-moved-42");

		// exit closes the part the terminal is in.
		type(back, "exit\r");
		await("view closed", () -> !workbench.views.contains(back));
	}

	@Test
	void tabKeepsTheIconOfTheShellAndItsBadgeWhenTheTerminalMoves() throws Exception {
		XtermView view = openView();
		Image idle = view.getTitleImage();
		// A program asked for attention: the green badge.
		run(view, "printf '\\a'; echo rang", "rang\n");
		await("badge", () -> view.getTitleImage() != idle);
		ImageData done = view.getTitleImage().getImageData();

		action(workbench.toolBar, "Move to the Editor Area").run();
		XtermEditor editor = editors.get(0);
		await("moved", () -> screen(editor.terminal()).contains("rang"));
		assertArrayEquals(done.data, editor.getTitleImage().getImageData().data, "still marked");
		TestWorkbench.TabModel tab = workbench.tabs.get(workbench.tabs.size() - 1);
		await("icon in the model of the editor tab",
				() -> "platform:/plugin/org.eclipse.xterm4eclipse/icons/shells/bash.png".equals(tab.iconUri));
		assertSame(editor.getTitleImage(), tab.transientData.get(IPresentationEngine.OVERRIDE_ICON_IMAGE_KEY));
		// The lists of editors show the icon of the shell too.
		assertArrayEquals(XtermPlugin.image("icons/shells/bash.png").getImageData().data,
				editor.getEditorInput().getImageDescriptor().getImageData(100).data);

		// A running command: the orange badge at once, and the editor is marked dirty.
		type(editor.terminal(), "sleep 30\r");
		await("running", editor::isDirty);
		byte[] plain = XtermPlugin.image("icons/shells/bash.png").getImageData().data;
		await("running badge", () -> {
			byte[] shown = editor.getTitleImage().getImageData().data;
			return !java.util.Arrays.equals(done.data, shown) && !java.util.Arrays.equals(plain, shown);
		});
		ImageData running = editor.getTitleImage().getImageData();
		List<Integer> properties = new ArrayList<>();
		editorAction(editor, "Move to the Terminal View").run();
		XtermView back = views.get(0);
		back.addPropertyListener((source, property) -> properties.add(property));
		await("moved back", () -> back.isDirty());
		assertArrayEquals(running.data, back.getTitleImage().getImageData().data, "still running");
		type(back, "\u0003");
		await("done after the command", () -> !java.util.Arrays.equals(running.data, back.getTitleImage().getImageData().data));

		// An input restored after a restart knows its shell for its icon; without one, the Xterm icon.
		XMLMemento memento = XMLMemento.createWriteRoot("editor");
		memento.putString("id", "e1");
		memento.putString("shell", "/usr/bin/zsh -l");
		assertArrayEquals(XtermPlugin.image("icons/shells/zsh.png").getImageData().data,
				((XtermEditorInput) new XtermEditorInputFactory().createElement(memento)).getImageDescriptor()
						.getImageData(100).data);
		XMLMemento noShell = XMLMemento.createWriteRoot("editor");
		noShell.putString("id", "e2");
		assertArrayEquals(XtermPlugin.image("icons/xterm.png").getImageData().data,
				((XtermEditorInput) new XtermEditorInputFactory().createElement(noShell)).getImageDescriptor()
						.getImageData(100).data);
	}

	@Test
	void fullScreenProgramsKeepTheirScreenWhenTheyMove() throws Exception {
		XtermView view = openView();
		type(view, "printf '\\033[?1049h\\033[2J\\033[HFULL-SCREEN'; cat\r");
		await("alternate screen", () -> screen(view).contains("FULL-SCREEN"));
		action(workbench.toolBar, "Move to the Editor Area").run();
		XtermEditor editor = editors.get(0);
		await("alternate screen moved", () -> screen(editor.terminal()).contains("FULL-SCREEN"));
		assertFalse(screen(editor.terminal()).contains("printf"), "the normal screen is behind it");
		// cat is still the same process, reading the keyboard of the editor.
		type(editor.terminal(), "typed\r");
		await("echo of cat", () -> screen(editor.terminal()).contains("typed\ntyped"));
		type(editor.terminal(), "\u0003");
		type(editor.terminal(), "printf '\\033[?1049l'; echo normal\r");
		await("normal screen", () -> screen(editor.terminal()).contains("printf '\\033[?1049h"));
	}

	@Test
	void terminalThatCannotMoveStaysWhereItIs() throws Exception {
		XtermView view = openView();
		run(view, "Y=kept; echo ready", "ready\n");

		// The editor cannot be opened.
		workbench.page.on("openEditor", args -> {
			throw new IllegalStateException(new PartInitException("no editor"));
		});
		action(workbench.toolBar, "Move to the Editor Area").run();
		assertTrue(workbench.views.contains(view));
		run(view, "echo still-$Y", "still-kept");

		// The workbench shows something else than the terminal editor (an error part).
		workbench.page.on("openEditor", args -> null);
		action(workbench.toolBar, "Move to the Editor Area").run();
		assertTrue(workbench.views.contains(view));
		run(view, "echo again-$Y", "again-kept");

		// Same for a terminal of the editor area that cannot go to a view.
		workbench.page.on("openEditor", args -> {
			try {
				XtermEditor editor = workbench.openEditor((IEditorInput) args[0]);
				editors.add(editor);
				return editor;
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		action(workbench.toolBar, "Move to the Editor Area").run();
		XtermEditor editor = editors.get(0);
		await("moved", () -> screen(editor.terminal()).contains("again-kept"));
		workbench.page.on("showView", args -> {
			throw new IllegalStateException(new PartInitException("no view"));
		});
		editorAction(editor, "Move to the Terminal View").run();
		assertTrue(workbench.editors.contains(editor));
		run(editor.terminal(), "echo editor-$Y", "editor-kept");
	}

	@Test
	void outputAndExitDuringTheMoveAreHandedOverInOrder() {
		TerminalTransfer transfer = new TerminalTransfer(null, null, 0, 0, "sh", null, "name", 42);
		assertEquals("", transfer.screen);
		transfer.output(null, "one ".getBytes(StandardCharsets.UTF_8), 4);
		byte[] reused = "two three".getBytes(StandardCharsets.UTF_8);
		transfer.output(null, reused, 3);
		reused[0] = 'X';
		transfer.exited(null, 7);
		ByteArrayOutputStream replayed = new ByteArrayOutputStream();
		List<String> received = new ArrayList<>();
		PtySession.Listener receiver = new PtySession.Listener() {
			@Override
			public void output(PtySession session, byte[] data, int length) {
				received.add("output " + new String(data, 0, length, StandardCharsets.UTF_8));
			}

			@Override
			public void exited(PtySession session, int exitCode) {
				received.add("exited " + exitCode);
			}
		};
		transfer.handOver(receiver, data -> replayed.writeBytes(data));
		assertEquals("one two", replayed.toString(StandardCharsets.UTF_8));
		assertEquals(List.of("exited 7"), received);
		// After the hand-over, everything goes straight to the new part.
		transfer.output(null, "late".getBytes(StandardCharsets.UTF_8), 4);
		transfer.exited(null, 0);
		assertEquals(List.of("exited 7", "output late", "exited 0"), received);
		assertThrows(IllegalStateException.class, () -> transfer.handOver(receiver, data -> {
		}));
	}

	@Test
	void terminalsOfTheEditorAreaComeBackAfterARestart() throws Exception {
		XtermView view = openView();
		run(view, "cd /usr/lib && echo marker-$((6*7))", "marker-42\n");
		action(workbench.toolBar, "Move to the Editor Area").run();
		XtermEditor editor = editors.get(0);
		await("moved", () -> screen(editor.terminal()).contains("marker-42"));
		editor.terminal().rename("my editor terminal");
		assertEquals("my editor terminal", editor.getPartName());
		XtermEditorInput input = (XtermEditorInput) editor.getEditorInput();
		assertEquals("my editor terminal", input.getName());
		assertEquals(input.getName(), input.getToolTipText());
		assertFalse(input.exists(), "not in the list of recent files");
		assertSame(input, input.getPersistable());
		assertNull(input.getAdapter(String.class));
		assertTrue(input.getImageDescriptor() != null);

		// Eclipse shuts down: the input saves the terminal.
		XMLMemento memento = XMLMemento.createWriteRoot("editor");
		input.saveState(memento);
		assertEquals(XtermEditorInputFactory.ID, input.getFactoryId());
		assertEquals("/usr/lib", memento.getString("directory"));
		assertEquals("my editor terminal", memento.getString("name"));
		workbench.closing = true;
		editor.dispose();
		workbench.editors.remove(editor);
		workbench.closing = false;

		// Eclipse starts again.
		XtermEditorInput restoredInput = (XtermEditorInput) new XtermEditorInputFactory().createElement(memento);
		assertEquals(input, restoredInput);
		assertEquals(input.hashCode(), restoredInput.hashCode());
		assertNotEquals(input, new XtermEditorInput(new TerminalTransfer(null, "", 80, 24, SHELL, null, null, 0)));
		// Saved again before its editor was ever shown: the state of last time is kept.
		XMLMemento again = XMLMemento.createWriteRoot("editor");
		restoredInput.saveState(again);
		assertEquals("/usr/lib", again.getString("directory"));
		assertEquals("Xterm", restoredInput.getName());

		XtermEditor restored = workbench.openEditor(restoredInput);
		await("restored screen", () -> screen(restored.terminal()).contains("History restored"));
		assertTrue(screen(restored.terminal()).contains("marker-42"));
		assertEquals("my editor terminal", restored.getPartName());
		run(restored.terminal(), "echo in=$PWD", "in=/usr/lib");

		// Closed by the user: nothing to restore next time.
		restored.dispose();
		workbench.editors.remove(restored);
		assertFalse(Files.exists(XtermPlugin.stateDirectory().resolve(restoredInput.id() + ".screen")));

		// Only the ids this plug-in writes are accepted.
		XMLMemento forged = XMLMemento.createWriteRoot("editor");
		forged.putString("id", "../../escape");
		assertNull(new XtermEditorInputFactory().createElement(forged));
		assertNull(new XtermEditorInputFactory().createElement(XMLMemento.createWriteRoot("editor")));
		assertThrows(PartInitException.class,
				() -> new XtermEditor().init(workbench.editorSite(), new Fake().as(IEditorInput.class)));
	}

	@Test
	void editorHasTheActionsOfTheView() throws Exception {
		XtermView view = openView();
		action(workbench.toolBar, "Move to the Editor Area").run();
		XtermEditor editor = editors.get(0);
		await("moved", () -> screen(editor.terminal()).contains("$"));
		IActionBars bars = editor.terminal().getViewSite().getActionBars();
		// The menu of the view is behind the last button of the tool bar of the editor.
		IAction rename = action(bars.getMenuManager(), "Rename");
		assertTrue(rename.isEnabled());
		IContributionItem[] items = bars.getToolBarManager().getItems();
		IAction more = ((ActionContributionItem) items[items.length - 1]).getAction();
		assertEquals("More Actions", more.getToolTipText());
		more.run();
		TestWorkbench.pump(200);
		action(bars.getToolBarManager(), "Clear").run();
		action(bars.getToolBarManager(), "New Terminal").run();
		assertEquals(1, views.size(), "new terminals open in the view");

		// The editor has no global actions of its own; the rest is the one of the editor site.
		bars.setGlobalActionHandler("copy", rename);
		assertNull(bars.getGlobalActionHandler("copy"));
		bars.clearGlobalActionHandlers();
		bars.updateActionBars();
		assertNull(bars.getStatusLineManager());
		assertNotEquals(null, bars.getServiceLocator());
		assertEquals(editor.terminal().getViewSite(), editor.terminal().getViewSite());
		assertTrue(editor.terminal().getViewSite().toString().startsWith("Terminal site of"));
		assertEquals(System.identityHashCode(editor.terminal().getViewSite()), editor.terminal().getViewSite().hashCode());
		editor.setFocus();
		assertSame(editor, editor.terminal().host());
		assertSame(view, view.host());
	}

	@Test
	void shellThatHadEndedStartsAgainInItsNewPart() throws Exception {
		ShellProfiles.setDefaultCommandLine("/bin/sh -c \"echo ended; exit 3\"");
		XtermView view = new XtermView();
		workbench.open(view, null, null);
		await("ended", () -> screen(view).contains("Process exited with code 3"));
		ShellProfiles.setDefaultCommandLine(SHELL);
		action(workbench.toolBar, "Move to the Editor Area").run();
		XtermEditor editor = editors.get(0);
		// The old screen, then the same command run again.
		await("run again", () -> screen(editor.terminal()).split("Process exited with code 3", -1).length == 3);
	}
}
