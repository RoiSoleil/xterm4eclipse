package org.eclipse.xterm4eclipse;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Window &gt; Preferences &gt; Xterm Terminal.
 */
public class XtermPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public XtermPreferencePage() {
		super(GRID);
		setPreferenceStore(XtermPlugin.preferences());
		setDescription("Settings of the Xterm terminal view."); //$NON-NLS-1$
	}

	@Override
	public void init(IWorkbench workbench) {
		// Nothing to prepare.
	}

	@Override
	protected void createFieldEditors() {
		addField(new StringFieldEditor(XtermPlugin.PREF_DEFAULT_SHELL,
				"Default &shell command line (empty: shell of the system):", getFieldEditorParent())); //$NON-NLS-1$
		addField(new BooleanFieldEditor(XtermPlugin.PREF_FOCUS_ON_FINISH,
				"Give the &focus to the terminal view when a command finishes or asks for attention", //$NON-NLS-1$
				getFieldEditorParent()));
		addField(new BooleanFieldEditor(XtermPlugin.PREF_RESTORE_HISTORY,
				"&Restore the content of the terminals when Eclipse restarts", getFieldEditorParent())); //$NON-NLS-1$
		addField(new BooleanFieldEditor(XtermPlugin.PREF_WARN_MULTI_LINE_PASTE,
				"&Confirm a paste of several lines when the shell would run each line at once", //$NON-NLS-1$
				getFieldEditorParent()));
		addField(new StringFieldEditor(XtermPlugin.PREF_ECLIPSE_SHORTCUTS,
				"&Eclipse shortcuts that work in the terminal (M1 is Ctrl, or Cmd on macOS):", //$NON-NLS-1$
				getFieldEditorParent()));
	}
}
