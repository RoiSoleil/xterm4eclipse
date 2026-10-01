package org.eclipse.xterm4eclipse;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * The "Open an Xterm Terminal" command of the main toolbar: opens a new terminal running the default
 * shell.
 */
public class OpenTerminalHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
		if (page != null) {
			try {
				XtermView.open(page, ShellProfiles.defaultCommandLine());
			} catch (PartInitException e) {
				throw new ExecutionException("Could not open a terminal", e); //$NON-NLS-1$
			}
		}
		return null;
	}
}
