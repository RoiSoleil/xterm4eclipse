package org.eclipse.xterm4eclipse.claude;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The conversations that Claude Code keeps, one file of JSON lines for each in
 * {@code ~/.claude/projects/<folder>/<id>.jsonl}: those of the CLI, of Claude Desktop and of the
 * chats are the same.
 */
final class Conversations {

	/** A kept conversation. */
	record Conversation(String id, String title, long modified) {
	}

	/** The home of the user; a test may change it. */
	static Path userHome = Path.of(System.getProperty("user.home", "")); //$NON-NLS-1$ //$NON-NLS-2$
	/** Claude Code shortens the names of the folders that are longer, and ends them with a hash. */
	private static final int LONGEST_NAME = 200;
	/** How long a title is in the lists. */
	private static final int TITLE_LENGTH = 100;

	private Conversations() {
	}

	/** Where Claude Code keeps its files: {@code CLAUDE_CONFIG_DIR}, or else {@code ~/.claude}. */
	static Path home() {
		String moved = ClaudeCommand.environment.apply("CLAUDE_CONFIG_DIR"); //$NON-NLS-1$
		return moved != null && !moved.isBlank() ? Path.of(moved) : userHome.resolve(".claude"); //$NON-NLS-1$
	}

	/** The name of the folder of the conversations of a folder: each character but a letter or a digit is a dash. */
	static String folderName(File directory) {
		return directory.getAbsolutePath().replaceAll("[^A-Za-z0-9]", "-"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** @return the folder of the conversations of a folder, or {@code null} if there is none */
	static Path folder(File directory) {
		Path projects = home().resolve("projects"); //$NON-NLS-1$
		String name = folderName(directory);
		Path folder = projects.resolve(name);
		if (Files.isDirectory(folder) || name.length() <= LONGEST_NAME) {
			return Files.isDirectory(folder) ? folder : null;
		}
		String start = name.substring(0, LONGEST_NAME);
		try (Stream<Path> folders = Files.list(projects)) {
			return folders.filter(path -> path.getFileName().toString().startsWith(start)).findFirst().orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	/** @return {@code true} if Claude Code has kept the conversation of this id for the folder */
	static boolean exists(File directory, String id) {
		Path folder = folder(directory);
		return folder != null && ClaudeCommand.SESSION_ID.matcher(id).matches()
				&& Files.isRegularFile(folder.resolve(id + ".jsonl")); //$NON-NLS-1$
	}

	/** @return the conversations of the folder, the latest first */
	static List<Conversation> list(File directory) {
		List<Conversation> conversations = new ArrayList<>();
		Path folder = folder(directory);
		if (folder == null) {
			return conversations;
		}
		try (Stream<Path> files = Files.list(folder)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				String name = file.getFileName().toString();
				String id = name.endsWith(".jsonl") ? name.substring(0, name.length() - 6) : null; //$NON-NLS-1$
				if (id == null || !ClaudeCommand.SESSION_ID.matcher(id).matches()) {
					continue;
				}
				String title = title(file);
				if (title != null) {
					conversations.add(new Conversation(id, title, Files.getLastModifiedTime(file).toMillis()));
				}
			}
		} catch (IOException e) {
			// Nothing more to list.
		}
		conversations.sort(Comparator.comparingLong(Conversation::modified).reversed());
		return conversations;
	}

	/** @return the latest conversation of the folder, or {@code null} */
	static Conversation latest(File directory) {
		List<Conversation> conversations = list(directory);
		return conversations.isEmpty() ? null : conversations.get(0);
	}

	/**
	 * @return the title of a conversation: the one given to it, or else its first message; or
	 *         {@code null} if the user has said nothing in it
	 */
	static String title(Path file) {
		String given = null;
		String first = null;
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				// Most lines are not of interest: not read as JSON.
				if (line.contains("\"custom-title\"") || line.contains("\"summary\"")) { //$NON-NLS-1$ //$NON-NLS-2$
					Map<String, Object> entry = Json.object(line);
					String type = Json.string(entry, "type"); //$NON-NLS-1$
					String title = "custom-title".equals(type) ? Json.string(entry, "customTitle") //$NON-NLS-1$ //$NON-NLS-2$
							: "summary".equals(type) ? Json.string(entry, "summary") : null; //$NON-NLS-1$ //$NON-NLS-2$
					if (title != null && !title.isBlank() && (given == null || "custom-title".equals(type))) { //$NON-NLS-1$
						given = title;
					}
				} else if (first == null && line.contains("\"type\":\"user\"")) { //$NON-NLS-1$
					Map<String, Object> entry = Json.object(line);
					String text = entry == null ? null : userText(entry);
					if (text != null) {
						first = text;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// What could be read.
		}
		String title = given != null ? given : first;
		if (title == null) {
			return null;
		}
		title = title.strip().replaceAll("\\s+", " "); //$NON-NLS-1$ //$NON-NLS-2$
		return title.length() > TITLE_LENGTH ? title.substring(0, TITLE_LENGTH) + '…' : title;
	}

	/** @return what the user wrote in an entry, or {@code null} if it is something else */
	private static String userText(Map<String, Object> entry) {
		if (!"user".equals(Json.string(entry, "type")) || Boolean.TRUE.equals(entry.get("isMeta")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				|| Boolean.TRUE.equals(entry.get("isSidechain")) || Boolean.TRUE.equals(entry.get("isCompactSummary"))) { //$NON-NLS-1$ //$NON-NLS-2$
			return null;
		}
		Object content = Json.object(entry, "message") == null ? null : Json.object(entry, "message").get("content"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		String text = null;
		if (content instanceof String string) {
			text = string;
		} else if (content instanceof List<?> blocks) {
			for (Object block : blocks) {
				if (block instanceof Map<?, ?> map && "text".equals(map.get("type")) && map.get("text") instanceof String string) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					text = string;
					break;
				}
			}
		}
		// The commands and the reminders that Claude Code adds, not the words of the user.
		return text == null || text.isBlank() || text.startsWith("<") || text.startsWith("[Request interrupted") ? null //$NON-NLS-1$ //$NON-NLS-2$
				: text;
	}

	/**
	 * @return the messages of a kept conversation, as Claude Code writes them in a chat: those of
	 *         the user and of the assistant, with the results of its tools
	 */
	static List<Map<String, Object>> history(File directory, String id) {
		List<Map<String, Object>> messages = new ArrayList<>();
		Path folder = folder(directory);
		if (folder == null || !ClaudeCommand.SESSION_ID.matcher(id).matches()) {
			return messages;
		}
		try (BufferedReader reader = Files.newBufferedReader(folder.resolve(id + ".jsonl"), StandardCharsets.UTF_8)) { //$NON-NLS-1$
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.contains("\"type\":\"user\"") && !line.contains("\"type\":\"assistant\"")) { //$NON-NLS-1$ //$NON-NLS-2$
					continue;
				}
				Map<String, Object> entry = Json.object(line);
				String type = Json.string(entry, "type"); //$NON-NLS-1$
				if (entry == null || !("user".equals(type) || "assistant".equals(type)) //$NON-NLS-1$ //$NON-NLS-2$
						|| Boolean.TRUE.equals(entry.get("isSidechain")) || Boolean.TRUE.equals(entry.get("isMeta")) //$NON-NLS-1$ //$NON-NLS-2$
						|| Boolean.TRUE.equals(entry.get("isCompactSummary")) || Json.object(entry, "message") == null) { //$NON-NLS-1$ //$NON-NLS-2$
					continue;
				}
				Object content = Json.object(entry, "message").get("content"); //$NON-NLS-1$ //$NON-NLS-2$
				if ("user".equals(type) && content instanceof String && userText(entry) == null) { //$NON-NLS-1$
					continue;
				}
				Map<String, Object> message = new LinkedHashMap<>();
				message.put("type", type); //$NON-NLS-1$
				message.put("message", entry.get("message")); //$NON-NLS-1$ //$NON-NLS-2$
				// Named as in the messages of a chat.
				if (entry.get("toolUseResult") != null) { //$NON-NLS-1$
					message.put("tool_use_result", entry.get("toolUseResult")); //$NON-NLS-1$ //$NON-NLS-2$
				}
				messages.add(message);
			}
		} catch (IOException | RuntimeException e) {
			// What could be read.
		}
		return messages;
	}
}
