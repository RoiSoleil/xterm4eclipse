package org.eclipse.xterm4eclipse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the terminal page as a single self-contained HTML file.
 * <p>
 * The embedded browsers (WebKitGTK, WebKit on macOS, Edge on Windows) do not all let a local page
 * load sibling files, and Eclipse caches resources extracted from a bundle. One file holding the
 * markup, the style sheet and the scripts avoids both problems.
 */
final class WebPage {

	/** Gives the content of a file of the {@code web} folder. */
	interface Resources {
		String read(String name) throws IOException;
	}

	private static final Pattern STYLE = Pattern.compile("<link rel=\"stylesheet\" href=\"([^\"]+)\">"); //$NON-NLS-1$
	private static final Pattern SCRIPT = Pattern.compile("<script src=\"([^\"]+)\"></script>"); //$NON-NLS-1$

	private static final Set<Path> WRITTEN = new HashSet<>();

	private WebPage() {
	}

	/** Inlines the style sheets and scripts referenced by {@code index.html}. */
	static String assemble(Resources resources) throws IOException {
		String html = resources.read("index.html"); //$NON-NLS-1$
		html = inline(html, STYLE, "<style>\n", "\n</style>", resources); //$NON-NLS-1$ //$NON-NLS-2$
		return inline(html, SCRIPT, "<script>\n", "\n</script>", resources); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/**
	 * Writes the page to {@code file}, once per run: a file left by a previous session may come from
	 * an older build.
	 *
	 * @return {@code file}
	 */
	static synchronized Path materialize(Path file, Resources resources) throws IOException {
		if (WRITTEN.add(file) || !Files.isRegularFile(file)) {
			Files.createDirectories(file.getParent());
			Files.writeString(file, assemble(resources), StandardCharsets.UTF_8);
		}
		return file;
	}

	private static String inline(String html, Pattern pattern, String open, String close, Resources resources)
			throws IOException {
		StringBuilder result = new StringBuilder();
		Matcher matcher = pattern.matcher(html);
		int position = 0;
		while (matcher.find()) {
			// The HTML parser ends an inline block at the first closing tag, even inside a string.
			String content = resources.read(matcher.group(1)).replace("</script", "<\\/script") //$NON-NLS-1$ //$NON-NLS-2$
					.replace("</style", "<\\/style"); //$NON-NLS-1$ //$NON-NLS-2$
			result.append(html, position, matcher.start()).append(open).append(content).append(close);
			position = matcher.end();
		}
		return result.append(html, position, html.length()).toString();
	}
}
