package org.eclipse.xterm4eclipse;

import java.io.File;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Opens a file printed in the terminal in an editor, at the given line. Isolated so that the view
 * still loads when the optional IDE bundles are absent.
 */
final class FileOpener {

	private FileOpener() {
	}

	/**
	 * @param line
	 *            1 for the first line, 0 to leave the editor where it is
	 * @param column
	 *            1 for the first column, 0 for the start of the line
	 */
	static void open(IWorkbenchPage page, File file, int line, int column) throws PartInitException {
		IEditorPart editor;
		IFile resource = workspaceFile(file);
		if (resource != null) {
			editor = IDE.openEditor(page, resource);
		} else {
			editor = IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().getStore(file.toURI()));
		}
		ITextEditor text = Adapters.adapt(editor, ITextEditor.class);
		if (line <= 0 || text == null || text.getDocumentProvider() == null) {
			return;
		}
		IDocument document = text.getDocumentProvider().getDocument(text.getEditorInput());
		if (document == null) {
			return;
		}
		try {
			int index = Math.min(line, document.getNumberOfLines()) - 1;
			int offset = document.getLineOffset(index);
			int length = document.getLineLength(index);
			text.selectAndReveal(offset + Math.max(0, Math.min(column - 1, length)), 0);
		} catch (BadLocationException e) {
			// The file changed since it was printed: the editor stays at the top.
		}
	}

	/** The file as a workspace resource, so that the editor knows its project. */
	private static IFile workspaceFile(File file) {
		try {
			for (IFile candidate : ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(file.toURI())) {
				if (candidate.isAccessible()) {
					return candidate;
				}
			}
		} catch (IllegalStateException | LinkageError e) {
			// No workspace: opened as a plain file.
		}
		return null;
	}
}
