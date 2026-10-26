package org.eclipse.xterm4eclipse.claude;

import java.io.File;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xterm4eclipse.spi.CommandLine;
import org.eclipse.xterm4eclipse.workbench.Terminals;

/**
 * Resumes a conversation that Claude Code has kept for the project of the selection, in a new
 * terminal: the same list as in the chat, and Claude Code resumes it in the terminal as
 * {@code claude --resume} does.
 */
public class ResumeInTerminalHandler extends AbstractHandler {

	/** Opens a terminal running a program. */
	interface Opener {
		void open(IWorkbenchPage page, String commandLine, File directory) throws PartInitException;
	}

	/** Opens the terminal; a test may change it. */
	static Opener opener = Terminals::open;

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
		IWorkbenchPage page = window.getActivePage();
		if (page != null) {
			resume(window.getShell(), page, directory(HandlerUtil.getActivePart(event), page));
		}
		return null;
	}

	/**
	 * @return where the terminal is, when the command runs from a terminal (its button): that is the
	 *         project whose conversations it resumes, not the one of the selection of the workbench
	 */
	static File directory(IWorkbenchPart active, IWorkbenchPage page) {
		File terminal = Terminals.directoryOf(active);
		return terminal != null && terminal.isDirectory() ? terminal : NewChatHandler.directory(page);
	}

	/** Asks which conversation of the folder to resume, and resumes it in a new terminal. */
	static void resume(Shell shell, IWorkbenchPage page, File directory) {
		Conversations.Conversation chosen = ResumeHandler.pick(shell, directory);
		if (chosen == null) {
			return;
		}
		// Only the ids of Claude Code, which are UUIDs: a command line takes nothing else for the argument of --resume.
		if (!ClaudeCommand.SESSION_ID.matcher(chosen.id()).matches()) {
			ChatPlugin.log("Did not resume the conversation " + chosen.id() + ": its id is not one of Claude Code", null); //$NON-NLS-1$ //$NON-NLS-2$
			return;
		}
		// The program of the preferences of the chat, or else the one that Eclipse finds.
		String configured = ChatPlugin.preference(ChatPlugin.PREF_PROGRAM);
		File program = ClaudeCommand.program(configured);
		if (program == null && configured != null && !configured.isBlank()) {
			MessageDialog.openError(shell, "Resume a Conversation", //$NON-NLS-1$
					"The program of Claude Code set in the preferences is not a file: " + configured.trim()); //$NON-NLS-1$
			return;
		}
		String commandLine = CommandLine.quote(program == null ? "claude" : program.getPath()) + " --resume " + chosen.id(); //$NON-NLS-1$ //$NON-NLS-2$
		try {
			opener.open(page, commandLine, directory);
		} catch (PartInitException e) {
			ChatPlugin.log("Could not open a terminal with Claude Code", e); //$NON-NLS-1$
		}
	}
}
