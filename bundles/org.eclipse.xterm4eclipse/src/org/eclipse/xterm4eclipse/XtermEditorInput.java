package org.eclipse.xterm4eclipse;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IPersistableElement;

/**
 * What an {@link XtermEditor} shows: a terminal, identified by an id that also names the file of its
 * saved screen. Persisted with the workbench, so that the terminals of the editor area come back
 * after a restart of Eclipse, as the views do.
 */
public final class XtermEditorInput implements IEditorInput, IPersistableElement {

	static final String MEMENTO_ID = "id"; //$NON-NLS-1$
	private static final String[] STATE_KEYS = {"shell", "directory", "name"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	private final String id;
	/** The state saved by an earlier session of Eclipse, until the editor is created. */
	private final IMemento state;
	private TerminalTransfer transfer;
	private XtermEditor editor;

	/** A terminal moving to the editor area. */
	XtermEditorInput(TerminalTransfer transfer) {
		this(XtermView.nextId("e"), null); //$NON-NLS-1$
		this.transfer = transfer;
	}

	/** A terminal of an earlier session of Eclipse. */
	XtermEditorInput(String id, IMemento state) {
		this.id = id;
		this.state = state;
	}

	String id() {
		return id;
	}

	IMemento state() {
		return state;
	}

	/** @return the terminal moving here, once: the first caller owns it */
	synchronized TerminalTransfer takeTransfer() {
		TerminalTransfer taken = transfer;
		transfer = null;
		return taken;
	}

	/** Gives back a terminal that an editor could not take. */
	synchronized void giveBack(TerminalTransfer moving) {
		transfer = moving;
	}

	void setEditor(XtermEditor shownBy) {
		editor = shownBy;
	}

	@Override
	public boolean exists() {
		// Not offered in the list of recently opened files: a terminal is not a file to open again.
		return false;
	}

	@Override
	public ImageDescriptor getImageDescriptor() {
		return ImageDescriptor.createFromFile(XtermEditorInput.class, "/icons/xterm.png"); //$NON-NLS-1$
	}

	@Override
	public String getName() {
		return editor != null ? editor.getPartName() : "Xterm"; //$NON-NLS-1$
	}

	@Override
	public String getToolTipText() {
		return getName();
	}

	@Override
	public IPersistableElement getPersistable() {
		return this;
	}

	@Override
	public <T> T getAdapter(Class<T> adapter) {
		return null;
	}

	@Override
	public String getFactoryId() {
		return XtermEditorInputFactory.ID;
	}

	@Override
	public void saveState(IMemento memento) {
		memento.putString(MEMENTO_ID, id);
		if (editor != null) {
			editor.saveTerminalState(memento);
		} else if (state != null) {
			// Not shown since Eclipse started: what was saved last time is still what to restore.
			for (String key : STATE_KEYS) {
				if (state.getString(key) != null) {
					memento.putString(key, state.getString(key));
				}
			}
		}
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof XtermEditorInput input && input.id.equals(id);
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}
}
