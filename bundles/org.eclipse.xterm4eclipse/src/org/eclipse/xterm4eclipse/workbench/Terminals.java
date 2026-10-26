package org.eclipse.xterm4eclipse.workbench;

import java.io.File;

import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.xterm4eclipse.XtermView;

/** Opens terminals for the other plug-ins, which have no access to the view of the terminal. */
public final class Terminals {

	private Terminals() {
	}

	/**
	 * Opens one more terminal in the page, running a program.
	 *
	 * @param commandLine
	 *            the program and its arguments, as typed in a shell: an argument with spaces is
	 *            written between double quotes
	 * @param directory
	 *            where the program starts, or {@code null} for the project of the selection
	 */
	public static void open(IWorkbenchPage page, String commandLine, File directory) throws PartInitException {
		XtermView.open(page, commandLine, directory);
	}
}
