package org.eclipse.xterm4eclipse.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationsTest {

	static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
	static final String OTHER = "1a2b3c4d-0000-4000-8000-000000000001";

	/**
	 * A conversation as Claude Code 2.1 keeps it, shortened: the lines of its queue, of its
	 * attachments and of its costs are not messages.
	 */
	static String conversation(File directory) {
		String cwd = Json.string(directory.getPath());
		return String.join("\n",
				"{\"type\":\"queue-operation\",\"operation\":\"enqueue\",\"content\":\"Fix the build\"}",
				"{\"parentUuid\":null,\"isSidechain\":false,\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
						+ "\"<command-name>/model</command-name>\"},\"cwd\":" + cwd + "}",
				"{\"parentUuid\":null,\"isSidechain\":false,\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":"
						+ "\"Fix   the\\nbuild\"},\"cwd\":" + cwd + "}",
				"{\"type\":\"attachment\",\"attachment\":{\"type\":\"date\",\"date\":\"2026-10-23\"}}",
				"{\"type\":\"user\",\"isMeta\":true,\"message\":{\"role\":\"user\",\"content\":\"Caveat: meta\"}}",
				"{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\","
						+ "\"id\":\"toolu_1\",\"name\":\"Edit\",\"input\":{\"file_path\":\"/p/a.txt\"}}]}}",
				"{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"toolu_1\","
						+ "\"type\":\"tool_result\",\"content\":\"ok\"}]},\"toolUseResult\":{\"filePath\":\"/p/a.txt\","
						+ "\"originalFile\":\"hello\\n\",\"structuredPatch\":[]}}",
				"{\"type\":\"assistant\",\"isSidechain\":true,\"message\":{\"role\":\"assistant\",\"content\":"
						+ "[{\"type\":\"text\",\"text\":\"of a subagent\"}]}}",
				"{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Done.\"}]}}",
				"{\"type\":\"cost-state\",\"totalCostUSD\":0.01}",
				"not json at all", "");
	}

	@TempDir
	Path temp;
	File project;
	private Path previousHome;
	private UnaryOperator<String> previousEnvironment;
	private final Map<String, String> environment = new HashMap<>();

	@BeforeEach
	void isolate() throws Exception {
		previousHome = Conversations.userHome;
		previousEnvironment = ClaudeCommand.environment;
		Conversations.userHome = temp.resolve("home");
		ClaudeCommand.environment = environment::get;
		project = Files.createDirectories(temp.resolve("my_project.v2")).toFile();
	}

	@AfterEach
	void restore() {
		Conversations.userHome = previousHome;
		ClaudeCommand.environment = previousEnvironment;
	}

	Path folder() throws Exception {
		return Files.createDirectories(temp.resolve("home/.claude/projects").resolve(Conversations.folderName(project)));
	}

	@Test
	void namesTheFolderOfAProjectAsClaudeCodeDoes() {
		if (ClaudeCommand.windows) {
			assertEquals("C--Users-me-my-project-v2", Conversations.folderName(new File("C:\\Users\\me\\my_project.v2")));
		} else {
			assertEquals("-home-me-my-project-v2", Conversations.folderName(new File("/home/me/my_project.v2")));
		}
	}

	@Test
	void findsTheConversationsOfTheProject() throws Exception {
		assertTrue(Conversations.list(project).isEmpty(), "none yet");
		assertFalse(Conversations.exists(project, ID));
		Path folder = folder();
		Files.writeString(folder.resolve(ID + ".jsonl"), conversation(project));
		Files.writeString(folder.resolve(OTHER + ".jsonl"), "{\"type\":\"custom-title\",\"customTitle\":\"Named\"}\n"
				+ "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"first words\"}}\n");
		// Nothing said yet: not listed.
		Files.writeString(folder.resolve("2a2b3c4d-0000-4000-8000-000000000002.jsonl"), "{\"type\":\"queue-operation\"}\n");
		Files.writeString(folder.resolve("notes.jsonl"), "{\"type\":\"user\"}\n");
		Files.setLastModifiedTime(folder.resolve(ID + ".jsonl"), FileTime.fromMillis(2000_000));
		Files.setLastModifiedTime(folder.resolve(OTHER + ".jsonl"), FileTime.fromMillis(1000_000));

		List<Conversations.Conversation> conversations = Conversations.list(project);
		assertEquals(2, conversations.size(), conversations.toString());
		assertEquals(new Conversations.Conversation(ID, "Fix the build", 2000_000), conversations.get(0),
				"the latest first, named after its first words");
		assertEquals("Named", conversations.get(1).title(), "the title that the user gave it");
		assertEquals(ID, Conversations.latest(project).id());
		assertTrue(Conversations.exists(project, ID));
		assertFalse(Conversations.exists(project, "../" + ID));
	}

	@Test
	void readsTheMessagesOfAConversation() throws Exception {
		Files.writeString(folder().resolve(ID + ".jsonl"), conversation(project));
		List<Map<String, Object>> history = Conversations.history(project, ID);
		assertEquals(List.of("user", "assistant", "user", "assistant"), history.stream().map(m -> m.get("type")).toList(),
				"neither commands, meta messages, subagents nor the other lines");
		assertEquals("Fix   the\nbuild", Json.object(history.get(0), "message").get("content"));
		assertEquals("hello\n", Json.string(Json.object(history.get(2), "tool_use_result"), "originalFile"),
				"named as in the messages of a chat");
		assertTrue(Conversations.history(project, "x").isEmpty());
		assertTrue(Conversations.history(project, OTHER).isEmpty());
	}

	@Test
	void shortensLongTitles() throws Exception {
		Files.writeString(folder().resolve(ID + ".jsonl"),
				"{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"" + "a".repeat(150)
						+ "\"}]}}\n");
		String title = Conversations.list(project).get(0).title();
		assertEquals(101, title.length());
		assertTrue(title.endsWith("\u2026"));
	}

	@Test
	void findsAShortenedFolderOfALongPath() throws Exception {
		project = Files.createDirectories(temp.resolve("x".repeat(220))).toFile();
		String name = Conversations.folderName(project);
		Path shortened = Files.createDirectories(temp.resolve("home/.claude/projects").resolve(name.substring(0, 200) + "-1abc"));
		Files.writeString(shortened.resolve(ID + ".jsonl"), conversation(project));
		assertEquals(shortened, Conversations.folder(project));
		assertTrue(Conversations.exists(project, ID));
	}

	@Test
	void followsTheConfigurationFolderOfClaudeCode() throws Exception {
		Path moved = Files.createDirectories(temp.resolve("config"));
		environment.put("CLAUDE_CONFIG_DIR", moved.toString());
		assertEquals(moved, Conversations.home());
		Path folder = Files.createDirectories(moved.resolve("projects").resolve(Conversations.folderName(project)));
		Files.writeString(folder.resolve(ID + ".jsonl"), conversation(project));
		assertTrue(Conversations.exists(project, ID));
		assertNull(Conversations.folder(temp.toFile()));
	}
}
