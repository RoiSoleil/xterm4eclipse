package org.eclipse.xterm4eclipse.harness;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

/** Where the coding agents keep their files: in the home of the user, unless a variable says otherwise. */
final class Places {

	/** The environment of Eclipse; a test may change it. */
	static UnaryOperator<String> environment = System::getenv;
	/** The home of the user; a test may change it. */
	static Path userHome = Path.of(System.getProperty("user.home", "")); //$NON-NLS-1$ //$NON-NLS-2$

	private Places() {
	}

	/** @return the value of a variable of the environment, or {@code null} if it is not set */
	static String env(String name) {
		String value = environment.apply(name);
		return value == null || value.isBlank() ? null : value;
	}

	/**
	 * @param variable
	 *            the variable with which the user moves the folder, or {@code null}
	 * @param folder
	 *            the name of the folder in the home of the user, for example {@code .codex}
	 */
	static Path home(String variable, String folder) {
		String moved = variable == null ? null : env(variable);
		return moved != null ? Path.of(moved) : userHome.resolve(folder);
	}

	/** @return a folder of the configurations of the user: {@code ~/.config/name}, on every system */
	static Path config(String name) {
		String moved = env("XDG_CONFIG_HOME"); //$NON-NLS-1$
		return (moved != null ? Path.of(moved) : userHome.resolve(".config")).resolve(name); //$NON-NLS-1$
	}
}
