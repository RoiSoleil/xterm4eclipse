package org.eclipse.xterm4eclipse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * What the plug-in needs from the Eclipse runtime: a place to store files, preferences, a log and
 * its own resources. Outside of OSGi (unit tests) plain Java replacements are used.
 */
final class XtermPlugin {

	static final String ID = "org.eclipse.xterm4eclipse"; //$NON-NLS-1$

	/** Command line of the shell of new terminals, empty for the shell of the system. */
	static final String PREF_DEFAULT_SHELL = "defaultShell"; //$NON-NLS-1$
	/** Whether the view takes the focus when a command finishes. */
	static final String PREF_FOCUS_ON_FINISH = "focusOnFinish"; //$NON-NLS-1$
	/** Whether the screen content is shown again after a restart of Eclipse. */
	static final String PREF_RESTORE_HISTORY = "restoreHistory"; //$NON-NLS-1$

	/** Key strokes that run their Eclipse command instead of going to the shell. */
	static final String PREF_ECLIPSE_SHORTCUTS = "eclipseShortcuts"; //$NON-NLS-1$
	/** Whether a paste of several lines is confirmed when the shell would run each line at once. */
	static final String PREF_WARN_MULTI_LINE_PASTE = "warnMultiLinePaste"; //$NON-NLS-1$
	/** Whether the selection goes to the clipboard as soon as it is made. */
	static final String PREF_COPY_ON_SELECT = "copyOnSelect"; //$NON-NLS-1$
	/** Font of the terminal, empty for the text font of Eclipse. */
	static final String PREF_FONT_FAMILY = "fontFamily"; //$NON-NLS-1$
	/** Font size in points, 0 for the one of the text font of Eclipse. */
	static final String PREF_FONT_SIZE = "fontSize"; //$NON-NLS-1$
	/** Lines kept above the screen. */
	static final String PREF_SCROLLBACK = "scrollback"; //$NON-NLS-1$
	/** block, underline or bar. */
	static final String PREF_CURSOR_STYLE = "cursorStyle"; //$NON-NLS-1$
	static final String PREF_CURSOR_BLINK = "cursorBlink"; //$NON-NLS-1$
	/** Whether the Option key of macOS is Meta (Alt+B, Alt+F... in the shell) rather than for accents. */
	static final String PREF_MAC_OPTION_IS_META = "macOptionIsMeta"; //$NON-NLS-1$
	/** Environment variables of the shells, see {@link ShellEnvironment}. */
	static final String PREF_ENVIRONMENT = "environment"; //$NON-NLS-1$

	private static final Bundle BUNDLE = FrameworkUtil.getBundle(XtermPlugin.class);
	private static IPreferenceStore preferences;
	private static final Map<String, Image> IMAGES = new HashMap<>();

	private XtermPlugin() {
	}

	/** A directory private to the plug-in, kept between Eclipse sessions. */
	static Path stateDirectory() {
		if (BUNDLE != null) {
			return Platform.getStateLocation(BUNDLE).toFile().toPath();
		}
		return Path.of(System.getProperty("xterm4eclipse.state", //$NON-NLS-1$
				System.getProperty("java.io.tmpdir") + "/xterm4eclipse")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	static String version() {
		return BUNDLE != null ? BUNDLE.getVersion().toString() : "dev"; //$NON-NLS-1$
	}

	/** The settings of the plug-in, shown in the preference page. */
	static synchronized IPreferenceStore preferences() {
		if (preferences == null) {
			preferences = BUNDLE != null ? new ScopedPreferenceStore(InstanceScope.INSTANCE, ID) : new PreferenceStore();
			preferences.setDefault(PREF_DEFAULT_SHELL, ""); //$NON-NLS-1$
			preferences.setDefault(PREF_FOCUS_ON_FINISH, false);
			preferences.setDefault(PREF_RESTORE_HISTORY, true);
			preferences.setDefault(PREF_ECLIPSE_SHORTCUTS, EclipseShortcuts.DEFAULTS);
			preferences.setDefault(PREF_WARN_MULTI_LINE_PASTE, true);
			preferences.setDefault(PREF_COPY_ON_SELECT, false);
			preferences.setDefault(PREF_FONT_FAMILY, ""); //$NON-NLS-1$
			preferences.setDefault(PREF_FONT_SIZE, 0);
			preferences.setDefault(PREF_SCROLLBACK, 10000);
			preferences.setDefault(PREF_CURSOR_STYLE, "block"); //$NON-NLS-1$
			preferences.setDefault(PREF_CURSOR_BLINK, true);
			preferences.setDefault(PREF_MAC_OPTION_IS_META, false);
			preferences.setDefault(PREF_ENVIRONMENT, ""); //$NON-NLS-1$
		}
		return preferences;
	}

	static String preference(String key) {
		return preferences().getString(key);
	}

	static boolean isEnabled(String key) {
		return preferences().getBoolean(key);
	}

	static void setPreference(String key, String value) {
		IPreferenceStore store = preferences();
		store.setValue(key, value);
		if (store instanceof ScopedPreferenceStore scoped) {
			try {
				scoped.save();
			} catch (IOException e) {
				// The value still applies until Eclipse exits.
			}
		}
	}

	static void log(String message, Throwable exception) {
		if (BUNDLE != null) {
			Platform.getLog(BUNDLE).error(message, exception);
		} else {
			System.err.println(message + ": " + exception); //$NON-NLS-1$
		}
	}

	/**
	 * An image of the plug-in, for example {@code icons/shells/bash.png}. Must be called on the UI
	 * thread; the image is shared and disposed with the display.
	 */
	static Image image(String path) {
		Image image = IMAGES.get(path);
		if (image == null || image.isDisposed()) {
			Image created = ImageDescriptor.createFromFile(XtermPlugin.class, '/' + path).createImage();
			Display.getCurrent().disposeExec(created::dispose);
			IMAGES.put(path, created);
			image = created;
		}
		return image;
	}

	/** Reads a text file of the plug-in, for example {@code web/index.html}. */
	static String resource(String path) throws IOException {
		try (InputStream in = XtermPlugin.class.getResourceAsStream('/' + path)) {
			if (in == null) {
				throw new IOException("Missing resource " + path); //$NON-NLS-1$
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
