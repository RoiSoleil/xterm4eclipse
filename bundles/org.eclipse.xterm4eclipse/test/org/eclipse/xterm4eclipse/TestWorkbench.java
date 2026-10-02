package org.eclipse.xterm4eclipse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IActionBars;
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

	final Fake page = new Fake();
	final Fake bindings = new Fake();
	final Fake handlers = new Fake();
	final Fake progress = new Fake();
	final Fake workbench = new Fake();
	final ToolBarManager toolBar = new ToolBarManager();
	final List<IPartListener2> partListeners = new ArrayList<>();
	final List<IPropertyChangeListener> themeListeners = new ArrayList<>();
	final List<Shell> shells = new ArrayList<>();
	final List<XtermView> views = new ArrayList<>();
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
		IWorkbenchWindow window = new Fake().on("getWorkbench", args -> workbench.as(IWorkbench.class))
				.as(IWorkbenchWindow.class);
		return new Fake().on("getPage", args -> page.as(IWorkbenchPage.class))
				.on("getSecondaryId", args -> secondaryId)
				.on("getActionBars", args -> new Fake().on("getToolBarManager", a -> toolBar).as(IActionBars.class))
				.on("getWorkbenchWindow", args -> window)
				.on("getService",
						args -> args[0] == IWorkbenchSiteProgressService.class
								? progress.as(IWorkbenchSiteProgressService.class)
								: null)
				.as(IViewSite.class);
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
		long deadline = System.currentTimeMillis() + 15000;
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
