package org.eclipse.xterm4eclipse.claude;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Claude Code run for a chat: the messages of the user go to its input as JSON, one per line, and
 * it writes its own messages the same way, its requests for permissions among them. No pseudo
 * terminal: plain pipes.
 */
final class ClaudeProcess {

	/** What the process says, on a thread of its own. */
	interface Listener {
		/** A message of Claude Code: a line of its output that is a JSON object. */
		void message(Map<String, Object> message);

		/**
		 * The process has ended.
		 *
		 * @param errors
		 *            the end of what it wrote besides its messages, to tell what went wrong
		 */
		void exited(int exitCode, String errors);
	}

	/** How much of what the process writes besides its messages is kept. */
	private static final int ERRORS_KEPT = 4000;
	private static final AtomicInteger REQUESTS = new AtomicInteger();

	private final Process process;
	private final Writer input;
	private final StringBuilder errors = new StringBuilder();

	private ClaudeProcess(Process process) {
		this.process = process;
		this.input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
	}

	/** Starts the process; the listener hears of it from now on. */
	static ClaudeProcess start(List<String> command, File directory, Listener listener) throws IOException {
		ProcessBuilder builder = new ProcessBuilder(command);
		if (directory != null && directory.isDirectory()) {
			builder.directory(directory);
		}
		// Claude Code refuses to start inside another of its sessions, which Eclipse may have been
		// started from.
		builder.environment().remove("CLAUDECODE"); //$NON-NLS-1$
		ClaudeProcess claude = new ClaudeProcess(builder.start());
		Thread errors = new Thread(() -> claude.keepErrors(claude.process.getErrorStream()), "Claude Code errors"); //$NON-NLS-1$
		errors.setDaemon(true);
		errors.start();
		Thread reader = new Thread(() -> claude.read(listener, errors), "Claude Code messages"); //$NON-NLS-1$
		reader.setDaemon(true);
		reader.start();
		return claude;
	}

	private void read(Listener listener, Thread errorsReader) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				Map<String, Object> message = line.startsWith("{") ? Json.object(line) : null; //$NON-NLS-1$
				if (message != null) {
					listener.message(message);
				} else if (!line.isBlank()) {
					// What a login shell prints, or a message of Claude Code that is not JSON.
					keep(line + '\n');
				}
			}
		} catch (IOException e) {
			// The process is gone.
		} catch (RuntimeException e) {
			ChatPlugin.log("Error in a message of Claude Code", e); //$NON-NLS-1$
		}
		int exitCode;
		try {
			exitCode = process.waitFor();
			errorsReader.join(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			exitCode = -1;
		}
		String kept;
		synchronized (errors) {
			kept = errors.toString();
		}
		listener.exited(exitCode, withoutShellNoise(kept));
	}

	/** @return the errors without what an interactive shell says when it has no terminal */
	static String withoutShellNoise(String errors) {
		return errors.replaceAll("(?m)^.*(cannot set terminal process group|no job control in this shell|" //$NON-NLS-1$
				+ "can't access tty; job control turned off).*(\\R|$)", ""); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private void keepErrors(InputStream stream) {
		byte[] buffer = new byte[4096];
		try {
			int read;
			while ((read = stream.read(buffer)) >= 0) {
				keep(new String(buffer, 0, read, StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			// The process is gone.
		}
	}

	private void keep(String text) {
		synchronized (errors) {
			errors.append(text);
			if (errors.length() > ERRORS_KEPT) {
				errors.delete(0, errors.length() - ERRORS_KEPT);
			}
		}
	}

	boolean isAlive() {
		return process.isAlive();
	}

	/** Sends a message to Claude Code. */
	synchronized void send(Map<String, Object> message) {
		try {
			input.write(Json.write(message));
			input.write('\n');
			input.flush();
		} catch (IOException e) {
			// The process has ended: the listener hears of it.
		}
	}

	/** Sends a message of the user. */
	void sendUser(String text) {
		send(Map.of("type", "user", "message", Map.of("role", "user", "content", text))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	}

	/**
	 * Sends a message of the user with images.
	 *
	 * @param images each with its {@code media_type} and its base64 {@code data}
	 */
	void sendUser(String text, List<Map<String, String>> images) {
		if (images.isEmpty()) {
			sendUser(text);
			return;
		}
		List<Object> blocks = new ArrayList<>();
		for (Map<String, String> image : images) {
			blocks.add(Map.of("type", "image", "source", Map.of("type", "base64", "media_type", image.get("media_type"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
					"data", image.get("data")))); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (!text.isBlank()) {
			blocks.add(Map.of("type", "text", "text", text)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		send(Map.of("type", "user", "message", Map.of("role", "user", "content", blocks))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	}

	/**
	 * Asks Claude Code to do something: {@code interrupt}, {@code set_model}...
	 *
	 * @return the id of the request
	 */
	String request(String subtype, Map<String, Object> more) {
		String id = "x4e-" + REQUESTS.incrementAndGet(); //$NON-NLS-1$
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("subtype", subtype); //$NON-NLS-1$
		request.putAll(more);
		send(Map.of("type", "control_request", "request_id", id, "request", request)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
		return id;
	}

	/** Answers a request of Claude Code. */
	void answer(String requestId, Map<String, Object> response) {
		send(Map.of("type", "control_response", "response", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				Map.of("subtype", "success", "request_id", requestId, "response", response))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/** Answers a request of Claude Code that the chat cannot do. */
	void refuse(String requestId, String error) {
		send(Map.of("type", "control_response", "response", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				Map.of("subtype", "error", "request_id", requestId, "error", error))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}

	/** Ends the process: its input first, so that it ends by itself, then for good. */
	void stop() {
		try {
			synchronized (this) {
				input.close();
			}
		} catch (IOException e) {
			// Already closed.
		}
		Thread stopper = new Thread(() -> {
			try {
				if (!process.waitFor(2, TimeUnit.SECONDS)) {
					// The login shell, Claude Code and what it runs.
					process.descendants().forEach(ProcessHandle::destroy);
					process.destroy();
					if (!process.waitFor(3, TimeUnit.SECONDS)) {
						process.descendants().forEach(ProcessHandle::destroyForcibly);
						process.destroyForcibly();
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "Claude Code stop"); //$NON-NLS-1$
		stopper.setDaemon(true);
		stopper.start();
	}
}
