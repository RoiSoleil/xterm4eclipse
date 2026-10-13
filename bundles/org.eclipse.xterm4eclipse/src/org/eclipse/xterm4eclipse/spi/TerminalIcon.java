package org.eclipse.xterm4eclipse.spi;

import org.eclipse.jface.resource.ImageDescriptor;

/**
 * An image of a plug-in, 16 x 16 (with a {@code @2x} file next to it for the dense screens).
 *
 * @param owner
 *            a class of the plug-in, which loads the image
 * @param plugin
 *            the symbolic name of the plug-in
 * @param path
 *            the path of the image in the plug-in, for example {@code icons/claude.png}
 */
public record TerminalIcon(Class<?> owner, String plugin, String path) {

	/** The image as the model of the workbench names it, kept in the layout after a restart. */
	public String uri() {
		return "platform:/plugin/" + plugin + '/' + path; //$NON-NLS-1$
	}

	public ImageDescriptor descriptor() {
		return ImageDescriptor.createFromFile(owner, '/' + path);
	}
}
