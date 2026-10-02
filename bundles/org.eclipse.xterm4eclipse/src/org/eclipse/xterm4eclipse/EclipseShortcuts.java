package org.eclipse.xterm4eclipse;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.bindings.keys.KeyLookupFactory;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.KeyStroke;
import org.eclipse.jface.bindings.keys.ParseException;

/**
 * The Eclipse key bindings that keep working while a terminal has the keyboard, like the
 * {@code terminal.integrated.commandsToSkipShell} of VS Code. All the other keys go to the shell.
 * <p>
 * The page and the Java side name a key stroke the same way: the modifiers in alphabetical order
 * then the key, as in {@code CTRL+SHIFT+R} or {@code COMMAND+3}.
 */
final class EclipseShortcuts {

	/** Quick Access, Open Resource, Open Type, next/previous view, editor and tab, new terminal. */
	static final String DEFAULTS = "M1+3, M1+M2+R, M1+M2+T, M1+F6, M1+M2+F6, M1+F7, M1+M2+F7, M1+F8, M1+M2+F8, " //$NON-NLS-1$
			+ "M1+PAGE_UP, M1+PAGE_DOWN, M1+M2+M3+X"; //$NON-NLS-1$

	private EclipseShortcuts() {
	}

	/**
	 * @param setting
	 *            key strokes separated by commas, in the format of the Keys preference page (M1+3,
	 *            CTRL+SHIFT+R...)
	 * @return the single key strokes of the setting as named by the page, invalid entries left out
	 */
	static List<String> parse(String setting) {
		List<String> result = new ArrayList<>();
		for (String entry : setting.split(",")) { //$NON-NLS-1$
			String trimmed = entry.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			try {
				KeyStroke[] strokes = KeySequence.getInstance(trimmed).getKeyStrokes();
				if (strokes.length == 1 && strokes[0].isComplete()) {
					result.add(name(strokes[0]));
				}
			} catch (ParseException | IllegalArgumentException e) {
				// Ignored, as a mistyped entry in the preference page.
			}
		}
		return result;
	}

	/** The name the page gives to a key stroke. */
	static String name(KeyStroke stroke) {
		StringBuilder name = new StringBuilder();
		int modifiers = stroke.getModifierKeys();
		var lookup = KeyLookupFactory.getDefault();
		if ((modifiers & lookup.getAlt()) != 0) {
			name.append("ALT+"); //$NON-NLS-1$
		}
		if ((modifiers & lookup.getCommand()) != 0) {
			name.append("COMMAND+"); //$NON-NLS-1$
		}
		if ((modifiers & lookup.getCtrl()) != 0) {
			name.append("CTRL+"); //$NON-NLS-1$
		}
		if ((modifiers & lookup.getShift()) != 0) {
			name.append("SHIFT+"); //$NON-NLS-1$
		}
		return name.append(lookup.formalNameLookup(stroke.getNaturalKey())).toString();
	}

	/**
	 * @param name
	 *            a key stroke as named by the page
	 * @return the key stroke as Eclipse knows it, or {@code null} if the name cannot be read
	 */
	static KeySequence sequence(String name) {
		try {
			return KeySequence.getInstance(name);
		} catch (ParseException | IllegalArgumentException e) {
			return null;
		}
	}

	/** The setting as a JavaScript array literal of key stroke names. */
	static String toJson(List<String> names) {
		StringBuilder json = new StringBuilder("["); //$NON-NLS-1$
		for (String name : names) {
			if (json.length() > 1) {
				json.append(',');
			}
			json.append('"').append(name.replace("\\", "\\\\").replace("\"", "\\\"")).append('"'); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		}
		return json.append(']').toString();
	}
}
