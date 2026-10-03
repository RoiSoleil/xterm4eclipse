package org.eclipse.xterm4eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class XtermPreferencePageTest {

	private final Shell shell = new Shell(TestWorkbench.DISPLAY);

	@AfterEach
	void tearDown() {
		shell.dispose();
		resetPreferences();
	}

	static void resetPreferences() {
		for (String key : new String[] {XtermPlugin.PREF_DEFAULT_SHELL, XtermPlugin.PREF_FOCUS_ON_FINISH,
				XtermPlugin.PREF_RESTORE_HISTORY, XtermPlugin.PREF_ECLIPSE_SHORTCUTS, XtermPlugin.PREF_WARN_MULTI_LINE_PASTE}) {
			XtermPlugin.preferences().setToDefault(key);
		}
	}

	@Test
	void defaultsKeepTheFocusAndRestoreTheHistory() {
		assertFalse(XtermPlugin.isEnabled(XtermPlugin.PREF_FOCUS_ON_FINISH));
		assertTrue(XtermPlugin.isEnabled(XtermPlugin.PREF_RESTORE_HISTORY));
		assertEquals("", XtermPlugin.preference(XtermPlugin.PREF_DEFAULT_SHELL));
	}

	@Test
	void pageEditsTheSettings() {
		XtermPreferencePage page = new XtermPreferencePage();
		page.init(null);
		page.createControl(shell);
		List<Button> checkboxes = new ArrayList<>();
		List<Text> texts = new ArrayList<>();
		collect(page.getControl(), checkboxes, texts);
		assertEquals(3, checkboxes.size());
		assertTrue(checkboxes.get(2).getSelection(), "multi-line paste confirmed by default");
		assertEquals(2, texts.size());
		assertEquals(EclipseShortcuts.DEFAULTS, texts.get(1).getText());
		assertFalse(checkboxes.get(0).getSelection(), "focus on finish is off by default");
		assertTrue(checkboxes.get(1).getSelection(), "history is restored by default");

		texts.get(0).setText("/usr/bin/fish");
		texts.get(1).setText("M1+3");
		toggle(checkboxes.get(0), true);
		toggle(checkboxes.get(1), false);
		toggle(checkboxes.get(2), false);
		assertTrue(page.performOk());

		assertEquals("/usr/bin/fish", ShellProfiles.defaultCommandLine());
		assertTrue(XtermPlugin.isEnabled(XtermPlugin.PREF_FOCUS_ON_FINISH));
		assertFalse(XtermPlugin.isEnabled(XtermPlugin.PREF_RESTORE_HISTORY));
		assertFalse(XtermPlugin.isEnabled(XtermPlugin.PREF_WARN_MULTI_LINE_PASTE));
		assertEquals("M1+3", XtermPlugin.preference(XtermPlugin.PREF_ECLIPSE_SHORTCUTS));
	}

	private static void toggle(Button checkbox, boolean selected) {
		checkbox.setSelection(selected);
		checkbox.notifyListeners(SWT.Selection, new Event());
	}

	private static void collect(Control control, List<Button> checkboxes, List<Text> texts) {
		if (control instanceof Button button && (button.getStyle() & SWT.CHECK) != 0) {
			checkboxes.add(button);
		} else if (control instanceof Text text) {
			texts.add(text);
		} else if (control instanceof Composite composite) {
			for (Control child : composite.getChildren()) {
				collect(child, checkboxes, texts);
			}
		}
	}
}
