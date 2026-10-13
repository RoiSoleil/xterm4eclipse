package org.eclipse.xterm4eclipse.harness;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/** The preferences of the coding agents, under those of the terminal. */
public class HarnessPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public HarnessPreferencePage() {
		super(GRID);
		setPreferenceStore(HarnessPlugin.preferences());
		StringBuilder agents = new StringBuilder();
		for (Harness harness : Harnesses.ALL) {
			agents.append(agents.isEmpty() ? "" : ", ").append(harness.product()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		setDescription("Coding agents that the terminals know: " + agents + "."); //$NON-NLS-1$ //$NON-NLS-2$
	}

	@Override
	public void init(IWorkbench workbench) {
		// Nothing to prepare.
	}

	@Override
	protected void createFieldEditors() {
		addField(new BooleanFieldEditor(HarnessPlugin.PREF_OFFER_ECLIPSE_MCP,
				"Offer to add the &MCP server of Eclipse to new terminals of a coding agent, when it runs", //$NON-NLS-1$
				getFieldEditorParent()));
	}
}
