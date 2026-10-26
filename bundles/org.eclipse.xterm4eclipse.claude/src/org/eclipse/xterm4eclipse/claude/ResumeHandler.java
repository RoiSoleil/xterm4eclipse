package org.eclipse.xterm4eclipse.claude;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Resumes a conversation that Claude Code has kept for the project of the selection: of a chat, of
 * a terminal or of Claude Desktop.
 */
public class ResumeHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
		IWorkbenchPage page = window.getActivePage();
		resume(window.getShell(), page, NewChatHandler.directory(page));
		return null;
	}

	/** Asks which conversation of the folder to resume, and opens it in a chat. */
	static void resume(Shell shell, IWorkbenchPage page, File directory) {
		List<Conversations.Conversation> conversations = Conversations.list(directory);
		if (conversations.isEmpty()) {
			MessageDialog.openInformation(shell, "Resume a Conversation", //$NON-NLS-1$
					"Claude Code has kept no conversation for " + directory.getPath() + '.'); //$NON-NLS-1$
			return;
		}
		DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
		ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider() {
			@Override
			public String getText(Object element) {
				Conversations.Conversation conversation = (Conversations.Conversation) element;
				return conversation.title() + "  —  " + format.format(new Date(conversation.modified())); //$NON-NLS-1$
			}
		});
		dialog.setTitle("Resume a Conversation"); //$NON-NLS-1$
		dialog.setMessage("Conversations of Claude Code in " + directory.getPath() + ':'); //$NON-NLS-1$
		dialog.setElements(conversations.toArray());
		dialog.setMultipleSelection(false);
		// The list keeps the order of the elements: the latest first.
		dialog.setIgnoreCase(true);
		dialog.setMatchEmptyString(true);
		if (dialog.open() != ElementListSelectionDialog.OK || dialog.getFirstResult() == null) {
			return;
		}
		Conversations.Conversation chosen = (Conversations.Conversation) dialog.getFirstResult();
		try {
			ChatView.open(page, directory, chosen.id());
		} catch (PartInitException e) {
			ChatPlugin.log("Could not open a chat with Claude Code", e); //$NON-NLS-1$
		}
	}
}
