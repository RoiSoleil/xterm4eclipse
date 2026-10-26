package org.eclipse.xterm4eclipse.workbench;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartSite;

/**
 * The button of a view that moves it to the editor area, or back among the other views: the
 * terminal and the chat with Claude Code have the same. It moves the view as it is (same shell,
 * same screen), like dragging its tab.
 */
public final class MoveToEditorAreaAction extends Action {

	private final IWorkbenchPart part;
	private final String viewId;
	private final String noun;

	/**
	 * @param part
	 *            the view, which is activated again after a move
	 * @param viewId
	 *            the id of the view, which goes back next to the views like it
	 * @param noun
	 *            what the view is, in the tooltip: "terminal", "chat"
	 */
	public MoveToEditorAreaAction(IWorkbenchPart part, String viewId, String noun) {
		this.part = part;
		this.viewId = viewId;
		this.noun = noun;
		update();
	}

	private IWorkbenchPartSite site() {
		return part.getSite();
	}

	@Override
	public void run() {
		move(!isInEditorArea());
	}

	/** @return {@code true} if the view is in the editor area of the window */
	public boolean isInEditorArea() {
		try {
			return PartMover.isInEditorArea(site());
		} catch (LinkageError | RuntimeException e) {
			// Not in an Eclipse 4 workbench.
			return false;
		}
	}

	private boolean canMove() {
		try {
			return PartMover.canMove(site(), viewId);
		} catch (LinkageError | RuntimeException e) {
			return false;
		}
	}

	/** Moves the view to the editor area, next to the active editor. */
	public void moveToEditorArea() {
		move(true);
	}

	/** Moves the view back among the views, where it came from if that place still exists. */
	public void moveToViews() {
		move(false);
	}

	private void move(boolean toEditorArea) {
		boolean moved;
		try {
			moved = toEditorArea ? PartMover.toEditorArea(site()) : PartMover.toViews(site(), viewId);
		} catch (LinkageError | RuntimeException e) {
			ILog.of(MoveToEditorAreaAction.class).log(Status.error("Could not move the " + noun, e)); //$NON-NLS-1$
			moved = false;
		}
		if (moved) {
			site().getPage().activate(part);
		}
		update();
	}

	/**
	 * Shows what fits where the view is now: move to the editor area, or back among the other
	 * views. To be called when the view may have moved, such as when the user activates it.
	 */
	public void update() {
		boolean inEditorArea = isInEditorArea();
		setText(inEditorArea ? "Move Back to the Views" : "Move to the Editor Area"); //$NON-NLS-1$ //$NON-NLS-2$
		setToolTipText(inEditorArea ? "Move this " + noun + " back among the views" //$NON-NLS-1$ //$NON-NLS-2$
				: "Move this " + noun + " to the editor area, next to the editors"); //$NON-NLS-1$ //$NON-NLS-2$
		setImageDescriptor(ImageDescriptor.createFromFile(MoveToEditorAreaAction.class,
				inEditorArea ? "/icons/move-to-view.png" : "/icons/move-to-editor.png")); //$NON-NLS-1$ //$NON-NLS-2$
		setEnabled(canMove());
	}
}
