package org.eclipse.xterm4eclipse;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.menus.IWorkbenchContribution;
import org.eclipse.ui.services.IServiceLocator;

/**
 * The entries of the "Show in Xterm" context menu: one per detected shell. Choosing one opens a
 * terminal running that shell in the directory of the selected resource.
 */
public class ShowInXtermMenu extends CompoundContributionItem implements IWorkbenchContribution {

	private IServiceLocator services;

	@Override
	public void initialize(IServiceLocator serviceLocator) {
		services = serviceLocator;
	}

	@Override
	protected IContributionItem[] getContributionItems() {
		List<IContributionItem> items = new ArrayList<>();
		for (ShellProfiles.Profile profile : ShellProfiles.detect()) {
			items.add(new ContributionItem() {
				@Override
				public void fill(Menu menu, int index) {
					MenuItem item = new MenuItem(menu, SWT.PUSH, index);
					item.setText(profile.name());
					item.setImage(XtermPlugin.image(profile.icon()));
					item.addListener(SWT.Selection, event -> show(profile));
				}
			});
		}
		return items.toArray(IContributionItem[]::new);
	}

	private void show(ShellProfiles.Profile profile) {
		IWorkbenchWindow window = services.getService(IWorkbenchWindow.class);
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null) {
			return;
		}
		ISelection selection = window.getSelectionService().getSelection();
		File directory = selection instanceof IStructuredSelection structured && !structured.isEmpty()
				? XtermView.directoryOf(structured.getFirstElement())
				: null;
		try {
			XtermView.open(page, profile.commandLine(), directory);
		} catch (PartInitException e) {
			XtermPlugin.log("Could not open a terminal", e); //$NON-NLS-1$
		}
	}
}
