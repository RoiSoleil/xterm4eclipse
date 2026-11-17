package org.eclipse.xterm4eclipse.claude;

import java.io.File;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

public class FindConversationHandler extends AbstractHandler {

	interface Finder {
		ConversationDialog open(Shell shell, File directory, String text);
	}

	static Finder finder = FindConversationHandler::openDialog;

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
		IWorkbenchPage page = window.getActivePage();
		if (page != null) {
			find(window.getShell(), page, NewChatHandler.directory(page), text(HandlerUtil.getCurrentSelection(event)));
		}
		return null;
	}

	static String text(ISelection selection) {
		if (selection instanceof ITextSelection text && text.getText() != null && !text.getText().isBlank()) {
			String value = text.getText().strip();
			int newline = value.indexOf('\n');
			return newline < 0 ? value : value.substring(0, newline).strip();
		}
		if (selection instanceof IStructuredSelection structured && !structured.isEmpty()) {
			IResource resource = Adapters.adapt(structured.getFirstElement(), IResource.class);
			if (resource != null && resource.getType() != IResource.ROOT) {
				return resource.getType() == IResource.PROJECT ? resource.getName()
						: resource.getProjectRelativePath().toString();
			}
		}
		return "";
	}

	static void find(Shell shell, IWorkbenchPage page, File directory, String text) {
		ConversationDialog dialog = finder.open(shell, directory, text);
		if (dialog == null || dialog.getReturnCode() != Window.OK || dialog.chosen() == null) {
			return;
		}
		if (dialog.inTerminal()) {
			ResumeInTerminalHandler.open(shell, page, dialog.chosen(), directory);
		} else {
			ResumeHandler.openChat(shell, page, dialog.chosen(), directory);
		}
	}

	private static ConversationDialog openDialog(Shell shell, File directory, String text) {
		ConversationDialog dialog = new ConversationDialog(shell, directory, Conversations.list(directory), text, true, true);
		dialog.open();
		return dialog;
	}
}
