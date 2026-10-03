package org.eclipse.xterm4eclipse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.EditorPart;

/**
 * The terminals of the editor area saved by version 0.1 of the plug-in, which showed them in editors:
 * when Eclipse restores one, it comes back as a terminal view in the editor area, with its shell,
 * directory, name and screen, and the editor closes. Terminals now move to the editor area as views.
 */
public class XtermEditor extends EditorPart {

	public static final String ID = "org.eclipse.xterm4eclipse.editor"; //$NON-NLS-1$

	@Override
	public void init(IEditorSite site, IEditorInput input) throws PartInitException {
		if (!(input instanceof XtermEditorInput)) {
			throw new PartInitException("Not a terminal: " + input); //$NON-NLS-1$
		}
		setSite(site);
		setInput(input);
		setPartName("Xterm"); //$NON-NLS-1$
	}

	@Override
	public void createPartControl(Composite parent) {
		new Composite(parent, SWT.NONE);
		// Not while the workbench creates this editor.
		parent.getDisplay().asyncExec(this::becomeView);
	}

	/** Opens the terminal as a view in the editor area, then closes this editor. */
	void becomeView() {
		IWorkbenchPage page = getSite().getPage();
		if (page == null) {
			return;
		}
		XtermEditorInput input = (XtermEditorInput) getEditorInput();
		IMemento state = input.state();
		String shell = state == null ? null : state.getString("shell"); //$NON-NLS-1$
		String directory = state == null ? null : state.getString("directory"); //$NON-NLS-1$
		String name = state == null ? null : state.getString("name"); //$NON-NLS-1$
		try {
			XtermView view = XtermView.open(page, shell == null ? ShellProfiles.defaultCommandLine() : shell,
					directory == null ? null : new File(directory), name, history(input.id()));
			if (view != null) {
				view.moveToEditorArea();
			}
		} catch (PartInitException | RuntimeException e) {
			XtermPlugin.log("Could not restore the terminal of the editor area", e); //$NON-NLS-1$
		}
		page.closeEditor(this, false);
	}

	/** The screen saved for the editor, read once: the view saves its own from now on. */
	private static byte[] history(String id) {
		Path file = XtermPlugin.stateDirectory().resolve(id + ".screen"); //$NON-NLS-1$
		try {
			if (!XtermPlugin.isEnabled(XtermPlugin.PREF_RESTORE_HISTORY) || !Files.isReadable(file)) {
				return null;
			}
			return Files.readAllBytes(file);
		} catch (IOException e) {
			return null;
		} finally {
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				// A stale file is never read again.
			}
		}
	}

	@Override
	public void setFocus() {
		// Nothing to focus: the editor closes right away.
	}

	@Override
	public void doSave(IProgressMonitor monitor) {
		// Nothing to save.
	}

	@Override
	public void doSaveAs() {
		// Not allowed.
	}

	@Override
	public boolean isDirty() {
		return false;
	}

	@Override
	public boolean isSaveAsAllowed() {
		return false;
	}
}
