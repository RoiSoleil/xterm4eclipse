package org.eclipse.xterm4eclipse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.e4.ui.model.application.ui.basic.MBasicFactory;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.keys.IBindingService;
import org.eclipse.ui.progress.IWorkbenchSiteProgressService;
import org.eclipse.ui.themes.IThemeManager;

/**
 * The slice of the Eclipse workbench an {@link XtermView} talks to, backed by fakes, plus a real SWT
 * shell to host the view.
 */
final class TestWorkbench {

	static final Display DISPLAY = new Display();

	static final boolean MAC = System.getProperty("os.name").startsWith("Mac");
	static final boolean LINUX = System.getProperty("os.name").startsWith("Linux");
	/** The modifier of the Eclipse shortcuts (M1): Command on macOS, Ctrl elsewhere. */
	static final String M1 = MAC ? "COMMAND" : "CTRL";
	/** The event property of that modifier in the page. */
	static final String M1_KEY = MAC ? "metaKey" : "ctrlKey";
	/**
	 * The bash of the tests, at least 4.4 for bracketed paste (/bin/bash of macOS is 3.2): test.sh
	 * gives the one of the PATH.
	 */
	static final String BASH = System.getProperty("xterm4eclipse.test.bash", "/bin/bash");
	static final String SHELL = '"' + BASH + "\" --norc --noprofile";

	final Fake page = new Fake();
	/** The page of every view, the same object each time as in Eclipse. */
	final IWorkbenchPage workbenchPage = page.as(IWorkbenchPage.class);
	final Fake bindings = new Fake();
	final Fake handlers = new Fake();
	final Fake progress = new Fake();
	final Fake workbench = new Fake();
	final ToolBarManager toolBar = new ToolBarManager();
	final MenuManager viewMenu = new MenuManager();
	final List<IPartListener2> partListeners = new ArrayList<>();
	final List<IPropertyChangeListener> themeListeners = new ArrayList<>();
	final List<Shell> shells = new ArrayList<>();
	final List<XtermView> views = new ArrayList<>();
	final List<XtermEditor> editors = new ArrayList<>();
	/** The part of each view or editor in the model of the workbench, in the order of creation. */
	final List<MPart> parts = new ArrayList<>();
	/** The part in the model for the next view or editor, instead of a new one. */
	MPart nextPart;

	private MPart nextPart() {
		MPart part = nextPart != null ? nextPart : MBasicFactory.INSTANCE.createPart();
		nextPart = null;
		parts.add(part);
		return part;
	}
	boolean closing;
	ISelection selection;
	Object activePart;

	TestWorkbench() {
		page.on("addPartListener", args -> partListeners.add((IPartListener2) args[0]));
		page.on("removePartListener", args -> partListeners.remove(args[0]));
		page.on("getSelection", args -> selection);
		page.on("getActivePart", args -> activePart);
		workbench.on("isClosing", args -> closing);
		workbench.on("getSharedImages", args -> new Fake().as(ISharedImages.class));
		workbench.on("getService", args -> args[0] == IBindingService.class ? bindings.as(IBindingService.class)
				: args[0] == IHandlerService.class ? handlers.as(IHandlerService.class) : null);
		workbench.on("getThemeManager",
				args -> new Fake().on("addPropertyChangeListener", a -> themeListeners.add((IPropertyChangeListener) a[0]))
						.on("removePropertyChangeListener", a -> themeListeners.remove(a[0])).as(IThemeManager.class));
	}

	IViewSite site(String secondaryId) {
		MPart part = nextPart();
		IWorkbenchWindow window = new Fake().on("getWorkbench", args -> workbench.as(IWorkbench.class))
				.as(IWorkbenchWindow.class);
		return new Fake().on("getPage", args -> workbenchPage)
				.on("getSecondaryId", args -> secondaryId)
				.on("getActionBars", args -> new Fake().on("getToolBarManager", a -> toolBar)
						.on("getMenuManager", a -> viewMenu).as(IActionBars.class))
				.on("getWorkbenchWindow", args -> window)
				.on("getService",
						args -> args[0] == IWorkbenchSiteProgressService.class
								? progress.as(IWorkbenchSiteProgressService.class)
								: args[0] == MPart.class ? part : null)
				.as(IViewSite.class);
	}

	IEditorSite editorSite() {
		MPart part = nextPart();
		IWorkbenchWindow window = new Fake().on("getWorkbench", args -> workbench.as(IWorkbench.class))
				.as(IWorkbenchWindow.class);
		return new Fake().on("getPage", args -> workbenchPage)
				.on("getActionBars", args -> new Fake().as(IActionBars.class))
				.on("getWorkbenchWindow", args -> window)
				.on("getService",
						args -> args[0] == IWorkbenchSiteProgressService.class
								? progress.as(IWorkbenchSiteProgressService.class)
								: args[0] == MPart.class ? part : null)
				.as(IEditorSite.class);
	}

	/** Creates an editor the way the workbench does, in its own window. */
	XtermEditor openEditor(IEditorInput input) throws Exception {
		XtermEditor editor = new XtermEditor();
		editor.init(editorSite(), input);
		Shell shell = new Shell(DISPLAY);
		shell.setLayout(new FillLayout());
		shell.setSize(700, 420);
		editor.createPartControl(shell);
		shell.open();
		shells.add(shell);
		editors.add(editor);
		return editor;
	}

	/** Creates a view the way the workbench does, in its own window. */
	XtermView open(XtermView view, String secondaryId, IMemento memento) throws Exception {
		view.init(site(secondaryId), memento);
		Shell shell = new Shell(DISPLAY);
		shell.setLayout(new FillLayout());
		shell.setSize(700, 420);
		view.createPartControl(shell);
		shell.open();
		shells.add(shell);
		views.add(view);
		return view;
	}

	IWorkbenchPartReference reference(XtermView view) {
		return new Fake().on("getPart", args -> view).as(IWorkbenchPartReference.class);
	}

	void close() {
		for (XtermView view : views) {
			view.dispose();
		}
		for (XtermEditor editor : editors) {
			editor.dispose();
		}
		for (Shell shell : shells) {
			shell.dispose();
		}
		pump(100);
	}

	static String screen(XtermView view) {
		Object text = view.browser.evaluate("return Array.from(document.querySelectorAll('.xterm-rows > div'))"
				+ ".map(function (row) { return row.textContent; }).join('\\n')");
		return text == null ? "" : text.toString();
	}

	static void type(XtermView view, String text) {
		view.browser.execute("javaInput('" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r") + "')");
	}

	/** Runs the event loop until the condition holds. */
	static void await(String what, BooleanSupplier condition) {
		await(what, condition, 15000);
	}

	static void await(String what, BooleanSupplier condition, long millis) {
		long deadline = System.currentTimeMillis() + millis;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("Timed out waiting for: " + what);
			}
			pump(20);
		}
	}

	static void pump(long millis) {
		long end = System.currentTimeMillis() + millis;
		do {
			if (!DISPLAY.readAndDispatch()) {
				try {
					Thread.sleep(5);
				} catch (InterruptedException e) {
					throw new AssertionError(e);
				}
			}
		} while (System.currentTimeMillis() < end);
	}
}
