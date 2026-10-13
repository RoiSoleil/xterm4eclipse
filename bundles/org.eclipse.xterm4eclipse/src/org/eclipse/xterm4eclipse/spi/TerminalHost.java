package org.eclipse.xterm4eclipse.spi;

import java.io.File;

/** The machine the shells and programs are looked up on. */
public interface TerminalHost {

	boolean isWindows();

	boolean isMac();

	/** @return the value of an environment variable, or {@code null} */
	String env(String name);

	/**
	 * Looks a program up in the absolute directories of the PATH only: an empty or relative entry is
	 * the current directory of Eclipse, where anybody could have left a program of the same name.
	 *
	 * @param executables
	 *            file names, by order of preference in each directory
	 * @return the program, or {@code null}
	 */
	File findOnPath(String... executables);
}
