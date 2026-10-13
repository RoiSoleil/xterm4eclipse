package org.eclipse.xterm4eclipse;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IRegistryEventListener;
import org.eclipse.core.runtime.Platform;
import org.eclipse.xterm4eclipse.spi.Terminal;
import org.eclipse.xterm4eclipse.spi.TerminalCompanion;
import org.eclipse.xterm4eclipse.spi.TerminalExtension;
import org.eclipse.xterm4eclipse.spi.TerminalHost;
import org.eclipse.xterm4eclipse.spi.TerminalIcon;
import org.eclipse.xterm4eclipse.spi.TerminalProfile;

/**
 * The extensions of the terminal that other plug-ins bring, if any is installed: for example the
 * coding agents (Claude Code, Codex...). An extension that fails is logged and left out: the
 * terminal works without it.
 */
final class TerminalExtensions {

	static final String POINT = XtermPlugin.ID + ".terminalExtensions"; //$NON-NLS-1$

	private static List<TerminalExtension> extensions;
	private static boolean listening;

	private TerminalExtensions() {
	}

	static synchronized List<TerminalExtension> all() {
		if (extensions == null) {
			extensions = load();
		}
		return extensions;
	}

	/** The extensions to use instead of the installed ones, for a test; {@code null} for the installed ones. */
	static synchronized void set(List<TerminalExtension> replacement) {
		extensions = replacement == null ? null : List.copyOf(replacement);
	}

	private static List<TerminalExtension> load() {
		List<TerminalExtension> found = new ArrayList<>();
		if (!XtermPlugin.inEclipse()) {
			// Outside of Eclipse (tests): the extensions of the class path.
			ServiceLoader.load(TerminalExtension.class, TerminalExtensions.class.getClassLoader()).forEach(found::add);
			return List.copyOf(found);
		}
		IExtensionRegistry registry = Platform.getExtensionRegistry();
		if (registry == null) {
			return List.of();
		}
		if (!listening) {
			// A plug-in installed or removed while Eclipse runs.
			registry.addListener(new IRegistryEventListener() {
				@Override
				public void added(IExtension[] added) {
					set(null);
				}

				@Override
				public void removed(IExtension[] removed) {
					set(null);
				}

				@Override
				public void added(IExtensionPoint[] added) {
					// Only this plug-in declares the point.
				}

				@Override
				public void removed(IExtensionPoint[] removed) {
					// Only this plug-in declares the point.
				}
			}, POINT);
			listening = true;
		}
		for (IConfigurationElement element : registry.getConfigurationElementsFor(POINT)) {
			try {
				if (element.createExecutableExtension("class") instanceof TerminalExtension extension) { //$NON-NLS-1$
					found.add(extension);
				}
			} catch (CoreException | RuntimeException | LinkageError e) {
				XtermPlugin.log("Could not load the terminal extension of " + element.getContributor().getName(), e); //$NON-NLS-1$
			}
		}
		return List.copyOf(found);
	}

	/** @return the programs that the extensions offer in the menus */
	static List<TerminalProfile> profiles(TerminalHost host) {
		List<TerminalProfile> profiles = new ArrayList<>();
		for (TerminalExtension extension : all()) {
			try {
				profiles.addAll(extension.profiles(host));
			} catch (RuntimeException | LinkageError e) {
				failed(extension, e);
			}
		}
		return profiles;
	}

	/** @return the icon that an extension gives to the program of the command line, or {@code null} */
	static TerminalIcon icon(String commandLine) {
		for (TerminalExtension extension : all()) {
			try {
				TerminalIcon icon = extension.icon(commandLine);
				if (icon != null) {
					return icon;
				}
			} catch (RuntimeException | LinkageError e) {
				failed(extension, e);
			}
		}
		return null;
	}

	/** @return what the extension that knows the program of the terminal does in it, or {@code null} */
	static TerminalCompanion accompany(Terminal terminal) {
		for (TerminalExtension extension : all()) {
			try {
				TerminalCompanion companion = extension.accompany(terminal);
				if (companion != null) {
					return companion;
				}
			} catch (RuntimeException | LinkageError e) {
				failed(extension, e);
			}
		}
		return null;
	}

	static void failed(Object extension, Throwable exception) {
		XtermPlugin.log("The terminal extension " + extension.getClass().getName() + " failed", exception); //$NON-NLS-1$ //$NON-NLS-2$
	}
}
