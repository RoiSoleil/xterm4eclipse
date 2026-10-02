package org.eclipse.xterm4eclipse;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * "Run Selected Text in Terminal": sends the selection of the editor, or the line of the cursor when
 * nothing is selected, to the last active terminal and runs it, like the command of the same name in
 * VS Code.
 */
public class RunSelectionHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		String text = textToRun(HandlerUtil.getCurrentSelection(event), HandlerUtil.getActivePart(event));
		IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
		if (text == null || text.isBlank() || page == null) {
			return null;
		}
		try {
			XtermView.run(page, text);
		} catch (PartInitException e) {
			throw new ExecutionException("Could not open a terminal", e); //$NON-NLS-1$
		}
		return null;
	}

	/**
	 * @return the selected text, or the line of the cursor if the selection is empty, or {@code null}
	 *         if the selection is not in a text
	 */
	static String textToRun(ISelection selection, IWorkbenchPart part) {
		if (!(selection instanceof ITextSelection textSelection)) {
			return null;
		}
		if (textSelection.getLength() > 0) {
			return textSelection.getText();
		}
		ITextEditor editor = Adapters.adapt(part, ITextEditor.class);
		if (editor == null || editor.getDocumentProvider() == null) {
			return null;
		}
		IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
		if (document == null) {
			return null;
		}
		try {
			IRegion line = document.getLineInformationOfOffset(textSelection.getOffset());
			return document.get(line.getOffset(), line.getLength());
		} catch (BadLocationException e) {
			return null;
		}
	}
}
