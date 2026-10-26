package org.eclipse.xterm4eclipse.claude;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the file that a message names by its name only (<code>AbonnementFiche.js</code>), or by the end of
 * its path (<code>abonnement/AbonnementFiche.js</code>), in the folder of the chat: Claude Code writes the
 * names that it finds shortest.
 */
final class FileFinder {

	/** The folders of what is built or fetched, where a file of the same name is not the one that is meant. */
	private static final Set<String> IGNORED = Set.of(".git", ".svn", ".hg", "node_modules", "target", "build", "dist", "out", "bin",
			".gradle", ".idea", ".metadata", ".next", ".venv", "__pycache__", "vendor");
	private static final int MAX_DEPTH = 20;
	private static final int MAX_VISITED = 200_000;
	private static final int MAX_FOUND = 50;

	private FileFinder() {
	}

	/**
	 * @param path
	 *            a name, or the end of a path, with slashes or backslashes
	 * @return the files of the folder that end with this path, whose folders are not the ignored ones
	 */
	static List<File> find(File directory, String path) {
		List<File> found = new ArrayList<>();
		String wanted = path.replace('\\', '/');
		while (wanted.startsWith("./")) { //$NON-NLS-1$
			wanted = wanted.substring(2);
		}
		List<String> segments = List.of(wanted.split("/", -1)); //$NON-NLS-1$
		if (wanted.isEmpty() || wanted.startsWith("/") || segments.contains("..") || directory == null || !directory.isDirectory()) { //$NON-NLS-1$ //$NON-NLS-2$
			return found;
		}
		String ending = wanted;
		// The folder that the path goes through is where it is looked for, ignored or not.
		Set<String> ignored = new HashSet<>(IGNORED);
		ignored.removeAll(segments.subList(0, segments.size() - 1)); // its folders, not its name
		int[] visited = {0};
		FileVisitor<Path> visitor = new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) {
				if (++visited[0] > MAX_VISITED) {
					return FileVisitResult.TERMINATE;
				}
				return !folder.equals(directory.toPath()) && ignored.contains(folder.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE
						: FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
				String relative = directory.toPath().relativize(file).toString().replace('\\', '/');
				if (attributes.isRegularFile() && (relative.equals(ending) || relative.endsWith('/' + ending))) {
					found.add(file.toFile());
				}
				return ++visited[0] > MAX_VISITED || found.size() >= MAX_FOUND ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException e) {
				return FileVisitResult.CONTINUE;
			}
		};
		try {
			Files.walkFileTree(directory.toPath(), Set.of(), MAX_DEPTH, visitor);
		} catch (IOException | RuntimeException e) {
			// What was found.
		}
		found.sort(null);
		return found;
	}
}
