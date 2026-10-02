package org.eclipse.xterm4eclipse;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.ISaveablePart2;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPartConstants;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.EditorPart;
import org.eclipse.ui.services.IServiceLocator;

/**
 * A terminal in the editor area, as the terminal editors of VS Code: the same terminal as the view,
 * which it embeds, with its actions in a tool bar of its own. A terminal moves between the view and
 * the editor area with its running shell.
 */
public class XtermEditor extends EditorPart implements ISaveablePart2 {

	public static final String ID = "org.eclipse.xterm4eclipse.editor"; //$NON-NLS-1$

	private final XtermView terminal = new XtermView();
	private final ToolBarManager toolBar = new ToolBarManager(SWT.FLAT | SWT.RIGHT);
	private final MenuManager menu = new MenuManager();

	@Override
	public void init(IEditorSite site, IEditorInput input) throws PartInitException {
		if (!(input instanceof XtermEditorInput terminalInput)) {
			throw new PartInitException("Not a terminal: " + input); //$NON-NLS-1$
		}
		setSite(site);
		setInput(input);
		terminalInput.setEditor(this);
		terminal.embedIn(this);
		XtermView.nextTransfer = terminalInput.takeTransfer();
		try {
			terminal.init(viewSite(site, terminalInput.id()), terminalInput.state());
		} finally {
			if (XtermView.nextTransfer != null) {
				// Not taken: the part it comes from keeps it.
				terminalInput.giveBack(XtermView.nextTransfer);
				XtermView.nextTransfer = null;
			}
		}
		terminal.addPropertyListener((source, property) -> {
			if (property == IWorkbenchPartConstants.PROP_DIRTY) {
				firePropertyChange(IWorkbenchPartConstants.PROP_DIRTY);
			} else {
				showTitle();
			}
		});
	}

	/**
	 * The site of the embedded terminal: the one of this editor, but with the tool bar and menu of
	 * this editor, and the id of its input to name the file of the saved screen.
	 */
	private IViewSite viewSite(IEditorSite site, String id) {
		IActionBars bars = new ActionBars(site);
		return (IViewSite) Proxy.newProxyInstance(XtermEditor.class.getClassLoader(), new Class<?>[] {IViewSite.class},
				(proxy, method, args) -> switch (method.getName()) {
				case "getSecondaryId" -> id; //$NON-NLS-1$
				case "getActionBars" -> bars; //$NON-NLS-1$
				case "equals" -> proxy == args[0]; //$NON-NLS-1$
				case "hashCode" -> System.identityHashCode(proxy); //$NON-NLS-1$
				case "toString" -> "Terminal site of " + site; //$NON-NLS-1$ //$NON-NLS-2$
				default -> delegate(site, method, args);
				});
	}

	private static Object delegate(IEditorSite site, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(site, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	/** The tool bar and menu of this editor, standing for those of a view. */
	private final class ActionBars implements IActionBars {
		private final IEditorSite site;

		ActionBars(IEditorSite site) {
			this.site = site;
		}

		@Override
		public void clearGlobalActionHandlers() {
			// The terminal has none.
		}

		@Override
		public IAction getGlobalActionHandler(String actionId) {
			return null;
		}

		@Override
		public IMenuManager getMenuManager() {
			return menu;
		}

		@Override
		public IServiceLocator getServiceLocator() {
			return site;
		}

		@Override
		public IStatusLineManager getStatusLineManager() {
			return site.getActionBars() == null ? null : site.getActionBars().getStatusLineManager();
		}

		@Override
		public IToolBarManager getToolBarManager() {
			return toolBar;
		}

		@Override
		public void setGlobalActionHandler(String actionId, IAction handler) {
			// The terminal has none.
		}

		@Override
		public void updateActionBars() {
			toolBar.update(true);
		}
	}

	@Override
	public void createPartControl(Composite parent) {
		Composite root = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout();
		layout.marginWidth = 0;
		layout.marginHeight = 0;
		layout.verticalSpacing = 0;
		root.setLayout(layout);
		ToolBar bar = toolBar.createControl(root);
		bar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
		Composite body = new Composite(root, SWT.NONE);
		body.setLayout(new FillLayout());
		body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		terminal.createPartControl(body);
		if (!menu.isEmpty()) {
			Action more = new Action("⋮") { //$NON-NLS-1$
				@Override
				public void run() {
					showMenu(bar);
				}
			};
			more.setToolTipText("More Actions"); //$NON-NLS-1$
			toolBar.add(more);
		}
		toolBar.update(true);
		root.layout(true, true);
		showTitle();
	}

	private void showMenu(ToolBar bar) {
		Menu popup = menu.createContextMenu(bar);
		ToolItem[] items = bar.getItems();
		Rectangle bounds = items.length > 0 ? items[items.length - 1].getBounds() : bar.getClientArea();
		popup.setLocation(bar.toDisplay(bounds.x, bounds.y + bounds.height));
		popup.setVisible(true);
	}

	/** The name, tool tip and image of the tab are those of the terminal. */
	private void showTitle() {
		setPartName(terminal.getPartName());
		String toolTip = terminal.getTitleToolTip();
		setTitleToolTip(toolTip == null ? "" : toolTip); //$NON-NLS-1$
		Image image = terminal.activityImage();
		if (image != null && !image.isDisposed()) {
			setTitleImage(image);
		}
	}

	/** The terminal this editor shows. */
	XtermView terminal() {
		return terminal;
	}

	void saveTerminalState(IMemento memento) {
		terminal.saveState(memento);
	}

	@Override
	public void setFocus() {
		terminal.setFocus();
	}

	@Override
	public boolean isDirty() {
		return terminal.isDirty();
	}

	@Override
	public int promptToSaveOnClose() {
		return terminal.promptToSaveOnClose();
	}

	@Override
	public void doSave(IProgressMonitor monitor) {
		// Nothing to save: the editor is only "dirty" to confirm its closing while a command runs.
	}

	@Override
	public void doSaveAs() {
		// Not allowed.
	}

	@Override
	public boolean isSaveAsAllowed() {
		return false;
	}

	@Override
	public void dispose() {
		if (terminal.getSite() != null) {
			terminal.dispose();
		}
		toolBar.dispose();
		menu.dispose();
		super.dispose();
	}
}
