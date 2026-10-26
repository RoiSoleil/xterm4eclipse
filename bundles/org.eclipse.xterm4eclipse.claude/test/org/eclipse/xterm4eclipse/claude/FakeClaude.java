package org.eclipse.xterm4eclipse.claude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stand-in for Claude Code in a chat, which spends no tokens: it answers the messages of the user
 * with the messages that Claude Code 2.1 writes with {@code --output-format stream-json}, recorded
 * from it. It writes what it reads in the file of the property {@code fake.log}, its arguments
 * first.
 * <ul>
 * <li>{@code hello}: a text, streamed, then a result;</li>
 * <li>{@code edit}: asks to edit {@code a.txt}, edits it if allowed, then a result;</li>
 * <li>{@code slow}: works until it is interrupted;</li>
 * <li>{@code crash}: fails on its error output.</li>
 * </ul>
 */
public final class FakeClaude {

	private static final String SESSION = "0f8fad5b-d9cb-469f-a165-70867728950e";
	private static PrintStream out;
	private static Path log;
	private static BufferedReader in;
	private static String model = "claude-sonnet-5-5";
	private static String mode = "default";
	private static boolean initialized;

	private FakeClaude() {
	}

	public static void main(String[] args) throws IOException {
		out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
		log = Path.of(System.getProperty("fake.log"));
		write("args: " + String.join(" ", args));
		for (int i = 0; i < args.length - 1; i++) {
			if (args[i].equals("--model")) {
				model = args[i + 1];
			} else if (args[i].equals("--permission-mode")) {
				mode = args[i + 1];
			}
		}
		// What a login shell may print before the program: not a message.
		out.println("Welcome to the fake shell");
		in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		String line;
		while ((line = in.readLine()) != null) {
			write(line);
			Map<String, Object> message = Json.object(line);
			if (message == null) {
				continue;
			}
			if ("control_request".equals(message.get("type"))) {
				control(message);
				continue;
			}
			if (!initialized) {
				initialized = true;
				send(Map.of("type", "system", "subtype", "init", "session_id", SESSION, "model", model, "permissionMode", mode,
						"tools", List.of("Read", "Edit")));
			}
			String text = String.valueOf(Json.object(message, "message").get("content"));
			switch (text.trim()) {
			case "hello" -> hello();
			case "edit" -> edit();
			case "slow" -> slow();
			case "crash" -> {
				System.err.println("boom: the fake crashed");
				System.exit(3);
			}
			default -> {
				assistant(List.of(Map.of("type", "text", "text", "You said: " + text)));
				result(false, "success");
			}
			}
		}
	}

	private static void control(Map<String, Object> message) {
		Map<String, Object> request = Json.object(message, "request");
		String subtype = Json.string(request, "subtype");
		Map<String, Object> response = new LinkedHashMap<>();
		if ("set_model".equals(subtype) && "nonexistent".equals(request.get("model"))) {
			send(Map.of("type", "control_response", "response", Map.of("subtype", "error", "request_id",
					message.get("request_id"), "error", "Unknown model nonexistent")));
			return;
		}
		if ("set_model".equals(subtype)) {
			model = request.get("model") == null ? "claude-sonnet-5-5" : "claude-" + request.get("model") + "-5-5";
			response.put("model", model);
		} else if ("set_permission_mode".equals(subtype)) {
			mode = String.valueOf(request.get("mode"));
			response.put("mode", mode);
		}
		send(Map.of("type", "control_response", "response",
				Map.of("subtype", "success", "request_id", message.get("request_id"), "response", response)));
	}

	private static void hello() {
		stream(Map.of("type", "content_block_start", "index", 0, "content_block", Map.of("type", "text", "text", "")));
		for (String part : List.of("Hello ", "**world**, see ", "`a.txt:2`")) {
			stream(Map.of("type", "content_block_delta", "index", 0, "delta", Map.of("type", "text_delta", "text", part)));
		}
		assistant(List.of(Map.of("type", "text", "text", "Hello **world**, see `a.txt:2`")));
		stream(Map.of("type", "content_block_stop", "index", 0));
		result(false, "success");
	}

	private static void edit() throws IOException {
		Path file = Path.of("a.txt").toAbsolutePath();
		Map<String, Object> input = Map.of("file_path", file.toString(), "old_string", "hello", "new_string", "salut",
				"replace_all", false);
		assistant(List.of(Map.of("type", "tool_use", "id", "toolu_edit", "name", "Edit", "input", input)));
		send(Map.of("type", "control_request", "request_id", "perm-1", "request",
				Map.of("subtype", "can_use_tool", "tool_name", "Edit", "display_name", "Edit", "input", input,
						"description", "a.txt", "tool_use_id", "toolu_edit", "permission_suggestions",
						List.of(Map.of("type", "setMode", "mode", "acceptEdits", "destination", "session")))));
		Map<String, Object> answer = null;
		String line;
		while ((line = in.readLine()) != null) {
			write(line);
			Map<String, Object> message = Json.object(line);
			if (message != null && "control_response".equals(message.get("type"))) {
				answer = Json.object(Json.object(message, "response"), "response");
				break;
			}
		}
		if (answer == null) {
			return;
		}
		if ("allow".equals(answer.get("behavior"))) {
			String before = Files.readString(file);
			Files.writeString(file, before.replace("hello", "salut"));
			Map<String, Object> change = new LinkedHashMap<>();
			change.put("filePath", file.toString());
			change.put("oldString", "hello");
			change.put("newString", "salut");
			change.put("originalFile", before);
			change.put("structuredPatch", List.of(Map.of("oldStart", 1, "oldLines", 1, "newStart", 1, "newLines", 1,
					"lines", List.of("-hello", "+salut"))));
			change.put("userModified", false);
			send(Map.of("type", "user", "message", Map.of("role", "user", "content", List.of(Map.of("tool_use_id",
					"toolu_edit", "type", "tool_result", "content", "The file " + file + " has been updated successfully."))),
					"tool_use_result", change));
			assistant(List.of(Map.of("type", "text", "text", "Edited.")));
		} else {
			send(Map.of("type", "user", "message", Map.of("role", "user", "content", List.of(Map.of("tool_use_id",
					"toolu_edit", "type", "tool_result", "is_error", true, "content", String.valueOf(answer.get("message")))))));
			assistant(List.of(Map.of("type", "text", "text", "Not edited.")));
		}
		result(false, "success");
	}

	private static void slow() throws IOException {
		stream(Map.of("type", "content_block_start", "index", 0, "content_block", Map.of("type", "text", "text", "")));
		stream(Map.of("type", "content_block_delta", "index", 0, "delta", Map.of("type", "text_delta", "text", "1\n2\n3")));
		String line;
		while ((line = in.readLine()) != null) {
			write(line);
			Map<String, Object> message = Json.object(line);
			Map<String, Object> request = Json.object(message, "request");
			if (request != null && "interrupt".equals(request.get("subtype"))) {
				send(Map.of("type", "control_response", "response", Map.of("subtype", "success", "request_id",
						message.get("request_id"), "response", Map.of("still_queued", List.of()))));
				send(Map.of("type", "user", "message", Map.of("role", "user", "content",
						List.of(Map.of("type", "text", "text", "[Request interrupted by user]")))));
				result(true, "error_during_execution");
				return;
			}
		}
	}

	private static void stream(Map<String, Object> event) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", "stream_event");
		message.put("event", event);
		message.put("session_id", SESSION);
		message.put("parent_tool_use_id", null);
		send(message);
	}

	private static void assistant(List<Map<String, Object>> content) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", "assistant");
		message.put("message", Map.of("model", model, "id", "msg_1", "type", "message", "role", "assistant", "content", content,
				"usage", Map.of("input_tokens", 2, "cache_creation_input_tokens", 1000, "cache_read_input_tokens", 37000,
						"output_tokens", 12)));
		message.put("parent_tool_use_id", null);
		message.put("session_id", SESSION);
		send(message);
	}

	private static void result(boolean error, String subtype) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", "result");
		message.put("subtype", subtype);
		message.put("is_error", error);
		message.put("total_cost_usd", 0.0125);
		message.put("num_turns", 1);
		message.put("session_id", SESSION);
		message.put("usage", Map.of("input_tokens", 2, "output_tokens", 12));
		send(message);
	}

	private static void send(Map<String, Object> message) {
		out.println(Json.write(message));
	}

	private static void write(String line) {
		try {
			Files.writeString(log, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** @return the lines that the stand-in has read, its arguments first */
	static List<String> lines(Path log) throws IOException {
		return Files.exists(log) ? Files.readAllLines(log, StandardCharsets.UTF_8) : new ArrayList<>();
	}
}
