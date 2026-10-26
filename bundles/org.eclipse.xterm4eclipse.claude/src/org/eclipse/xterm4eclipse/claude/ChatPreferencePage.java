package org.eclipse.xterm4eclipse.claude;

import java.util.Map;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ComboFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/** The preferences of the chats with Claude Code, under those of the terminal. */
public class ChatPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public ChatPreferencePage() {
		super(GRID);
		setPreferenceStore(ChatPlugin.preferences());
		setDescription("The chats with Claude Code (Window > Show View > Xterm > Claude Code Chat). Their " //$NON-NLS-1$
				+ "conversations are those of the CLI and of Claude Desktop."); //$NON-NLS-1$
	}

	@Override
	public void init(IWorkbench workbench) {
		// Nothing to prepare.
	}

	@Override
	protected void createFieldEditors() {
		FileFieldEditor program = new FileFieldEditor(ChatPlugin.PREF_PROGRAM, "&Program of Claude Code:", true, //$NON-NLS-1$
				getFieldEditorParent());
		program.setEmptyStringAllowed(true);
		addField(program);
		StringFieldEditor model = new StringFieldEditor(ChatPlugin.PREF_MODEL, "&Model (opus, sonnet, haiku or a full name):", //$NON-NLS-1$
				getFieldEditorParent()) {
			@Override
			protected boolean doCheckState() {
				String value = getStringValue().trim();
				return value.isEmpty() || ClaudeCommand.isModel(value);
			}
		};
		model.setErrorMessage("Not the name of a model"); //$NON-NLS-1$
		addField(model);
		String[][] modes = new String[ClaudeCommand.PERMISSION_MODES.size()][];
		int i = 0;
		for (Map.Entry<String, String> mode : ClaudeCommand.PERMISSION_MODES.entrySet()) {
			modes[i++] = new String[] {mode.getValue(), mode.getKey()};
		}
		addField(new ComboFieldEditor(ChatPlugin.PREF_PERMISSION_MODE, "P&ermissions:", modes, getFieldEditorParent())); //$NON-NLS-1$
		addField(new StringFieldEditor(ChatPlugin.PREF_ARGUMENTS, "More &arguments:", getFieldEditorParent())); //$NON-NLS-1$
		addField(new BooleanFieldEditor(ChatPlugin.PREF_CONTINUE_LAST,
				"A new chat &continues the last conversation of its project", getFieldEditorParent())); //$NON-NLS-1$
		addField(new BooleanFieldEditor(ChatPlugin.PREF_ECLIPSE_MCP,
				"Connect Claude Code to the MCP server of &Eclipse when it runs", getFieldEditorParent())); //$NON-NLS-1$
	}
}
