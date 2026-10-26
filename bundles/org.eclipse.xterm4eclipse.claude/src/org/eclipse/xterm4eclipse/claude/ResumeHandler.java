package org.eclipse.xterm4eclipse.claude;

import java.io.File;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Resumes a conversation that Claude Code has kept for the project of the selection: of a chat, of
 * a terminal or of Claude Desktop.
 */
public class ResumeHandler extends AbstractHandler {

	/** Asks which conversation to resume. */
	interface Chooser {
		/** @return the conversation, or {@code null} for none */
		Conversations.Conversation choose(Shell shell, File directory, List<Conversations.Conversation> conversations);
	}

	/** In a dialog; a test may change it. */
	static Chooser chooser = ResumeHandler::choose;

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
		Conversations.Conversation chosen = chooser.choose(shell, directory, conversations);
		if (chosen == null) {
			return;
		}
		try {
			ChatView.open(page, directory, chosen.id());
		} catch (PartInitException e) {
			ChatPlugin.log("Could not open a chat with Claude Code", e); //$NON-NLS-1$
		}
	}

	private static Conversations.Conversation choose(Shell shell, File directory, List<Conversations.Conversation> conversations) {
		ConversationDialog dialog = new ConversationDialog(shell, directory, conversations);
		return dialog.open() == Window.OK ? dialog.chosen() : null;
	}
}
