package org.eclipse.xterm4eclipse;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.ui.IElementFactory;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.XMLMemento;

/**
 * Recreates the input of a terminal of the editor area when Eclipse restarts.
 */
public class XtermEditorInputFactory implements IElementFactory {

	static final String ID = "org.eclipse.xterm4eclipse.editorInputFactory"; //$NON-NLS-1$

	@Override
	public IAdaptable createElement(IMemento memento) {
		String id = memento.getString(XtermEditorInput.MEMENTO_ID);
		// The id names the file of the saved screen: only accept what this plug-in writes.
		if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) { //$NON-NLS-1$
			return null;
		}
		XMLMemento state = XMLMemento.createWriteRoot("terminal"); //$NON-NLS-1$
		state.putMemento(memento);
		return new XtermEditorInput(id, state);
	}
}
