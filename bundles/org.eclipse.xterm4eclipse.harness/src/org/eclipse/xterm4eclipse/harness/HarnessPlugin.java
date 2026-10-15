package org.eclipse.xterm4eclipse.harness;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * What the plug-in needs from the Eclipse runtime: its preferences and a log. Outside of OSGi (unit
 * tests) plain Java replacements are used.
 */
final class HarnessPlugin {

	static final String ID = "org.eclipse.xterm4eclipse.harness"; //$NON-NLS-1$
	/**
	 * The preferences are kept with those of the terminal: where the answer "Never Ask" was kept
	 * before the coding agents had a plug-in of their own.
	 */
	static final String PREFERENCES = "org.eclipse.xterm4eclipse"; //$NON-NLS-1$
	/** New terminals of a coding agent offer to add the MCP server of Eclipse when it runs. */
	static final String PREF_OFFER_ECLIPSE_MCP = "offerEclipseMcp"; //$NON-NLS-1$
	/** The MCP server of Eclipse is added without asking: the user answered "Always Add". */
	static final String PREF_ADD_ECLIPSE_MCP_WITHOUT_ASKING = "addEclipseMcpWithoutAsking"; //$NON-NLS-1$

	private static final Bundle BUNDLE = FrameworkUtil.getBundle(HarnessPlugin.class);
	private static IPreferenceStore preferences;

	private HarnessPlugin() {
	}

	static synchronized IPreferenceStore preferences() {
		if (preferences == null) {
			preferences = BUNDLE != null ? new ScopedPreferenceStore(InstanceScope.INSTANCE, PREFERENCES)
					: new PreferenceStore();
			preferences.setDefault(PREF_OFFER_ECLIPSE_MCP, true);
			preferences.setDefault(PREF_ADD_ECLIPSE_MCP_WITHOUT_ASKING, false);
		}
		return preferences;
	}

	/** Runs later on the UI thread, unless Eclipse is closing. */
	static void later(Display display, Runnable runnable) {
		try {
			if (!display.isDisposed()) {
				display.asyncExec(runnable);
			}
		} catch (SWTException e) {
			// The display is gone: Eclipse is closing.
		}
	}

	static void log(String message, Throwable exception) {
		if (BUNDLE != null) {
			Platform.getLog(BUNDLE).error(message, exception);
		} else {
			// Outside of Eclipse (tests): the whole story, with the causes.
			System.err.println(message);
			if (exception != null) {
				exception.printStackTrace();
			}
		}
	}
}
