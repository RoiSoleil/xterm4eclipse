package org.eclipse.xterm4eclipse.harness;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs a command of a coding agent beside its terminal: to add an MCP server, to list its conversations. */
final class Commands {

	/**
	 * @param exitCode
	 *            0 if the command succeeded
	 * @param output
	 *            what it printed
	 */
	record Result(int exitCode, String output) {

		boolean succeeded() {
			return exitCode == 0;
		}

		/** What went wrong, for a message. */
		String failure() {
			return output.isBlank() ? "exit code " + exitCode : output; //$NON-NLS-1$
		}
	}

	private Commands() {
	}

	/**
	 * Runs a command to its end, at most a minute.
	 *
	 * @param environment
	 *            the environment of the terminal, as {@code NAME=value}
	 */
	static Result run(String[] command, File directory, String[] environment) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
			if (directory != null && directory.isDirectory()) {
				builder.directory(directory);
			}
			builder.environment().clear();
			for (String variable : environment) {
				int equals = variable.indexOf('=', 1);
				if (equals > 0) {
					builder.environment().put(variable.substring(0, equals), variable.substring(equals + 1));
				}
			}
			Process process = builder.start();
			process.getOutputStream().close();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			Thread reader = new Thread(() -> {
				try {
					process.getInputStream().transferTo(output);
				} catch (IOException e) {
					// The process is gone.
				}
			});
			reader.setDaemon(true);
			reader.start();
			if (!process.waitFor(60, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return new Result(-1, "no answer after a minute"); //$NON-NLS-1$
			}
			reader.join(1000);
			return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			return new Result(-1, String.valueOf(e.getMessage()));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Result(-1, "interrupted"); //$NON-NLS-1$
		}
	}
}
