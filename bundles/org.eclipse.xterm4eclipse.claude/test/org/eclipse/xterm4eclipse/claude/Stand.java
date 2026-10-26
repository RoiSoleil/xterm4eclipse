package org.eclipse.xterm4eclipse.claude;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The program of the stand-in for Claude Code ({@link FakeClaude}): a script that runs it with this Java. */
final class Stand {

	static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

	private Stand() {
	}

	/**
	 * @param log
	 *            where the stand-in writes its arguments and what it reads
	 * @return the program
	 */
	static File program(Path folder, Path log) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString();
		String classPath = String.join(File.pathSeparator, List.of(location(FakeClaude.class), location(Json.class)));
		if (WINDOWS) {
			Path program = folder.resolve("claude.cmd");
			Files.writeString(program, "@\"" + java + "\" -cp \"" + classPath + "\" \"-Dfake.log=" + log + "\" "
					+ FakeClaude.class.getName() + " %*\r\n");
			return program.toFile();
		}
		Path program = folder.resolve("claude");
		Files.writeString(program, "#!/bin/sh\nexec '" + java + "' -cp '" + classPath + "' '-Dfake.log=" + log + "' "
				+ FakeClaude.class.getName() + " \"$@\"\n");
		assertTrue(program.toFile().setExecutable(true));
		return program.toFile();
	}

	private static String location(Class<?> type) throws Exception {
		return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
	}
}
