package org.eclipse.xterm4eclipse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.dnd.Clipboard;
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
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.keys.IBindingService;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.progress.IWorkbenchSiteProgressService;

/**
 * A terminal view rendered by xterm.js (the terminal emulator used by VS Code) inside an SWT
 * browser, connected to a local shell through a pseudo terminal.
 */
public class XtermView extends ViewPart implements PtySession.Listener {

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
	/** Output must flow at least this long to count as work whose end is worth signalling. */
	private static final long WORK_MILLIS = 2000;
	/** Silence after which the work of a foreground program is considered finished. */
	private static final long QUIET_MILLIS = 1500;
	private static final String MEMENTO_SHELL = "shell"; //$NON-NLS-1$
	private static final String MEMENTO_DIRECTORY = "directory"; //$NON-NLS-1$

	/** How long the browser may stay hidden while its page loads. */
	static int revealTimeoutMillis = 1500;

	/** Shell requested by the "New Terminal" menu for the view that is about to be created. */
	private static String nextCommandLine;
	/** Directory requested by "Show in Xterm" for the view that is about to be created. */
	private static File nextDirectory;

	Browser browser;
	private Display display;
	private Clipboard clipboard;
	private volatile PtySession session;

	/** What the tab icon tells about the shell. */
	private enum Activity {
		/** The shell waits at its prompt. */
		IDLE("icons/xterm.png"), //$NON-NLS-1$
		/** A command runs in the foreground. */
		RUNNING("icons/xterm-running.png"), //$NON-NLS-1$
		/** A command has finished, or a program asked for attention, and the user has not looked yet. */
		DONE("icons/xterm-done.png"); //$NON-NLS-1$

		final String icon;

		Activity(String icon) {
			this.icon = icon;
		}
	}

	private final Image[] activityImages = new Image[Activity.values().length];
	private Activity activity = Activity.IDLE;
	private ScheduledExecutorService activityPoller;
	private volatile boolean lastBusy;

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
	private long sessionStart;
	/** Screen content saved before the last Eclipse shutdown, replayed once when the view opens. */
	private byte[] restoredContent;
	private File workingDirectory;
	/** Directory announced by the shell itself through an escape sequence, if it does so. */
	private File reportedDirectory;
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
				setKeyFilterEnabled(false);
				clearDone();
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
		if (nextCommandLine != null) {
			commandLine = nextCommandLine;
			nextCommandLine = null;
			workingDirectory = nextDirectory;
			nextDirectory = null;
		} else if (memento != null && memento.getString(MEMENTO_SHELL) != null) {
			commandLine = memento.getString(MEMENTO_SHELL);
		} else {
			commandLine = ShellProfiles.defaultCommandLine();
		}
		if (memento != null) {
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
		setPartName(ShellProfiles.displayName(commandLine));
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
		// The java* functions only exist in the page once it is loaded, so the page waits for us.
		browser.addProgressListener(
				ProgressListener.completedAdapter(event -> browser.execute("xtermInit(" + config() + ")"))); //$NON-NLS-1$ //$NON-NLS-2$
		try {
			// One file per plug-in version, shared by all the terminals.
			Path page = WebPage.materialize(
					XtermPlugin.stateDirectory().resolve("terminal-" + XtermPlugin.version() + ".html"), //$NON-NLS-1$ //$NON-NLS-2$
					name -> XtermPlugin.resource("web/" + name)); //$NON-NLS-1$
			browser.setUrl(page.toUri().toString());
		} catch (IOException e) {
			XtermPlugin.log("Could not load the xterm.js resources", e); //$NON-NLS-1$
			browser.setText("<pre>Could not load the xterm.js resources: " + e + "</pre>"); //$NON-NLS-1$ //$NON-NLS-2$
		}

		createActions();
		for (Activity value : Activity.values()) {
			activityImages[value.ordinal()] = ImageDescriptor.createFromFile(XtermView.class, '/' + value.icon)
					.createImage();
		}
		activityPoller = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "Xterm activity poller"); //$NON-NLS-1$
			thread.setDaemon(true);
			return thread;
		});
		activityPoller.scheduleWithFixedDelay(this::pollActivity, 1000, 400, TimeUnit.MILLISECONDS);
		getSite().getPage().addPartListener(partListener);
		workbench().getThemeManager().addPropertyChangeListener(themeListener);
		JFaceResources.getFontRegistry().addListener(themeListener);
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
		function("javaAttention", args -> { //$NON-NLS-1$
			signalDone();
			return null;
		});
		function("javaDirectory", args -> { //$NON-NLS-1$
			File directory = parseDirectory((String) args[0]);
			if (directory != null && directory.isDirectory()) {
				reportedDirectory = directory;
			}
			return null;
		});
		function("javaOpenLink", args -> { //$NON-NLS-1$
			Program.launch((String) args[0]);
			return null;
		});
		function("javaTitle", args -> { //$NON-NLS-1$
			String title = (String) args[0];
			if (title != null && !title.isBlank()) {
				setTitleToolTip(title);
				setPartName(title.length() > MAX_TITLE_LENGTH
						? "…" + title.substring(title.length() - MAX_TITLE_LENGTH) //$NON-NLS-1$
						: title);
			}
			return null;
		});
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
		newTerminal.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_OBJ_ADD));
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
		nextCommandLine = shellCommandLine;
		nextDirectory = directory != null && directory.isDirectory() ? directory : null;
		try {
			// The first terminal is the plain view, the next ones are numbered copies of it.
			String secondaryId = page.findViewReference(ID) == null ? null : "t" + System.currentTimeMillis(); //$NON-NLS-1$
			page.showView(ID, secondaryId, IWorkbenchPage.VIEW_ACTIVATE);
		} finally {
			nextCommandLine = null;
			nextDirectory = null;
		}
	}

	/**
	 * @return the directory of a selected resource (the folder holding it for a file), or
	 *         {@code null} if the element is not a resource
	 */
	static File directoryOf(Object element) {
		try {
			return WorkspaceLocations.directory(element);
		} catch (LinkageError e) {
			// org.eclipse.core.resources is optional.
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
			if (busy != lastBusy) {
				lastBusy = busy;
				display.asyncExec(() -> {
					if (busy) {
						setActivity(Activity.RUNNING);
					} else if (activity == Activity.RUNNING) {
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

	/** The user has seen the mark: back to the icon that tells what the shell is doing. */
	private void clearDone() {
		if (activity == Activity.DONE) {
			setActivity(lastBusy ? Activity.RUNNING : Activity.IDLE);
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
		String family = "'" + font.getName().replace("\\", "").replace("'", "") + "', 'DejaVu Sans Mono', monospace"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
		// Font heights are in points, CSS wants pixels (96 dpi reference).
		int size = Math.max(8, Math.round(font.getHeight() * 96f / 72f));
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
		StringBuilder json = new StringBuilder("{\"fontFamily\":\"").append(family.replace("\"", "")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				.append("\",\"fontSize\":").append(size).append(",\"dark\":").append(dark) //$NON-NLS-1$ //$NON-NLS-2$
				.append(",\"os\":\"").append(IS_WINDOWS ? "windows" : IS_MAC ? "mac" : "linux").append('"') //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				.append(",\"background\":\"").append(hex(background)).append('"'); //$NON-NLS-1$
		// A theme may leave the foreground unstyled: only use it when it is readable.
		if (Math.abs(luminance(foreground) - luminance(background)) > 0.4) {
			json.append(",\"foreground\":\"").append(hex(foreground)).append('"'); //$NON-NLS-1$
		}
		return json.append('}').toString();
	}

	private static double luminance(RGB rgb) {
		return (0.2126 * rgb.red + 0.7152 * rgb.green + 0.0722 * rgb.blue) / 255;
	}

	private static String hex(RGB rgb) {
		return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue); //$NON-NLS-1$
	}

	/** Pushes the current Eclipse colors and font to the terminal, after a theme or font change. */
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

	private void startSession() {
		if (session != null) {
			session.dispose();
		}
		synchronized (pending) {
			pending.reset();
			pending.notifyAll();
		}
		if (restoredContent != null) {
			append(null, restoredContent);
			append(null, "\r\n\u001b[2m[History restored]\u001b[0m\r\n".getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
			restoredContent = null;
		}
		sessionStart = System.currentTimeMillis();
		try {
			session = new PtySession(ShellProfiles.parse(commandLine), workingDirectory, cols, rows, this);
		} catch (IOException | RuntimeException | LinkageError e) {
			session = null;
			XtermPlugin.log("Could not start the shell", e); //$NON-NLS-1$
			append(null, ("\r\n\u001b[31mCould not start the shell: " + e + "\u001b[0m\r\n") //$NON-NLS-1$ //$NON-NLS-2$
					.getBytes(StandardCharsets.UTF_8));
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
		if (session == null || !session.isAlive()) {
			return;
		}
		session.write(data.getBytes(binary ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8));
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
		// A shell that dies right away with an error could not start properly: keep the view open so
		// that its output can be read. Otherwise the user left the shell, close the view like VS Code.
		boolean failedToStart = exitCode != 0 && System.currentTimeMillis() - sessionStart < FAILED_START_MILLIS;
		if (failedToStart) {
			append(null, ("\r\n\u001b[2m[Process exited with code " + exitCode + "]\u001b[0m\r\n") //$NON-NLS-1$ //$NON-NLS-2$
					.getBytes(StandardCharsets.UTF_8));
		} else if (!display.isDisposed()) {
			display.asyncExec(() -> {
				if (!browser.isDisposed() && source == session && !workbench().isClosing()) {
					getSite().getPage().hideView(this);
				}
			});
		}
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

	private File resolveWorkingDirectory() {
		try {
			ISelection selection = getSite().getPage().getSelection();
			if (selection instanceof IStructuredSelection structured && !structured.isEmpty()) {
				File directory = WorkspaceLocations.projectDirectory(structured.getFirstElement());
				if (directory != null && directory.isDirectory()) {
					return directory;
				}
			}
		} catch (LinkageError e) {
			// org.eclipse.core.resources is optional.
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
		getSite().getPage().removePartListener(partListener);
		workbench().getThemeManager().removePropertyChangeListener(themeListener);
		JFaceResources.getFontRegistry().removeListener(themeListener);
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
