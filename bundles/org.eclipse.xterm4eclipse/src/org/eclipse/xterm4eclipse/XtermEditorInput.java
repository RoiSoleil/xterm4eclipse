package org.eclipse.xterm4eclipse;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IPersistableElement;

/**
 * A terminal of the editor area saved by version 0.1 of the plug-in, until {@link XtermEditor} turns
 * it into a view.
 */
public final class XtermEditorInput implements IEditorInput, IPersistableElement {

	static final String MEMENTO_ID = "id"; //$NON-NLS-1$
	private static final String[] STATE_KEYS = {"shell", "directory", "name"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	private final String id;
	private final IMemento state;

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

	@Override
	public boolean exists() {
		return false;
	}

	@Override
	public ImageDescriptor getImageDescriptor() {
		String shell = state == null ? null : state.getString("shell"); //$NON-NLS-1$
		return ImageDescriptor.createFromFile(XtermEditorInput.class,
				'/' + (shell == null ? "icons/xterm.png" : ShellProfiles.iconOf(shell))); //$NON-NLS-1$
	}

	@Override
	public String getName() {
		return "Xterm"; //$NON-NLS-1$
	}

	@Override
	public String getToolTipText() {
		return getName();
	}

	@Override
	public IPersistableElement getPersistable() {
		// Saved again as it is if Eclipse closes before the editor became a view.
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
		if (state != null) {
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
