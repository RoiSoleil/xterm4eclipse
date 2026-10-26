package org.eclipse.xterm4eclipse.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.XMLMemento;
import org.eclipse.ui.themes.IThemeManager;
import org.eclipse.xterm4eclipse.Fake;
import org.eclipse.xterm4eclipse.TestWorkbench;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The chat with its real view and browser, and the stand-in for Claude Code: what the user sees
 * and does, and what Claude Code is told.
 */
class ChatViewTest {

	static final Display DISPLAY = TestWorkbench.DISPLAY;

	@TempDir
	Path temp;
	File project;
	Path log;
	private final Map<String, String> environment = new HashMap<>();
	private final Fake page = new Fake();
	private final IWorkbenchPage workbenchPage = page.as(IWorkbenchPage.class);
	private final List<ChatView> views = new ArrayList<>();
	private final List<Shell> shells = new ArrayList<>();
	private final List<IPartListener2> partListeners = new ArrayList<>();
	private final List<IPropertyChangeListener> themeListeners = new ArrayList<>();
	/** What the user opened from the chat: files and changes. */
	private final List<String> opened = new ArrayList<>();
	private Path previousHome;
	private UnaryOperator<String> previousEnvironment;
	private ChatView.Opener previousOpener;

	@BeforeEach
	void isolate() throws Exception {
		project = Files.createDirectories(temp.resolve("project")).toFile().getCanonicalFile();
		Files.writeString(project.toPath().resolve("a.txt"), "hello\n");
		log = temp.resolve("log");
		previousHome = Conversations.userHome;
		previousEnvironment = ClaudeCommand.environment;
		previousMcpServer = EclipseMcpConfig.server;
		previousMcpKnown = EclipseMcpConfig.known;
		EclipseMcpConfig.server = () -> null; // the MCP server of the Eclipse that runs the tests, if any, is not for the chats of the tests
		previousOpener = ChatView.opener;
		Conversations.userHome = temp.resolve("home");
		// No login shell: the stand-in runs as it is.
		ClaudeCommand.environment = environment::get;
		ChatView.opener = new ChatView.Opener() {
			@Override
			public void open(IWorkbenchPage page, File file, int line) {
				opened.add(file.getName() + ':' + line);
			}

			@Override
			public void compare(IWorkbenchPage page, File file, String before) {
				opened.add("compare " + file.getName() + " from " + before.strip());
			}
		};
		ChatPlugin.preferences().setValue(ChatPlugin.PREF_PROGRAM, Stand.program(temp, log).getPath());
		page.on("addPartListener", args -> partListeners.add((IPartListener2) args[0]));
		page.on("removePartListener", args -> partListeners.remove(args[0]));
		page.on("getViewReferences", args -> views.stream().map(view -> new Fake().on("getId", a -> ChatView.ID)
				.on("getPart", a -> view).as(IViewReference.class)).toArray(IViewReference[]::new));
		page.on("showView", args -> {
			try {
				return create(null);
			} catch (PartInitException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	@AfterEach
	void restore() {
		for (ChatView view : views) {
			view.dispose();
		}
		for (Shell shell : shells) {
			shell.dispose();
		}
		TestWorkbench.pump(100);
		Conversations.userHome = previousHome;
		ClaudeCommand.environment = previousEnvironment;
		EclipseMcpConfig.server = previousMcpServer;
		EclipseMcpConfig.known = previousMcpKnown;
		ChatPlugin.preferences().setToDefault(ChatPlugin.PREF_ECLIPSE_MCP);
		ChatView.opener = previousOpener;
		for (String key : List.of(ChatPlugin.PREF_PROGRAM, ChatPlugin.PREF_MODEL, ChatPlugin.PREF_PERMISSION_MODE,
				ChatPlugin.PREF_ARGUMENTS, ChatPlugin.PREF_CONTINUE_LAST)) {
			ChatPlugin.preferences().setToDefault(key);
		}
	}

	private java.util.function.Supplier<org.eclipse.xterm4eclipse.harness.EclipseMcp.Endpoint> previousMcpServer;
	private java.util.function.BiPredicate<org.eclipse.xterm4eclipse.harness.EclipseMcp.Endpoint, File> previousMcpKnown;

	/** The toolbar of the last view that was created. */
	private ToolBarManager lastToolBar;

	private IViewSite site() {
		Fake workbench = new Fake();
		workbench.on("getSharedImages", args -> new Fake().as(ISharedImages.class));
		workbench.on("getThemeManager",
				args -> new Fake().on("addPropertyChangeListener", a -> themeListeners.add((IPropertyChangeListener) a[0]))
						.on("removePropertyChangeListener", a -> themeListeners.remove(a[0])).as(IThemeManager.class));
		IWorkbenchWindow window = new Fake().on("getWorkbench", args -> workbench.as(IWorkbench.class))
				.as(IWorkbenchWindow.class);
		ToolBarManager toolBar = new ToolBarManager();
		lastToolBar = toolBar;
		MenuManager menu = new MenuManager();
		return new Fake().on("getPage", args -> workbenchPage)
				.on("getActionBars", args -> new Fake().on("getToolBarManager", a -> toolBar)
						.on("getMenuManager", a -> menu).as(IActionBars.class))
				.on("getWorkbenchWindow", args -> window).as(IViewSite.class);
	}

	/** Creates a view the way the workbench does, in its own window. */
	private ChatView create(IMemento memento) throws PartInitException {
		ChatView view = new ChatView();
		view.init(site(), memento);
		Shell shell = new Shell(DISPLAY);
		shell.setLayout(new FillLayout());
		shell.setSize(700, 600);
		view.createPartControl(shell);
		shell.open();
		shells.add(shell);
		views.add(view);
		await("the page of the chat", view::isPageReady);
		return view;
	}

	private ChatView open(String sessionId) throws Exception {
		return ChatView.open(workbenchPage, project, sessionId);
	}

	static void await(String what, BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + 30000;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("Timed out waiting for: " + what);
			}
			TestWorkbench.pump(20);
		}
	}

	/** What the page shows. */
	static Map<String, Object> state(ChatView view) {
		return Json.object((String) view.browser.evaluate("return chatState()"));
	}

	static String text(ChatView view) {
		return (String) state(view).get("text");
	}

	static int turns(ChatView view) {
		return ((Number) state(view).get("turns")).intValue();
	}

	/** Types a message and sends it with Enter, as the user does. */
	static void say(ChatView view, String message) {
		view.browser.execute("var input = document.getElementById('input'); input.value = " + Json.string(message)
				+ "; input.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true}));");
	}

	static void click(ChatView view, String selector) {
		view.browser.execute("document.querySelector(" + Json.string(selector) + ").click()");
	}

	private void awaitText(ChatView view, String text) {
		await("\"" + text + "\" in the chat", () -> text(view).contains(text));
	}

	private List<String> read() throws Exception {
		return FakeClaude.lines(log);
	}

	@Test
	void scrollsWithAThinScrollbar() throws Exception {
		ChatView view = open(null);
		// Where the browser knows the property, the log has the thin scrollbar of the page, not the wide one of the system.
		Object width = view.browser.evaluate("return CSS.supports('scrollbar-width', 'thin') "
				+ "? getComputedStyle(document.getElementById('log')).scrollbarWidth : 'unsupported'");
		assertTrue("thin".equals(width) || "unsupported".equals(width), String.valueOf(width));
		Object color = view.browser.evaluate("return CSS.supports('scrollbar-color', 'red blue') "
				+ "? getComputedStyle(document.getElementById('log')).scrollbarColor : 'unsupported'");
		assertNotEquals("auto", color, "a color that follows the theme");
	}

	@Test
	void givesTheMcpServerOfEclipseToClaudeCode() throws Exception {
		EclipseMcpConfig.server = () -> new org.eclipse.xterm4eclipse.harness.EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", "token-1234567890");
		EclipseMcpConfig.known = (endpoint, directory) -> false;
		ChatView view = open(null);
		say(view, "hello");
		// Read as soon as Claude Code has started: the chat deletes the file when it ends.
		await("the arguments of Claude Code", () -> {
			try {
				return !read().isEmpty();
			} catch (Exception e) {
				return false;
			}
		});
		String arguments = read().get(0);
		int at = arguments.indexOf("--mcp-config ");
		assertTrue(at > 0, arguments);
		// The last argument, which is the only one that the chat of the tests gives.
		Path file = Path.of(arguments.substring(at + "--mcp-config ".length()).strip());
		assertTrue(Files.readString(file).contains("token-1234567890"), "for this conversation: the server of Eclipse");
		awaitText(view, "Hello world, see a.txt:2");
	}

	@Test
	void talksWithClaudeCode() throws Exception {
		ChatView view = open(null);
		assertEquals(project, view.directory());
		assertTrue(view.getPartName().endsWith("project"), view.getPartName());
		say(view, "hello");
		awaitText(view, "Hello world, see a.txt:2");
		await("the end of the answer", () -> turns(view) == 0 && !view.isBusy());
		String text = text(view);
		assertTrue(text.indexOf("hello") < text.indexOf("Hello world"), "the message of the user first: " + text);
		assertEquals("38.0k tokens · $0.0125", view.browser.evaluate("return document.getElementById('usage').textContent"));
		assertEquals("args: -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages "
				+ "--permission-prompt-tool stdio --session-id " + view.sessionId(), read().get(0));
		assertEquals("", state(view).get("input"), "sent: the message is empty again");

		click(view, "a.file");
		await("the file opened", () -> !opened.isEmpty());
		assertEquals(List.of("a.txt:2"), opened, "at its line");

		say(view, "and then?");
		awaitText(view, "You said: and then?");
		assertEquals(1, read().stream().filter(line -> line.startsWith("args:")).count(), "the same Claude Code");
	}

	@Test
	void asksForThePermissionsOfTheTools() throws Exception {
		ChatView view = open(null);
		say(view, "edit");
		await("the request of permission", () -> Boolean.TRUE.equals(
				view.browser.evaluate("return document.querySelector('.permission .buttons') !== null")));
		assertEquals("Claude Code wants to use Edit|a.txt", view.browser.evaluate("var title = document.querySelector('.permission-title');"
				+ " return title.firstChild.textContent.trim() + ' ' + title.querySelector('.tool-name').textContent + '|'"
				+ " + title.querySelector('a.file').textContent"));
		assertTrue(text(view).contains("- hello\n+ salut"), "the change, before it is allowed: " + text(view));
		assertEquals(1, ((Number) state(view).get("requests")).intValue());
		click(view, ".permission button.primary");
		awaitText(view, "Edited.");
		assertEquals("salut\n", Files.readString(project.toPath().resolve("a.txt")));
		assertTrue(text(view).contains("Allowed"));
		Map<String, Object> answer = answer();
		assertEquals("allow", answer.get("behavior"));
		assertEquals("hello", Json.object(answer, "updatedInput").get("old_string"), "the input that Claude Code asked for");
		assertNull(answer.get("updatedPermissions"));

		click(view, ".tool button.compare");
		await("the change compared", () -> !opened.isEmpty());
		assertEquals(List.of("compare a.txt from hello"), opened);
	}

	@Test
	void allowsAllTheEditsOfTheConversation() throws Exception {
		ChatView view = open(null);
		say(view, "edit");
		awaitText(view, "Allow All Edits");
		click(view, ".permission .buttons button:nth-child(2)");
		awaitText(view, "Edited.");
		assertEquals(List.of(Map.of("type", "setMode", "mode", "acceptEdits", "destination", "session")),
				answer().get("updatedPermissions"));
	}

	@Test
	void deniesATool() throws Exception {
		ChatView view = open(null);
		say(view, "edit");
		awaitText(view, "Deny");
		click(view, ".permission .buttons button:last-child");
		awaitText(view, "Not edited.");
		assertEquals("hello\n", Files.readString(project.toPath().resolve("a.txt")));
		assertEquals("deny", answer().get("behavior"));
		assertTrue(text(view).contains("Denied"));
		assertTrue((Boolean) view.browser.evaluate("return document.querySelector('.tool').classList.contains('failed')"));
		view.answer("perm-1", "allow");
	}

	/** @return the answer of the chat to the request of permission, as Claude Code read it */
	private Map<String, Object> answer() throws Exception {
		for (String line : read()) {
			Map<String, Object> message = Json.object(line);
			if (message != null && "control_response".equals(message.get("type"))) {
				return Json.object(Json.object(message, "response"), "response");
			}
		}
		throw new AssertionError("no answer in " + read());
	}

	@Test
	void stopsClaudeCodeWithEscape() throws Exception {
		ChatView view = open(null);
		view.interrupt();
		say(view, "slow");
		awaitText(view, "1\n2\n3");
		await("Claude Code at work", view::isBusy);
		assertEquals("flex", view.browser.evaluate("return getComputedStyle(document.getElementById('working')).display"));
		view.browser.execute("document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}))");
		awaitText(view, "Interrupted");
		await("the end of the turn", () -> !view.isBusy() && turns(view) == 0);
		assertTrue(read().stream().anyMatch(line -> line.contains("\"interrupt\"")), read().toString());
		assertTrue(text(view).contains("1\n2\n3"), "the text that came stays: " + text(view));
	}

	@Test
	void startsClaudeCodeAgainAfterItFailed() throws Exception {
		ChatView view = open(null);
		say(view, "crash");
		awaitText(view, "Claude Code has ended (exit code 3)");
		assertTrue(text(view).contains("boom: the fake crashed"), text(view));
		assertFalse(view.isBusy());
		say(view, "hello");
		awaitText(view, "Hello world");
		assertEquals(2, read().stream().filter(line -> line.startsWith("args:")).count());
	}

	@Test
	void changesTheModelAndThePermissions() throws Exception {
		ChatPlugin.preferences().setValue(ChatPlugin.PREF_MODEL, "haiku");
		ChatPlugin.preferences().setValue(ChatPlugin.PREF_PERMISSION_MODE, "plan");
		ChatView view = open(null);
		assertEquals("haiku", view.browser.evaluate("return document.getElementById('model').value"));
		assertEquals("plan", view.browser.evaluate("return document.getElementById('mode').value"));
		view.browser.execute("var s = document.getElementById('model'); s.value = 'opus'; s.dispatchEvent(new Event('change'))");
		view.browser.execute("var m = document.getElementById('mode'); m.value = 'acceptEdits'; m.dispatchEvent(new Event('change'))");
		say(view, "hello");
		awaitText(view, "Hello world");
		assertTrue(read().get(0).endsWith("--model opus --permission-mode acceptEdits"), read().get(0));

		view.browser.execute("var s = document.getElementById('model'); s.value = 'sonnet'; s.dispatchEvent(new Event('change'))");
		view.browser.execute("var m = document.getElementById('mode'); m.value = 'plan'; m.dispatchEvent(new Event('change'))");
		await("the changes told to Claude Code", () -> {
			try {
				return read().stream().anyMatch(line -> line.contains("set_permission_mode") && line.contains("\"plan\""))
						&& read().stream().anyMatch(line -> line.contains("set_model") && line.contains("\"sonnet\""));
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		view.setModel("nonexistent");
		awaitText(view, "Claude Code: Unknown model nonexistent");
		view.setModel("$(rm)");
		view.setMode("bypassPermissions");
		IMemento memento = XMLMemento.createWriteRoot("view");
		view.saveState(memento);
		assertEquals("nonexistent", memento.getString("model"), "not a name of a model: left out");
		assertEquals("plan", memento.getString("permissionMode"), "nor a mode that the chat does not offer");
	}

	@Test
	void comesBackToItsConversationAfterARestart() throws Exception {
		ChatView view = open(null);
		say(view, "hello");
		awaitText(view, "Hello world");
		view.setModel("opus");
		IMemento memento = XMLMemento.createWriteRoot("view");
		view.saveState(memento);
		// Claude Code has kept the conversation.
		Path folder = Files.createDirectories(Conversations.home().resolve("projects").resolve(Conversations.folderName(project)));
		Files.writeString(folder.resolve(view.sessionId() + ".jsonl"), ConversationsTest.conversation(project));

		ChatView restored = create(memento);
		assertEquals(view.sessionId(), restored.sessionId());
		assertEquals(project, restored.directory());
		awaitText(restored, "Resumed");
		String text = text(restored);
		assertTrue(text.indexOf("Fix the") < text.indexOf("Done.") && text.indexOf("Done.") < text.indexOf("Resumed"), text);
		assertFalse(text.contains("of a subagent"), text);
		assertTrue((Boolean) restored.browser.evaluate("return document.querySelector('.tool').classList.contains('done')"));
		say(restored, "hello");
		awaitText(restored, "Hello world");
		List<String> starts = read().stream().filter(line -> line.startsWith("args:")).toList();
		assertTrue(starts.get(1).endsWith("--resume " + view.sessionId() + " --model opus"), starts.get(1));
	}

	@Test
	void showsTheChatOfAConversationThatIsOpen() throws Exception {
		ChatView first = open(null);
		assertSame(first, open(first.sessionId()));
		ChatView other = open(null);
		assertNotEquals(first.sessionId(), other.sessionId(), "a new conversation");
		assertEquals(2, views.size());
	}

	@Test
	void tellsWhenClaudeCodeIsNotInstalled() throws Exception {
		ChatPlugin.preferences().setValue(ChatPlugin.PREF_PROGRAM, temp.resolve("missing").toString());
		ChatView view = open(null);
		say(view, "hello");
		awaitText(view, "Claude Code was not found");
		assertEquals(0, turns(view));
		assertFalse(Files.exists(log));
	}

	@Test
	void addsTheReferencesOfTheEditorsToTheMessage() throws Exception {
		ChatView view = open(null);
		partListeners.forEach(listener -> listener.partActivated(
				new Fake().on("getPart", args -> view).as(IWorkbenchPartReference.class)));
		assertSame(view, ChatView.lastActive());
		view.browser.execute("document.getElementById('input').value = 'Explain'");
		view.insert("@a.txt#L2 ");
		await("the reference in the message", () -> "Explain @a.txt#L2 ".equals(state(view).get("input")));
	}

	@Test
	void writesTheReferencesAsClaudeCodeReadsThem() throws Exception {
		File file = new File(project, "src/My File.java");
		assertEquals("@src/Foo.java#L3-5", AskClaudeHandler.reference(project, new File(project, "src/Foo.java"), "#L3-5"));
		assertEquals("@\"src/My File.java\"", AskClaudeHandler.reference(project, file, null));
		assertEquals('@' + temp.resolve("b.txt").toAbsolutePath().toString().replace('\\', '/'),
				AskClaudeHandler.reference(project, temp.resolve("b.txt").toFile(), null), "outside of the folder: absolute");
		assertTrue(AskClaudeHandler.contains(project, file));
		assertFalse(AskClaudeHandler.contains(project, temp.toFile()));
		Document document = new Document("one\ntwo\nthree\nfour\n");
		assertEquals("#L2-3", AskClaudeHandler.lines(new TextSelection(document, 4, 8)));
		assertEquals("#L2", AskClaudeHandler.lines(new TextSelection(document, 4, 2)));
		assertNull(AskClaudeHandler.lines(new TextSelection(document, 4, 0)), "no selection: the whole file");
	}

	/** The window of the workbench, for the handlers. */
	private ExecutionEvent event(Object part, ISelection selection) {
		IWorkbenchWindow window = new Fake().on("getActivePage", args -> workbenchPage)
				.on("getShell", args -> shells.isEmpty() ? null : shells.get(0)).as(IWorkbenchWindow.class);
		EvaluationContext context = new EvaluationContext(null, new Object());
		context.addVariable(ISources.ACTIVE_WORKBENCH_WINDOW_NAME, window);
		if (part != null) {
			context.addVariable(ISources.ACTIVE_PART_NAME, part);
		}
		if (selection != null) {
			context.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, selection);
		}
		return new ExecutionEvent(null, Map.of(), null, context);
	}

	/** An editor of a file outside of the workspace. */
	private IEditorPart editor(File file) {
		IURIEditorInput input = new Fake().on("getURI", args -> file.toURI()).as(IURIEditorInput.class);
		return new Fake().on("getEditorInput", args -> input).as(IEditorPart.class);
	}

	private Path keptConversation(String id) throws Exception {
		Path folder = Files.createDirectories(Conversations.home().resolve("projects").resolve(Conversations.folderName(project)));
		return Files.writeString(folder.resolve(id + ".jsonl"), ConversationsTest.conversation(project));
	}

	@Test
	void opensANewChatInTheFolderOfTheActiveEditor() throws Exception {
		File file = new File(project, "a.txt");
		page.on("getActiveEditor", args -> editor(file));
		assertEquals(project, NewChatHandler.directory(workbenchPage));
		new NewChatHandler().execute(event(null, null));
		assertEquals(1, views.size());
		assertEquals(project, views.get(0).directory());
		assertFalse(Conversations.exists(project, views.get(0).sessionId()), "a new conversation");

		keptConversation(ConversationsTest.ID);
		ChatPlugin.preferences().setValue(ChatPlugin.PREF_CONTINUE_LAST, true);
		new NewChatHandler().execute(event(null, null));
		assertEquals(ConversationsTest.ID, views.get(1).sessionId(), "the last conversation of the project");
		awaitText(views.get(1), "Resumed");

		page.on("getActiveEditor", args -> null);
		assertEquals(new File(System.getProperty("user.home")), NewChatHandler.directory(workbenchPage), "nothing selected");
		assertEquals(new File(System.getProperty("user.home")), NewChatHandler.directory(null));
	}

	@Test
	void asksClaudeCodeAboutTheSelectedLinesOrFiles() throws Exception {
		Files.writeString(project.toPath().resolve("a.txt"), "one\ntwo\nthree\n");
		ChatView view = open(null);
		partListeners.forEach(listener -> listener.partActivated(
				new Fake().on("getPart", args -> view).as(IWorkbenchPartReference.class)));
		Document document = new Document("one\ntwo\nthree\n");
		new AskClaudeHandler().execute(event(editor(new File(project, "a.txt")), new TextSelection(document, 4, 6)));
		await("the lines in the message", () -> "@a.txt#L2-3 ".equals(state(view).get("input")));
		assertEquals(1, views.size(), "the chat of the folder");

		File outside = Files.writeString(temp.resolve("b.txt"), "").toFile();
		IURIEditorInput element = new Fake().on("getURI", args -> outside.toURI()).as(IURIEditorInput.class);
		new AskClaudeHandler().execute(event(null, new StructuredSelection(element)));
		assertEquals(2, views.size(), "a file outside of its folder: a new chat");
		ChatView other = views.get(1);
		assertEquals(temp.toFile().getCanonicalFile(), other.directory().getCanonicalFile());
		await("the file in the message", () -> "@b.txt ".equals(state(other).get("input")));

		assertNull(new AskClaudeHandler().execute(event(null, new StructuredSelection(new Object()))), "nothing to ask about");
		assertNull(AskClaudeHandler.file(new Fake().on("getURI", args -> java.net.URI.create("https://example.com/a"))
				.as(IURIEditorInput.class)), "not a file");
	}

	/** Closes the next dialog, as the user does. */
	private void closeTheNextDialog() {
		DISPLAY.timerExec(200, () -> {
			for (Shell shell : DISPLAY.getShells()) {
				if (!shells.contains(shell) && shell.isVisible()) {
					shell.close();
					return;
				}
			}
			closeTheNextDialog();
		});
	}

	/** The action of the toolbar of the last view that is called so. */
	private IAction toolBarAction(String text) {
		for (IContributionItem item : lastToolBar.getItems()) {
			if (item instanceof ActionContributionItem contribution && text.equals(contribution.getAction().getText())) {
				return contribution.getAction();
			}
		}
		return null;
	}

	@Test
	void hasTheButtonThatMovesTheViewToTheEditorArea() throws Exception {
		ChatView view = open(null);
		IAction move = toolBarAction("Move to the Editor Area");
		assertNotNull(move, "the same button as the terminal has");
		assertEquals("Move this chat to the editor area, next to the editors", move.getToolTipText());
		assertFalse(move.isEnabled(), "a view that is not in an Eclipse 4 workbench cannot move");
		// The user may have dragged the view: it looks again where it is when it is activated.
		partListeners.forEach(listener -> listener.partActivated(new Fake().on("getPart", args -> view).as(IWorkbenchPartReference.class)));
		assertEquals("Move to the Editor Area", move.getText());
	}

	@Test
	void resumesAConversationOfTheProject() throws Exception {
		Shell parent = new Shell(DISPLAY);
		shells.add(parent);
		List<String> offered = new ArrayList<>();
		ResumeHandler.Chooser previous = ResumeHandler.chooser;
		try {
			ResumeHandler.chooser = (shell, directory, conversations) -> {
				conversations.forEach(conversation -> offered.add(conversation.title()));
				return offered.size() > 1 ? conversations.get(0) : null;
			};
			closeTheNextDialog();
			ResumeHandler.resume(parent, workbenchPage, project);
			assertTrue(views.isEmpty() && offered.isEmpty(), "none kept: nothing to resume");

			keptConversation(ConversationsTest.ID);
			ResumeHandler.resume(parent, workbenchPage, project);
			assertEquals(List.of("Fix the build"), offered);
			assertTrue(views.isEmpty(), "none chosen");

			page.on("getSelection", args -> new StructuredSelection(new Fake()
					.on("getURI", a -> new File(project, "a.txt").toURI()).as(IURIEditorInput.class)));
			new ResumeHandler().execute(event(null, null));
			assertEquals(1, views.size(), "the conversations of the project of the selection");
			assertEquals(ConversationsTest.ID, views.get(0).sessionId());
			awaitText(views.get(0), "Resumed");
		} finally {
			ResumeHandler.chooser = previous;
		}
	}

	@Test
	void resumesAConversationInATerminal() throws Exception {
		Shell parent = new Shell(DISPLAY);
		shells.add(parent);
		List<String> opened = new ArrayList<>();
		ResumeHandler.Chooser previousChooser = ResumeHandler.chooser;
		ResumeInTerminalHandler.Opener previousOpener = ResumeInTerminalHandler.opener;
		try {
			ResumeHandler.chooser = (shell, directory, conversations) -> conversations.get(0);
			ResumeInTerminalHandler.opener = (target, commandLine, directory) -> opened.add(commandLine + " in " + directory.getName());
			closeTheNextDialog();
			ResumeInTerminalHandler.resume(parent, workbenchPage, project);
			assertTrue(opened.isEmpty(), "none kept: nothing to resume");

			keptConversation(ConversationsTest.ID);
			ResumeInTerminalHandler.resume(parent, workbenchPage, project);
			assertEquals(1, opened.size());
			assertTrue(opened.get(0).contains("--resume " + ConversationsTest.ID + " in " + project.getName()), opened.get(0));

			page.on("getSelection", args -> new StructuredSelection(new Fake()
					.on("getURI", a -> new File(project, "a.txt").toURI()).as(IURIEditorInput.class)));
			new ResumeInTerminalHandler().execute(event(null, null));
			assertEquals(2, opened.size(), "the conversations of the project of the selection");
			assertTrue(views.isEmpty(), "in a terminal, not in a chat");
		} finally {
			ResumeHandler.chooser = previousChooser;
			ResumeInTerminalHandler.opener = previousOpener;
		}
	}

	@Test
	void resumesInTheFolderOfTheTerminalNotOfTheSelection() throws Exception {
		// From the button of a terminal the project is the one of the terminal; the selection is of another.
		page.on("getSelection", args -> new StructuredSelection(new Fake()
				.on("getURI", a -> new File(project, "a.txt").toURI()).as(IURIEditorInput.class)));
		assertEquals(project, ResumeInTerminalHandler.directory(new Fake().as(IWorkbenchPart.class), workbenchPage),
				"not a terminal: the project of the selection, as for the menu");
		assertEquals(project, ResumeInTerminalHandler.directory(null, workbenchPage), "no part");
	}

	@Test
	void doesNotResumeInATerminalWhatItCannotRunSafely() throws Exception {
		Shell parent = new Shell(DISPLAY);
		shells.add(parent);
		List<String> opened = new ArrayList<>();
		ResumeHandler.Chooser previousChooser = ResumeHandler.chooser;
		ResumeInTerminalHandler.Opener previousOpener = ResumeInTerminalHandler.opener;
		String program = ChatPlugin.preference(ChatPlugin.PREF_PROGRAM);
		try {
			keptConversation(ConversationsTest.ID);
			ResumeInTerminalHandler.opener = (target, commandLine, directory) -> opened.add(commandLine);

			// An id that the command line of Claude Code would take for an option.
			ResumeHandler.chooser = (shell, directory, conversations) -> new Conversations.Conversation("-x", "an option", 0);
			ResumeInTerminalHandler.resume(parent, workbenchPage, project);
			assertTrue(opened.isEmpty(), "not a plain id");

			ResumeHandler.chooser = (shell, directory, conversations) -> conversations.get(0);
			// A program that is not a file: said, not replaced by another claude.
			ChatPlugin.preferences().setValue(ChatPlugin.PREF_PROGRAM, new File(project, "no-claude").getPath());
			closeTheNextDialog();
			ResumeInTerminalHandler.resume(parent, workbenchPage, project);
			assertTrue(opened.isEmpty(), "the program of the preferences is not a file");
		} finally {
			ResumeHandler.chooser = previousChooser;
			ResumeInTerminalHandler.opener = previousOpener;
			ChatPlugin.preferences().setValue(ChatPlugin.PREF_PROGRAM, program);
		}
	}

	@Test
	void editsTheSettings() {
		Shell shell = new Shell(DISPLAY);
		shells.add(shell);
		ChatPreferencePage preferences = new ChatPreferencePage();
		preferences.init(null);
		preferences.createControl(shell);
		List<Text> texts = new ArrayList<>();
		List<Combo> combos = new ArrayList<>();
		List<Button> checks = new ArrayList<>();
		collect(preferences.getControl(), texts, combos, checks);
		assertEquals(3, texts.size(), "program, model, arguments");
		assertEquals(1, combos.size());
		assertEquals("Ask before edits", combos.get(0).getText());
		type(texts.get(1), "$(rm)");
		assertFalse(preferences.isValid(), "not a model");
		type(texts.get(1), "opus");
		type(texts.get(2), "--effort high");
		assertTrue(preferences.isValid());
		assertTrue(preferences.performOk());
		assertEquals("opus", ChatPlugin.preference(ChatPlugin.PREF_MODEL));
		assertEquals("--effort high", ChatPlugin.preference(ChatPlugin.PREF_ARGUMENTS));
	}

	/** Types a text as the user does: the field editors check it on each key. */
	private static void type(Text text, String value) {
		text.setText(value);
		text.notifyListeners(SWT.KeyUp, new Event());
	}

	private static void collect(Control control, List<Text> texts, List<Combo> combos, List<Button> checks) {
		if (control instanceof Text text) {
			texts.add(text);
		} else if (control instanceof Combo combo) {
			combos.add(combo);
		} else if (control instanceof Button button && (button.getStyle() & SWT.CHECK) != 0) {
			checks.add(button);
		}
		if (control instanceof Composite composite) {
			for (Control child : composite.getChildren()) {
				collect(child, texts, combos, checks);
			}
		}
	}

	@Test
	void opensTheFilesOnlyInTheEditorsOfEclipse() {
		assertEquals(Workspace.TEXT_EDITOR, Workspace.editorId(null));
		assertEquals(Workspace.TEXT_EDITOR, Workspace.editorId(new Fake().on("isOpenExternal", args -> true)
				.as(org.eclipse.ui.IEditorDescriptor.class)), "never a program of the system");
		assertEquals("java", Workspace.editorId(new Fake().on("getId", args -> "java").as(org.eclipse.ui.IEditorDescriptor.class)));
		assertNull(Workspace.location(new Object()));
		assertNull(Workspace.projectDirectory(new Object()));
		Workspace.refresh(project, true);
	}

	@Test
	void showsOnlyItsOwnPage() throws Exception {
		ChatView view = open(null);
		assertFalse(view.isOwnPage("https://example.com/"));
		assertFalse(view.isOwnPage("file:///etc/passwd"));
		assertTrue(view.isOwnPage("about:blank"));
		assertFalse(view.isOwnPage(null));
		assertTrue(ChatView.isWebLink("https://claude.com/claude-code"));
		assertFalse(ChatView.isWebLink("file:///etc/passwd"));
		assertFalse(ChatView.isWebLink("javascript:alert(1)"));
		view.browser.execute("location.href = 'https://example.com/'");
		TestWorkbench.pump(300);
		assertTrue(view.isOwnPage(view.browser.getUrl()), view.browser.getUrl());
		themeListeners.forEach(listener -> listener.propertyChange(null));
		TestWorkbench.pump(100);
		assertEquals(Boolean.TRUE, view.browser.evaluate(
				"return document.body.classList.contains('light') !== document.body.classList.contains('dark')"),
				"themed again");
	}
}
