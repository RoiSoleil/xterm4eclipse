package org.eclipse.xterm4eclipse.claude;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Opens a chat with Claude Code in the project of the selected resource, or else of the file of the
 * active editor: a new conversation, or the last one of the project if the preferences say so.
 */
public class NewChatHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
		IWorkbenchPage page = window.getActivePage();
		File directory = directory(page);
		String sessionId = null;
		if (ChatPlugin.preferences().getBoolean(ChatPlugin.PREF_CONTINUE_LAST)) {
			Conversations.Conversation latest = Conversations.latest(directory);
			sessionId = latest == null ? null : latest.id();
		}
		try {
			ChatView.open(page, directory, sessionId);
		} catch (PartInitException e) {
			throw new ExecutionException("Could not open a chat with Claude Code", e); //$NON-NLS-1$
		}
		return null;
	}

	/**
	 * Where a chat works: the project of the selected resource, or else of the file of the active
	 * editor (a file outside of the workspace: its folder), or else the home of the user.
	 */
	static File directory(IWorkbenchPage page) {
		List<Object> candidates = new ArrayList<>();
		ISelection selection = page == null ? null : page.getSelection();
		if (selection instanceof IStructuredSelection structured && !structured.isEmpty()) {
			candidates.add(structured.getFirstElement());
		}
		IEditorPart editor = page == null ? null : page.getActiveEditor();
		if (editor != null && editor.getEditorInput() != null) {
			candidates.add(editor.getEditorInput());
		}
		for (Object candidate : candidates) {
			File directory = Workspace.projectDirectory(candidate);
			if (directory == null) {
				File file = AskClaudeHandler.file(candidate);
				directory = file == null ? null : file.getParentFile();
			}
			if (directory != null && directory.isDirectory()) {
				return directory;
			}
		}
		return new File(System.getProperty("user.home")); //$NON-NLS-1$
	}
}
