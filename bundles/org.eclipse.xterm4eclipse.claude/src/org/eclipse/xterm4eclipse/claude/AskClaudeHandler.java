package org.eclipse.xterm4eclipse.claude;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Tells a chat about the selection: the selected lines of an editor, or the selected files, as
 * references that Claude Code reads ({@code @src/Foo.java#L10-20}), added to the message that the
 * user writes. The chat is the last one used, if it works in the folder of the files; else a new
 * one in their project.
 */
public class AskClaudeHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchPart part = HandlerUtil.getActivePart(event);
		ISelection selection = HandlerUtil.getCurrentSelection(event);
		List<File> files = new ArrayList<>();
		String lines = null;
		if (part instanceof IEditorPart editor && selection instanceof ITextSelection text) {
			File file = file(editor.getEditorInput());
			if (file != null) {
				files.add(file);
				lines = lines(text);
			}
		} else if (selection instanceof IStructuredSelection structured) {
			for (Object element : structured) {
				File file = file(element);
				if (file != null) {
					files.add(file);
				}
			}
		}
		if (files.isEmpty()) {
			return null;
		}
		IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
		ChatView chat = ChatView.lastActive();
		if (chat == null || !contains(chat.directory(), files.get(0))) {
			File directory = Workspace.projectDirectory(part instanceof IEditorPart editor ? editor.getEditorInput()
					: ((IStructuredSelection) selection).getFirstElement());
			if (directory == null) {
				directory = files.get(0).isDirectory() ? files.get(0) : files.get(0).getParentFile();
			}
			try {
				chat = ChatView.open(page, directory, null);
			} catch (PartInitException e) {
				throw new ExecutionException("Could not open a chat with Claude Code", e); //$NON-NLS-1$
			}
		} else {
			page.activate(chat);
		}
		StringBuilder references = new StringBuilder();
		for (File file : files) {
			references.append(reference(chat.directory(), file, lines)).append(' ');
		}
		chat.insert(references.toString());
		return null;
	}

	/** @return the file of an element (a resource, an editor input), or {@code null} */
	static File file(Object element) {
		File file = Workspace.location(element);
		if (file != null) {
			return file;
		}
		IURIEditorInput input = Adapters.adapt(element, IURIEditorInput.class);
		URI uri = input == null ? null : input.getURI();
		if (uri != null && "file".equals(uri.getScheme())) { //$NON-NLS-1$
			try {
				return new File(uri);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
		return null;
	}

	/** @return the lines of a selection as Claude Code reads them ({@code #L10-20}), or {@code null} for none */
	static String lines(ITextSelection selection) {
		if (selection.getLength() == 0 || selection.getStartLine() < 0) {
			return null;
		}
		int start = selection.getStartLine() + 1;
		int end = selection.getEndLine() + 1;
		return start == end ? "#L" + start : "#L" + start + '-' + end; //$NON-NLS-1$ //$NON-NLS-2$
	}

	static boolean contains(File directory, File file) {
		return file.toPath().toAbsolutePath().normalize().startsWith(directory.toPath().toAbsolutePath().normalize());
	}

	/**
	 * @return the reference of a file for Claude Code: relative to the folder of the chat when it is
	 *         in it, between quotes when it has blanks
	 */
	static String reference(File directory, File file, String lines) {
		Path path = file.toPath().toAbsolutePath().normalize();
		Path base = directory.toPath().toAbsolutePath().normalize();
		String shown = path.startsWith(base) && !path.equals(base) ? base.relativize(path).toString() : path.toString();
		shown = shown.replace('\\', '/');
		if (lines != null) {
			shown += lines;
		}
		return shown.matches(".*\\s.*") ? "@\"" + shown + '"' : '@' + shown; //$NON-NLS-1$ //$NON-NLS-2$
	}
}
