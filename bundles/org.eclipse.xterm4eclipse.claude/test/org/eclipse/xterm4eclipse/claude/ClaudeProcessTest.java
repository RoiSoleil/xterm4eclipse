package org.eclipse.xterm4eclipse.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Claude Code beside a chat, played by its stand-in: the messages both ways, and its end. */
class ClaudeProcessTest {

	@TempDir
	Path temp;
	private final BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
	private final CompletableFuture<String> exit = new CompletableFuture<>();
	private ClaudeProcess claude;

	private final Map<String, String> environment = new HashMap<>();
	private UnaryOperator<String> previousEnvironment;

	@BeforeEach
	void isolate() {
		previousEnvironment = ClaudeCommand.environment;
		// No login shell, unless a test chooses one.
		ClaudeCommand.environment = environment::get;
	}

	@AfterEach
	void stop() throws Exception {
		if (claude != null) {
			claude.stop();
			// Its folder is deleted after the test: Windows deletes no folder that a process works in.
			exit.get(30, TimeUnit.SECONDS);
		}
		ClaudeCommand.environment = previousEnvironment;
	}

	private ClaudeProcess start() throws Exception {
		File program = Stand.program(temp, temp.resolve("log"));
		List<String> command = ClaudeCommand.commandLine(program,
				ClaudeCommand.arguments(ClaudeCommandTest.ID, false, "haiku", "plan", ""));
		Files.writeString(temp.resolve("a.txt"), "hello\n");
		claude = ClaudeProcess.start(command, temp.toFile(), new ClaudeProcess.Listener() {
			@Override
			public void message(Map<String, Object> message) {
				messages.add(message);
			}

			@Override
			public void exited(int exitCode, String errors) {
				exit.complete(exitCode + ": " + errors);
			}
		});
		return claude;
	}

	/** @return the next message that matches, after those that do not */
	private Map<String, Object> next(Predicate<Map<String, Object>> wanted) throws Exception {
		while (true) {
			Map<String, Object> message = messages.poll(30, TimeUnit.SECONDS);
			if (message == null) {
				throw new AssertionError("no message; read: " + FakeClaude.lines(temp.resolve("log")));
			}
			if (wanted.test(message)) {
				return message;
			}
		}
	}

	private static Predicate<Map<String, Object>> type(String type) {
		return message -> type.equals(message.get("type"));
	}

	@Test
	void talksWithClaudeCode() throws Exception {
		start().sendUser("hello");
		Map<String, Object> init = next(type("system"));
		assertEquals("haiku", init.get("model"), "started with the arguments of the chat");
		assertEquals("plan", init.get("permissionMode"));
		assertEquals("Hello **world**, see `a.txt:2`",
				((Map<?, ?>) Json.list(Json.object(next(type("assistant")), "message"), "content").get(0)).get("text"));
		assertEquals(0.0125, next(type("result")).get("total_cost_usd"));
		assertTrue(claude.isAlive(), "it waits for the next message");

		String id = claude.request("set_model", Map.of("model", "opus"));
		Map<String, Object> response = Json.object(next(type("control_response")), "response");
		assertEquals(id, response.get("request_id"));
		assertEquals("claude-opus-5-5", Json.object(response, "response").get("model"));

		claude.stop();
		String ended = exit.get(30, TimeUnit.SECONDS);
		assertTrue(ended.startsWith("0: "), "it ends with its input: " + ended);
		assertTrue(ended.contains("Welcome to the fake shell"), "what is not a message is kept for the errors: " + ended);
		assertFalse(claude.isAlive());
		List<String> read = FakeClaude.lines(temp.resolve("log"));
		assertTrue(read.get(0).startsWith("args: -p --input-format stream-json"), read.get(0));
		assertEquals(Map.of("type", "user", "message", Map.of("role", "user", "content", "hello")), Json.object(read.get(1)));
	}

	@Test
	void answersTheRequestsOfPermission() throws Exception {
		start().sendUser("edit");
		Map<String, Object> request = next(type("control_request"));
		assertEquals("can_use_tool", Json.object(request, "request").get("subtype"));
		claude.answer((String) request.get("request_id"), Map.of("behavior", "allow", "updatedInput",
				Json.object(Json.object(request, "request"), "input")));
		Map<String, Object> result = next(type("user"));
		assertEquals("hello\n", Json.object(result, "tool_use_result").get("originalFile"));
		next(type("result"));
		assertEquals("salut\n", Files.readString(temp.resolve("a.txt")));
	}

	@Test
	void refusesWhatTheChatCannotDo() throws Exception {
		start().sendUser("hi");
		next(type("result"));
		claude.refuse("req-9", "Not supported");
		claude.stop();
		exit.get(30, TimeUnit.SECONDS);
		assertTrue(FakeClaude.lines(temp.resolve("log")).stream().map(Json::object).anyMatch(line -> Map.of("type",
				"control_response", "response", Map.of("subtype", "error", "request_id", "req-9", "error", "Not supported"))
				.equals(line)));
	}

	@Test
	@DisabledOnOs(value = OS.WINDOWS, disabledReason = "no login shell on Windows")
	void talksThroughTheLoginShell() throws Exception {
		environment.put("SHELL", "/bin/sh");
		start().sendUser("crash");
		String ended = exit.get(30, TimeUnit.SECONDS);
		assertTrue(ended.startsWith("3: "), ended);
		assertTrue(ended.contains("boom: the fake crashed"), ended);
		assertFalse(ended.contains("job control"), "not what the shell says without a terminal: " + ended);
		assertEquals("args: -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages "
				+ "--permission-prompt-tool stdio --session-id " + ClaudeCommandTest.ID + " --model haiku --permission-mode plan",
				FakeClaude.lines(temp.resolve("log")).get(0), "the arguments as they are, through the shell");
	}

	@Test
	void tellsWhyItEnded() throws Exception {
		start().sendUser("crash");
		String ended = exit.get(30, TimeUnit.SECONDS);
		assertTrue(ended.startsWith("3: "), ended);
		assertTrue(ended.contains("boom: the fake crashed"), ended);
		claude.sendUser("nobody reads it");
	}
}
