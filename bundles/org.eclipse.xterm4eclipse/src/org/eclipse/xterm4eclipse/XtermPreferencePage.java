package org.eclipse.xterm4eclipse;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
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
		addField(new BooleanFieldEditor(XtermPlugin.PREF_COPY_ON_SELECT,
				"Co&py the selection to the clipboard as soon as it is made", getFieldEditorParent())); //$NON-NLS-1$
		addField(new StringFieldEditor(XtermPlugin.PREF_FONT_FAMILY,
				"Fo&nt (empty: text font of Eclipse):", getFieldEditorParent())); //$NON-NLS-1$
		IntegerFieldEditor fontSize = new IntegerFieldEditor(XtermPlugin.PREF_FONT_SIZE,
				"Font si&ze in points (0: the one of the text font):", getFieldEditorParent()); //$NON-NLS-1$
		fontSize.setValidRange(0, XtermView.MAX_FONT_SIZE);
		addField(fontSize);
		IntegerFieldEditor scrollback = new IntegerFieldEditor(XtermPlugin.PREF_SCROLLBACK,
				"Lines &kept above the screen:", getFieldEditorParent()); //$NON-NLS-1$
		scrollback.setValidRange(0, XtermView.MAX_SCROLLBACK);
		addField(scrollback);
		addField(new ComboFieldEditor(XtermPlugin.PREF_CURSOR_STYLE, "C&ursor:", //$NON-NLS-1$
				new String[][] {{"Block", "block"}, {"Underline", "underline"}, {"Bar", "bar"}}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
				getFieldEditorParent()));
		addField(new BooleanFieldEditor(XtermPlugin.PREF_CURSOR_BLINK, "B&linking cursor", getFieldEditorParent())); //$NON-NLS-1$
		addField(new BooleanFieldEditor(XtermPlugin.PREF_MAC_OPTION_IS_META,
				"macOS: the &Option key is Meta (Option+B, Option+F... in the shell) instead of typing accents", //$NON-NLS-1$
				getFieldEditorParent()));
		addField(new StringFieldEditor(XtermPlugin.PREF_ENVIRONMENT,
				"En&vironment variables of new shells, one per line (NAME=value, ${OTHER} for the value of another, -NAME to remove one):", //$NON-NLS-1$
				40, 4, StringFieldEditor.VALIDATE_ON_KEY_STROKE, getFieldEditorParent()) {
			@Override
			protected boolean doCheckState() {
				String error = ShellEnvironment.errorIn(getStringValue());
				setErrorMessage(error);
				return error == null;
			}
		});
		addField(new StringFieldEditor(XtermPlugin.PREF_ECLIPSE_SHORTCUTS,
				"&Eclipse shortcuts that work in the terminal (M1 is Ctrl, or Cmd on macOS):", //$NON-NLS-1$
				getFieldEditorParent()));
	}
}
