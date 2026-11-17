package org.eclipse.xterm4eclipse.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.eclipse.xterm4eclipse.claude.ConversationSearch.Author;
import org.eclipse.xterm4eclipse.claude.ConversationSearch.Hit;
import org.eclipse.xterm4eclipse.claude.ConversationSearch.Kind;
import org.eclipse.xterm4eclipse.claude.ConversationSearch.Mode;
import org.eclipse.xterm4eclipse.claude.ConversationSearch.Options;
import org.eclipse.xterm4eclipse.claude.ConversationSearch.Origin;
import org.eclipse.xterm4eclipse.claude.ConversationSearch.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationSearchTest {

	static final String A = "0f8fad5b-d9cb-469f-a165-70867728950a";
	static final String B = "0f8fad5b-d9cb-469f-a165-70867728950b";
	static final String C = "0f8fad5b-d9cb-469f-a165-70867728950c";
	static final String D = "0f8fad5b-d9cb-469f-a165-70867728950d";
	static final long DAY = 24 * 3600 * 1000L;
	static final long NOW = 1_800_000_000_000L;

	@TempDir
	Path temp;
	File project;
	File other;
	private Path previousHome;
	private UnaryOperator<String> previousEnvironment;
	private final Map<String, String> environment = new HashMap<>();

	@BeforeEach
	void isolate() throws Exception {
		previousHome = Conversations.userHome;
		previousEnvironment = ClaudeCommand.environment;
		Conversations.userHome = temp.resolve("home");
		ClaudeCommand.environment = environment::get;
		ConversationSearch.now = NOW;
		project = Files.createDirectories(temp.resolve("my_app")).toFile();
		other = Files.createDirectories(temp.resolve("other app")).toFile();
	}

	@AfterEach
	void restore() {
		Conversations.userHome = previousHome;
		ClaudeCommand.environment = previousEnvironment;
		ConversationSearch.now = -1;
	}

	static String time(long millis) {
		return java.time.Instant.ofEpochMilli(millis).toString();
	}

	static String user(String session, File cwd, String branch, long at, String text) {
		return "{\"type\":\"user\",\"sessionId\":\"" + session + "\",\"cwd\":" + Json.string(cwd.getPath()) + ",\"gitBranch\":\""
				+ branch + "\",\"timestamp\":\"" + time(at) + "\",\"message\":{\"role\":\"user\",\"content\":" + Json.string(text) + "}}";
	}

	static String assistant(String session, long at, String text) {
		return "{\"type\":\"assistant\",\"sessionId\":\"" + session + "\",\"timestamp\":\"" + time(at)
				+ "\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":" + Json.string(text) + "}]}}";
	}

	static String tool(String session, long at, String name, String file) {
		return "{\"type\":\"assistant\",\"sessionId\":\"" + session + "\",\"timestamp\":\"" + time(at)
				+ "\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"" + name
				+ "\",\"input\":{\"file_path\":" + Json.string(file) + "}}]}}";
	}

	static String result(String session, long at, String text) {
		return "{\"type\":\"user\",\"sessionId\":\"" + session + "\",\"timestamp\":\"" + time(at)
				+ "\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":"
				+ Json.string(text) + "}]}}";
	}

	Path write(File directory, String id, long modified, String... lines) throws Exception {
		Path folder = Files.createDirectories(temp.resolve("home/.claude/projects").resolve(Conversations.folderName(directory)));
		Path file = folder.resolve(id + ".jsonl");
		Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
		return file;
	}

	static List<String> ids(List<Hit> hits) {
		List<String> ids = new ArrayList<>();
		for (Hit hit : hits) {
			ids.add(hit.id());
		}
		return ids;
	}

	List<Hit> search(String text) {
		return ConversationSearch.search(Options.of(ConversationSearch.parse(text, Mode.AUTO)), () -> false);
	}

	@Test
	void detectsWhatTheTextIs() {
		assertEquals(Kind.NONE, ConversationSearch.parse("  ", Mode.AUTO).kind());
		assertEquals(Kind.REVIEW, ConversationSearch.parse("https://github.com/acme/app/pull/1234", Mode.AUTO).kind());
		assertEquals(Kind.REVIEW, ConversationSearch.parse("github.com/acme/app/pull/1234/files?x=1#y", Mode.AUTO).kind());
		assertEquals(Kind.REVIEW, ConversationSearch.parse("acme/app#1234", Mode.AUTO).kind());
		assertEquals(Kind.REVIEW, ConversationSearch.parse("https://gitlab.com/g/sub/app/-/merge_requests/12", Mode.AUTO).kind());
		assertEquals(Kind.REVIEW,
				ConversationSearch.parse("https://git.eclipse.org/r/c/jdt/core/+/1234/5?usp=email", Mode.AUTO).kind());
		assertEquals(Kind.REVIEW,
				ConversationSearch.parse("https://bitbucket.org/acme/app/pull-requests/7/overview", Mode.AUTO).kind());
		assertEquals(Kind.NUMBER, ConversationSearch.parse("1234", Mode.AUTO).kind());
		assertEquals(Kind.TICKET, ConversationSearch.parse("PROJ-123", Mode.AUTO).kind());
		assertEquals(Kind.TICKET, ConversationSearch.parse("#123", Mode.AUTO).kind());
		assertEquals(Kind.COMMIT, ConversationSearch.parse("a1b2c3d4e5", Mode.AUTO).kind());
		assertEquals(Kind.CHANGE_ID, ConversationSearch.parse("I1a2b3c4d5e6f", Mode.AUTO).kind());
		assertEquals(Kind.URL, ConversationSearch.parse("https://ci.example.com/job/1/?a=b#c", Mode.AUTO).kind());
		assertEquals("Address ci.example.com/job/1", ConversationSearch.parse("https://ci.example.com/job/1/", Mode.AUTO).description());
		assertEquals(Kind.PATH, ConversationSearch.parse("src\\main\\Foo.java", Mode.AUTO).kind());
		assertEquals(Kind.PATH, ConversationSearch.parse("@src/Foo.java#L10-20", Mode.AUTO).kind());
		assertEquals("src/foo.java", ConversationSearch.parse("@src/Foo.java#L10-20", Mode.AUTO).path());
		assertEquals(Kind.TYPE, ConversationSearch.parse("OrderService", Mode.AUTO).kind());
		assertEquals(Kind.WORDS, ConversationSearch.parse("fix the build", Mode.AUTO).kind());
		assertEquals(Kind.EXACT, ConversationSearch.parse("1234", Mode.EXACT).kind());
		assertEquals(Kind.REGEX, ConversationSearch.parse("pull/\\d+", Mode.REGEX).kind());
		assertTrue(ConversationSearch.parse("(", Mode.REGEX).description().startsWith("Invalid regular expression"));
		Query words = ConversationSearch.parse("alpha \"beta gamma\" OR delta -epsilon", Mode.AUTO, true);
		assertEquals(List.of(List.of("alpha", "beta gamma"), List.of("delta")), words.any());
		assertEquals(List.of("epsilon"), words.excluded());
		assertTrue(words.description().contains("approximate"));
		Query exclusions = ConversationSearch.parse("-only", Mode.AUTO);
		assertEquals(Kind.WORDS, exclusions.kind());
		assertEquals("All conversations, without only", exclusions.description());
		assertEquals(Kind.WORDS, ConversationSearch.parse("Bug", Mode.AUTO).kind(), "one capitalized word is no type");
		assertEquals(Kind.WORDS, ConversationSearch.parse("defaced", Mode.AUTO).kind(), "a word of hex letters is no commit");
		Query issue = ConversationSearch.parse("https://github.com/acme/app/issues/77#top", Mode.AUTO);
		assertEquals("Issue acme/app#77", issue.description());
		assertEquals("acme/app/issues/77", issue.variants().get(0).text());
		assertEquals("Review g/app#3", ConversationSearch.parse("https://gitlab.com/g/app/-/merge_requests/3", Mode.AUTO).description());
		assertEquals("Issue g/app#4", ConversationSearch.parse("https://gitlab.com/g/app/-/issues/4", Mode.AUTO).description());
	}

	@Test
	void normalizesCaseAccentsAndPaths() {
		assertEquals("parametrage de transmission", ConversationSearch.normalize("Paramétrage de TRANSMISSION"));
		assertEquals("c:/work/app/foo.java", ConversationSearch.normalizePath("C:\\work\\\\app\\Foo.java"));
		assertEquals("src/foo.java", ConversationSearch.normalizePath("./src/Foo.java#L3"));
		assertEquals(1, ConversationSearch.distance("transmision", "transmission", 2));
		assertEquals(3, ConversationSearch.distance("abcdef", "uvwxyz", 2));
	}

	@Test
	void numbersAndReferencesHaveBoundaries() {
		Query number = ConversationSearch.parse("1234", Mode.AUTO);
		assertTrue(ConversationSearch.find(number, "see PR 1234.") != null);
		assertNull(ConversationSearch.find(number, "see PR 12345"));
		assertNull(ConversationSearch.find(number, "version v1.2.34 and 51234"));
		Query ticket = ConversationSearch.parse("proj-12", Mode.AUTO);
		assertTrue(ConversationSearch.find(ticket, "Fixes PROJ-12!") != null);
		assertNull(ConversationSearch.find(ticket, "Fixes PROJ-123"));
		Query commit = ConversationSearch.parse("a1b2c3d", Mode.AUTO);
		assertTrue(ConversationSearch.find(commit, "commit a1b2c3d4e5f6 done") != null, "a prefix of a longer hash");
		assertNull(ConversationSearch.find(commit, "commit ffa1b2c3d"));
		Query regex = ConversationSearch.parse("pull/\\d{3}\\b", Mode.REGEX);
		assertTrue(ConversationSearch.find(regex, "x/PULL/123 y") != null);
		assertNull(ConversationSearch.find(ConversationSearch.parse("", Mode.AUTO), "anything"));
	}

	@Test
	void findsAReviewWhateverItsForm() throws Exception {
		write(project, A, NOW - DAY, user(A, project, "main", NOW - DAY, "Look at https://github.com/acme/app/pull/1234 please"));
		write(project, B, NOW - DAY, user(B, project, "main", NOW - DAY, "Look at https://github.com/acme/app/pull/12345"));
		write(other, C, NOW - 2 * DAY, user(C, other, "dev", NOW - 2 * DAY, "acme/app#1234 is merged"));
		List<String> full = ids(search("https://github.com/acme/app/pull/1234"));
		assertEquals(List.of(A, C), full);
		assertEquals(full, ids(search("github.com/acme/app/pull/1234/files")));
		assertEquals(full, ids(search("acme/app#1234")));
	}

	@Test
	void whatTheUserWroteComesFirst() throws Exception {
		write(project, A, NOW - DAY, user(A, project, "main", NOW - DAY, "Start"), assistant(A, NOW - DAY, "About PROJ-7."));
		write(project, B, NOW - 3 * DAY, user(B, project, "main", NOW - 3 * DAY, "Fix PROJ-7 please"));
		write(project, C, NOW - DAY / 2, user(C, project, "main", NOW - DAY / 2, "Start"), result(C, NOW - DAY / 2, "PROJ-7 found"));
		List<Hit> hits = search("PROJ-7");
		assertEquals(List.of(B, A, C), ids(hits));
		assertEquals(1, hits.get(0).mine());
		assertEquals(0, hits.get(0).others());
		assertEquals(1, hits.get(1).others());
		assertTrue(hits.get(0).snippet().contains("PROJ-7"));
		assertEquals("PROJ-7", hits.get(0).snippet().substring(hits.get(0).matchStart(), hits.get(0).matchEnd()));
		assertEquals(List.of(B), ids(ConversationSearch.search(
				Options.of(ConversationSearch.parse("PROJ-7", Mode.AUTO)).by(Author.USER), () -> false)));
		assertEquals(List.of(A), ids(ConversationSearch.search(
				Options.of(ConversationSearch.parse("PROJ-7", Mode.AUTO)).by(Author.CLAUDE), () -> false)));
		assertEquals(List.of(C), ids(ConversationSearch.search(
				Options.of(ConversationSearch.parse("PROJ-7", Mode.AUTO)).by(Author.TOOLS), () -> false)));
	}

	@Test
	void wordsIgnoreCaseAndAccents() throws Exception {
		write(project, A, NOW - DAY, user(A, project, "main", NOW - DAY, "Paramétrage de transmission"));
		write(project, B, NOW - DAY, "{\"type\":\"user\",\"sessionId\":\"" + B + "\",\"timestamp\":\"" + time(NOW - DAY)
				+ "\",\"message\":{\"role\":\"user\",\"content\":\"Le param\\u00e9trage puis la transmission\"}}");
		write(project, C, NOW - DAY, user(C, project, "main", NOW - DAY, "transmission seulement"));
		assertEquals(List.of(A, B), ids(search("parametrage transmission")).stream().sorted().toList());
		assertEquals(List.of(A), ids(search("\"parametrage de transmission\"")));
		assertEquals(List.of(B), ids(search("parametrage transmission -de")));
		assertEquals(List.of(A, B, C), ids(search("parametrage OR seulement")).stream().sorted().toList());
		assertEquals(List.of(), ids(search("paramtrage transmision")));
		assertEquals(List.of(A, B), ids(ConversationSearch.search(
				Options.of(ConversationSearch.parse("paramtrage transmision", Mode.AUTO, true)), () -> false)).stream().sorted().toList());
		assertEquals(List.of(A), ids(ConversationSearch.search(
				Options.of(ConversationSearch.parse("Param.trage de", Mode.REGEX)), () -> false)));
	}

	@Test
	void exclusionsAloneAndExactTextWithQuotes() throws Exception {
		write(project, A, NOW - DAY, user(A, project, "main", NOW - DAY, "the test is flaky"));
		write(project, B, NOW - DAY, user(B, project, "main", NOW - DAY, "say \"hi\"\tthen\\leave"));
		assertEquals(List.of(B), ids(search("-flaky")));
		assertEquals(List.of(B), ids(ConversationSearch.search(Options.of(ConversationSearch.parse("say \"hi\"", Mode.EXACT)),
				() -> false)));
		assertEquals("a\nb\t\"c\"/d\\e\r\b\f", ConversationSearch.unescapeLine("a\\nb\\t\\\"c\\\"\\/d\\\\e\\r\\b\\f"));
		assertEquals("plain", ConversationSearch.unescapeLine("plain"));
	}

	@Test
	void findsTheConversationsThatModifiedAFile() throws Exception {
		write(project, A, NOW - DAY, user(A, project, "main", NOW - DAY, "Refactor"),
				tool(A, NOW - DAY, "Edit", project.getPath() + "/src/com/acme/OrderService.java"));
		write(project, B, NOW - DAY, user(B, project, "main", NOW - DAY, "Explain"),
				tool(B, NOW - DAY, "Read", project.getPath() + "/src/com/acme/OrderService.java"));
		write(project, C, NOW - DAY, user(C, project, "main", NOW - DAY, "What does OrderService do?"));
		assertEquals(List.of(A, B, C), ids(search("OrderService")).stream().sorted().toList());
		assertEquals(List.of(C, A, B), ids(search("OrderService")), "quoted by the user, then modified, then read");
		Options modified = Options.of(ConversationSearch.parse("OrderService", Mode.AUTO)).modifiedOnly(true);
		assertEquals(List.of(A), ids(ConversationSearch.search(modified, () -> false)));
		Options path = Options.of(ConversationSearch.parse("src\\com\\acme\\OrderService.java", Mode.AUTO)).modifiedOnly(true);
		assertEquals(List.of(A), ids(ConversationSearch.search(path, () -> false)));
		assertEquals(List.of(A, B), ids(search("@src/com/acme/OrderService.java#L1-5")).stream().sorted().toList());
		assertEquals(List.of(A), ids(ConversationSearch.search(Options.of(ConversationSearch.parse("", Mode.AUTO)).modifiedOnly(true),
				() -> false)));
	}

	@Test
	void listsTheRecentConversationsOfAProject() throws Exception {
		write(project, A, NOW - DAY, user(A, project, "main", NOW - DAY, "Recent"));
		write(project, B, NOW - 10 * DAY, user(B, project, "main", NOW - 10 * DAY, "Old"));
		write(other, C, NOW - DAY, user(C, other, "main", NOW - DAY, "Elsewhere"));
		write(project, D, NOW - 60_000, user(D, project, "feature/x", NOW - 60_000, "Now"));
		Options options = Options.of(ConversationSearch.parse("", Mode.AUTO)).in(project).between(NOW - 7 * DAY, Long.MAX_VALUE);
		List<Hit> hits = ConversationSearch.search(options, () -> false);
		assertEquals(List.of(D, A), ids(hits));
		assertTrue(hits.get(0).recent(), "in progress");
		assertFalse(hits.get(1).recent());
		assertEquals("feature/x", hits.get(0).branch());
		assertEquals(project.getPath(), hits.get(0).cwd());
		assertEquals("Now", hits.get(0).title());
		assertEquals(project, hits.get(0).conversation().directory());
		assertEquals(List.of(D), ids(ConversationSearch.search(options.onBranch("FEATURE"), () -> false)));
		assertEquals(List.of(A), ids(ConversationSearch.search(options.between(NOW - 7 * DAY, NOW - DAY / 2), () -> false)));
		assertEquals(4, ConversationSearch.search(Options.of(ConversationSearch.parse("", Mode.AUTO)), () -> false).size());
		assertEquals(List.of(), ConversationSearch.search(Options.of(ConversationSearch.parse("", Mode.AUTO)), () -> true));
		assertEquals(List.of(), ConversationSearch.search(options.in(temp.resolve("none").toFile()), () -> false));
	}

	@Test
	void groupsAConversationResumedSeveralTimes() throws Exception {
		write(project, A, NOW - 3 * DAY, user(A, project, "main", NOW - 3 * DAY, "First part about Kafka"));
		write(project, B, NOW - 2 * DAY, user(A, project, "main", NOW - 3 * DAY, "First part about Kafka"),
				user(B, project, "main", NOW - 2 * DAY, "Second part"));
		write(project, C, NOW - DAY, user(B, project, "main", NOW - 2 * DAY, "Second part"),
				user(C, project, "main", NOW - DAY, "Third part"));
		List<Hit> hits = search("kafka");
		assertEquals(List.of(C), ids(hits), "once, resumed from its latest file");
		assertEquals(3, hits.get(0).files().size());
		assertEquals(NOW - 3 * DAY, hits.get(0).start());
	}

	@Test
	void subagentsAreSearchedOnRequest() throws Exception {
		Path main = write(project, A, NOW - DAY, user(A, project, "main", NOW - DAY, "Delegate"));
		Path agents = Files.createDirectories(main.resolveSibling(A).resolve("subagents"));
		Files.writeString(agents.resolve("agent-1.jsonl"),
				"{\"type\":\"assistant\",\"isSidechain\":true,\"sessionId\":\"" + A + "\",\"timestamp\":\"" + time(NOW - DAY)
						+ "\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Zanzibar found\"}]}}\n");
		assertEquals(List.of(), ids(search("Zanzibar")));
		List<Hit> hits = ConversationSearch.search(Options.of(ConversationSearch.parse("Zanzibar", Mode.AUTO)).withSubagents(true),
				() -> false);
		assertEquals(List.of(A), ids(hits));
		assertEquals(main, hits.get(0).file());
		assertEquals(1, hits.get(0).counts().get(Origin.CLAUDE));
	}

	@Test
	void skipsWhatIsNotAMessageOfTheUser() throws Exception {
		write(project, A, NOW - DAY, "not json at all {\"type\":\"user\" Quokka",
				"{\"type\":\"user\",\"sessionId\":\"" + A + "\",\"message\":{\"role\":\"user\",\"content\":\"<command-name>Quokka</command-name>\"}}",
				"{\"type\":\"user\",\"isMeta\":true,\"message\":{\"role\":\"user\",\"content\":\"Quokka meta\"}}",
				"{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"<system-reminder>Quokka\"}]}}",
				"{\"type\":\"summary\",\"summary\":\"Quokka\"}", result(A, NOW - DAY, "Quokka in a tool output"));
		List<Hit> hits = search("quokka");
		assertEquals(List.of(A), ids(hits));
		assertEquals(0, hits.get(0).mine());
		assertEquals(1, hits.get(0).counts().get(Origin.TOOL_RESULT));
		assertEquals(0, ConversationSearch.units(Map.of("type", "system")).size());
		assertEquals(0, ConversationSearch.touched(Map.of("type", "assistant"), true).size());
	}

	@Test
	void searchesTheExtraFoldersToo() throws Exception {
		Path extra = Files.createDirectories(temp.resolve("extra").resolve(Conversations.folderName(project)));
		Files.writeString(extra.resolve(A + ".jsonl"), user(A, project, "main", NOW - DAY, "Hello Wombat") + "\n");
		assertEquals(List.of(), ids(search("wombat")));
		Options options = Options.of(ConversationSearch.parse("wombat", Mode.AUTO)).withRoots(List.of(temp.resolve("extra")));
		assertEquals(List.of(A), ids(ConversationSearch.search(options, () -> false)));
		assertEquals(List.of(A), ids(ConversationSearch.search(options.in(project), () -> false)));
		environment.put("CLAUDE_CONFIG_DIR", temp.resolve("extra").getParent().resolve("nothing").toString());
		assertEquals(List.of(), ids(search("wombat")));
	}
}
