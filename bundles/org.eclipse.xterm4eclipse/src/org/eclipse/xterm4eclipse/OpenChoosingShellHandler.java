package org.eclipse.xterm4eclipse;

import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * The "Open an Xterm Terminal Choosing the Shell" command: asks which of the shells found on this
 * system to run in a new terminal.
 */
public class OpenChoosingShellHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
		if (page == null) {
			return null;
		}
		List<ShellProfiles.Profile> profiles = ShellProfiles.detect();
		// No shell found: nothing to choose from, the default one is the only one to try
		ShellProfiles.Profile profile = profiles.isEmpty() ? new ShellProfiles.Profile("", ShellProfiles.defaultCommandLine()) //$NON-NLS-1$
				: choose(HandlerUtil.getActiveShell(event), profiles);
		if (profile != null) {
			try {
				XtermView.open(page, profile.commandLine());
			} catch (PartInitException e) {
				throw new ExecutionException("Could not open a terminal", e); //$NON-NLS-1$
			}
		}
		return null;
	}

	/** @return the shell the user chose, or {@code null} if the dialog was cancelled */
	private static ShellProfiles.Profile choose(Shell parent, List<ShellProfiles.Profile> profiles) {
		ElementListSelectionDialog dialog = new ElementListSelectionDialog(parent, new ProfileLabels());
		dialog.setTitle("Xterm"); //$NON-NLS-1$
		dialog.setMessage("Select the shell to open:"); //$NON-NLS-1$
		dialog.setElements(profiles.toArray());
		dialog.setMultipleSelection(false);
		if (!profiles.isEmpty()) {
			dialog.setInitialSelections(profiles.get(0));
		}
		return dialog.open() == Window.OK ? (ShellProfiles.Profile) dialog.getFirstResult() : null;
	}

	/** The shells of the dialog: their name, and the icon they have in the menus. */
	static final class ProfileLabels extends LabelProvider {

		@Override
		public String getText(Object element) {
			return ((ShellProfiles.Profile) element).name();
		}

		@Override
		public Image getImage(Object element) {
			return XtermPlugin.image(((ShellProfiles.Profile) element).terminalIcon());
		}
	}
}
