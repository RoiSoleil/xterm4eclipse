package org.eclipse.xterm4eclipse;

import static org.eclipse.xterm4eclipse.TestWorkbench.await;
import static org.eclipse.xterm4eclipse.TestWorkbench.pump;
import static org.eclipse.xterm4eclipse.TestWorkbench.screen;
import static org.eclipse.xterm4eclipse.TestWorkbench.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.e4.ui.model.application.ui.advanced.MAdvancedFactory;
import org.eclipse.e4.ui.model.application.ui.advanced.MArea;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspective;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.basic.MBasicFactory;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.XMLMemento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Moves a real terminal view between a stack of views and the editor area of a model of the Eclipse 4
 * workbench, as dragging its tab does.
 */
class MoveToEditorAreaTest {

	private static final String SHELL = TestWorkbench.SHELL;

	private TestWorkbench workbench;

	/** The model of the window: a perspective with a stack of views and the shared editor area. */
	private MPerspective perspective;
	private MPartStack bottom;
	private MPartStack editors;
	private MPart part;
	private MPlaceholder placeholder;

	@BeforeEach
	void setUp() {
		workbench = new TestWorkbench();
		ShellProfiles.setDefaultCommandLine(SHELL);
		perspective = MAdvancedFactory.INSTANCE.createPerspective();
		bottom = MBasicFactory.INSTANCE.createPartStack();
		bottom.setElementId("bottom");
		perspective.getChildren().add(bottom);

		MArea area = MAdvancedFactory.INSTANCE.createArea();
		area.setElementId("org.eclipse.ui.editorss");
		editors = MBasicFactory.INSTANCE.createPartStack();
		editors.setElementId("org.eclipse.e4.primaryDataStack");
		area.getChildren().add(editors);
		MPlaceholder areaPlaceholder = MAdvancedFactory.INSTANCE.createPlaceholder();
		areaPlaceholder.setElementId("org.eclipse.ui.editorss");
		areaPlaceholder.setRef(area);
		area.setCurSharedRef(areaPlaceholder);
		perspective.getChildren().add(areaPlaceholder);

		// The view: a placeholder in the perspective for the part shared by the perspectives.
		part = MBasicFactory.INSTANCE.createPart();
		part.setElementId(XtermView.ID);
		placeholder = MAdvancedFactory.INSTANCE.createPlaceholder();
		placeholder.setElementId(XtermView.ID);
		placeholder.setRef(part);
		part.setCurSharedRef(placeholder);
		bottom.getChildren().add(placeholder);
		workbench.page.on("activate", args -> null);
	}

	@AfterEach
	void tearDown() {
		workbench.close();
		XtermPreferencePageTest.resetPreferences();
	}

	private XtermView openView() throws Exception {
		workbench.nextPart = part;
		XtermView view = new XtermView();
		workbench.open(view, null, null);
		await("shell prompt", () -> screen(view).contains("$"));
		return view;
	}

	private static void run(XtermView view, String command, String expected) {
		type(view, command + "\r");
		try {
			await("output of " + command, () -> screen(view).contains(expected));
		} catch (AssertionError e) {
			throw new AssertionError(e.getMessage() + ", expected " + expected + " on the screen:\n" + screen(view), e);
		}
	}

	private IAction moveAction() {
		for (IContributionItem item : workbench.toolBar.getItems()) {
			if (item instanceof ActionContributionItem action && action.getAction().getText().startsWith("Move")) {
				return action.getAction();
			}
		}
		throw new AssertionError("No move action");
	}

	@Test
	void viewMovesToTheEditorAreaAndBackWithItsShell() throws Exception {
		XtermView view = openView();
		run(view, "X=still-$((6*7))", "$");
		IAction move = moveAction();
		assertEquals("Move to the Editor Area", move.getText());
		assertTrue(move.isEnabled());
		assertFalse(view.isInEditorArea());

		// The view itself goes to the stack of the editors, as when its tab is dragged there.
		move.run();
		assertSame(editors, placeholder.getParent());
		assertSame(placeholder, editors.getSelectedElement());
		assertFalse(bottom.getChildren().contains(placeholder));
		assertTrue(view.isInEditorArea());
		assertEquals("bottom", part.getPersistedState().get(PartMover.ORIGIN), "where it comes from is kept");
		assertEquals(1, workbench.page.count("activate"));
		assertEquals("Move Back to the Views", move.getText());
		// The same view, the same shell.
		run(view, "echo $X", "still-42");

		// And back where it came from.
		move.run();
		assertSame(bottom, placeholder.getParent());
		assertSame(placeholder, bottom.getSelectedElement());
		assertFalse(view.isInEditorArea());
		assertNull(part.getPersistedState().get(PartMover.ORIGIN));
		assertEquals("Move to the Editor Area", move.getText());
		run(view, "echo back-$X", "back-still-42");
	}

	@Test
	void viewGoesBackNextToTheConsoleWhenItsStackIsGone() throws Exception {
		XtermView view = openView();
		moveAction().run();
		assertTrue(view.isInEditorArea());
		// The stack it came from no longer exists; the console is in another one.
		perspective.getChildren().remove(bottom);
		MPartStack hidden = stack("hidden", "org.eclipse.ui.views.ContentOutline");
		hidden.setToBeRendered(false);
		MPartStack console = stack("right", "org.eclipse.ui.console.ConsoleView");
		moveAction().run();
		assertSame(console, placeholder.getParent());

		// It goes back to where it came from, even with another terminal elsewhere.
		MPartStack terminals = stack("terminals", XtermView.ID + ":t1");
		moveAction().run();
		moveAction().run();
		assertSame(console, placeholder.getParent());

		// Next to another terminal rather than next to the console.
		moveAction().run();
		console.setElementId("renamed");
		moveAction().run();
		assertSame(terminals, placeholder.getParent());

		// Otherwise in the first stack of views that is shown.
		moveAction().run();
		perspective.getChildren().removeAll(List.of(console, terminals));
		MPartStack shown = stack("shown", "some.view");
		moveAction().run();
		assertSame(shown, placeholder.getParent());
		assertTrue(perspective.getChildren().indexOf(hidden) < perspective.getChildren().indexOf(shown));
	}

	private MPartStack stack(String id, String viewId) {
		MPartStack stack = MBasicFactory.INSTANCE.createPartStack();
		stack.setElementId(id);
		MPlaceholder view = MAdvancedFactory.INSTANCE.createPlaceholder();
		view.setElementId(viewId);
		stack.getChildren().add(view);
		// Before the editor area, after the stacks added before.
		perspective.getChildren().add(perspective.getChildren().size() - 1, stack);
		return stack;
	}

	@Test
	void viewGoesNextToTheActiveEditor() throws Exception {
		// A second stack of editors, the one of the active editor.
		MPartStack split = MBasicFactory.INSTANCE.createPartStack();
		MPart editorPart = MBasicFactory.INSTANCE.createPart();
		split.getChildren().add(editorPart);
		((MArea) ((MPlaceholder) perspective.getChildren().get(1)).getRef()).getChildren().add(split);
		IEditorPart editor = new Fake().on("getSite", args -> new Fake()
				.on("getService", a -> a[0] == MPart.class ? editorPart : null).as(org.eclipse.ui.IEditorSite.class))
				.as(IEditorPart.class);
		workbench.page.on("getActiveEditor", args -> editor);
		XtermView view = openView();
		moveAction().run();
		assertSame(split, placeholder.getParent());
		assertTrue(view.isInEditorArea());
	}

	@Test
	void viewOutsideOfAPerspectiveDoesNotMove() throws Exception {
		// A view without a model (not in an Eclipse 4 workbench), or alone in a detached window.
		workbench.nextPart = null;
		XtermView alone = new XtermView();
		workbench.open(alone, "alone", null);
		await("shell prompt", () -> screen(alone).contains("$"));
		assertFalse(moveAction().isEnabled());
		alone.moveToEditorArea();
		alone.moveToViews();
		assertFalse(alone.isInEditorArea());

		MPartStack detached = MBasicFactory.INSTANCE.createPartStack();
		MPart detachedPart = MBasicFactory.INSTANCE.createPart();
		detached.getChildren().add(detachedPart);
		workbench.nextPart = detachedPart;
		XtermView view = new XtermView();
		workbench.open(view, "detached", null);
		await("shell prompt", () -> screen(view).contains("$"));
		view.moveToEditorArea();
		assertSame(detached, detachedPart.getParent());
		view.moveToViews();
		assertSame(detached, detachedPart.getParent());
	}

	@Test
	void terminalEditorOfAnEarlierVersionComesBackAsAViewInTheEditorArea() throws Exception {
		Files.createDirectories(XtermPlugin.stateDirectory());
		Path screenFile = XtermPlugin.stateDirectory().resolve("e123.screen");
		Files.writeString(screenFile, "marker-of-the-editor\r\n", StandardCharsets.UTF_8);
		XMLMemento memento = XMLMemento.createWriteRoot("editor");
		memento.putString("id", "e123");
		memento.putString("shell", SHELL);
		memento.putString("directory", TestWorkbench.FOLDER.getPath());
		memento.putString("name", "my editor terminal");
		XtermEditorInput input = (XtermEditorInput) new XtermEditorInputFactory().createElement(memento);
		assertEquals("Xterm", input.getName());
		assertEquals(input.getName(), input.getToolTipText());
		assertFalse(input.exists());
		assertSame(input, input.getPersistable());
		assertNull(input.getAdapter(String.class));
		assertEquals(XtermEditorInputFactory.ID, input.getFactoryId());
		assertEquals(input, new XtermEditorInputFactory().createElement(memento));
		assertEquals(input.hashCode(), new XtermEditorInputFactory().createElement(memento).hashCode());
		assertTrue(input.getImageDescriptor() != null);
		XMLMemento saved = XMLMemento.createWriteRoot("editor");
		input.saveState(saved);
		assertEquals(TestWorkbench.FOLDER.getPath(), saved.getString("directory"));
		assertEquals("e123", saved.getString("id"));

		// The view opens, goes to the editor area, and the editor closes.
		List<XtermView> opened = new ArrayList<>();
		workbench.page.on("showView", args -> {
			try {
				workbench.nextPart = part;
				XtermView view = new XtermView();
				workbench.open(view, (String) args[1], null);
				opened.add(view);
				return view;
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		List<Object> closed = new ArrayList<>();
		workbench.page.on("closeEditor", args -> closed.add(args[0]));
		XtermEditor editor = workbench.openEditor(input);
		await("editor closed", () -> closed.size() == 1);
		assertSame(editor, closed.get(0));
		XtermView view = opened.get(0);
		assertTrue(view.isInEditorArea());
		await("screen of the editor", () -> TestWorkbench.text(view).contains("marker-of-the-editor"));
		assertEquals("my editor terminal", view.getPartName());
		run(view, "echo in=$PWD", "in=" + TestWorkbench.shellPath(TestWorkbench.FOLDER));
		assertFalse(Files.exists(screenFile), "read once");

		editor.setFocus();
		editor.doSave(null);
		editor.doSaveAs();
		assertFalse(editor.isDirty());
		assertFalse(editor.isSaveAsAllowed());
		assertThrows(PartInitException.class,
				() -> new XtermEditor().init(workbench.editorSite(), new Fake().as(IEditorInput.class)));

		// Without a saved state: the default shell, in the home directory.
		XtermEditor empty = workbench.openEditor(new XtermEditorInput("e124", null));
		await("second editor closed", () -> closed.size() == 2);
		assertSame(empty, closed.get(1));
		assertEquals(2, opened.size());
		pump(100);
		XMLMemento noState = XMLMemento.createWriteRoot("editor");
		new XtermEditorInput("e125", null).saveState(noState);
		assertEquals("e125", noState.getString("id"));
	}
}
