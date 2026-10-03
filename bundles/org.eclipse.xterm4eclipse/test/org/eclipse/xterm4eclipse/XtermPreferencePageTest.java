package org.eclipse.xterm4eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
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
				XtermPlugin.PREF_RESTORE_HISTORY, XtermPlugin.PREF_ECLIPSE_SHORTCUTS, XtermPlugin.PREF_WARN_MULTI_LINE_PASTE, XtermPlugin.PREF_COPY_ON_SELECT, XtermPlugin.PREF_FONT_FAMILY,
				XtermPlugin.PREF_FONT_SIZE, XtermPlugin.PREF_SCROLLBACK, XtermPlugin.PREF_CURSOR_STYLE, XtermPlugin.PREF_CURSOR_BLINK,
				XtermPlugin.PREF_MAC_OPTION_IS_META}) {
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
		List<Combo> combos = new ArrayList<>();
		collect(page.getControl(), checkboxes, texts, combos);
		assertEquals(6, checkboxes.size());
		assertEquals(5, texts.size());
		assertEquals(1, combos.size());
		assertFalse(checkboxes.get(0).getSelection(), "focus on finish is off by default");
		assertTrue(checkboxes.get(1).getSelection(), "history is restored by default");
		assertTrue(checkboxes.get(2).getSelection(), "multi-line paste confirmed by default");
		assertFalse(checkboxes.get(3).getSelection(), "no copy on select by default");
		assertTrue(checkboxes.get(4).getSelection(), "blinking cursor by default");
		assertFalse(checkboxes.get(5).getSelection(), "Option types accents by default");
		assertEquals("", texts.get(1).getText(), "text font of Eclipse by default");
		assertEquals("0", texts.get(2).getText());
		assertEquals("10000", texts.get(3).getText());
		assertEquals(EclipseShortcuts.DEFAULTS, texts.get(4).getText());
		assertEquals("Block", combos.get(0).getText());

		texts.get(0).setText("/usr/bin/fish");
		texts.get(1).setText("Fira Code");
		texts.get(2).setText("14");
		texts.get(3).setText("500");
		texts.get(4).setText("M1+3");
		combos.get(0).select(2);
		combos.get(0).notifyListeners(SWT.Selection, new Event());
		toggle(checkboxes.get(0), true);
		toggle(checkboxes.get(1), false);
		toggle(checkboxes.get(2), false);
		toggle(checkboxes.get(3), true);
		toggle(checkboxes.get(4), false);
		toggle(checkboxes.get(5), true);
		assertTrue(page.performOk());

		assertEquals("/usr/bin/fish", ShellProfiles.defaultCommandLine());
		assertTrue(XtermPlugin.isEnabled(XtermPlugin.PREF_FOCUS_ON_FINISH));
		assertFalse(XtermPlugin.isEnabled(XtermPlugin.PREF_RESTORE_HISTORY));
		assertFalse(XtermPlugin.isEnabled(XtermPlugin.PREF_WARN_MULTI_LINE_PASTE));
		assertTrue(XtermPlugin.isEnabled(XtermPlugin.PREF_COPY_ON_SELECT));
		assertEquals("M1+3", XtermPlugin.preference(XtermPlugin.PREF_ECLIPSE_SHORTCUTS));
		assertEquals("Fira Code", XtermPlugin.preference(XtermPlugin.PREF_FONT_FAMILY));
		assertEquals(14, XtermPlugin.preferences().getInt(XtermPlugin.PREF_FONT_SIZE));
		assertEquals(500, XtermPlugin.preferences().getInt(XtermPlugin.PREF_SCROLLBACK));
		assertEquals("bar", XtermPlugin.preference(XtermPlugin.PREF_CURSOR_STYLE));
		assertFalse(XtermPlugin.isEnabled(XtermPlugin.PREF_CURSOR_BLINK));
		assertTrue(XtermPlugin.isEnabled(XtermPlugin.PREF_MAC_OPTION_IS_META));

	}

	private static void toggle(Button checkbox, boolean selected) {
		checkbox.setSelection(selected);
		checkbox.notifyListeners(SWT.Selection, new Event());
	}

	private static void collect(Control control, List<Button> checkboxes, List<Text> texts, List<Combo> combos) {
		if (control instanceof Button button && (button.getStyle() & SWT.CHECK) != 0) {
			checkboxes.add(button);
		} else if (control instanceof Text text) {
			texts.add(text);
		} else if (control instanceof Combo combo) {
			combos.add(combo);
		} else if (control instanceof Composite composite) {
			for (Control child : composite.getChildren()) {
				collect(child, checkboxes, texts, combos);
			}
		}
	}
}
