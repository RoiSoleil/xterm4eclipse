package org.eclipse.xterm4eclipse.claude;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.xterm4eclipse.workbench.MoveToEditorAreaAction;

/**
 * A conversation with Claude Code in a view, as in the Code tab of Claude Desktop: its messages,
 * its tools and their changes, and the permissions it asks for. Claude Code runs beside it with
 * {@code --input-format stream-json --output-format stream-json}; its conversations are those of
 * the CLI and of Claude Desktop, which can resume them.
 */
public class ChatView extends ViewPart {

	public static final String ID = "org.eclipse.xterm4eclipse.claude.chat"; //$NON-NLS-1$

	private static final String MEMENTO_DIRECTORY = "directory"; //$NON-NLS-1$
	private static final String MEMENTO_SESSION = "session"; //$NON-NLS-1$
	private static final String MEMENTO_MODEL = "model"; //$NON-NLS-1$
	private static final String MEMENTO_MODE = "permissionMode"; //$NON-NLS-1$
	/** How much of the result of a tool goes to the page: the result of a Read can be a whole file. */
	static final int RESULT_SHOWN = 20_000;

	/** What the next view shows: given by {@link #open}, taken by {@link #init}. */
	private record Request(File directory, String sessionId) {
	}

	private static Request next;

	/** Opens the files and the changes that the user clicks in the chat. */
	interface Opener {
		/** @param line 1 for the first line, 0 for none */
		void open(IWorkbenchPage page, File file, int line) throws PartInitException;

		void compare(IWorkbenchPage page, File file, String before);
	}

	/** In the editors of Eclipse; a test may change it. */
	static Opener opener = new Opener() {
		@Override
		public void open(IWorkbenchPage page, File file, int line) throws PartInitException {
			Workspace.open(page, file, line);
		}

		@Override
		public void compare(IWorkbenchPage page, File file, String before) {
			ChangeCompare.open(page, file, before);
		}
	};
	/** The chat that the user used last: the one that the editors send to. */
	private static ChatView lastActive;

	Display display;
	Browser browser;
	private File directory;
	private String sessionId;
	private String model;
	private String mode;
	/** Claude Code, while it runs. */
	private ClaudeProcess claude;
	/** The messages of the user that Claude Code has not answered yet: it queues those sent meanwhile. */
	private int turns;
	private File pageFile;
	/** The java* functions of the page, by name, called through a single BrowserFunction. */
	private final Map<String, JsFunction> functions = new LinkedHashMap<>();
	private BrowserFunction javaCall;
	private boolean pageReady;
	/** The scripts for the page until it is ready. */
	private final List<String> pending = new ArrayList<>();
	/** The permissions that Claude Code asks for, by the id of the request. */
	private final Map<String, Map<String, Object>> permissions = new HashMap<>();
	/** The changes to files, by the id of the tool that made them: to compare them. */
	private final Map<String, Change> changes = new HashMap<>();
	private Action stop;

	/** A change of Claude Code to a file: the file, and its content before. */
	record Change(File file, String before) {
	}

	private final IPropertyChangeListener themeListener = event -> {
		if (display != null && !display.isDisposed()) {
			display.asyncExec(this::applyTheme);
		}
	};

	/** Moves the view to the editor area, or back to the views. */
	private MoveToEditorAreaAction moveAction;

	private final IPartListener2 partListener = new IPartListener2() {
		@Override
		public void partActivated(IWorkbenchPartReference ref) {
			if (ref.getPart(false) == ChatView.this) {
				lastActive = ChatView.this;
				// The user may also have dragged the view to or from the editor area.
				if (moveAction != null) {
					moveAction.update();
				}
			}
		}
	};

	@Override
	public void init(IViewSite site, IMemento memento) throws PartInitException {
		super.init(site, memento);
		Request request = next;
		next = null;
		if (memento != null) {
			String kept = memento.getString(MEMENTO_DIRECTORY);
			directory = kept == null ? null : new File(kept);
			sessionId = memento.getString(MEMENTO_SESSION);
			model = memento.getString(MEMENTO_MODEL);
			mode = memento.getString(MEMENTO_MODE);
		} else if (request != null) {
			directory = request.directory();
			sessionId = request.sessionId();
		}
		if (directory == null || !directory.isDirectory()) {
			directory = new File(System.getProperty("user.home")); //$NON-NLS-1$
		}
		if (sessionId == null || !ClaudeCommand.SESSION_ID.matcher(sessionId).matches()) {
			// The chat chooses the id: it knows its conversation before Claude Code has said anything.
			sessionId = UUID.randomUUID().toString();
		}
		if (model == null) {
			model = ChatPlugin.preference(ChatPlugin.PREF_MODEL).trim();
		}
		if (mode == null || !ClaudeCommand.PERMISSION_MODES.containsKey(mode)) {
			String preferred = ChatPlugin.preference(ChatPlugin.PREF_PERMISSION_MODE);
			mode = ClaudeCommand.PERMISSION_MODES.containsKey(preferred) ? preferred : ClaudeCommand.DEFAULT_MODE;
		}
	}

	@Override
	public void saveState(IMemento memento) {
		super.saveState(memento);
		memento.putString(MEMENTO_DIRECTORY, directory.getAbsolutePath());
		memento.putString(MEMENTO_SESSION, sessionId);
		memento.putString(MEMENTO_MODEL, model);
		memento.putString(MEMENTO_MODE, mode);
	}

	/**
	 * Opens a chat, or shows the one of the conversation if there is one already.
	 *
	 * @param sessionId
	 *            the conversation to resume, or {@code null} for a new one
	 */
	public static ChatView open(IWorkbenchPage page, File directory, String sessionId) throws PartInitException {
		if (sessionId != null) {
			for (IViewReference reference : page.getViewReferences()) {
				if (ID.equals(reference.getId()) && reference.getPart(false) instanceof ChatView view
						&& sessionId.equals(view.sessionId)) {
					page.activate(view);
					return view;
				}
			}
		}
		next = new Request(directory, sessionId);
		try {
			return (ChatView) page.showView(ID, UUID.randomUUID().toString(), IWorkbenchPage.VIEW_ACTIVATE);
		} finally {
			next = null;
		}
	}

	/** @return the chat that the user used last, if it is still open, else {@code null} */
	static ChatView lastActive() {
		return lastActive != null && lastActive.browser != null && !lastActive.browser.isDisposed() ? lastActive : null;
	}

	File directory() {
		return directory;
	}

	String sessionId() {
		return sessionId;
	}

	@Override
	public void createPartControl(Composite parent) {
		display = parent.getDisplay();
		parent.setLayout(new FillLayout());
		setPartName("Claude Code: " + (directory.getName().isEmpty() ? directory.getPath() : directory.getName())); //$NON-NLS-1$
		setTitleToolTip("Claude Code in " + directory.getPath()); //$NON-NLS-1$
		// On Windows the default can still be Internet Explorer.
		browser = new Browser(parent, ClaudeCommand.windows ? SWT.EDGE : SWT.NONE);
		registerFunctions();
		String javaFunctions = javaFunctions();
		// The java* functions give the page the files of the computer and Claude Code: no other page
		// may ever be loaded in this browser, nor open a window of its own.
		browser.addLocationListener(LocationListener.changingAdapter(event -> {
			event.doit = isOwnPage(event.location);
			if (!event.doit) {
				ChatPlugin.log("Blocked a navigation of the chat to " + event.location, null); //$NON-NLS-1$
			}
		}));
		browser.addOpenWindowListener(event -> event.required = true);
		browser.addProgressListener(ProgressListener.completedAdapter(event -> {
			if (javaCall == null) {
				registerJavaCall();
			}
			browser.execute(javaFunctions + "chatInit(" + config() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
			pageReady = true;
			for (String script : pending) {
				browser.execute(script);
			}
			pending.clear();
		}));
		try {
			Path page = ChatPage.materialize(ChatPlugin.stateDirectory().resolve("chat-" + ChatPlugin.version() + ".html"), //$NON-NLS-1$ //$NON-NLS-2$
					name -> ChatPlugin.resource("web/" + name)); //$NON-NLS-1$
			pageFile = page.toFile().getAbsoluteFile();
			browser.setUrl(page.toUri().toString());
		} catch (IOException e) {
			ChatPlugin.log("Could not load the page of the chat", e); //$NON-NLS-1$
			browser.setText("<pre>Could not load the page of the chat.</pre>"); //$NON-NLS-1$
		}
		createActions();
		getSite().getPage().addPartListener(partListener);
		workbench().getThemeManager().addPropertyChangeListener(themeListener);
		JFaceResources.getFontRegistry().addListener(themeListener);
		showHistory();
	}

	/** Shows the messages of the conversation that the chat resumes, read in the background. */
	private void showHistory() {
		if (!Conversations.exists(directory, sessionId)) {
			return;
		}
		File folder = directory;
		String id = sessionId;
		Thread reader = new Thread(() -> {
			List<Map<String, Object>> history = Conversations.history(folder, id);
			ChatPlugin.later(display, () -> {
				if (browser.isDisposed()) {
					return;
				}
				for (Map<String, Object> message : history) {
					Map<String, Object> shown = shown(message, false);
					if (shown != null) {
						shown.put("history", true); //$NON-NLS-1$
						post(shown);
					}
				}
				post(Map.of("type", "x4e_history_end")); //$NON-NLS-1$ //$NON-NLS-2$
			});
		}, "Claude Code conversation"); //$NON-NLS-1$
		reader.setDaemon(true);
		reader.start();
	}

	private interface JsFunction {
		Object call(Object[] args);
	}

	private void function(String name, JsFunction body) {
		functions.put(name, body);
	}

	private void registerFunctions() {
		function("javaSend", args -> { //$NON-NLS-1$
			if (args.length > 0 && args[0] instanceof String text) {
				List<Map<String, String>> images = images(args.length > 1 ? args[1] : null);
				// Not from within the call: it may run a script in the page.
				display.asyncExec(() -> send(text, images));
			}
			return null;
		});
		function("javaInterrupt", args -> { //$NON-NLS-1$
			interrupt();
			return null;
		});
		function("javaAnswer", args -> { //$NON-NLS-1$
			if (args.length > 1 && args[0] instanceof String request && args[1] instanceof String decision) {
				answer(request, decision);
			}
			return null;
		});
		function("javaSetModel", args -> { //$NON-NLS-1$
			if (args.length > 0 && args[0] instanceof String chosen) {
				setModel(chosen);
			}
			return null;
		});
		function("javaSetMode", args -> { //$NON-NLS-1$
			if (args.length > 0 && args[0] instanceof String chosen) {
				setMode(chosen);
			}
			return null;
		});
		function("javaOpenFile", args -> { //$NON-NLS-1$
			if (args.length > 1 && args[0] instanceof String path && args[1] instanceof Number line) {
				// A dialog may run the event loop: not from within the call.
				display.asyncExec(() -> openFile(path, line.intValue()));
			}
			return null;
		});
		function("javaOpenChange", args -> { //$NON-NLS-1$
			if (args.length > 0 && args[0] instanceof String toolUseId) {
				display.asyncExec(() -> openChange(toolUseId));
			}
			return null;
		});
		function("javaOpenLink", args -> { //$NON-NLS-1$
			if (args.length > 0 && args[0] instanceof String link) {
				display.asyncExec(() -> openLink(link));
			}
			return null;
		});
	}

	/**
	 * Gives the page its java* functions through a single BrowserFunction, created once the page is
	 * loaded, as the terminal does: with Edge each BrowserFunction is a round trip to WebView2 in the
	 * UI thread.
	 */
	private void registerJavaCall() {
		javaCall = new BrowserFunction(browser, "javaCall") { //$NON-NLS-1$
			@Override
			public Object function(Object[] arguments) {
				String name = arguments.length > 0 && arguments[0] instanceof String string ? string : null;
				JsFunction body = name == null ? null : functions.get(name);
				if (body == null) {
					return null;
				}
				try {
					return body.call(Arrays.copyOfRange(arguments, 1, arguments.length));
				} catch (RuntimeException e) {
					ChatPlugin.log("Error in " + name, e); //$NON-NLS-1$
					return null;
				}
			}
		};
	}

	/** The script that defines each java* function of the page as a call of javaCall. */
	private String javaFunctions() {
		StringBuilder names = new StringBuilder();
		for (String name : functions.keySet()) {
			names.append(names.isEmpty() ? "" : ",").append('\'').append(name).append('\''); //$NON-NLS-1$ //$NON-NLS-2$
		}
		return "[" + names + "].forEach(function (name) { window[name] = function () {" //$NON-NLS-1$ //$NON-NLS-2$
				+ " return javaCall.apply(null, [name].concat(Array.prototype.slice.call(arguments))); }; });"; //$NON-NLS-1$
	}

	boolean isOwnPage(String location) {
		if (location == null || location.equals("about:blank")) { //$NON-NLS-1$
			return location != null;
		}
		try {
			URI uri = new URI(location);
			return pageFile != null && "file".equalsIgnoreCase(uri.getScheme()) && uri.getQuery() == null //$NON-NLS-1$
					&& new File(uri.getPath()).getAbsoluteFile().equals(pageFile);
		} catch (URISyntaxException | IllegalArgumentException e) {
			return false;
		}
	}

	private void createActions() {
		ISharedImages images = workbench().getSharedImages();
		IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();
		stop = new Action("Stop") { //$NON-NLS-1$
			@Override
			public void run() {
				interrupt();
			}
		};
		stop.setToolTipText("Stop Claude Code (Escape)"); //$NON-NLS-1$
		stop.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_ELCL_STOP));
		stop.setDisabledImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_ELCL_STOP_DISABLED));
		stop.setEnabled(false);
		Action newConversation = new Action("New Conversation") { //$NON-NLS-1$
			@Override
			public void run() {
				try {
					open(getSite().getPage(), directory, null);
				} catch (PartInitException e) {
					ChatPlugin.log("Could not open a chat", e); //$NON-NLS-1$
				}
			}
		};
		newConversation.setToolTipText("New Conversation in " + directory.getPath()); //$NON-NLS-1$
		newConversation.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_OBJ_ADD));
		Action resume = new Action("Resume a Conversation…") { //$NON-NLS-1$
			@Override
			public void run() {
				ResumeHandler.resume(getSite().getShell(), getSite().getPage(), directory);
			}
		};
		resume.setToolTipText("Resume a Conversation of " + directory.getPath()); //$NON-NLS-1$
		resume.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_OBJ_FOLDER));
		toolBar.add(stop);
		toolBar.add(newConversation);
		toolBar.add(resume);
		moveAction = new MoveToEditorAreaAction(this, ID, "chat"); //$NON-NLS-1$
		toolBar.add(moveAction);
		getViewSite().getActionBars().updateActionBars();
	}

	// Claude Code.

	/** Sends a message of the user with images, starting Claude Code if it does not run. */
	void send(String text, List<Map<String, String>> images) {
		if ((text.isBlank() && images.isEmpty()) || browser.isDisposed()) {
			return;
		}
		if ((claude == null || !claude.isAlive()) && !start()) {
			return;
		}
		claude.sendUser(text, images);
		setTurns(turns + 1);
	}

	/** The formats of image that the API takes, the number of images of a message and the size of the data of one. */
	private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	private static final int MAX_IMAGES = 20;
	private static final int MAX_IMAGE_DATA = 5 * 1024 * 1024;
	private static final int MAX_TOTAL_DATA = 25 * 1024 * 1024;
	private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]+={0,2}"); //$NON-NLS-1$

	/** @return the images the page sends as a JSON array of {@code media_type} and base64 {@code data} */
	static List<Map<String, String>> images(Object json) {
		List<Map<String, String>> images = new ArrayList<>();
		if (json instanceof String text && !text.isEmpty() && Json.parse(text) instanceof List<?> list) {
			int total = 0;
			for (Object item : list) {
				if (images.size() < MAX_IMAGES && item instanceof Map<?, ?> map
						&& map.get("media_type") instanceof String type && IMAGE_TYPES.contains(type) //$NON-NLS-1$
						&& map.get("data") instanceof String data && data.length() <= MAX_IMAGE_DATA //$NON-NLS-1$
						&& total + data.length() <= MAX_TOTAL_DATA && data.length() % 4 == 0
						&& BASE64.matcher(data).matches()) {
					total += data.length();
					images.add(Map.of("media_type", type, "data", data)); //$NON-NLS-1$ //$NON-NLS-2$
				}
			}
		}
		return images;
	}

	/** @return {@code true} if Claude Code has started */
	private boolean start() {
		File program = ClaudeCommand.program(ChatPlugin.preference(ChatPlugin.PREF_PROGRAM));
		if (program == null) {
			post(notice("error", "Claude Code was not found. Install it (https://claude.com/claude-code), or choose its " //$NON-NLS-1$ //$NON-NLS-2$
					+ "program in Window > Preferences > Xterm Terminal > Claude Code Chat.")); //$NON-NLS-1$
			return false;
		}
		// Resumed if Claude Code has kept it: it keeps nothing before the first message.
		boolean resume = Conversations.exists(directory, sessionId);
		// The MCP server of Eclipse, for this conversation only.
		Path mcpConfig = EclipseMcpConfig.write(directory);
		List<String> command;
		try {
			command = ClaudeCommand.commandLine(program, ClaudeCommand.arguments(sessionId, resume, model, mode,
					ChatPlugin.preference(ChatPlugin.PREF_ARGUMENTS), mcpConfig == null ? null : mcpConfig.toString()));
		} catch (RuntimeException e) {
			EclipseMcpConfig.delete(mcpConfig);
			throw e;
		}
		ClaudeProcess[] started = new ClaudeProcess[1];
		try {
			started[0] = ClaudeProcess.start(command, directory, new ClaudeProcess.Listener() {
				@Override
				public void message(Map<String, Object> message) {
					ChatPlugin.later(display, () -> received(started[0], message));
				}

				@Override
				public void exited(int exitCode, String errors) {
					EclipseMcpConfig.delete(mcpConfig);
					ChatPlugin.later(display, () -> ended(started[0], exitCode, errors));
				}
			});
		} catch (RuntimeException e) {
			EclipseMcpConfig.delete(mcpConfig);
			throw e;
		} catch (IOException e) {
			EclipseMcpConfig.delete(mcpConfig);
			ChatPlugin.log("Could not start Claude Code", e); //$NON-NLS-1$
			post(notice("error", "Could not start Claude Code: " + e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
			return false;
		}
		claude = started[0];
		return true;
	}

	private void received(ClaudeProcess source, Map<String, Object> message) {
		if (source != claude || browser.isDisposed()) {
			return;
		}
		String type = Json.string(message, "type"); //$NON-NLS-1$
		if ("control_request".equals(type)) { //$NON-NLS-1$
			String id = Json.string(message, "request_id"); //$NON-NLS-1$
			Map<String, Object> request = Json.object(message, "request"); //$NON-NLS-1$
			if (id == null || request == null) {
				return;
			}
			if (!"can_use_tool".equals(Json.string(request, "subtype"))) { //$NON-NLS-1$ //$NON-NLS-2$
				claude.refuse(id, "Not supported by the chat of Eclipse"); //$NON-NLS-1$
				return;
			}
			permissions.put(id, request);
		} else if ("control_cancel_request".equals(type)) { //$NON-NLS-1$
			permissions.remove(Json.string(message, "request_id")); //$NON-NLS-1$
		} else if ("result".equals(type)) { //$NON-NLS-1$
			setTurns(turns - 1);
			// Claude Code may have changed any file of the folder, with its shell.
			Workspace.refresh(directory, true);
		}
		Map<String, Object> shown = shown(message, true);
		if (shown != null) {
			post(shown);
		}
	}

	/**
	 * @param live
	 *            the message comes from Claude Code now, not from a kept conversation
	 * @return what the page is told of a message of Claude Code, or {@code null} for nothing
	 */
	Map<String, Object> shown(Map<String, Object> message, boolean live) {
		String type = Json.string(message, "type"); //$NON-NLS-1$
		if (type == null) {
			return null;
		}
		Map<String, Object> shown = new LinkedHashMap<>();
		shown.put("type", type); //$NON-NLS-1$
		switch (type) {
		case "system" -> { //$NON-NLS-1$
			if (!"init".equals(Json.string(message, "subtype"))) { //$NON-NLS-1$ //$NON-NLS-2$
				return null;
			}
			shown.put("subtype", "init"); //$NON-NLS-1$ //$NON-NLS-2$
			shown.put("model", Json.string(message, "model")); //$NON-NLS-1$ //$NON-NLS-2$
			shown.put("permissionMode", Json.string(message, "permissionMode")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		case "stream_event" -> { //$NON-NLS-1$
			// Only the text as it comes: the rest comes again in the message of the assistant.
			Map<String, Object> event = Json.object(message, "event"); //$NON-NLS-1$
			String kind = Json.string(event, "type"); //$NON-NLS-1$
			Map<String, Object> delta = Json.object(event, "delta"); //$NON-NLS-1$
			if ("content_block_delta".equals(kind) && "text_delta".equals(Json.string(delta, "type"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				shown.put("text", Json.string(delta, "text")); //$NON-NLS-1$ //$NON-NLS-2$
			} else if ("content_block_start".equals(kind)) { //$NON-NLS-1$
				shown.put("start", true); //$NON-NLS-1$
			} else {
				return null;
			}
			shown.put("parent", message.get("parent_tool_use_id")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		case "assistant" -> { //$NON-NLS-1$
			shown.put("message", message.get("message")); //$NON-NLS-1$ //$NON-NLS-2$
			shown.put("parent", message.get("parent_tool_use_id")); //$NON-NLS-1$ //$NON-NLS-2$
		}
		case "user" -> { //$NON-NLS-1$
			Map<String, Object> content = Json.object(message, "message"); //$NON-NLS-1$
			Object blocks = content == null ? null : content.get("content"); //$NON-NLS-1$
			shown.put("parent", message.get("parent_tool_use_id")); //$NON-NLS-1$ //$NON-NLS-2$
			if (blocks instanceof String text) {
				if (live) {
					// The page shows the messages of the user as they are sent.
					return null;
				}
				shown.put("text", text); //$NON-NLS-1$
				return shown;
			}
			List<Object> results = new ArrayList<>();
			for (Object block : blocks instanceof List<?> list ? list : List.of()) {
				if (!(block instanceof Map<?, ?> map)) {
					continue;
				}
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("type", map.get("type")); //$NON-NLS-1$ //$NON-NLS-2$
				result.put("tool_use_id", map.get("tool_use_id")); //$NON-NLS-1$ //$NON-NLS-2$
				result.put("is_error", map.get("is_error")); //$NON-NLS-1$ //$NON-NLS-2$
				result.put("text", map.get("text")); //$NON-NLS-1$ //$NON-NLS-2$
				if ("image".equals(map.get("type"))) { //$NON-NLS-1$ //$NON-NLS-2$
					result.put("source", map.get("source")); //$NON-NLS-1$ //$NON-NLS-2$
				}
				result.put("content", resultText(map.get("content"))); //$NON-NLS-1$ //$NON-NLS-2$
				results.add(result);
				if (map.get("tool_use_id") instanceof String toolUseId && change(toolUseId, message, live)) { //$NON-NLS-1$
					result.put("change", true); //$NON-NLS-1$
				}
			}
			shown.put("content", results); //$NON-NLS-1$
		}
		case "control_request" -> { //$NON-NLS-1$
			Map<String, Object> request = Json.object(message, "request"); //$NON-NLS-1$
			shown.put("request_id", message.get("request_id")); //$NON-NLS-1$ //$NON-NLS-2$
			shown.put("tool_name", request.get("tool_name")); //$NON-NLS-1$ //$NON-NLS-2$
			shown.put("tool_use_id", request.get("tool_use_id")); //$NON-NLS-1$ //$NON-NLS-2$
			shown.put("description", request.get("description")); //$NON-NLS-1$ //$NON-NLS-2$
			shown.put("input", request.get("input")); //$NON-NLS-1$ //$NON-NLS-2$
			shown.put("always", !Json.list(request, "permission_suggestions").isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		case "control_cancel_request" -> shown.put("request_id", message.get("request_id")); //$NON-NLS-1$ //$NON-NLS-2$
		case "control_response" -> { //$NON-NLS-1$
			// The answers to the changes of model and mode: only their errors.
			Map<String, Object> response = Json.object(message, "response"); //$NON-NLS-1$
			if (!"error".equals(Json.string(response, "subtype"))) { //$NON-NLS-1$ //$NON-NLS-2$
				return null;
			}
			return notice("error", "Claude Code: " + Json.string(response, "error")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		case "result" -> { //$NON-NLS-1$
			for (String key : List.of("subtype", "is_error", "total_cost_usd", "usage", "num_turns", "duration_ms")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				shown.put(key, message.get(key));
			}
			if (Boolean.TRUE.equals(message.get("is_error"))) { //$NON-NLS-1$
				shown.put("result", message.get("result")); //$NON-NLS-1$ //$NON-NLS-2$
				shown.put("errors", message.get("errors")); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		default -> {
			return null;
		}
		}
		return shown;
	}

	/** The text of the result of a tool, shortened: the result of a Read can be a whole file. */
	static String resultText(Object content) {
		String text;
		if (content instanceof String string) {
			text = string;
		} else if (content instanceof List<?> blocks) {
			StringBuilder joined = new StringBuilder();
			for (Object block : blocks) {
				if (block instanceof Map<?, ?> map && map.get("text") instanceof String part) { //$NON-NLS-1$
					joined.append(joined.isEmpty() ? "" : "\n").append(part); //$NON-NLS-1$ //$NON-NLS-2$
				} else if (block instanceof Map<?, ?> map && "image".equals(map.get("type"))) { //$NON-NLS-1$ //$NON-NLS-2$
					joined.append(joined.isEmpty() ? "" : "\n").append("[image]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
			}
			text = joined.toString();
		} else {
			return null;
		}
		return text.length() > RESULT_SHOWN ? text.substring(0, RESULT_SHOWN) + "\n…" : text; //$NON-NLS-1$
	}

	/**
	 * Keeps the change that a tool made to a file, to compare it, and refreshes the file in the
	 * workspace.
	 *
	 * @return {@code true} if the tool changed a file
	 */
	private boolean change(String toolUseId, Map<String, Object> message, boolean live) {
		Map<String, Object> result = Json.object(message, "tool_use_result"); //$NON-NLS-1$
		String path = Json.string(result, "filePath"); //$NON-NLS-1$
		// Edit and Write tell what the file was ("originalFile", null for a new file); Read does not.
		if (path == null || !result.containsKey("originalFile") || !result.containsKey("structuredPatch")) { //$NON-NLS-1$ //$NON-NLS-2$
			return false;
		}
		File file = new File(path);
		if (!file.isAbsolute()) {
			file = new File(directory, path);
		}
		String before = Json.string(result, "originalFile"); //$NON-NLS-1$
		changes.put(toolUseId, new Change(file, before == null ? "" : before)); //$NON-NLS-1$
		if (live) {
			Workspace.refresh(file, false);
		}
		return true;
	}

	private void ended(ClaudeProcess source, int exitCode, String errors) {
		if (source != claude) {
			return;
		}
		claude = null;
		permissions.clear();
		setTurns(0);
		if (browser.isDisposed()) {
			return;
		}
		Map<String, Object> exit = new LinkedHashMap<>();
		exit.put("type", "x4e_exit"); //$NON-NLS-1$ //$NON-NLS-2$
		exit.put("code", exitCode); //$NON-NLS-1$
		exit.put("errors", errors.strip()); //$NON-NLS-1$
		post(exit);
	}

	/** Stops what Claude Code does: it stays for the next message. */
	void interrupt() {
		if (claude != null && turns > 0) {
			claude.request("interrupt", Map.of()); //$NON-NLS-1$
		}
	}

	/**
	 * Answers a request of permission of Claude Code.
	 *
	 * @param decision
	 *            {@code allow}, {@code always} (and the rules that Claude Code suggests, such as
	 *            accepting the edits of the session) or {@code deny}
	 */
	void answer(String requestId, String decision) {
		Map<String, Object> request = permissions.remove(requestId);
		if (request == null || claude == null) {
			return;
		}
		Map<String, Object> response = new LinkedHashMap<>();
		if (decision.equals("allow") || decision.equals("always")) { //$NON-NLS-1$ //$NON-NLS-2$
			response.put("behavior", "allow"); //$NON-NLS-1$ //$NON-NLS-2$
			// The input that Claude Code asked for, not one from the page.
			Map<String, Object> input = Json.object(request, "input"); //$NON-NLS-1$
			response.put("updatedInput", input == null ? Map.of() : input); //$NON-NLS-1$
			List<?> suggestions = Json.list(request, "permission_suggestions"); //$NON-NLS-1$
			if (decision.equals("always") && !suggestions.isEmpty()) { //$NON-NLS-1$
				response.put("updatedPermissions", suggestions); //$NON-NLS-1$
			}
		} else {
			response.put("behavior", "deny"); //$NON-NLS-1$ //$NON-NLS-2$
			response.put("message", "The user refused this in Eclipse."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		claude.answer(requestId, response);
	}

	/** @param chosen an alias or a full name, empty for the model of Claude Code */
	void setModel(String chosen) {
		String value = chosen.trim();
		if (!value.isEmpty() && !ClaudeCommand.isModel(value)) {
			return;
		}
		model = value;
		if (claude != null) {
			claude.request("set_model", value.isEmpty() ? Map.of() : Map.of("model", value)); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	void setMode(String chosen) {
		if (!ClaudeCommand.PERMISSION_MODES.containsKey(chosen)) {
			return;
		}
		mode = chosen;
		if (claude != null) {
			claude.request("set_permission_mode", Map.of("mode", chosen)); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private void setTurns(int value) {
		turns = Math.max(0, value);
		if (stop != null) {
			stop.setEnabled(turns > 0);
		}
	}

	boolean isBusy() {
		return turns > 0;
	}

	boolean isPageReady() {
		return pageReady;
	}

	// The page.

	/** Tells the page about something. */
	private void post(Map<String, Object> event) {
		String script = "chatEvent(" + Json.write(event) + ")"; //$NON-NLS-1$ //$NON-NLS-2$
		if (!pageReady) {
			pending.add(script);
		} else if (!browser.isDisposed()) {
			browser.execute(script);
		}
	}

	private static Map<String, Object> notice(String level, String text) {
		Map<String, Object> notice = new LinkedHashMap<>();
		notice.put("type", "x4e_notice"); //$NON-NLS-1$ //$NON-NLS-2$
		notice.put("level", level); //$NON-NLS-1$
		notice.put("text", text); //$NON-NLS-1$
		return notice;
	}

	/** Adds a text to the message that the user writes, for example a reference to a file. */
	void insert(String text) {
		post(Map.of("type", "x4e_insert", "text", text)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	/** Chooses among several files that a name can be: the list of what was found; a test may change it. */
	static Function<List<File>, File> fileChooser = ChatView::chooseFile;

	private void openFile(String path, int line) {
		File file = new File(path);
		if (!file.isAbsolute()) {
			file = new File(directory, path);
		}
		if (file.isFile()) {
			openEditor(file, line);
		} else if (!new File(path).isAbsolute()) {
			// Only a name, or the end of a path: looked for in the folder, off the UI thread.
			Thread search = new Thread(() -> {
				List<File> found = FileFinder.find(directory, path);
				if (!found.isEmpty()) {
					ChatPlugin.later(display, () -> {
						if (browser.isDisposed()) {
							return; // the chat was closed meanwhile
						}
						File chosen = found.size() == 1 ? found.get(0) : fileChooser.apply(found);
						if (chosen != null) {
							openEditor(chosen, line);
						}
					});
				}
			}, "Claude Code Chat: find " + path); //$NON-NLS-1$
			search.setDaemon(true);
			search.start();
		}
	}

	private void openEditor(File file, int line) {
		try {
			opener.open(getSite().getPage(), file, line);
		} catch (PartInitException | RuntimeException e) {
			ChatPlugin.log("Could not open " + file, e); //$NON-NLS-1$
		}
	}

	private static File chooseFile(List<File> files) {
		ElementListSelectionDialog dialog = new ElementListSelectionDialog(Display.getDefault().getActiveShell(), new LabelProvider() {
			@Override
			public String getText(Object element) {
				return ((File) element).getPath();
			}
		});
		dialog.setTitle("Open in Eclipse"); //$NON-NLS-1$
		dialog.setMessage("Several files have this name:"); //$NON-NLS-1$
		dialog.setElements(files.toArray());
		dialog.setMultipleSelection(false);
		return dialog.open() == Window.OK ? (File) dialog.getFirstResult() : null;
	}

	private void openChange(String toolUseId) {
		Change change = changes.get(toolUseId);
		if (change != null) {
			try {
				opener.compare(getSite().getPage(), change.file(), change.before());
			} catch (RuntimeException e) {
				ChatPlugin.log("Could not compare " + change.file(), e); //$NON-NLS-1$
			}
		}
	}

	/** The links of the messages that open in the browser of the system: http and https only. */
	static boolean isWebLink(String link) {
		try {
			URI uri = new URI(link);
			return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) //$NON-NLS-1$ //$NON-NLS-2$
					&& uri.getHost() != null;
		} catch (URISyntaxException e) {
			return false;
		}
	}

	private void openLink(String link) {
		if (isWebLink(link)) {
			Program.launch(link);
		}
	}

	private IWorkbench workbench() {
		return getSite().getWorkbenchWindow().getWorkbench();
	}

	/** The colors and fonts of Eclipse, and the choices of the chat. */
	private String config() {
		RGB background = browser.getParent().getBackground().getRGB();
		RGB foreground = browser.getParent().getForeground().getRGB();
		boolean dark = luminance(background) < 0.5;
		if (!dark) {
			// Light themes leave the views grey; text areas are drawn on this one.
			background = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
			foreground = display.getSystemColor(SWT.COLOR_LIST_FOREGROUND).getRGB();
			dark = luminance(background) < 0.5;
		}
		FontData font = JFaceResources.getDialogFont().getFontData()[0];
		FontData code = JFaceResources.getTextFont().getFontData()[0];
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("dark", dark); //$NON-NLS-1$
		config.put("background", hex(background)); //$NON-NLS-1$
		// A theme may leave the foreground unstyled: only used when it is readable.
		config.put("foreground", Math.abs(luminance(foreground) - luminance(background)) > 0.4 ? hex(foreground) : null); //$NON-NLS-1$
		config.put("font", fontFamily(font.getName(), "sans-serif")); //$NON-NLS-1$ //$NON-NLS-2$
		config.put("fontSize", Math.max(9, Math.round(font.getHeight() * 96f / 72f))); //$NON-NLS-1$
		config.put("codeFont", fontFamily(code.getName(), "monospace")); //$NON-NLS-1$ //$NON-NLS-2$
		config.put("codeFontSize", Math.max(9, Math.round(code.getHeight() * 96f / 72f))); //$NON-NLS-1$
		config.put("os", ClaudeCommand.windows ? "windows" : SWT.getPlatform().equals("cocoa") ? "mac" : "linux"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		config.put("directory", directory.getPath()); //$NON-NLS-1$
		config.put("model", model); //$NON-NLS-1$
		config.put("models", ClaudeCommand.MODELS); //$NON-NLS-1$
		config.put("mode", mode); //$NON-NLS-1$
		config.put("modes", ClaudeCommand.PERMISSION_MODES); //$NON-NLS-1$
		return Json.write(config);
	}

	/** The CSS font list for a font name, with a fallback; the name keeps only what a font name holds. */
	static String fontFamily(String name, String fallback) {
		String clean = name.replaceAll("[^\\p{L}\\p{N} ._+-]", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
		return (clean.isEmpty() ? "" : "'" + clean + "', ") + fallback; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private static double luminance(RGB rgb) {
		return (0.2126 * rgb.red + 0.7152 * rgb.green + 0.0722 * rgb.blue) / 255;
	}

	private static String hex(RGB rgb) {
		return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue); //$NON-NLS-1$
	}

	private void applyTheme() {
		if (browser != null && !browser.isDisposed() && pageReady) {
			browser.execute("chatSetTheme(" + config() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	@Override
	public void setFocus() {
		if (browser != null && !browser.isDisposed()) {
			// The page gives the keyboard to the message when it receives the focus: running a script
			// here would block the UI until the browser answers.
			browser.setFocus();
		}
	}

	@Override
	public void dispose() {
		getSite().getPage().removePartListener(partListener);
		if (lastActive == this) {
			lastActive = null;
		}
		workbench().getThemeManager().removePropertyChangeListener(themeListener);
		JFaceResources.getFontRegistry().removeListener(themeListener);
		if (claude != null) {
			ClaudeProcess running = claude;
			claude = null;
			running.stop();
		}
		super.dispose();
	}
}
