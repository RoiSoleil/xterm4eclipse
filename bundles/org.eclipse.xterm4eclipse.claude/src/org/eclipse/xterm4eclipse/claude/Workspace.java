package org.eclipse.xterm4eclipse.claude;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

/** The files of the workspace that a chat talks about: opened in an editor, refreshed after a change. */
final class Workspace {

	static final String TEXT_EDITOR = "org.eclipse.ui.DefaultTextEditor"; //$NON-NLS-1$

	private Workspace() {
	}

	/**
	 * Opens a file in an editor of Eclipse, never in a program of the system: a path written by
	 * Claude Code must never start a program, as an {@code .exe} associated with itself would.
	 *
	 * @param line
	 *            1 for the first line, 0 to leave the editor where it is
	 */
	static void open(IWorkbenchPage page, File file, int line) throws PartInitException {
		IEditorPart editor;
		IFile resource = file(file);
		if (resource != null) {
			editor = IDE.openEditor(page, resource, editorId(IDE.getEditorDescriptor(resource, true, false)));
		} else {
			editor = page.openEditor(new FileStoreEditorInput(EFS.getLocalFileSystem().getStore(file.toURI())),
					editorId(IDE.getEditorDescriptor(file.getName(), true, false)));
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
			text.selectAndReveal(document.getLineOffset(index), 0);
		} catch (BadLocationException e) {
			// The file changed since: the editor stays at the top.
		}
	}

	static String editorId(IEditorDescriptor descriptor) {
		if (descriptor == null || descriptor.isOpenExternal() || descriptor.isOpenInPlace()) {
			return TEXT_EDITOR;
		}
		return descriptor.getId();
	}

	/** @return the file as a resource of the workspace, or {@code null} */
	static IFile file(File file) {
		try {
			for (IFile candidate : root().findFilesForLocationURI(file.toURI())) {
				if (candidate.isAccessible()) {
					return candidate;
				}
			}
		} catch (IllegalStateException e) {
			// No workspace.
		}
		return null;
	}

	/** @return the location of the project of an element (a resource, an editor input...), or {@code null} */
	static File projectDirectory(Object element) {
		IResource resource = Adapters.adapt(element, IResource.class);
		IPath location = resource == null || resource.getProject() == null ? null : resource.getProject().getLocation();
		return location == null ? null : location.toFile();
	}

	/** @return the location of a resource, or {@code null} */
	static File location(Object element) {
		IResource resource = Adapters.adapt(element, IResource.class);
		IPath location = resource == null ? null : resource.getLocation();
		return location == null ? null : location.toFile();
	}

	/**
	 * Refreshes what the workspace knows of a file, or of all that is in a folder, in the
	 * background: Claude Code changed it.
	 */
	static void refresh(File file, boolean folder) {
		List<IResource> resources = new ArrayList<>();
		try {
			if (folder) {
				for (IContainer container : root().findContainersForLocationURI(file.toURI())) {
					resources.add(container);
				}
			} else {
				for (IFile candidate : root().findFilesForLocationURI(file.toURI())) {
					// A new file is not in the workspace yet: its folder is.
					resources.add(candidate.exists() ? candidate : candidate.getParent());
				}
			}
		} catch (IllegalStateException e) {
			// No workspace.
			return;
		}
		if (resources.isEmpty()) {
			return;
		}
		Job job = Job.create("Refreshing the files changed by Claude Code", (IProgressMonitor monitor) -> { //$NON-NLS-1$
			for (IResource resource : resources) {
				try {
					resource.refreshLocal(IResource.DEPTH_INFINITE, monitor);
				} catch (CoreException e) {
					return e.getStatus();
				}
			}
			return Status.OK_STATUS;
		});
		job.setSystem(true);
		job.setPriority(Job.SHORT);
		job.schedule();
	}

	private static IWorkspaceRoot root() {
		return ResourcesPlugin.getWorkspace().getRoot();
	}
}
