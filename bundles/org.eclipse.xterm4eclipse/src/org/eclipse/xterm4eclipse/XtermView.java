package org.eclipse.xterm4eclipse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.CommandException;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.IPresentationEngine;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.DecorationOverlayIcon;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.ISaveablePart2;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartConstants;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.keys.IBindingService;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.progress.IWorkbenchSiteProgressService;

/**
 * A terminal view rendered by xterm.js (the terminal emulator used by VS Code) inside an SWT
 * browser, connected to a local shell through a pseudo terminal.
 */
public class XtermView extends ViewPart implements PtySession.Listener, ISaveablePart2 {

	public static final String ID = "org.eclipse.xterm4eclipse.view"; //$NON-NLS-1$

	private static final int MAX_PENDING_BYTES = 1024 * 1024;
	private static final int MAX_TITLE_LENGTH = 30;
	private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
	private static final boolean IS_WINDOWS = OS_NAME.contains("win"); //$NON-NLS-1$
	private static final boolean IS_MAC = OS_NAME.contains("mac"); //$NON-NLS-1$
	private static final long FAILED_START_MILLIS = 2000;
	private static final String FOCUS_IN = "\u001b[I"; //$NON-NLS-1$
	private static final String FOCUS_OUT = "\u001b[O"; //$NON-NLS-1$
	/** Output within this delay after a key press is its echo. */
	private static final long ECHO_MILLIS = 1000;
	/** A command must run at least this long to show as running, and its end to be signalled. */
	private static final long RUNNING_MILLIS = 500;
	/** Output must flow at least this long to count as work whose end is worth signalling. */
	private static final long WORK_MILLIS = 2000;
	/** Silence after which the work of a foreground program is considered finished. */
	private static final long QUIET_MILLIS = 1500;
	private static final String MEMENTO_SHELL = "shell"; //$NON-NLS-1$
	private static final String MEMENTO_DIRECTORY = "directory"; //$NON-NLS-1$
	private static final String MEMENTO_NAME = "name"; //$NON-NLS-1$
	private static final String MEMENTO_CLAUDE_SESSION = "claudeSession"; //$NON-NLS-1$

	/** How long the browser may stay hidden while its page loads. */
	static int revealTimeoutMillis = 1500;

	/** Shell requested by the "New Terminal" menu for the view that is about to be created. */
	private static String nextCommandLine;
	/** Directory requested by "Show in Xterm" for the view that is about to be created. */
	private static File nextDirectory;
	private static long lastSecondaryId;
	/** Text that "Run Selected Text in Terminal" sends to the view that is about to be created. */
	private static String nextInput;
	/** Name and saved screen of a terminal of an earlier version that comes back as a view. */
	private static String nextName;
	private static byte[] nextHistory;
	/** The terminal the user worked in last, where "Run Selected Text in Terminal" runs the text. */
	private static XtermView lastActive;

	Browser browser;
	private Display display;
	private Clipboard clipboard;
	private volatile PtySession session;

	/** What the tab icon tells about the shell. */
	private enum Activity {
		/** The shell waits at its prompt. */
		IDLE(null),
		/** A command runs in the foreground. */
		RUNNING("icons/ovr-running.png"), //$NON-NLS-1$
		/** A command has finished, or a program asked for attention, and the user has not looked yet. */
		DONE("icons/ovr-done.png"); //$NON-NLS-1$

		/** The badge drawn over the icon of the shell, {@code null} for none. */
		final String overlay;

		Activity(String overlay) {
			this.overlay = overlay;
		}
	}

	private final Image[] activityImages = new Image[Activity.values().length];
	private Activity activity = Activity.IDLE;
	private ScheduledExecutorService activityPoller;
	private volatile boolean lastBusy;
	/** When the poller saw the current command start, or 0. */
	private volatile long busySince;
	/** The current command has run for {@link #RUNNING_MILLIS} at least. */
	private volatile boolean longRunning;

	/**
	 * A program that stays in the foreground, such as Claude Code, never "finishes": the end of its
	 * work shows as output that flows for a while (spinner, progress) and then stops. The output that
	 * closely follows a key press is the echo of the typing, not work.
	 */
	private final Object outputActivity = new Object();
	private long lastInputMillis;
	private long lastOutputMillis;
	/** Start of the current stretch of continuous output, 0 if there is none. */
	private long workStartMillis;
	private String commandLine;
	/** Name given by the user, which the titles set by the programs no longer replace. */
	private String customName;
	/** The page of the terminal, the only one the browser may show. */
	private File pageFile;
	private long sessionStart;
	/** The shell or program has ended and the view stays open: Enter starts it again. */
	private volatile boolean ended;
	/** Screen content saved before the last Eclipse shutdown, replayed once when the view opens. */
	private byte[] restoredContent;
	private File workingDirectory;
	/** Opened by the user, not restored by Eclipse: a new Claude Code may offer the MCP server of Eclipse. */
	private boolean openedByUser;
	/** The user answered "Not Now": not asked again until Eclipse restarts. */
	static boolean mcpPostponed;
	/** The conversation of Claude Code in this terminal, if it runs Claude Code. */
	private String claudeSession;
	/** The next start of Claude Code resumes its conversation. */
	private boolean resumeClaude;
	/** Directory announced by the shell itself through an escape sequence, if it does so. */
	private File reportedDirectory;
	/** Text to run once the shell has started. */
	private String pendingInput;
	/** Moves the view to the editor area, or back to the views. */
	private Action moveAction;
	private int cols = 80;
	private int rows = 24;
	private boolean keyFilterDisabled;

	private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
	private boolean flushScheduled;

	private final IPropertyChangeListener themeListener = event -> {
		if (!display.isDisposed()) {
			// Let the CSS engine restyle the widgets first.
			display.asyncExec(this::applyTheme);
		}
	};

	private final IPartListener2 partListener = new IPartListener2() {
		@Override
		public void partActivated(IWorkbenchPartReference ref) {
			if (ref.getPart(false) == XtermView.this) {
				lastActive = XtermView.this;
				setKeyFilterEnabled(false);
				clearDone();
				// The user may also have dragged the view to or from the editor area.
				updateMoveAction();
			}
		}

		@Override
		public void partDeactivated(IWorkbenchPartReference ref) {
			if (ref.getPart(false) == XtermView.this) {
				setKeyFilterEnabled(true);
			}
		}
	};

	@Override
	public void init(IViewSite site, IMemento memento) throws PartInitException {
		super.init(site, memento);
		// Opened by the user: the state that Eclipse may still hold for this view from an earlier
		// session must not override the shell and directory just chosen.
		boolean requested = nextCommandLine != null;
		pendingInput = nextInput;
		nextInput = null;
		openedByUser = requested;
		if (requested) {
			commandLine = nextCommandLine;
			nextCommandLine = null;
			workingDirectory = nextDirectory;
			nextDirectory = null;
			customName = nextName;
			nextName = null;
			restoredContent = nextHistory;
			nextHistory = null;
		} else if (memento != null && memento.getString(MEMENTO_SHELL) != null) {
			commandLine = memento.getString(MEMENTO_SHELL);
		} else {
			commandLine = ShellProfiles.defaultCommandLine();
		}
		if (memento != null && !requested) {
			customName = memento.getString(MEMENTO_NAME);
			// Checked: it goes into the command line, which a shell may run.
			String claude = memento.getString(MEMENTO_CLAUDE_SESSION);
			if (ClaudeSessions.isId(claude)) {
				claudeSession = claude;
				resumeClaude = true;
			}
			String directory = memento.getString(MEMENTO_DIRECTORY);
			if (directory != null && new File(directory).isDirectory()) {
				workingDirectory = new File(directory);
			}
			try {
				Path file = contentFile();
				if (XtermPlugin.isEnabled(XtermPlugin.PREF_RESTORE_HISTORY) && Files.isReadable(file)) {
					restoredContent = Files.readAllBytes(file);
				}
			} catch (IOException | RuntimeException e) {
				XtermPlugin.log("Could not restore the terminal content", e); //$NON-NLS-1$
			}
		}
	}

	@Override
	public void saveState(IMemento memento) {
		memento.putString(MEMENTO_SHELL, commandLine);
		if (customName != null) {
			memento.putString(MEMENTO_NAME, customName);
		}
		if (claudeSession != null) {
			memento.putString(MEMENTO_CLAUDE_SESSION, claudeSession);
		}
		File directory = session != null ? session.currentDirectory() : null;
		if (directory == null) {
			directory = reportedDirectory != null ? reportedDirectory : workingDirectory;
		}
		if (directory != null) {
			memento.putString(MEMENTO_DIRECTORY, directory.getAbsolutePath());
		}
		if (browser == null || browser.isDisposed()) {
			return;
		}
		try {
			if (!XtermPlugin.isEnabled(XtermPlugin.PREF_RESTORE_HISTORY)) {
				Files.deleteIfExists(contentFile());
				return;
			}
			Object content = browser.evaluate("return window.xtermSerialize ? xtermSerialize() : ''"); //$NON-NLS-1$
			if (content instanceof String text && !text.isEmpty()) {
				Path file = contentFile();
				Files.createDirectories(file.getParent());
				Files.writeString(file, text, StandardCharsets.UTF_8);
			}
		} catch (IOException | SWTException e) {
			XtermPlugin.log("Could not save the terminal content", e); //$NON-NLS-1$
		}
	}

	/** The file holding the saved screen of this view, one per view instance. */
	private Path contentFile() {
		String secondaryId = getViewSite().getSecondaryId();
		String name = secondaryId == null ? "main" : secondaryId.replaceAll("[^A-Za-z0-9_-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		return XtermPlugin.stateDirectory().resolve(name + ".screen"); //$NON-NLS-1$
	}

	@Override
	public void createPartControl(Composite parent) {
		display = parent.getDisplay();
		setPartName(customName != null ? customName : ShellProfiles.displayName(commandLine));
		clipboard = new Clipboard(display);
		if (workingDirectory == null) {
			workingDirectory = resolveWorkingDirectory();
		}

		// On Windows the default can still be Internet Explorer, which cannot run xterm.js.
		browser = new Browser(parent, IS_WINDOWS ? SWT.EDGE : SWT.NONE);
		// A browser is white until its page is drawn: keep it hidden behind the themed background of
		// the view until the terminal is ready, with a deadline in case the page never says so.
		browser.setVisible(false);
		display.timerExec(revealTimeoutMillis, this::reveal);
		registerFunctions();
		// The java* functions give the page the keyboard of the shell: no other page may ever be
		// loaded in this browser, nor open a window of its own.
		browser.addLocationListener(LocationListener.changingAdapter(event -> {
			event.doit = isOwnPage(event.location);
			if (!event.doit) {
				XtermPlugin.log("Blocked a navigation of the terminal to " + event.location, null); //$NON-NLS-1$
			}
		}));
		browser.addOpenWindowListener(event -> event.required = true);
		// The java* functions only exist in the page once it is loaded, so the page waits for us.
		browser.addProgressListener(
				ProgressListener.completedAdapter(event -> browser.execute("xtermInit(" + config() + ")"))); //$NON-NLS-1$ //$NON-NLS-2$
		try {
			// One file per plug-in version, shared by all the terminals.
			Path page = WebPage.materialize(
					XtermPlugin.stateDirectory().resolve("terminal-" + XtermPlugin.version() + ".html"), //$NON-NLS-1$ //$NON-NLS-2$
					name -> XtermPlugin.resource("web/" + name)); //$NON-NLS-1$
			pageFile = page.toFile().getAbsoluteFile();
			browser.setUrl(page.toUri().toString());
		} catch (IOException e) {
			XtermPlugin.log("Could not load the xterm.js resources", e); //$NON-NLS-1$
			browser.setText("<pre>Could not load the xterm.js resources: " + escapeHtml(e.toString()) + "</pre>"); //$NON-NLS-1$ //$NON-NLS-2$
		}

		createActions();
		// The tab shows the icon of the shell or program (bash, PowerShell, Claude Code...), with a badge
		// for what it does.
		ImageDescriptor shellIcon = ImageDescriptor.createFromFile(XtermView.class, '/' + ShellProfiles.iconOf(commandLine));
		for (Activity value : Activity.values()) {
			ImageDescriptor icon = value.overlay == null ? shellIcon
					: new DecorationOverlayIcon(shellIcon,
							ImageDescriptor.createFromFile(XtermView.class, '/' + value.overlay), IDecoration.BOTTOM_RIGHT);
			activityImages[value.ordinal()] = icon.createImage();
		}
		setTitleImage(activityImages[activity.ordinal()]);
		// After the workbench has set up the tab, which gives it the icon of the part declaration.
		display.asyncExec(this::showTabIcon);
		activityPoller = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "Xterm activity poller"); //$NON-NLS-1$
			thread.setDaemon(true);
			return thread;
		});
		activityPoller.scheduleWithFixedDelay(this::pollActivity, 1000, 400, TimeUnit.MILLISECONDS);
		getSite().getPage().addPartListener(partListener);
		workbench().getThemeManager().addPropertyChangeListener(themeListener);
		JFaceResources.getFontRegistry().addListener(themeListener);
		XtermPlugin.preferences().addPropertyChangeListener(themeListener);
	}

	private void registerFunctions() {
		function("javaReady", args -> { //$NON-NLS-1$
			// Not from within the call: revealing runs a script, which would deadlock WebKitGTK.
			display.asyncExec(this::reveal);
			return null;
		});
		function("javaStart", args -> { //$NON-NLS-1$
			updateSize(args);
			startSession();
			// Else it waits for the shell, which a question of the user delays.
			if (pendingInput != null && session != null) {
				String text = pendingInput;
				pendingInput = null;
				// Not from within the call: it runs a script in the page.
				display.asyncExec(() -> send(text));
			}
			return null;
		});
		function("javaResize", args -> { //$NON-NLS-1$
			updateSize(args);
			if (session != null) {
				session.resize(cols, rows);
			}
			return null;
		});
		function("javaInput", args -> { //$NON-NLS-1$
			input((String) args[0], false);
			return null;
		});
		function("javaBinary", args -> { //$NON-NLS-1$
			input((String) args[0], true);
			return null;
		});
		function("javaShortcut", args -> runEclipseShortcut((String) args[0])); //$NON-NLS-1$
		function("javaShiftEnter", args -> { //$NON-NLS-1$
			input(shiftEnterSequence(), false);
			return null;
		});
		function("javaConfirmPaste", args -> { //$NON-NLS-1$
			String text = args.length > 0 && args[0] instanceof String value ? value : null;
			if (text != null) {
				// Not from within the call: a dialog runs the event loop.
				display.asyncExec(() -> confirmPaste(text));
			}
			return null;
		});
		function("javaProgress", args -> { //$NON-NLS-1$
			int state = args.length > 0 && args[0] instanceof Number number ? number.intValue() : 0;
			// Not from within the call: the workbench updates the tab.
			display.asyncExec(() -> showProgress(state));
			return null;
		});
		function("javaCopy", args -> { //$NON-NLS-1$
			String text = (String) args[0];
			if (text != null && !text.isEmpty()) {
				clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
			}
			return null;
		});
		function("javaPaste", args -> { //$NON-NLS-1$
			Object text = clipboard.getContents(TextTransfer.getInstance());
			return text instanceof String ? text : ""; //$NON-NLS-1$
		});
		function("javaPastePrimary", args -> { //$NON-NLS-1$
			Object text = clipboard.getContents(TextTransfer.getInstance(), DND.SELECTION_CLIPBOARD);
			return text instanceof String ? text : ""; //$NON-NLS-1$
		});
		function("javaSelected", args -> { //$NON-NLS-1$
			String text = args.length > 0 && args[0] instanceof String value ? value : ""; //$NON-NLS-1$
			if (!text.isEmpty()) {
				if (!IS_WINDOWS && !IS_MAC) {
					clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()},
							DND.SELECTION_CLIPBOARD);
				}
				if (XtermPlugin.isEnabled(XtermPlugin.PREF_COPY_ON_SELECT)) {
					clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
				}
			}
			return null;
		});
		function("javaDrop", args -> { //$NON-NLS-1$
			List<String> paths = droppedPaths((String) args[0]);
			if (paths.isEmpty()) {
				// Text dragged from an editor, or files the browser does not give the path of.
				Object text = args.length > 1 ? args[1] : null;
				return text instanceof String ? text : ""; //$NON-NLS-1$
			}
			return quotePaths(paths, commandLine);
		});
		function("javaAttention", args -> { //$NON-NLS-1$
			signalDone();
			return null;
		});
		function("javaDirectory", args -> { //$NON-NLS-1$
			File directory = parseDirectory((String) args[0]);
			if (directory != null && IS_WINDOWS) {
				directory = new File(windowsPath(directory.getPath(), commandLine));
			}
			if (directory != null && directory.isDirectory()) {
				reportedDirectory = directory;
			}
			return null;
		});
		function("javaOpenLink", args -> { //$NON-NLS-1$
			String link = args.length > 0 && args[0] instanceof String text ? text : null;
			if (isWebLink(link)) {
				Program.launch(link);
			}
			return null;
		});
		function("javaResolveFiles", args -> { //$NON-NLS-1$
			Object[] candidates = args.length > 0 && args[0] instanceof Object[] array ? array : new Object[0];
			Object[] result = new Object[candidates.length];
			File directory = candidates.length == 0 ? null : currentDirectory();
			for (int i = 0; i < candidates.length; i++) {
				File file = candidates[i] instanceof String text ? resolveFile(text, directory) : null;
				result[i] = file == null ? null : file.getPath();
			}
			return result;
		});
		function("javaOpenFile", args -> { //$NON-NLS-1$
			File file = new File((String) args[0]);
			int line = args.length > 1 && args[1] instanceof Number number ? number.intValue() : 0;
			int column = args.length > 2 && args[2] instanceof Number number ? number.intValue() : 0;
			// Not from within the call: opening an editor runs the event loop.
			display.asyncExec(() -> openFile(file, line, column));
			return null;
		});
		function("javaTitle", args -> { //$NON-NLS-1$
			String title = (String) args[0];
			if (title != null && !title.isBlank() && !isProgramPath(title, commandLine)) {
				setTitleToolTip(title);
				if (customName != null) {
					return null;
				}
				setPartName(title.length() > MAX_TITLE_LENGTH
						? "…" + title.substring(title.length() - MAX_TITLE_LENGTH) //$NON-NLS-1$
						: title);
			}
			return null;
		});
	}

	/**
	 * Runs the Eclipse command bound to a key stroke that the user wants Eclipse to handle even in the
	 * terminal.
	 *
	 * @param name
	 *            the key stroke, as named by {@link EclipseShortcuts}
	 * @return {@code true} if a command takes the key, {@code false} to send it to the shell
	 */
	boolean runEclipseShortcut(String name) {
		KeySequence sequence = name == null ? null : EclipseShortcuts.sequence(name);
		IBindingService bindings = workbench().getService(IBindingService.class);
		Binding binding = sequence == null || bindings == null ? null : bindings.getPerfectMatch(sequence);
		ParameterizedCommand command = binding == null ? null : binding.getParameterizedCommand();
		if (command == null || !command.getCommand().isHandled()) {
			return false;
		}
		// Not from within the call: the command may open a dialog, or run scripts in this browser.
		display.asyncExec(() -> {
			IHandlerService handlers = workbench().getService(IHandlerService.class);
			if (handlers == null) {
				return;
			}
			try {
				handlers.executeCommand(command, null);
			} catch (ExecutionException e) {
				XtermPlugin.log("Could not run " + command.getId(), e); //$NON-NLS-1$
			} catch (CommandException e) {
				// Disabled or no longer handled: like the key in an editor, nothing happens.
			}
		});
		return true;
	}

	/** @return where the shell is now, as far as it is known */
	private File currentDirectory() {
		PtySession current = session;
		File directory = current != null ? current.currentDirectory() : null;
		if (directory == null) {
			directory = reportedDirectory != null ? reportedDirectory : workingDirectory;
		}
		return directory;
	}

	/**
	 * @param path
	 *            a path printed in the terminal, absolute, relative to the directory of the shell or
	 *            to the home directory ({@code ~/...})
	 * @return the file, or {@code null} if there is no such file
	 */
	static File resolveFile(String path, File directory) {
		if (path == null || path.isBlank()) {
			return null;
		}
		String name = path;
		if (name.startsWith("~/") || name.startsWith("~\\")) { //$NON-NLS-1$ //$NON-NLS-2$
			name = System.getProperty("user.home") + name.substring(1); //$NON-NLS-1$
		}
		File file = new File(name);
		if (!file.isAbsolute()) {
			if (directory == null) {
				return null;
			}
			file = new File(directory, name);
		}
		try {
			file = file.getCanonicalFile();
		} catch (IOException e) {
			return null;
		}
		return file.isFile() ? file : null;
	}

	/**
	 * Opens a file the user clicked in the terminal in an Eclipse editor, at the given line and
	 * column (1-based, 0 if unknown).
	 */
	void openFile(File file, int line, int column) {
		try {
			FileOpener.open(getSite().getPage(), file, line, column);
		} catch (PartInitException | RuntimeException e) {
			XtermPlugin.log("Could not open " + file, e); //$NON-NLS-1$
		} catch (LinkageError e) {
			// No IDE bundles: nothing to open the file with. It is never handed to the system, which
			// could run it.
			XtermPlugin.log("Cannot open " + file + " without the Eclipse IDE", e); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	/**
	 * Shift+Enter inserts a newline in Claude Code and similar programs, which read it as Alt+Enter
	 * (ESC CR). A shell at its prompt only rings the bell for that: there it runs the command, like
	 * Enter.
	 */
	String shiftEnterSequence() {
		PtySession current = session;
		boolean atPrompt = ShellProfiles.isShell(commandLine) && (current == null || !current.isBusy());
		return atPrompt ? "\r" : "\u001b\r"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * @return {@code true} for the page of the terminal, or the empty page the browser starts with
	 */
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

	/**
	 * @return {@code true} for the http and https URLs that the web links of the terminal may open in
	 *         the browser of the system; never a local file or a program
	 */
	static boolean isWebLink(String link) {
		if (link == null) {
			return false;
		}
		try {
			URI uri = new URI(link);
			String scheme = uri.getScheme();
			return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) //$NON-NLS-1$ //$NON-NLS-2$
					&& uri.getHost() != null && !uri.getHost().isEmpty();
		} catch (URISyntaxException e) {
			return false;
		}
	}

	static String escapeHtml(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	}

	private interface JsFunction {
		Object call(Object[] args);
	}

	private void function(String name, JsFunction body) {
		new BrowserFunction(browser, name) {
			@Override
			public Object function(Object[] arguments) {
				try {
					return body.call(arguments);
				} catch (RuntimeException e) {
					XtermPlugin.log("Error in " + name, e); //$NON-NLS-1$
					return null;
				}
			}
		};
	}

	private void createActions() {
		ISharedImages images = workbench().getSharedImages();
		IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();

		Action newTerminal = new Action("New Terminal", IAction.AS_DROP_DOWN_MENU) { //$NON-NLS-1$
			@Override
			public void run() {
				openTerminal(ShellProfiles.defaultCommandLine());
			}
		};
		newTerminal.setToolTipText("New Terminal (use the arrow to choose a shell)"); //$NON-NLS-1$
		newTerminal.setImageDescriptor(ImageDescriptor.createFromFile(XtermView.class, "/icons/xterm.png"));
		newTerminal.setMenuCreator(new ShellMenu());
		toolBar.add(newTerminal);

		Action clear = new Action("Clear") { //$NON-NLS-1$
			@Override
			public void run() {
				browser.execute("xtermClear()"); //$NON-NLS-1$
				setFocus();
			}
		};
		clear.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_ETOOL_CLEAR));
		toolBar.add(clear);

		Action rename = new Action("Rename\u2026") { //$NON-NLS-1$
			@Override
			public void run() {
				String name = askName(getPartName());
				if (name != null) {
					rename(name);
				}
			}
		};
		getViewSite().getActionBars().getMenuManager().add(rename);

		Action selectAll = new Action("Select All") { //$NON-NLS-1$
			@Override
			public void run() {
				browser.execute("window.xtermSelectAll && xtermSelectAll()"); //$NON-NLS-1$
				setFocus();
			}
		};
		getViewSite().getActionBars().getMenuManager().add(selectAll);

		Action restartAction = new Action("Restart") { //$NON-NLS-1$
			@Override
			public void run() {
				restart();
				setFocus();
			}
		};
		restartAction.setToolTipText("Start the shell again, in the directory it is in"); //$NON-NLS-1$
		getViewSite().getActionBars().getMenuManager().add(restartAction);

		moveAction = new Action() {
			@Override
			public void run() {
				if (isInEditorArea()) {
					moveToViews();
				} else {
					moveToEditorArea();
				}
			}
		};
		toolBar.add(moveAction);
		updateMoveAction();

		IMenuManager viewMenu = getViewSite().getActionBars().getMenuManager();
		Action find = new Action("Find\u2026\t" + (IS_MAC ? "\u2318F" : "Ctrl+Shift+F")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			@Override
			public void run() {
				setFocus();
				browser.execute("window.xtermFind && xtermFind()"); //$NON-NLS-1$
			}
		};
		viewMenu.add(find);
	}

	/**
	 * Shows the move action that fits where the view is now: to the editor area, or back among the
	 * other views.
	 */
	private void updateMoveAction() {
		if (moveAction == null) {
			return;
		}
		boolean inEditorArea = isInEditorArea();
		moveAction.setText(inEditorArea ? "Move Back to the Views" : "Move to the Editor Area"); //$NON-NLS-1$ //$NON-NLS-2$
		moveAction.setToolTipText(inEditorArea ? "Move this terminal back among the views" //$NON-NLS-1$
				: "Move this terminal to the editor area, next to the editors"); //$NON-NLS-1$
		moveAction.setImageDescriptor(ImageDescriptor.createFromFile(XtermView.class,
				inEditorArea ? "/icons/move-to-view.png" : "/icons/move-to-editor.png")); //$NON-NLS-1$ //$NON-NLS-2$
		moveAction.setEnabled(canMove());
	}

	/** @return {@code true} if the view is in the editor area of the window */
	boolean isInEditorArea() {
		try {
			return PartMover.isInEditorArea(getSite());
		} catch (LinkageError | RuntimeException e) {
			return false;
		}
	}

	private boolean canMove() {
		try {
			return PartMover.canMove(getSite());
		} catch (LinkageError | RuntimeException e) {
			// Not in an Eclipse 4 workbench.
			return false;
		}
	}

	/**
	 * Moves this view, as it is (same shell, same screen), to the editor area, like dragging its tab
	 * there.
	 */
	void moveToEditorArea() {
		move(true);
	}

	/** Moves this view back among the views, where it came from if that place still exists. */
	void moveToViews() {
		move(false);
	}

	private void move(boolean toEditorArea) {
		boolean moved;
		try {
			moved = toEditorArea ? PartMover.toEditorArea(getSite()) : PartMover.toViews(getSite());
		} catch (LinkageError | RuntimeException e) {
			XtermPlugin.log("Could not move the terminal", e); //$NON-NLS-1$
			moved = false;
		}
		if (moved) {
			getSite().getPage().activate(this);
		}
		updateMoveAction();
	}

	private void openTerminal(String shellCommandLine) {
		try {
			open(getSite().getPage(), shellCommandLine);
		} catch (PartInitException e) {
			XtermPlugin.log("Could not open a new terminal", e); //$NON-NLS-1$
		}
	}

	/** Opens one more terminal in the page, running the given shell. */
	static void open(IWorkbenchPage page, String shellCommandLine) throws PartInitException {
		open(page, shellCommandLine, null);
	}

	/**
	 * Opens one more terminal in the page.
	 *
	 * @param directory
	 *            where the shell starts, or {@code null} for the project of the selection
	 */
	static void open(IWorkbenchPage page, String shellCommandLine, File directory) throws PartInitException {
		open(page, shellCommandLine, directory, null, null);
	}

	/**
	 * Opens one more terminal in the page.
	 *
	 * @param name
	 *            the name the user gave to the terminal, or {@code null}
	 * @param history
	 *            the screen to show again, or {@code null}
	 * @return the new terminal, or {@code null} if the page made something else of it
	 */
	static XtermView open(IWorkbenchPage page, String shellCommandLine, File directory, String name, byte[] history)
			throws PartInitException {
		nextCommandLine = shellCommandLine;
		nextDirectory = directory != null && directory.isDirectory() ? directory : null;
		nextName = name;
		nextHistory = history;
		try {
			// The first terminal is the plain view, the next ones are numbered copies of it.
			String secondaryId = page.findViewReference(ID) == null ? null : nextSecondaryId();
			return page.showView(ID, secondaryId, IWorkbenchPage.VIEW_ACTIVATE) instanceof XtermView view ? view : null;
		} finally {
			nextCommandLine = null;
			nextDirectory = null;
			nextName = null;
			nextHistory = null;
		}
	}

	/**
	 * Runs a command line in the terminal the user worked in last, or in a new terminal if there is
	 * none in the page.
	 */
	static void run(IWorkbenchPage page, String text) throws PartInitException {
		XtermView target = lastActive;
		if (target != null && target.getSite().getPage() == page && target.browser != null
				&& !target.browser.isDisposed()) {
			page.bringToTop(target);
			target.send(text);
			return;
		}
		nextInput = text;
		try {
			open(page, ShellProfiles.defaultCommandLine());
		} finally {
			nextInput = null;
		}
	}

	/** Types the text in the terminal as a paste, then Enter to run it. */
	void send(String text) {
		if (browser.isDisposed()) {
			return;
		}
		String command = text.replaceAll("[\\r\\n]+$", ""); //$NON-NLS-1$ //$NON-NLS-2$
		browser.execute("xtermRun('" //$NON-NLS-1$
				+ Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8)) + "')"); //$NON-NLS-1$
	}

	/** A new id for each terminal, even when several are opened within the same millisecond. */
	private static String nextSecondaryId() {
		return nextId("t"); //$NON-NLS-1$
	}

	/** A new id, unique in this run of Eclipse and after a restart, for the views and the editors. */
	static synchronized String nextId(String prefix) {
		lastSecondaryId = Math.max(lastSecondaryId + 1, System.currentTimeMillis());
		return prefix + lastSecondaryId;
	}

	/**
	 * @return the directory of a selected resource (the folder holding it for a file), or
	 *         {@code null} if the element is not a resource
	 */
	static File directoryOf(Object element) {
		try {
			File directory = WorkspaceLocations.directory(element);
			if (directory != null) {
				return directory;
			}
		} catch (LinkageError e) {
			// org.eclipse.core.resources is optional.
		}
		// An editor on a file outside of the workspace.
		File file = uriFile(element);
		return file == null ? null : file.getParentFile();
	}

	/** @return the local file of an editor input on a file outside of the workspace, or {@code null} */
	private static File uriFile(Object element) {
		try {
			return EditorFiles.file(element);
		} catch (LinkageError e) {
			// org.eclipse.ui.ide is optional.
			return null;
		}
	}

	/** Isolated so that the view still loads when org.eclipse.ui.ide is absent. */
	private static final class EditorFiles {
		static File file(Object element) {
			if (element instanceof IURIEditorInput input && input.getURI() != null
					&& "file".equalsIgnoreCase(input.getURI().getScheme())) { //$NON-NLS-1$
				try {
					return new File(input.getURI());
				} catch (IllegalArgumentException e) {
					return null;
				}
			}
			return null;
		}
	}

	/**
	 * The drop down of the "New Terminal" button: one entry per detected shell, and a sub menu to
	 * choose the shell used by default.
	 */
	private final class ShellMenu implements IMenuCreator {
		private Menu menu;

		@Override
		public Menu getMenu(Control parent) {
			dispose();
			menu = new Menu(parent);
			String defaultCommandLine = ShellProfiles.defaultCommandLine();
			var profiles = ShellProfiles.detect();
			for (ShellProfiles.Profile profile : profiles) {
				MenuItem item = new MenuItem(menu, SWT.PUSH);
				item.setText(profile.commandLine().equals(defaultCommandLine) ? profile.name() + " (default)" //$NON-NLS-1$
						: profile.name());
				item.setImage(XtermPlugin.image(profile.icon()));
				item.addListener(SWT.Selection, event -> openTerminal(profile.commandLine()));
			}
			new MenuItem(menu, SWT.SEPARATOR);
			MenuItem defaultItem = new MenuItem(menu, SWT.CASCADE);
			defaultItem.setText("Select Default Shell"); //$NON-NLS-1$
			Menu defaultMenu = new Menu(menu);
			defaultItem.setMenu(defaultMenu);
			boolean known = false;
			for (ShellProfiles.Profile profile : profiles) {
				MenuItem item = new MenuItem(defaultMenu, SWT.RADIO);
				item.setText(profile.name());
				boolean selected = profile.commandLine().equals(defaultCommandLine);
				item.setSelection(selected);
				known |= selected;
				item.addListener(SWT.Selection, event -> {
					if (item.getSelection()) {
						ShellProfiles.setDefaultCommandLine(profile.commandLine());
					}
				});
			}
			MenuItem custom = new MenuItem(defaultMenu, SWT.RADIO);
			custom.setText(known ? "Custom\u2026" : "Custom: " + defaultCommandLine); //$NON-NLS-1$ //$NON-NLS-2$
			custom.setSelection(!known);
			custom.addListener(SWT.Selection, event -> {
				if (custom.getSelection()) {
					String chosen = askCustomShell(defaultCommandLine);
					if (chosen != null) {
						ShellProfiles.setDefaultCommandLine(chosen);
					}
				}
			});
			return menu;
		}

		@Override
		public Menu getMenu(Menu parent) {
			return null;
		}

		@Override
		public void dispose() {
			if (menu != null) {
				menu.dispose();
				menu = null;
			}
		}
	}

	/**
	 * Gives the terminal a name of the user's choice, kept after a restart of Eclipse.
	 *
	 * @param name
	 *            the new name, or an empty one to name the terminal after its program again
	 */
	void rename(String name) {
		String trimmed = name.trim();
		customName = trimmed.isEmpty() ? null : trimmed;
		setPartName(customName != null ? customName : ShellProfiles.displayName(commandLine));
	}

	/**
	 * Asks the user for a new name for the terminal.
	 *
	 * @return the name, empty to go back to the automatic name, or {@code null} if the user cancelled
	 */
	String askName(String current) {
		InputDialog dialog = new InputDialog(getSite().getShell(), "Rename Terminal", //$NON-NLS-1$
				"Name of the terminal (empty: the title set by the program):", current, null); //$NON-NLS-1$
		return dialog.open() == Window.OK ? dialog.getValue() : null;
	}

	/**
	 * Asks the user for the command line of a shell that is not in the list.
	 *
	 * @return the command line, or {@code null} if the user cancelled
	 */
	String askCustomShell(String current) {
		InputDialog dialog = new InputDialog(getSite().getShell(), "Default Shell", //$NON-NLS-1$
				"Command line of the shell (quote paths containing spaces):", current, //$NON-NLS-1$
				value -> ShellProfiles.parse(value).length == 0 ? "Enter a command" : null); //$NON-NLS-1$
		return dialog.open() == Window.OK ? dialog.getValue() : null;
	}

	/**
	 * Runs on the poller thread: detects when a foreground command starts or ends, and when a program
	 * that keeps running goes quiet after having worked.
	 */
	private void pollActivity() {
		try {
			PtySession current = session;
			boolean busy = current != null && current.isBusy();
			boolean wentQuiet = false;
			synchronized (outputActivity) {
				if (workStartMillis != 0 && System.currentTimeMillis() - lastOutputMillis >= QUIET_MILLIS) {
					wentQuiet = busy && lastBusy && lastOutputMillis - workStartMillis >= WORK_MILLIS;
					workStartMillis = 0;
				}
			}
			if (display.isDisposed()) {
				return;
			}
			long now = System.currentTimeMillis();
			if (!busy) {
				busySince = 0;
			} else if (busySince == 0) {
				busySince = now;
			}
			// A command shows as running, and its end is signalled, once it has run for a while: not
			// for every quick command that a poll happens to see.
			boolean running = busy && now - busySince >= RUNNING_MILLIS;
			boolean busyChanged = busy != lastBusy;
			boolean runningChanged = running != longRunning;
			lastBusy = busy;
			longRunning = running;
			if (busyChanged || runningChanged) {
				display.asyncExec(() -> {
					if (browser.isDisposed()) {
						return;
					}
					// A running command makes the view "dirty", so that closing it asks first.
					if (busyChanged) {
						firePropertyChange(IWorkbenchPartConstants.PROP_DIRTY);
					}
					if (runningChanged && running) {
						setActivity(Activity.RUNNING);
					} else if (runningChanged && activity == Activity.RUNNING) {
						signalDone();
					}
				});
			} else if (wentQuiet) {
				display.asyncExec(() -> {
					if (!browser.isDisposed()) {
						signalDone();
					}
				});
			}
		} catch (RuntimeException e) {
			// Keep polling: an exception would cancel the schedule.
		}
	}

	/** @return {@code true} while a command runs in the foreground: closing the view would kill it */
	@Override
	public boolean isDirty() {
		PtySession current = session;
		return current != null && current.isBusy();
	}

	@Override
	public boolean isSaveOnCloseNeeded() {
		return true;
	}

	@Override
	public int promptToSaveOnClose() {
		if (!isDirty()) {
			return NO;
		}
		return confirmClose() ? NO : CANCEL;
	}

	/** Asks the user whether to close the view and kill the command that runs in it. */
	boolean confirmClose() {
		return MessageDialog.openQuestion(getSite().getShell(), "Close Terminal", //$NON-NLS-1$
				"A command is still running in '" + getPartName() //$NON-NLS-1$
						+ "'. Closing the terminal will terminate it.\n\nClose anyway?"); //$NON-NLS-1$
	}

	@Override
	public void doSave(IProgressMonitor monitor) {
		// Nothing to save: the view is only "dirty" to confirm its closing.
	}

	@Override
	public void doSaveAs() {
		// Not allowed.
	}

	@Override
	public boolean isSaveAsAllowed() {
		return false;
	}

	/** The user has seen the mark: back to the icon that tells what the shell is doing. */
	private void clearDone() {
		if (activity == Activity.DONE) {
			setActivity(longRunning ? Activity.RUNNING : Activity.IDLE);
		}
	}

	/**
	 * Marks the tab so that the user sees that a command has finished, or brings the view to the
	 * front if the user asked for it. The mark stays until the view is activated or the user types
	 * in it.
	 */
	private void signalDone() {
		setActivity(Activity.DONE);
		if (getSite().getPage().getActivePart() != this) {
			if (XtermPlugin.isEnabled(XtermPlugin.PREF_FOCUS_ON_FINISH)) {
				// Activating the view also clears the mark, see the part listener.
				getSite().getPage().activate(this);
				return;
			}
			IWorkbenchSiteProgressService progress = getSite().getService(IWorkbenchSiteProgressService.class);
			if (progress != null) {
				progress.warnOfContentChange();
			}
		}
	}

	private void setActivity(Activity newActivity) {
		if (browser == null || browser.isDisposed() || activity == newActivity) {
			return;
		}
		activity = newActivity;
		setTitleImage(activityImages[newActivity.ordinal()]);
		showTabIcon();
	}

	/**
	 * Gives the tab of the part (view or editor) the icon of the shell in the model of the workbench:
	 * the icon it shows before the part is created (a tab in the background after a restart), keeps in
	 * the layout, and falls back to; and the current image, whatever the order in which the workbench
	 * sets up its parts.
	 */
	private void showTabIcon() {
		if (browser == null || browser.isDisposed()) {
			return;
		}
		try {
			TabIcons.show(getSite(), "platform:/plugin/" + XtermPlugin.ID + '/' + ShellProfiles.iconOf(commandLine), //$NON-NLS-1$
					activityImages[activity.ordinal()]);
		} catch (LinkageError | RuntimeException e) {
			// Not in an Eclipse 4 workbench: the title image is all there is.
		}
	}

	/** Isolated so that the view still loads without the Eclipse 4 model bundles. */
	private static final class TabIcons {
		static void show(org.eclipse.ui.IWorkbenchPartSite site, String iconUri, Image image) {
			Object service = site.getService((Class<?>) MPart.class);
			if (!(service instanceof MPart part)) {
				return;
			}
			if (!iconUri.equals(part.getIconURI())) {
				part.setIconURI(iconUri);
			}
			if (image != null && !image.isDisposed()) {
				part.getTransientData().put(IPresentationEngine.OVERRIDE_ICON_IMAGE_KEY, image);
			}
		}
	}

	/**
	 * @param value
	 *            a path, or a {@code file://host/path} URL as sent by OSC 7
	 */
	static File parseDirectory(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String path = value.trim();
		if (path.startsWith("file://")) { //$NON-NLS-1$
			try {
				path = new URI(path).getPath();
			} catch (URISyntaxException e) {
				return null;
			}
			// file:///C:/Users/me gives /C:/Users/me
			if (path != null && path.matches("/[A-Za-z]:.*")) { //$NON-NLS-1$
				path = path.substring(1);
			}
		}
		return path == null || path.isEmpty() ? null : new File(path);
	}

	private static final Pattern MSYS_DRIVE = Pattern.compile("[\\\\/](?:cygdrive[\\\\/])?([A-Za-z])([\\\\/].*)?"); //$NON-NLS-1$

	/**
	 * A path announced by the bash of Git for Windows, MSYS2 or Cygwin, as Windows names it:
	 * /c/Users/me and /cygdrive/c/Users/me are C:\Users\me, and /usr is in the installation of the
	 * shell.
	 *
	 * @return the path, unchanged when it is not such a path
	 */
	static String windowsPath(String path, String commandLine) {
		Matcher drive = MSYS_DRIVE.matcher(path);
		if (drive.matches()) {
			String rest = drive.group(2) == null ? "\\" : drive.group(2).replace('/', '\\'); //$NON-NLS-1$
			return Character.toUpperCase(drive.group(1).charAt(0)) + ":" + rest; //$NON-NLS-1$
		}
		if (path.matches("[\\\\/][^\\\\/].*")) { //$NON-NLS-1$
			String root = msysRoot(commandLine);
			if (root != null) {
				return root + path.replace('/', '\\');
			}
		}
		return path;
	}

	/** The installation of a bash of Windows: C:\Program Files\Git for its usr\bin\bash.exe. */
	private static String msysRoot(String commandLine) {
		String[] arguments = ShellProfiles.parse(commandLine == null ? "" : commandLine); //$NON-NLS-1$
		// Not File: the paths of Windows, on any system.
		String[] parts = arguments.length == 0 ? new String[0] : arguments[0].split("[\\\\/]"); //$NON-NLS-1$
		int bin = parts.length - 2;
		if (bin < 1 || !parts[bin].equalsIgnoreCase("bin")) { //$NON-NLS-1$
			return null;
		}
		int end = bin > 1 && parts[bin - 1].equalsIgnoreCase("usr") ? bin - 1 : bin; //$NON-NLS-1$
		return String.join("\\", java.util.Arrays.copyOf(parts, end)); //$NON-NLS-1$
	}

	/**
	 * @return {@code true} if the title is the path of the program the terminal runs: the pseudo
	 *         console of Windows names its window so, which the tab already says better
	 */
	static boolean isProgramPath(String title, String commandLine) {
		String[] arguments = ShellProfiles.parse(commandLine == null ? "" : commandLine); //$NON-NLS-1$
		// The title of an elevated program starts with "Administrator: ", in the language of Windows.
		String path = title.strip().replaceFirst("^[^\\\\/:]{1,40}?\\s?:\\s+(?=([A-Za-z]:)?[\\\\/])", ""); //$NON-NLS-1$ //$NON-NLS-2$
		if (arguments.length == 0 || !path.matches("([A-Za-z]:)?[\\\\/].*")) { //$NON-NLS-1$
			return false;
		}
		String program = fileName(arguments[0]);
		String name = fileName(path);
		return name.equalsIgnoreCase(program) || name.equalsIgnoreCase(program + ".exe"); //$NON-NLS-1$
	}

	private static String fileName(String path) {
		return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
	}

	/**
	 * The files dropped on the terminal: the {@code file:} URLs of the drag data when the browser
	 * gives them, otherwise the resources dragged from an Eclipse view such as the Project Explorer.
	 *
	 * @param uriList
	 *            the {@code text/uri-list} of the drop, may be {@code null}
	 */
	static List<String> droppedPaths(String uriList) {
		List<String> paths = new ArrayList<>();
		if (uriList != null) {
			for (String line : uriList.split("\\r?\\n")) { //$NON-NLS-1$
				line = line.trim();
				if (line.startsWith("file:")) { //$NON-NLS-1$
					File file = parseFileUri(line);
					if (file != null) {
						paths.add(file.getPath());
					}
				}
			}
		}
		if (paths.isEmpty() && LocalSelectionTransfer.getTransfer()
				.getSelection() instanceof IStructuredSelection selection) {
			for (Object element : selection) {
				File file = fileOf(element);
				if (file != null) {
					paths.add(file.getPath());
				}
			}
		}
		return paths;
	}

	private static File parseFileUri(String uri) {
		try {
			// file:/C:/Users/me, file:///home/me, file://server/share
			URI parsed = new URI(uri);
			if (parsed.getAuthority() != null && !parsed.getAuthority().isEmpty()
					&& !"localhost".equalsIgnoreCase(parsed.getAuthority())) { //$NON-NLS-1$
				return new File("//" + parsed.getAuthority() + parsed.getPath()); //$NON-NLS-1$
			}
			String path = parsed.getPath();
			if (path == null || path.isEmpty()) {
				return null;
			}
			return new File(path.matches("/[A-Za-z]:.*") ? path.substring(1) : path); //$NON-NLS-1$
		} catch (URISyntaxException e) {
			return null;
		}
	}

	private static File fileOf(Object element) {
		if (element instanceof File file) {
			return file;
		}
		try {
			return WorkspaceLocations.file(element);
		} catch (LinkageError e) {
			// org.eclipse.core.resources is optional.
			return null;
		}
	}

	/**
	 * The paths as the shell expects them on its command line: quoted when needed, separated and
	 * followed by a space so that the user can go on typing.
	 */
	static String quotePaths(List<String> paths, String shellCommandLine) {
		String shell = ShellProfiles.displayName(shellCommandLine == null ? "" : shellCommandLine).toLowerCase(); //$NON-NLS-1$
		StringBuilder result = new StringBuilder();
		for (String path : paths) {
			result.append(quotePath(path, shell)).append(' ');
		}
		return result.toString();
	}

	private static final Pattern WINDOWS_DRIVE = Pattern.compile("([A-Za-z]):[\\\\/](.*)"); //$NON-NLS-1$
	/** \\wsl$\Ubuntu\... or \\wsl.localhost\Ubuntu\...: the files of a WSL distribution. */
	private static final Pattern WSL_SHARE = Pattern.compile("(?i)[\\\\/]{2}wsl(?:\\$|\\.localhost)[\\\\/][^\\\\/]+(.*)"); //$NON-NLS-1$

	/**
	 * A Windows path as seen from WSL: C:\Users\me is /mnt/c/Users/me, and a file of the Linux
	 * system itself, \\wsl$\Ubuntu\home\me (or \\wsl.localhost\...), is /home/me.
	 */
	static String wslPath(String path) {
		Matcher drive = WINDOWS_DRIVE.matcher(path);
		if (drive.matches()) {
			return "/mnt/" + Character.toLowerCase(drive.group(1).charAt(0)) + '/' + drive.group(2).replace('\\', '/'); //$NON-NLS-1$
		}
		Matcher linux = WSL_SHARE.matcher(path);
		if (linux.matches()) {
			String rest = linux.group(1).replace('\\', '/');
			return rest.isEmpty() ? "/" : rest; //$NON-NLS-1$
		}
		return path.replace('\\', '/');
	}

	private static String quotePath(String path, String shell) {
		switch (shell) {
		case "cmd": //$NON-NLS-1$
			return path.matches("[^\\s&()\\[\\]{}^=;!'+,`~%]*") ? path : '"' + path + '"'; //$NON-NLS-1$
		case "powershell", "pwsh": //$NON-NLS-1$ //$NON-NLS-2$
			return path.matches("[\\w\\\\/:.\\-]*") ? path : "'" + path.replace("'", "''") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		case "wsl": //$NON-NLS-1$
			return quotePath(wslPath(path), "bash"); //$NON-NLS-1$
		default:
			// Git Bash and Cygwin understand C:/Users/me, not C:\Users\me.
			if (IS_WINDOWS || path.matches("[A-Za-z]:\\\\.*")) { //$NON-NLS-1$
				path = path.replace('\\', '/');
			}
			return path.matches("[\\w/:.,@%+=\\-]*") ? path : "'" + path.replace("'", "'\\''") + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
		}
	}

	private void reveal() {
		if (browser.isDisposed() || browser.getVisible()) {
			return;
		}
		browser.setVisible(true);
		if (getSite().getPage().getActivePart() == this) {
			setFocus();
		}
	}

	private IWorkbench workbench() {
		return getSite().getWorkbenchWindow().getWorkbench();
	}

	private String config() {
		FontData font = JFaceResources.getTextFont().getFontData()[0];
		// The font of the terminal, or else the text font of Eclipse.
		String chosenFamily = XtermPlugin.preference(XtermPlugin.PREF_FONT_FAMILY).trim();
		String family = cssFontFamily(chosenFamily.isEmpty() ? font.getName() : chosenFamily);
		// Font heights are in points, CSS wants pixels (96 dpi reference).
		int chosenSize = Math.min(MAX_FONT_SIZE, XtermPlugin.preferences().getInt(XtermPlugin.PREF_FONT_SIZE));
		int size = Math.max(8, Math.round((chosenSize > 0 ? chosenSize : font.getHeight()) * 96f / 72f));
		// Use the colors the Eclipse theme gave to the view, so that the terminal looks like its
		// neighbours whatever the theme is.
		RGB background = browser.getParent().getBackground().getRGB();
		RGB foreground = browser.getParent().getForeground().getRGB();
		boolean dark = luminance(background) < 0.5;
		if (!dark) {
			// Light themes leave the view grey, text areas such as the Console are drawn on this one.
			background = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
			foreground = display.getSystemColor(SWT.COLOR_LIST_FOREGROUND).getRGB();
			dark = luminance(background) < 0.5;
		}
		String cursorStyle = XtermPlugin.preference(XtermPlugin.PREF_CURSOR_STYLE);
		if (!CURSOR_STYLES.contains(cursorStyle)) {
			cursorStyle = "block"; //$NON-NLS-1$
		}
		int scrollback = Math.max(0, Math.min(MAX_SCROLLBACK, XtermPlugin.preferences().getInt(XtermPlugin.PREF_SCROLLBACK)));
		StringBuilder json = new StringBuilder("{\"fontFamily\":").append(jsonString(family)) //$NON-NLS-1$
				.append(",\"fontSize\":").append(size).append(",\"dark\":").append(dark) //$NON-NLS-1$ //$NON-NLS-2$
				.append(",\"scrollback\":").append(scrollback) //$NON-NLS-1$
				.append(",\"cursorStyle\":\"").append(cursorStyle).append('"') //$NON-NLS-1$
				.append(",\"cursorBlink\":").append(XtermPlugin.isEnabled(XtermPlugin.PREF_CURSOR_BLINK)) //$NON-NLS-1$
				.append(",\"macOptionIsMeta\":").append(XtermPlugin.isEnabled(XtermPlugin.PREF_MAC_OPTION_IS_META)) //$NON-NLS-1$
				.append(",\"os\":\"").append(IS_WINDOWS ? "windows" : IS_MAC ? "mac" : "linux").append('"') //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				.append(",\"background\":\"").append(hex(background)).append('"') //$NON-NLS-1$
				.append(",\"warnMultiLinePaste\":").append(XtermPlugin.isEnabled(XtermPlugin.PREF_WARN_MULTI_LINE_PASTE)) //$NON-NLS-1$
				.append(",\"shortcuts\":").append(EclipseShortcuts.toJson( //$NON-NLS-1$
						EclipseShortcuts.parse(XtermPlugin.preference(XtermPlugin.PREF_ECLIPSE_SHORTCUTS))));
		// A theme may leave the foreground unstyled: only use it when it is readable.
		if (Math.abs(luminance(foreground) - luminance(background)) > 0.4) {
			json.append(",\"foreground\":\"").append(hex(foreground)).append('"'); //$NON-NLS-1$
		}
		return json.append('}').toString();
	}

	static final java.util.Set<String> CURSOR_STYLES = java.util.Set.of("block", "underline", "bar"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	static final int MAX_SCROLLBACK = 1_000_000;
	static final int MAX_FONT_SIZE = 72;

	/** The CSS font list for a font name, with fallbacks; the name keeps only what a font name holds. */
	static String cssFontFamily(String name) {
		String clean = name.replaceAll("[^\\p{L}\\p{N} ._+-]", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
		return (clean.isEmpty() ? "" : "'" + clean + "', ") + "'DejaVu Sans Mono', monospace"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
	}

	/** A JSON (and JavaScript) string literal for the text. */
	static String jsonString(String text) {
		StringBuilder result = new StringBuilder("\""); //$NON-NLS-1$
		for (char c : text.toCharArray()) {
			if (c == '"' || c == '\\') {
				result.append('\\').append(c);
			} else if (c < 0x20 || c == 0x2028 || c == 0x2029 || c == '<' || c == '>') {
				result.append(String.format("\\u%04x", (int) c)); //$NON-NLS-1$
			} else {
				result.append(c);
			}
		}
		return result.append('"').toString();
	}

	private static double luminance(RGB rgb) {
		return (0.2126 * rgb.red + 0.7152 * rgb.green + 0.0722 * rgb.blue) / 255;
	}

	private static String hex(RGB rgb) {
		return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue); //$NON-NLS-1$
	}

	/**
	 * Pushes the current Eclipse colors, font and settings to the terminal, after a theme, font or
	 * preference change.
	 */
	private void applyTheme() {
		if (browser != null && !browser.isDisposed()) {
			browser.execute("window.xtermSetTheme && xtermSetTheme(" + config() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
		}
	}

	private void updateSize(Object[] args) {
		int newCols = ((Number) args[0]).intValue();
		int newRows = ((Number) args[1]).intValue();
		if (newCols > 0 && newRows > 0) {
			cols = newCols;
			rows = newRows;
		}
	}

	/** Cursor movements (CUU, CUD, CUF, CUB) at the end of a screen, before its last colors. */
	private static final Pattern TRAILING_CURSOR_MOVES = Pattern
			.compile("(?:\u001b\\[\\d*[ABCD])+((?:\u001b\\[[0-9;]*m)*)$"); //$NON-NLS-1$

	/**
	 * A saved screen ends by putting the cursor back where the program left it, often above its last
	 * lines (a prompt in a full screen program). Shown again, it must leave the cursor after the last
	 * line instead: what follows (the restore notice, the new shell) would otherwise overwrite it.
	 */
	static String endOfContent(String screen) {
		return TRAILING_CURSOR_MOVES.matcher(screen).replaceFirst("$1"); //$NON-NLS-1$
	}

	private void startSession() {
		if (session != null) {
			session.dispose();
		}
		synchronized (pending) {
			pending.reset();
			pending.notifyAll();
		}
		if (restoredContent != null) {
			append(null, endOfContent(new String(restoredContent, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
			append(null, "\r\n\u001b[2m[History restored]\u001b[0m\r\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
			keepAboveTheNewShell();
			restoredContent = null;
		}
		sessionStart = System.currentTimeMillis();
		startNewSession();
	}

	private void startNewSession() {
		boolean offer = openedByUser;
		openedByUser = false;
		if (offer && ClaudeSessions.runsClaude(commandLine) && offerEclipseMcp()) {
			return;
		}
		startProcess();
	}

	/** Answers of {@link #askAddEclipseMcp}. */
	static final int MCP_ADD = 0;
	static final int MCP_NOT_NOW = 1;
	static final int MCP_NEVER = 2;

	/**
	 * When the MCP server of Eclipse runs and Claude Code does not know it yet, asks the user whether
	 * to add it, before Claude Code starts so that it uses it at once.
	 *
	 * @return {@code true} if Claude Code starts later, once the user has answered
	 */
	private boolean offerEclipseMcp() {
		if (mcpPostponed || !XtermPlugin.isEnabled(XtermPlugin.PREF_OFFER_ECLIPSE_MCP)) {
			return false;
		}
		EclipseMcp.Endpoint endpoint = EclipseMcp.running();
		if (endpoint == null || EclipseMcp.configured(endpoint, workingDirectory)) {
			return false;
		}
		String[] claude = ShellProfiles.parse(commandLine);
		String[] add = EclipseMcp.mcpCommand(claude, EclipseMcp.addArguments(endpoint));
		String[] remove = EclipseMcp.mcpCommand(claude, "remove", "--scope", "user", EclipseMcp.NAME); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		if (add == null || remove == null) {
			return false;
		}
		// A dialog runs the event loop: not from within the call of the page.
		display.asyncExec(() -> {
			if (browser.isDisposed()) {
				return;
			}
			int answer = askAddEclipseMcp(endpoint.url());
			if (answer == MCP_ADD) {
				addEclipseMcp(add, remove);
				return;
			}
			if (answer == MCP_NEVER) {
				XtermPlugin.preferences().setValue(XtermPlugin.PREF_OFFER_ECLIPSE_MCP, false);
			} else {
				mcpPostponed = true;
			}
			startProcessAndSendPendingInput();
		});
		return true;
	}

	/** @return {@link #MCP_ADD}, {@link #MCP_NOT_NOW} or {@link #MCP_NEVER} */
	int askAddEclipseMcp(String url) {
		MessageDialog dialog = new MessageDialog(getSite().getShell(), "Eclipse MCP Server", null, //$NON-NLS-1$
				"The MCP server of Eclipse is running (" + url + ").\n\nAdd it to Claude Code, so that Claude can use " //$NON-NLS-1$ //$NON-NLS-2$
						+ "this Eclipse: its projects, problems, launches...?\n\nClaude Code keeps it for your user (claude mcp add " //$NON-NLS-1$
						+ "--scope user " + EclipseMcp.NAME + "), with the token of the server.", //$NON-NLS-1$ //$NON-NLS-2$
				MessageDialog.QUESTION, new String[] {"&Add", "&Not Now", "Ne&ver Ask"}, 0); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		int answer = dialog.open();
		return answer == MCP_ADD || answer == MCP_NEVER ? answer : MCP_NOT_NOW;
	}

	/** Adds the server with {@code claude mcp}, then starts Claude Code. */
	private void addEclipseMcp(String[] add, String[] remove) {
		append(null, "\u001b[2m[Adding the MCP server of Eclipse to Claude Code...]\u001b[0m\r\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		File directory = workingDirectory;
		String[] environment = PtySession.environment(add);
		Thread worker = new Thread(() -> {
			String failure = runClaude(add, directory, environment);
			if (failure != null && failure.contains("already exists")) { //$NON-NLS-1$
				// A server of this name from an earlier run, on another port: replaced.
				runClaude(remove, directory, environment);
				failure = runClaude(add, directory, environment);
			}
			String message = failure == null ? "\u001b[2m[Claude Code can now use Eclipse.]\u001b[0m\r\n" //$NON-NLS-1$
					: "\u001b[31m[Could not add the MCP server of Eclipse: " + oneLine(failure).strip() + "]\u001b[0m\r\n"; //$NON-NLS-1$ //$NON-NLS-2$
			if (failure != null) {
				XtermPlugin.log("Could not add the MCP server of Eclipse to Claude Code: " + failure, null); //$NON-NLS-1$
			}
			display.asyncExec(() -> {
				if (!browser.isDisposed()) {
					append(null, message.getBytes(StandardCharsets.UTF_8));
					keepAboveTheNewShell();
					startProcessAndSendPendingInput();
				}
			});
		}, "Xterm MCP of Claude Code"); //$NON-NLS-1$
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * Runs a command of Claude Code to its end, at most a minute.
	 *
	 * @return {@code null} if it succeeded, else what it printed
	 */
	static String runClaude(String[] command, File directory, String[] environment) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
			if (directory != null && directory.isDirectory()) {
				builder.directory(directory);
			}
			builder.environment().clear();
			for (String variable : environment) {
				int equals = variable.indexOf('=', 1);
				if (equals > 0) {
					builder.environment().put(variable.substring(0, equals), variable.substring(equals + 1));
				}
			}
			Process process = builder.start();
			process.getOutputStream().close();
			java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
			Thread reader = new Thread(() -> {
				try {
					process.getInputStream().transferTo(output);
				} catch (IOException e) {
					// The process is gone.
				}
			});
			reader.setDaemon(true);
			reader.start();
			if (!process.waitFor(60, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return "no answer after a minute"; //$NON-NLS-1$
			}
			reader.join(1000);
			String printed = output.toString(StandardCharsets.UTF_8);
			return process.exitValue() == 0 ? null : printed.isBlank() ? "exit code " + process.exitValue() : printed; //$NON-NLS-1$
		} catch (IOException e) {
			return String.valueOf(e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "interrupted"; //$NON-NLS-1$
		}
	}

	private void startProcessAndSendPendingInput() {
		startProcess();
		if (pendingInput != null && session != null) {
			String text = pendingInput;
			pendingInput = null;
			send(text);
		}
	}

	private void startProcess() {
		String[] arguments = ShellProfiles.parse(commandLine);
		if (ClaudeSessions.runsClaude(commandLine)) {
			if (claudeSession == null) {
				claudeSession = UUID.randomUUID().toString();
			}
			// Resumed only if there is a conversation: Claude Code keeps none before the first message.
			arguments = ClaudeSessions.withSession(arguments, claudeSession,
					resumeClaude && ClaudeSessions.exists(claudeSession));
			resumeClaude = false;
		}
		try {
			session = new PtySession(arguments, workingDirectory, cols, rows, this);
		} catch (IOException | RuntimeException | LinkageError e) {
			session = null;
			ended = true;
			XtermPlugin.log("Could not start the shell", e); //$NON-NLS-1$
			append(null, ("\r\n\u001b[31mCould not start the shell: " + e //$NON-NLS-1$
					+ "\u001b[0m\r\n\u001b[2m[Press Enter to try again.]\u001b[0m\r\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		}
	}

	private void input(String data, boolean binary) {
		if (data == null || data.isEmpty()) {
			return;
		}
		// Focus reports are sent by the terminal itself when the user switches to another view: they
		// are not typing, and must not hide that the program is still working.
		if (!data.equals(FOCUS_IN) && !data.equals(FOCUS_OUT)) {
			clearDone();
			synchronized (outputActivity) {
				lastInputMillis = System.currentTimeMillis();
			}
		}
		byte[] bytes = data.getBytes(binary ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
		if (session == null || !session.isAlive()) {
			if (ended && data.indexOf('\r') >= 0) {
				restart();
			}
			return;
		}
		session.write(bytes);
	}

	@Override
	public void output(PtySession source, byte[] data, int length) {
		synchronized (outputActivity) {
			long now = System.currentTimeMillis();
			if (now - lastInputMillis < ECHO_MILLIS) {
				workStartMillis = 0;
			} else if (workStartMillis == 0) {
				workStartMillis = now;
			}
			lastOutputMillis = now;
		}
		synchronized (pending) {
			// Back pressure: do not read faster than the browser can render.
			while (pending.size() > MAX_PENDING_BYTES && source.isAlive()) {
				try {
					pending.wait(1000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
		byte[] copy = new byte[length];
		System.arraycopy(data, 0, copy, 0, length);
		append(source, copy);
	}

	@Override
	public void exited(PtySession source, int exitCode) {
		// A program that ends does not report progress any more.
		if (!display.isDisposed()) {
			display.asyncExec(() -> showProgress(0));
		}
		if (keepsViewOpen(exitCode, System.currentTimeMillis() - sessionStart, commandLine)) {
			ended = true;
			append(null, ("\r\n\u001b[2m[Process exited with code " + exitCode //$NON-NLS-1$
					+ ". Press Enter to restart it.]\u001b[0m\r\n").getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		} else if (!display.isDisposed()) {
			display.asyncExec(() -> {
				if (!browser.isDisposed() && source == session && !workbench().isClosing()) {
					getSite().getPage().hideView(this);
				}
			});
		}
	}

	/**
	 * Whether the view stays open when its shell or program ends, so that its last output can be read
	 * and it can be started again: when it fails at once (it could not start properly), or when a
	 * program of its own (Claude Code...) fails. A shell that the user leaves, even with the error code
	 * of its last command ({@code false; exit}), closes the view like in VS Code.
	 */
	static boolean keepsViewOpen(int exitCode, long millisRunning, String commandLine) {
		return exitCode != 0 && (millisRunning < FAILED_START_MILLIS || !ShellProfiles.isShell(commandLine));
	}

	/**
	 * Starts the shell or program of the view again, in the directory it was in, after asking if a
	 * command still runs.
	 */
	void restart() {
		if (browser == null || browser.isDisposed()) {
			return;
		}
		PtySession current = session;
		if (current != null && current.isAlive() && current.isBusy() && !confirmRestart()) {
			return;
		}
		File directory = currentDirectory();
		if (directory != null && directory.isDirectory()) {
			workingDirectory = directory;
		}
		// Claude Code that ended with an error goes on with its conversation; restarted by the user,
		// it starts a new one.
		if (ended) {
			resumeClaude = true;
		} else {
			claudeSession = null;
		}
		append(null, "\r\n\u001b[2m[Restarted]\u001b[0m\r\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		keepAboveTheNewShell();
		ended = false;
		lastBusy = false;
		busySince = 0;
		longRunning = false;
		showProgress(0);
		sessionStart = System.currentTimeMillis();
		if (current != null) {
			current.dispose();
		}
		session = null;
		startNewSession();
		firePropertyChange(IWorkbenchPartConstants.PROP_DIRTY);
		setActivity(Activity.IDLE);
	}

	/**
	 * Windows: the pseudo console clears the screen when the shell starts. What the screen shows
	 * (the restored history, the end of the previous shell) goes up into the scrollback first,
	 * where the user finds it.
	 */
	private void keepAboveTheNewShell() {
		if (IS_WINDOWS) {
			append(null, "\r\n".repeat(Math.max(rows, 1)).getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
		}
	}

	/** Whether the tab shows that a program reports progress (OSC 9;4). */
	private boolean progressShown;

	/**
	 * Shows the tab as busy while a program reports progress, as Windows Terminal shows it on its tab.
	 *
	 * @param state
	 *            of OSC 9;4: 1 progress, 3 indeterminate, 4 paused show it; 0 (done) and 2 (error)
	 *            end it
	 */
	void showProgress(int state) {
		boolean busy = state == 1 || state == 3 || state == 4;
		if (busy == progressShown || browser == null || browser.isDisposed()) {
			return;
		}
		IWorkbenchSiteProgressService progress = getSite().getService(IWorkbenchSiteProgressService.class);
		if (progress == null) {
			return;
		}
		progressShown = busy;
		if (busy) {
			progress.incrementBusy();
		} else {
			progress.decrementBusy();
		}
	}

	/** Answers of {@link #askPaste}. */
	static final int PASTE = 0;
	static final int PASTE_AS_ONE_LINE = 1;
	static final int CANCEL_PASTE = 2;

	/**
	 * Pastes a text of several lines once the user has confirmed it: the shell, without bracketed
	 * paste, runs each line as soon as it gets it.
	 */
	private void confirmPaste(String text) {
		if (browser.isDisposed()) {
			return;
		}
		int choice = askPaste(text);
		if (choice == PASTE || choice == PASTE_AS_ONE_LINE) {
			String pasted = choice == PASTE_AS_ONE_LINE ? oneLine(text) : text;
			browser.execute("xtermPasteConfirmed('" //$NON-NLS-1$
					+ Base64.getEncoder().encodeToString(pasted.getBytes(StandardCharsets.UTF_8)) + "')"); //$NON-NLS-1$
		}
		setFocus();
	}

	/** The lines of the text joined by spaces, without the line break at its end. */
	static String oneLine(String text) {
		return text.replaceAll("(\\r\\n|\\r|\\n)+$", "").replaceAll("\\r\\n|\\r|\\n", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/**
	 * Asks the user how to paste a text of several lines.
	 *
	 * @return {@link #PASTE}, {@link #PASTE_AS_ONE_LINE} or {@link #CANCEL_PASTE}
	 */
	int askPaste(String text) {
		long lines = text.lines().count();
		MessageDialog dialog = new MessageDialog(getSite().getShell(), "Paste " + lines + " Lines", null, //$NON-NLS-1$ //$NON-NLS-2$
				"The text has " + lines + " lines. The shell will run each of them as soon as it is pasted.\n\n" //$NON-NLS-1$ //$NON-NLS-2$
						+ "(This warning can be turned off in Preferences > Xterm Terminal.)", //$NON-NLS-1$
				MessageDialog.WARNING, CANCEL_PASTE, "&Paste", "Paste as &One Line", "&Cancel"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		int choice = dialog.open();
		return choice < 0 ? CANCEL_PASTE : choice;
	}

	/** Asks the user whether to restart the terminal and terminate the command that runs in it. */
	boolean confirmRestart() {
		return MessageDialog.openQuestion(getSite().getShell(), "Restart Terminal", //$NON-NLS-1$
				"A command is still running in '" + getPartName() //$NON-NLS-1$
						+ "'. Restarting the terminal will terminate it.\n\nRestart anyway?"); //$NON-NLS-1$
	}

	/**
	 * Queues bytes for the browser. Can be called from any thread.
	 *
	 * @param source
	 *            the session that produced the data, or {@code null} for messages from the view
	 */
	private void append(PtySession source, byte[] data) {
		// Drop late output of a session that is being disposed.
		if (source != null && !source.isAlive()) {
			return;
		}
		synchronized (pending) {
			pending.write(data, 0, data.length);
			if (flushScheduled) {
				return;
			}
			flushScheduled = true;
		}
		if (display.isDisposed()) {
			return;
		}
		display.asyncExec(this::flush);
	}

	private void flush() {
		byte[] data;
		synchronized (pending) {
			flushScheduled = false;
			data = pending.toByteArray();
			pending.reset();
			pending.notifyAll();
		}
		if (browser.isDisposed() || data.length == 0) {
			return;
		}
		browser.execute("xtermWrite('" + Base64.getEncoder().encodeToString(data) + "')"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * While the terminal is active, Eclipse key bindings must not swallow keys such as Ctrl+E or
	 * Ctrl+Shift+V: they belong to the shell.
	 */
	private void setKeyFilterEnabled(boolean enabled) {
		if (keyFilterDisabled == !enabled) {
			return;
		}
		IBindingService bindings = workbench().getService(IBindingService.class);
		if (bindings != null) {
			bindings.setKeyFilterEnabled(enabled);
			keyFilterDisabled = !enabled;
		}
	}

	/**
	 * Where a new terminal starts: the project of the selected resource, or else of the file of the
	 * active editor (a file outside of the workspace: its folder), or else the home directory.
	 */
	private File resolveWorkingDirectory() {
		IWorkbenchPage page = getSite().getPage();
		ISelection selection = page.getSelection();
		List<Object> candidates = new ArrayList<>();
		if (selection instanceof IStructuredSelection structured && !structured.isEmpty()) {
			candidates.add(structured.getFirstElement());
		}
		IEditorPart editor = page.getActiveEditor();
		if (editor != null && editor.getEditorInput() != null) {
			candidates.add(editor.getEditorInput());
		}
		for (Object candidate : candidates) {
			File directory = null;
			try {
				directory = WorkspaceLocations.projectDirectory(candidate);
			} catch (LinkageError e) {
				// org.eclipse.core.resources is optional.
			}
			File file = directory == null ? uriFile(candidate) : null;
			if (file != null) {
				directory = file.getParentFile();
			}
			if (directory != null && directory.isDirectory()) {
				return directory;
			}
		}
		return new File(System.getProperty("user.home")); //$NON-NLS-1$
	}

	/** Isolated so that the view still loads when org.eclipse.core.resources is absent. */
	private static final class WorkspaceLocations {
		static File projectDirectory(Object element) {
			IResource resource = Adapters.adapt(element, IResource.class);
			if (resource == null) {
				return null;
			}
			IPath location = resource.getProject().getLocation();
			return location == null ? null : location.toFile();
		}

		static File file(Object element) {
			IResource resource = Adapters.adapt(element, IResource.class);
			IPath location = resource == null ? null : resource.getLocation();
			return location == null ? null : location.toFile();
		}

		static File directory(Object element) {
			IResource resource = Adapters.adapt(element, IResource.class);
			if (resource == null) {
				return null;
			}
			IPath location = (resource instanceof IContainer ? resource : resource.getParent()).getLocation();
			return location == null ? null : location.toFile();
		}
	}

	@Override
	public void setFocus() {
		if (browser != null && !browser.isDisposed()) {
			// The page gives the keyboard to the terminal when it receives the focus: running a script
			// here would block the UI until the browser answers.
			browser.setFocus();
		}
	}

	@Override
	public void dispose() {
		showProgress(0);
		getSite().getPage().removePartListener(partListener);
		if (lastActive == this) {
			lastActive = null;
		}
		workbench().getThemeManager().removePropertyChangeListener(themeListener);
		JFaceResources.getFontRegistry().removeListener(themeListener);
		XtermPlugin.preferences().removePropertyChangeListener(themeListener);
		setKeyFilterEnabled(true);
		if (session != null) {
			session.dispose();
			session = null;
		}
		if (clipboard != null) {
			clipboard.dispose();
		}
		if (activityPoller != null) {
			activityPoller.shutdownNow();
		}
		for (Image image : activityImages) {
			if (image != null) {
				image.dispose();
			}
		}
		if (!workbench().isClosing()) {
			// The view was closed by the user: there is nothing to restore next time.
			try {
				Files.deleteIfExists(contentFile());
			} catch (IOException | RuntimeException e) {
				// A stale file is harmless, it is only read when Eclipse restores the view.
			}
		}
		super.dispose();
	}
}
