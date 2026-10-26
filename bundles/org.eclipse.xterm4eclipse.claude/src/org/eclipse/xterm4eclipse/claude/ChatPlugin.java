package org.eclipse.xterm4eclipse.claude;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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
 * What the plug-in needs from the Eclipse runtime: its preferences, a place to store files, a log
 * and its own resources. Outside of OSGi (unit tests) plain Java replacements are used.
 */
final class ChatPlugin {

	static final String ID = "org.eclipse.xterm4eclipse.claude"; //$NON-NLS-1$

	/** The program of Claude Code, empty to look for {@code claude} on the PATH. */
	static final String PREF_PROGRAM = "program"; //$NON-NLS-1$
	/** The model of new conversations, empty for the one that Claude Code chooses. */
	static final String PREF_MODEL = "model"; //$NON-NLS-1$
	/** The permission mode of new conversations, see {@link ClaudeCommand#PERMISSION_MODES}. */
	static final String PREF_PERMISSION_MODE = "permissionMode"; //$NON-NLS-1$
	/** More arguments for the program, separated by blanks. */
	static final String PREF_ARGUMENTS = "arguments"; //$NON-NLS-1$
	/** A new chat continues the last conversation of its folder, if there is one. */
	static final String PREF_CONTINUE_LAST = "continueLast"; //$NON-NLS-1$
	/** Whether a chat gives Claude Code the MCP server of Eclipse, when it runs. */
	static final String PREF_ECLIPSE_MCP = "eclipseMcp"; //$NON-NLS-1$

	private static final Bundle BUNDLE = FrameworkUtil.getBundle(ChatPlugin.class);
	private static IPreferenceStore preferences;

	private ChatPlugin() {
	}

	static synchronized IPreferenceStore preferences() {
		if (preferences == null) {
			preferences = BUNDLE != null ? new ScopedPreferenceStore(InstanceScope.INSTANCE, ID) : new PreferenceStore();
			preferences.setDefault(PREF_PROGRAM, ""); //$NON-NLS-1$
			preferences.setDefault(PREF_MODEL, ""); //$NON-NLS-1$
			preferences.setDefault(PREF_PERMISSION_MODE, ClaudeCommand.DEFAULT_MODE);
			preferences.setDefault(PREF_ARGUMENTS, ""); //$NON-NLS-1$
			preferences.setDefault(PREF_CONTINUE_LAST, false);
			preferences.setDefault(PREF_ECLIPSE_MCP, true);
		}
		return preferences;
	}

	static String preference(String key) {
		return preferences().getString(key);
	}

	/** A directory private to the plug-in, kept between Eclipse sessions. */
	static Path stateDirectory() {
		if (BUNDLE != null) {
			return Platform.getStateLocation(BUNDLE).toFile().toPath();
		}
		return Path.of(System.getProperty("xterm4eclipse.state", //$NON-NLS-1$
				System.getProperty("java.io.tmpdir") + "/xterm4eclipse")).resolve("claude"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	static String version() {
		return BUNDLE != null ? BUNDLE.getVersion().toString() : "dev"; //$NON-NLS-1$
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

	/** Reads a text file of the plug-in, for example {@code web/index.html}. */
	static String resource(String path) throws IOException {
		try (InputStream in = ChatPlugin.class.getResourceAsStream('/' + path)) {
			if (in == null) {
				throw new IOException("Missing resource " + path); //$NON-NLS-1$
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
