package org.eclipse.xterm4eclipse.claude;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IWorkbenchPage;

/** Shows a change of Claude Code to a file in the compare editor of Eclipse: the file before, and now. */
final class ChangeCompare {

	private ChangeCompare() {
	}

	/**
	 * @param before
	 *            the content of the file before the change, empty for a new file
	 */
	static void open(IWorkbenchPage page, File file, String before) {
		String now;
		try {
			// As Claude Code reads it; not an error for a file in another encoding.
			now = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		} catch (IOException e) {
			// Deleted since: all of it is gone.
			now = ""; //$NON-NLS-1$
		}
		CompareConfiguration configuration = new CompareConfiguration();
		configuration.setLeftLabel(file.getName() + " (now)"); //$NON-NLS-1$
		configuration.setRightLabel(file.getName() + " (before Claude Code)"); //$NON-NLS-1$
		configuration.setLeftEditable(false);
		configuration.setRightEditable(false);
		String extension = file.getName().contains(".") ? file.getName().substring(file.getName().lastIndexOf('.') + 1) //$NON-NLS-1$
				: ITypedElement.TEXT_TYPE;
		Text left = new Text(file.getName(), extension, now);
		Text right = new Text(file.getName(), extension, before);
		CompareEditorInput input = new CompareEditorInput(configuration) {
			@Override
			protected Object prepareInput(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
				return new DiffNode(left, right);
			}
		};
		input.setTitle("Change of Claude Code to " + file.getName()); //$NON-NLS-1$
		CompareUI.openCompareEditorOnPage(input, page);
	}

	/** A text to compare. */
	private record Text(String name, String type, String content) implements ITypedElement, IStreamContentAccessor {

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Image getImage() {
			return null;
		}

		@Override
		public String getType() {
			return type;
		}

		@Override
		public InputStream getContents() {
			return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
		}
	}
}
