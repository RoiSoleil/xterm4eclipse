package org.eclipse.xterm4eclipse;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.e4.ui.model.application.ui.MElementContainer;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.advanced.MArea;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspective;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.model.application.ui.basic.MStackElement;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPartSite;

/**
 * Moves a view between the editor area and the stacks of views, in the model of the Eclipse 4
 * workbench, as dragging its tab does: the view stays the same, with its widgets, its shell and its
 * screen. Isolated so that the plug-in still loads without the Eclipse 4 model.
 */
final class PartMover {

	/** Persisted state of the part: the stack it came from, to go back there. */
	static final String ORIGIN = "org.eclipse.xterm4eclipse.origin"; //$NON-NLS-1$
	/** Views next to which a terminal goes back when the stack it came from is gone. */
	private static final String[] NEIGHBOURS = {XtermView.ID, "org.eclipse.ui.console.ConsoleView", //$NON-NLS-1$
			"org.eclipse.ui.views.ProblemView"}; //$NON-NLS-1$

	private PartMover() {
	}

	/** @return {@code true} if the part of the site is in the editor area */
	static boolean isInEditorArea(IWorkbenchPartSite site) {
		MPart part = part(site);
		return part != null && area(element(part)) != null;
	}

	/** @return {@code true} if the part of the site can go to the other side */
	static boolean canMove(IWorkbenchPartSite site) {
		MPart part = part(site);
		if (part == null) {
			return false;
		}
		MUIElement element = element(part);
		if (!(element instanceof MStackElement) || !((Object) element.getParent() instanceof MPartStack)) {
			return false;
		}
		return area(element) != null ? viewStack(element, part) != null : editorStack(site, element) != null;
	}

	/**
	 * Moves the part of the site to the editor area, next to the active editor.
	 *
	 * @return {@code true} if it moved
	 */
	static boolean toEditorArea(IWorkbenchPartSite site) {
		MPart part = part(site);
		if (part == null || area(element(part)) != null) {
			return false;
		}
		MUIElement element = element(part);
		MPartStack target = editorStack(site, element);
		if (target == null || !(element instanceof MStackElement)) {
			return false;
		}
		String origin = element.getParent() == null ? null : element.getParent().getElementId();
		if (origin != null) {
			part.getPersistedState().put(ORIGIN, origin);
		} else {
			part.getPersistedState().remove(ORIGIN);
		}
		moveTo((MStackElement) element, target);
		return true;
	}

	/**
	 * Moves the part of the site from the editor area back to the stack it came from, or next to
	 * other terminals or the console if that stack is gone.
	 *
	 * @return {@code true} if it moved
	 */
	static boolean toViews(IWorkbenchPartSite site) {
		MPart part = part(site);
		if (part == null || area(element(part)) == null) {
			return false;
		}
		MUIElement element = element(part);
		MPartStack target = viewStack(element, part);
		if (target == null || !(element instanceof MStackElement)) {
			return false;
		}
		part.getPersistedState().remove(ORIGIN);
		moveTo((MStackElement) element, target);
		return true;
	}

	private static MPart part(IWorkbenchPartSite site) {
		// Not typed: a site may hand out anything.
		Object service = site.getService((Class<?>) MPart.class);
		return service instanceof MPart part ? part : null;
	}

	/** What stands for the part in its stack: the placeholder of a view, or the part itself. */
	private static MUIElement element(MPart part) {
		MPlaceholder reference = part.getCurSharedRef();
		return reference != null ? reference : part;
	}

	/** @return the editor area holding the element, or {@code null} */
	private static MArea area(MUIElement element) {
		for (MUIElement current = element; current != null; current = current.getParent()) {
			if (current instanceof MArea area) {
				return area;
			}
		}
		return null;
	}

	/** @return the perspective the element is shown in, or {@code null} */
	private static MPerspective perspective(MUIElement element) {
		MUIElement current = element;
		while (current != null) {
			if (current instanceof MPerspective perspective) {
				return perspective;
			}
			// The editor area is shared by the perspectives: go on from where it is shown.
			current = current instanceof MArea area && area.getCurSharedRef() != null ? area.getCurSharedRef()
					: current.getParent();
		}
		return null;
	}

	/** The stack of the editor area the part goes to: the one of the active editor, or the first one. */
	private static MPartStack editorStack(IWorkbenchPartSite site, MUIElement element) {
		MPerspective perspective = perspective(element);
		MArea area = null;
		for (MPlaceholder placeholder : descendants(perspective, MPlaceholder.class)) {
			if (placeholder.getRef() instanceof MArea found) {
				area = found;
			}
		}
		if (area == null) {
			return null;
		}
		IEditorPart activeEditor = site.getPage() == null ? null : site.getPage().getActiveEditor();
		MPart editor = activeEditor == null ? null : part(activeEditor.getSite());
		if (editor != null && (Object) editor.getParent() instanceof MPartStack stack && area(stack) == area) {
			return stack;
		}
		List<MPartStack> stacks = descendants(area, MPartStack.class);
		for (MPartStack stack : stacks) {
			if (stack.isToBeRendered() && stack.isVisible()) {
				return stack;
			}
		}
		return stacks.isEmpty() ? null : stacks.get(0);
	}

	/** The stack of views the part goes back to. */
	private static MPartStack viewStack(MUIElement element, MPart part) {
		MPerspective perspective = perspective(element);
		// The perspective itself, not the editor area it shows: placeholders are not containers.
		List<MPartStack> stacks = descendants(perspective, MPartStack.class);
		String origin = part.getPersistedState().get(ORIGIN);
		if (origin != null) {
			for (MPartStack stack : stacks) {
				if (origin.equals(stack.getElementId())) {
					return stack;
				}
			}
		}
		for (String neighbour : NEIGHBOURS) {
			for (MPartStack stack : stacks) {
				for (MStackElement child : stack.getChildren()) {
					String id = child.getElementId();
					if (child != element && id != null && (id.equals(neighbour) || id.startsWith(neighbour + ':'))) {
						return stack;
					}
				}
			}
		}
		for (MPartStack stack : stacks) {
			if (stack.isToBeRendered()) {
				return stack;
			}
		}
		return stacks.isEmpty() ? null : stacks.get(0);
	}

	/** Moves the element to the end of the stack and shows it there, as a drop on the stack does. */
	private static void moveTo(MStackElement element, MPartStack stack) {
		MElementContainer<MUIElement> parent = element.getParent();
		if (parent != null) {
			parent.getChildren().remove(element);
		}
		// A stack left empty is hidden by the workbench; it shows again with the part.
		stack.setToBeRendered(true);
		stack.setVisible(true);
		element.setToBeRendered(true);
		element.setVisible(true);
		stack.getChildren().add(element);
		stack.setSelectedElement(element);
	}

	/** All the elements of the type under the container, depth first. */
	private static <T> List<T> descendants(MUIElement container, Class<T> type) {
		List<T> result = new ArrayList<>();
		collect(container, type, result);
		return result;
	}

	private static <T> void collect(MUIElement element, Class<T> type, List<T> result) {
		if (element == null) {
			return;
		}
		if (type.isInstance(element)) {
			result.add(type.cast(element));
		}
		if (element instanceof MElementContainer<?> container) {
			for (Object child : container.getChildren()) {
				collect((MUIElement) child, type, result);
			}
		}
	}
}
