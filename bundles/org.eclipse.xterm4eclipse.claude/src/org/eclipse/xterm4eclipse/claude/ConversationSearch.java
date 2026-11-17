package org.eclipse.xterm4eclipse.claude;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

final class ConversationSearch {

	enum Mode {
		AUTO, EXACT, REGEX
	}

	enum Origin {
		USER(5), CLAUDE(2), TOOL_USE(3), TOOL_RESULT(1);

		final int weight;

		Origin(int weight) {
			this.weight = weight;
		}
	}

	enum Author {
		ANY, USER, CLAUDE, TOOLS;

		boolean accepts(Origin origin) {
			return switch (this) {
			case ANY -> true;
			case USER -> origin == Origin.USER;
			case CLAUDE -> origin == Origin.CLAUDE;
			case TOOLS -> origin == Origin.TOOL_USE || origin == Origin.TOOL_RESULT;
			};
		}
	}

	enum Kind {
		NONE, REVIEW, NUMBER, TICKET, COMMIT, CHANGE_ID, URL, PATH, TYPE, WORDS, EXACT, REGEX
	}

	record Variant(String text, int score, boolean leftBoundary, boolean rightBoundary) {
	}

	record Query(Kind kind, String description, List<Variant> variants, List<List<String>> any, List<String> excluded,
			Pattern regex, String path, boolean fuzzy) {

		boolean empty() {
			return kind == Kind.NONE;
		}
	}

	record Options(Query query, File folder, String branch, long since, long until, Author author, boolean modifiedOnly,
			boolean subagents, List<Path> extraRoots) {

		static Options of(Query query) {
			return new Options(query, null, null, 0, Long.MAX_VALUE, Author.ANY, false, false, List.of());
		}

		Options in(File directory) {
			return new Options(query, directory, branch, since, until, author, modifiedOnly, subagents, extraRoots);
		}

		Options onBranch(String name) {
			return new Options(query, folder, name, since, until, author, modifiedOnly, subagents, extraRoots);
		}

		Options between(long from, long to) {
			return new Options(query, folder, branch, from, to, author, modifiedOnly, subagents, extraRoots);
		}

		Options by(Author who) {
			return new Options(query, folder, branch, since, until, who, modifiedOnly, subagents, extraRoots);
		}

		Options modifiedOnly(boolean only) {
			return new Options(query, folder, branch, since, until, author, only, subagents, extraRoots);
		}

		Options withSubagents(boolean with) {
			return new Options(query, folder, branch, since, until, author, modifiedOnly, with, extraRoots);
		}

		Options withRoots(List<Path> roots) {
			return new Options(query, folder, branch, since, until, author, modifiedOnly, subagents, List.copyOf(roots));
		}
	}

	record Hit(String id, Path file, String cwd, String branch, String title, long start, long end,
			Map<Origin, Integer> counts, Set<String> modified, String snippet, int matchStart, int matchEnd, int score,
			boolean recent, List<Path> files) {

		int mine() {
			return counts.getOrDefault(Origin.USER, 0);
		}

		int others() {
			return counts.values().stream().mapToInt(Integer::intValue).sum() - mine();
		}

		Conversations.Conversation conversation() {
			return new Conversations.Conversation(id, title, end, cwd == null ? null : new File(cwd));
		}
	}

	static final long RECENT = 2 * 60 * 1000L;
	static long now = -1;
	private static final int SNIPPET = 60;
	private static final Pattern SESSION = Pattern.compile("\"sessionId\":\"([0-9a-fA-F-]{36})\"");
	private static final Pattern CWD = Pattern.compile("\"cwd\":\"((?:[^\"\\\\]|\\\\.)*)\"");
	private static final Pattern BRANCH = Pattern.compile("\"gitBranch\":\"((?:[^\"\\\\]|\\\\.)*)\"");
	private static final Pattern TIMESTAMP = Pattern.compile("\"timestamp\":\"([^\"]+)\"");
	private static final Pattern GITHUB = Pattern.compile(
			"(?i)^(?:https?://)?(?:www\\.)?([\\w.-]+)/([\\w.-]+)/([\\w.-]+)/(pull|pulls|issues)/(\\d+)(?:[/?#].*)?$");
	private static final Pattern GITLAB = Pattern.compile(
			"(?i)^(?:https?://)?([\\w.-]+)/((?:[\\w.-]+/)*[\\w.-]+)/-/(merge_requests|issues)/(\\d+)(?:[/?#].*)?$");
	private static final Pattern GERRIT = Pattern.compile(
			"(?i)^(?:https?://)?([\\w.-]+)/(?:[\\w.-]+/)*c/((?:[\\w.-]+/)*[\\w.-]+)/\\+/(\\d+)(?:[/?#].*)?$");
	private static final Pattern BITBUCKET = Pattern.compile(
			"(?i)^(?:https?://)?([\\w.-]+)/(?:projects/)?([\\w.-]+)/(?:repos/)?([\\w.-]+)/pull-requests/(\\d+)(?:[/?#].*)?$");
	private static final Pattern REPO_REFERENCE = Pattern.compile("^([\\w.-]+)/([\\w.-]+)[#!](\\d+)$");
	private static final Pattern NUMBER = Pattern.compile("^\\d+$");
	private static final Pattern TICKET = Pattern.compile("^(?:[A-Za-z][A-Za-z0-9]*-\\d+|#\\d+)$");
	private static final Pattern CHANGE_ID = Pattern.compile("^I[0-9a-f]{8,40}$");
	private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{7,40}$");
	private static final Pattern URL = Pattern.compile("(?i)^https?://\\S+$");
	private static final Pattern PATH = Pattern.compile("^@?(?:[A-Za-z]:)?[\\\\/]?(?:[^\\s\\\\/]+[\\\\/])+[^\\s\\\\/]+$|^@?[^\\s\\\\/]+\\.[A-Za-z0-9]{1,8}(?:#L\\d+(?:-\\d+)?)?$");
	private static final Pattern TYPE = Pattern.compile("^[A-Z][a-z0-9_]+[A-Z][A-Za-z0-9_]*$");
	private static final Pattern WORDS_OF_TEXT = Pattern.compile("[\\p{L}\\p{N}_]+");
	private static final Pattern WORD = Pattern.compile("\"([^\"]*)\"|(\\S+)");
	private static final Set<String> EDITS = Set.of("Edit", "Write", "MultiEdit", "NotebookEdit");

	private ConversationSearch() {
	}

	static Query parse(String input, Mode mode) {
		return parse(input, mode, false);
	}

	static Query parse(String input, Mode mode, boolean fuzzy) {
		String text = input == null ? "" : input.strip();
		if (text.isEmpty()) {
			return new Query(Kind.NONE, "All conversations", List.of(), List.of(), List.of(), null, null, false);
		}
		if (mode == Mode.REGEX) {
			try {
				return new Query(Kind.REGEX, "Regular expression " + text, List.of(), List.of(), List.of(),
						Pattern.compile(text, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), null, false);
			} catch (PatternSyntaxException e) {
				return new Query(Kind.REGEX, "Invalid regular expression: " + e.getDescription(), List.of(), List.of(),
						List.of(), Pattern.compile("(?!)"), null, false);
			}
		}
		if (mode == Mode.EXACT) {
			return variants(Kind.EXACT, "Exact text “" + text + '”', new Variant(normalize(text), 100, false, false));
		}
		Matcher matcher;
		if ((matcher = GITHUB.matcher(text)).matches() && matcher.group(1).contains(".")) {
			String kind = matcher.group(4).toLowerCase(Locale.ROOT);
			return review(matcher.group(2), matcher.group(3), matcher.group(5), kind.startsWith("pull") ? "pull" : "issues");
		}
		if ((matcher = GITLAB.matcher(text)).matches()) {
			String project = matcher.group(2);
			int slash = project.lastIndexOf('/');
			return review(slash < 0 ? project : project.substring(0, slash), slash < 0 ? project : project.substring(slash + 1),
					matcher.group(4), "-/" + matcher.group(3).toLowerCase(Locale.ROOT));
		}
		if ((matcher = GERRIT.matcher(text)).matches()) {
			String number = matcher.group(3);
			String project = matcher.group(2);
			return variants(Kind.REVIEW, "Gerrit change " + project + " " + number,
					new Variant(normalize("c/" + project + "/+/" + number), 100, false, true),
					new Variant(normalize(project + "/+/" + number), 90, false, true),
					new Variant(normalize(project + "#" + number), 80, true, true), new Variant(number, 20, true, true));
		}
		if ((matcher = BITBUCKET.matcher(text)).matches()) {
			return review(matcher.group(2), matcher.group(3), matcher.group(4), "pull-requests");
		}
		if ((matcher = REPO_REFERENCE.matcher(text)).matches()) {
			return review(matcher.group(1), matcher.group(2), matcher.group(3), "pull");
		}
		if (NUMBER.matcher(text).matches()) {
			return variants(Kind.NUMBER, "Number " + text, new Variant(text, 100, true, true));
		}
		if (TICKET.matcher(text).matches()) {
			return variants(Kind.TICKET, "Ticket " + text, new Variant(normalize(text), 100, true, true));
		}
		if (CHANGE_ID.matcher(text).matches()) {
			List<Variant> list = new ArrayList<>();
			list.add(new Variant(normalize(text), 100, true, true));
			if (text.length() > 9) {
				list.add(new Variant(normalize(text.substring(0, 9)), 60, true, false));
			}
			return variants(Kind.CHANGE_ID, "Gerrit Change-Id " + text, list.toArray(Variant[]::new));
		}
		if (COMMIT.matcher(text).matches() && text.matches(".*\\d.*") && text.matches(".*[a-f].*")) {
			List<Variant> list = new ArrayList<>();
			list.add(new Variant(text, 100, true, true));
			list.add(new Variant(text, 80, true, false));
			if (text.length() > 7) {
				list.add(new Variant(text.substring(0, 7), 60, true, false));
			}
			return variants(Kind.COMMIT, "Commit " + text, list.toArray(Variant[]::new));
		}
		if (URL.matcher(text).matches()) {
			String url = text.replaceFirst("(?i)^https?://", "").replaceFirst("[?#].*$", "").replaceFirst("/+$", "");
			return variants(Kind.URL, "Address " + url, new Variant(normalize(url), 100, false, false));
		}
		if (PATH.matcher(text).matches()) {
			String path = normalizePath(text);
			String name = path.substring(path.lastIndexOf('/') + 1);
			List<Variant> list = new ArrayList<>();
			list.add(new Variant(path, 100, true, true));
			if (!name.equals(path)) {
				list.add(new Variant(name, 40, true, true));
			}
			return new Query(Kind.PATH, "File " + path, List.copyOf(list), List.of(), List.of(), null, path, false);
		}
		if (TYPE.matcher(text).matches()) {
			return new Query(Kind.TYPE, "Type " + text, List.of(new Variant(normalize(text), 100, true, true)), List.of(),
					List.of(), null, text, false);
		}
		return words(text, fuzzy);
	}

	private static Query review(String owner, String repository, String number, String path) {
		String reference = owner + "/" + repository;
		boolean issue = path.endsWith("issues");
		List<Variant> list = new ArrayList<>();
		list.add(new Variant(normalize(reference + "/" + path + "/" + number), 100, false, true));
		if (!issue) {
			list.add(new Variant(normalize(reference + "/pull/" + number), 100, false, true));
		}
		list.add(new Variant(normalize(reference + "#" + number), 90, true, true));
		list.add(new Variant(normalize(repository + "#" + number), 60, true, true));
		list.add(new Variant(number, 20, true, true));
		return variants(Kind.REVIEW, (issue ? "Issue " : "Review ") + reference + "#" + number, list.toArray(Variant[]::new));
	}

	private static Query variants(Kind kind, String description, Variant... variants) {
		Map<String, Variant> unique = new LinkedHashMap<>();
		for (Variant variant : variants) {
			unique.merge(variant.text() + variant.leftBoundary() + variant.rightBoundary(), variant,
					(a, b) -> a.score() >= b.score() ? a : b);
		}
		return new Query(kind, description, List.copyOf(unique.values()), List.of(), List.of(), null, null, false);
	}

	private static Query words(String text, boolean fuzzy) {
		List<List<String>> any = new ArrayList<>();
		List<String> all = new ArrayList<>();
		List<String> excluded = new ArrayList<>();
		Matcher matcher = WORD.matcher(text);
		while (matcher.find()) {
			String phrase = matcher.group(1);
			String word = matcher.group(2);
			if (phrase != null) {
				if (!phrase.isBlank()) {
					all.add(normalize(phrase.strip()));
				}
			} else if ("OR".equals(word)) {
				if (!all.isEmpty()) {
					any.add(List.copyOf(all));
					all.clear();
				}
			} else if (word.length() > 1 && word.startsWith("-")) {
				excluded.add(normalize(word.substring(1)));
			} else {
				all.add(normalize(word));
			}
		}
		if (!all.isEmpty()) {
			any.add(List.copyOf(all));
		}
		StringBuilder description = new StringBuilder(any.isEmpty() ? "All conversations" : "Words ");
		for (int i = 0; i < any.size(); i++) {
			description.append(i == 0 ? "" : " or ").append(String.join(" and ", any.get(i)));
		}
		if (!excluded.isEmpty()) {
			description.append(", without ").append(String.join(", ", excluded));
		}
		if (fuzzy) {
			description.append(" (approximate)");
		}
		if (any.isEmpty() && excluded.isEmpty()) {
			return new Query(Kind.NONE, "All conversations", List.of(), List.of(), List.of(), null, null, false);
		}
		return new Query(Kind.WORDS, description.toString(), List.of(), List.copyOf(any), List.copyOf(excluded), null, null,
				fuzzy);
	}

	static String normalize(String text) {
		return Normalized.of(text).text;
	}

	static String normalizePath(String path) {
		String result = path.strip();
		if (result.startsWith("@")) {
			result = result.substring(1);
		}
		result = result.replaceFirst("#L\\d+(?:-L?\\d+)?$", "").replace('\\', '/').replaceAll("/{2,}", "/");
		if (result.matches("^[A-Za-z]:.*")) {
			result = Character.toLowerCase(result.charAt(0)) + result.substring(1);
		}
		while (result.startsWith("./")) {
			result = result.substring(2);
		}
		return normalize(result);
	}

	static final class Normalized {
		final String text;
		final int[] origin;

		private Normalized(String text, int[] origin) {
			this.text = text;
			this.origin = origin;
		}

		static Normalized of(String text) {
			StringBuilder out = new StringBuilder(text.length());
			int[] origin = new int[text.length() + 1];
			int length = 0;
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				String piece;
				if (c < 128) {
					piece = c == '\\' ? "/" : String.valueOf(Character.toLowerCase(c));
				} else {
					piece = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
							.toLowerCase(Locale.ROOT);
				}
				for (int j = 0; j < piece.length(); j++) {
					if (length + 1 >= origin.length) {
						origin = java.util.Arrays.copyOf(origin, origin.length * 2 + 2);
					}
					origin[length++] = i;
					out.append(piece.charAt(j));
				}
			}
			if (length >= origin.length) {
				origin = java.util.Arrays.copyOf(origin, length + 1);
			}
			origin[length] = text.length();
			return new Normalized(out.toString(), origin);
		}
	}

	record Unit(Origin origin, String text) {
	}

	record Match(int start, int end, int score) {
	}

	static Match find(Query query, String text) {
		if (query.empty()) {
			return null;
		}
		if (query.regex() != null) {
			Matcher matcher = query.regex().matcher(text);
			return matcher.find() ? new Match(matcher.start(), matcher.end(), 100) : null;
		}
		Normalized normalized = Normalized.of(text);
		String haystack = normalized.text;
		if (query.kind() == Kind.WORDS) {
			for (List<String> group : query.any()) {
				int first = -1;
				int firstEnd = -1;
				boolean found = true;
				for (String word : group) {
					int[] at = locate(haystack, word, query.fuzzy());
					if (at == null) {
						found = false;
						break;
					}
					if (first < 0) {
						first = at[0];
						firstEnd = at[1];
					}
				}
				if (found) {
					return new Match(normalized.origin[first], normalized.origin[firstEnd], 100);
				}
			}
			return null;
		}
		Match best = null;
		for (Variant variant : query.variants()) {
			int index = indexOf(haystack, variant, 0);
			if (index >= 0 && (best == null || variant.score() > best.score())) {
				best = new Match(normalized.origin[index], normalized.origin[index + variant.text().length()], variant.score());
			}
		}
		return best;
	}

	static boolean containsAllWords(Query query, Set<String> seen) {
		if (query.any().isEmpty()) {
			return true;
		}
		for (List<String> group : query.any()) {
			if (seen.containsAll(group)) {
				return true;
			}
		}
		return false;
	}

	private static int[] locate(String haystack, String word, boolean fuzzy) {
		int index = haystack.indexOf(word);
		if (index >= 0) {
			return new int[] { index, index + word.length() };
		}
		if (!fuzzy || word.length() < 6 || word.contains(" ")) {
			return null;
		}
		int allowed = word.length() >= 10 ? 2 : 1;
		Matcher matcher = WORDS_OF_TEXT.matcher(haystack);
		while (matcher.find()) {
			String candidate = matcher.group();
			if (Math.abs(candidate.length() - word.length()) <= allowed && distance(candidate, word, allowed) <= allowed) {
				return new int[] { matcher.start(), matcher.end() };
			}
		}
		return null;
	}

	static int distance(String a, String b, int limit) {
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			current[0] = i;
			int smallest = current[0];
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
				smallest = Math.min(smallest, current[j]);
			}
			if (smallest > limit) {
				return limit + 1;
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[b.length()];
	}

	static int indexOf(String haystack, Variant variant, int from) {
		String needle = variant.text();
		int index = haystack.indexOf(needle, from);
		while (index >= 0) {
			boolean left = !variant.leftBoundary() || index == 0 || !boundaryChar(haystack.charAt(index - 1), needle.charAt(0), true);
			int end = index + needle.length();
			boolean right = !variant.rightBoundary() || end >= haystack.length()
					|| !boundaryChar(haystack.charAt(end), needle.charAt(needle.length() - 1), false);
			if (left && right) {
				return index;
			}
			index = haystack.indexOf(needle, index + 1);
		}
		return -1;
	}

	private static boolean boundaryChar(char neighbour, char edge, boolean left) {
		if (Character.isLetterOrDigit(edge) || edge == '_') {
			return Character.isLetterOrDigit(neighbour) || neighbour == '_'
					|| (left && Character.isDigit(edge) && neighbour == '.');
		}
		return false;
	}

	private static boolean mayContain(Query query, String line) {
		if (query.empty() || query.regex() != null || query.fuzzy()) {
			return true;
		}
		if (line.contains("\\u")) {
			return true;
		}
		String unescaped = unescapeLine(line);
		boolean ascii = true;
		for (int i = 0; i < unescaped.length() && ascii; i++) {
			ascii = unescaped.charAt(i) < 128;
		}
		String haystack = ascii ? unescaped.toLowerCase(Locale.ROOT).replace('\\', '/') : Normalized.of(unescaped).text;
		if (query.kind() == Kind.WORDS) {
			for (List<String> group : query.any()) {
				for (String word : group) {
					if (haystack.contains(word)) {
						return true;
					}
				}
			}
			return false;
		}
		for (Variant variant : query.variants()) {
			if (haystack.contains(variant.text())) {
				return true;
			}
		}
		return false;
	}

	static String unescapeLine(String line) {
		if (line.indexOf('\\') < 0) {
			return line;
		}
		StringBuilder out = new StringBuilder(line.length());
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '\\' && i + 1 < line.length()) {
				char next = line.charAt(++i);
				out.append(switch (next) {
				case 'n' -> '\n';
				case 't' -> '\t';
				case 'r' -> '\r';
				case 'b' -> '\b';
				case 'f' -> '\f';
				default -> next;
				});
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	static List<Unit> units(Map<String, Object> entry) {
		List<Unit> units = new ArrayList<>();
		String type = Json.string(entry, "type");
		if (!"user".equals(type) && !"assistant".equals(type) || Boolean.TRUE.equals(entry.get("isMeta"))) {
			return units;
		}
		Map<String, Object> message = Json.object(entry, "message");
		Object content = message == null ? null : message.get("content");
		if (content instanceof String string) {
			if ("assistant".equals(type)) {
				units.add(new Unit(Origin.CLAUDE, string));
			} else if (!string.startsWith("<") && !Boolean.TRUE.equals(entry.get("isCompactSummary"))) {
				units.add(new Unit(Origin.USER, string));
			}
		} else if (content instanceof List<?> blocks) {
			for (Object block : blocks) {
				if (!(block instanceof Map<?, ?> map)) {
					continue;
				}
				Object kind = map.get("type");
				if ("text".equals(kind) && map.get("text") instanceof String string) {
					if ("assistant".equals(type)) {
						units.add(new Unit(Origin.CLAUDE, string));
					} else if (!string.startsWith("<")) {
						units.add(new Unit(Origin.USER, string));
					}
				} else if ("tool_use".equals(kind)) {
					units.add(new Unit(Origin.TOOL_USE, Json.write(map.get("input"))));
				} else if ("tool_result".equals(kind)) {
					Object result = map.get("content");
					units.add(new Unit(Origin.TOOL_RESULT, result instanceof String string ? string : Json.write(result)));
				}
			}
		}
		return units;
	}

	static Set<String> touched(Map<String, Object> entry, boolean modifiedOnly) {
		Set<String> files = new LinkedHashSet<>();
		Map<String, Object> message = Json.object(entry, "message");
		if (message == null || !(message.get("content") instanceof List<?> blocks)) {
			return files;
		}
		for (Object block : blocks) {
			if (block instanceof Map<?, ?> map && "tool_use".equals(map.get("type")) && map.get("input") instanceof Map<?, ?> input) {
				Object name = map.get("name");
				boolean edit = EDITS.contains(name);
				if ((edit || !modifiedOnly && "Read".equals(name))) {
					Object path = input.get("file_path") != null ? input.get("file_path") : input.get("notebook_path");
					if (path instanceof String string) {
						files.add(string);
					}
				}
			}
		}
		return files;
	}

	static boolean sameFile(String file, Query query) {
		String path = normalizePath(file);
		if (query.kind() == Kind.PATH) {
			String wanted = query.path();
			return path.equals(wanted) || path.endsWith("/" + wanted) || wanted.endsWith("/" + path);
		}
		if (query.kind() == Kind.TYPE) {
			String name = path.substring(path.lastIndexOf('/') + 1);
			int dot = name.indexOf('.');
			return (dot < 0 ? name : name.substring(0, dot)).equals(normalize(query.path()));
		}
		return false;
	}

	static List<Path> roots(List<Path> extra) {
		List<Path> roots = new ArrayList<>();
		roots.add(Conversations.home().resolve("projects"));
		roots.addAll(extra);
		return roots;
	}

	private record Source(Path file, String parentId) {
	}

	static List<Source> sources(Options options) {
		List<Source> sources = new ArrayList<>();
		List<Path> folders = new ArrayList<>();
		if (options.folder() != null) {
			Path folder = Conversations.folder(options.folder());
			if (folder != null) {
				folders.add(folder);
			}
			for (Path root : options.extraRoots()) {
				Path extra = root.resolve(Conversations.folderName(options.folder()));
				if (Files.isDirectory(extra)) {
					folders.add(extra);
				}
			}
		} else {
			for (Path root : roots(options.extraRoots())) {
				if (!Files.isDirectory(root)) {
					continue;
				}
				try (Stream<Path> children = Files.list(root)) {
					children.filter(Files::isDirectory).forEach(folders::add);
				} catch (IOException e) {
					continue;
				}
			}
		}
		for (Path folder : folders) {
			try (Stream<Path> children = Files.list(folder)) {
				for (Path child : (Iterable<Path>) children::iterator) {
					String name = child.getFileName().toString();
					if (name.endsWith(".jsonl") && Files.isRegularFile(child)) {
						sources.add(new Source(child, null));
					} else if (options.subagents() && Files.isDirectory(child) && ClaudeCommand.SESSION_ID.matcher(name).matches()) {
						Path subagents = child.resolve("subagents");
						if (Files.isDirectory(subagents)) {
							try (Stream<Path> agents = Files.list(subagents)) {
								agents.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
										.forEach(path -> sources.add(new Source(path, name)));
							}
						}
					}
				}
			} catch (IOException e) {
				continue;
			}
		}
		return sources;
	}

	private static final class Scan {
		String id;
		Path file;
		String cwd;
		String branch;
		long start = Long.MAX_VALUE;
		long end = Long.MIN_VALUE;
		final Set<String> sessions = new LinkedHashSet<>();
		final Map<Origin, Integer> counts = new EnumMap<>(Origin.class);
		final Set<String> modified = new LinkedHashSet<>();
		final Set<String> read = new LinkedHashSet<>();
		final Set<String> words = new LinkedHashSet<>();
		boolean excluded;
		String snippet;
		int matchStart;
		int matchEnd;
		int score;
		long modifiedTime;
		int badDates;
		final List<Path> files = new ArrayList<>();
		final List<String> parents = new ArrayList<>();
	}

	static List<Hit> search(Options options, BooleanSupplier cancelled) {
		Query query = options.query();
		long now = ConversationSearch.now >= 0 ? ConversationSearch.now : System.currentTimeMillis();
		Map<String, Scan> scans = new LinkedHashMap<>();
		for (Source source : sources(options)) {
			if (cancelled.getAsBoolean()) {
				return List.of();
			}
			long modifiedTime;
			try {
				modifiedTime = Files.getLastModifiedTime(source.file()).toMillis();
			} catch (IOException e) {
				continue;
			}
			if (modifiedTime < options.since()) {
				continue;
			}
			Scan scan = scan(source, query, options, cancelled);
			if (scan == null) {
				continue;
			}
			scan.modifiedTime = modifiedTime;
			String key = source.parentId() != null ? source.parentId() : scan.id;
			scans.merge(key, scan, ConversationSearch::merge);
		}
		List<Scan> all = new ArrayList<>(scans.values());
		int[] parent = new int[all.size()];
		for (int i = 0; i < parent.length; i++) {
			parent[i] = i;
		}
		Map<String, Integer> owner = new HashMap<>();
		for (int i = 0; i < all.size(); i++) {
			for (String session : all.get(i).sessions) {
				Integer previous = owner.putIfAbsent(session, i);
				if (previous != null) {
					parent[root(parent, i)] = root(parent, previous);
				}
			}
		}
		Map<Integer, Scan> components = new LinkedHashMap<>();
		for (int i = 0; i < all.size(); i++) {
			components.merge(root(parent, i), all.get(i), ConversationSearch::merge);
		}
		List<Scan> grouped = new ArrayList<>(components.values());
		List<Hit> hits = new ArrayList<>();
		for (Scan scan : grouped) {
			long end = scan.end == Long.MIN_VALUE ? scan.modifiedTime : scan.end;
			long start = scan.start == Long.MAX_VALUE ? end : scan.start;
			if (end < options.since() || start > options.until()) {
				continue;
			}
			if (options.branch() != null && !options.branch().isBlank()
					&& (scan.branch == null || !scan.branch.toLowerCase(Locale.ROOT).contains(options.branch().strip().toLowerCase(Locale.ROOT)))) {
				continue;
			}
			if (!query.empty()) {
				if (scan.excluded) {
					continue;
				}
				if (query.kind() == Kind.WORDS) {
					if (!containsAllWords(query, scan.words)) {
						continue;
					}
				} else if (scan.score == 0) {
					continue;
				}
				if (options.modifiedOnly() && scan.modified.stream().noneMatch(file -> sameFile(file, query))) {
					if (query.kind() == Kind.PATH || query.kind() == Kind.TYPE || scan.modified.isEmpty()) {
						continue;
					}
				}
			} else if (options.modifiedOnly() && scan.modified.isEmpty()) {
				continue;
			}
			String title = Conversations.title(scan.file);
			hits.add(new Hit(scan.id, scan.file, scan.cwd, scan.branch, title == null ? "(no message)" : title, start, end,
					Map.copyOf(scan.counts), Set.copyOf(scan.modified), scan.snippet, scan.matchStart, scan.matchEnd,
					scan.score, now - scan.modifiedTime < RECENT, List.copyOf(scan.files)));
		}
		hits.sort(Comparator.comparingInt(Hit::score).reversed().thenComparing(Comparator.comparingLong(Hit::end).reversed()));
		return hits;
	}

	private static int root(int[] parent, int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	private static Scan merge(Scan a, Scan b) {
		Scan latest = a.end >= b.end ? a : b;
		Scan other = latest == a ? b : a;
		latest.start = Math.min(latest.start, other.start);
		latest.sessions.addAll(other.sessions);
		other.counts.forEach((origin, count) -> latest.counts.merge(origin, count, Integer::sum));
		latest.modified.addAll(other.modified);
		latest.read.addAll(other.read);
		latest.words.addAll(other.words);
		latest.excluded |= other.excluded;
		if (other.score > latest.score) {
			latest.score = other.score;
			latest.snippet = other.snippet;
			latest.matchStart = other.matchStart;
			latest.matchEnd = other.matchEnd;
		}
		latest.files.addAll(other.files);
		latest.modifiedTime = Math.max(latest.modifiedTime, other.modifiedTime);
		if (latest.cwd == null) {
			latest.cwd = other.cwd;
		}
		if (latest.branch == null) {
			latest.branch = other.branch;
		}
		return latest;
	}

	private static Scan scan(Source source, Query query, Options options, BooleanSupplier cancelled) {
		Scan scan = new Scan();
		String name = source.file().getFileName().toString();
		scan.id = source.parentId() != null ? source.parentId() : name.substring(0, name.length() - ".jsonl".length());
		scan.file = source.parentId() != null ? source.file().getParent().getParent().resolveSibling(source.parentId() + ".jsonl")
				: source.file();
		scan.files.add(source.file());
		scan.sessions.add(scan.id);
		boolean words = query.kind() == Kind.WORDS;
		try (BufferedReader reader = Files.newBufferedReader(source.file(), StandardCharsets.UTF_8)) {
			String line;
			int lines = 0;
			while ((line = reader.readLine()) != null) {
				if ((++lines & 1023) == 0 && cancelled.getAsBoolean()) {
					return null;
				}
				Matcher matcher = SESSION.matcher(line);
				if (matcher.find()) {
					scan.sessions.add(matcher.group(1));
				}
				if (scan.cwd == null && (matcher = CWD.matcher(line)).find()) {
					scan.cwd = unescape(matcher.group(1));
				}
				if ((matcher = BRANCH.matcher(line)).find() && !matcher.group(1).isEmpty()) {
					scan.branch = unescape(matcher.group(1));
				}
				if ((matcher = TIMESTAMP.matcher(line)).find()) {
					try {
						long time = Instant.parse(matcher.group(1)).toEpochMilli();
						scan.start = Math.min(scan.start, time);
						scan.end = Math.max(scan.end, time);
					} catch (DateTimeParseException e) {
						scan.badDates++;
					}
				}
				boolean user = line.contains("\"type\":\"user\"");
				boolean assistant = line.contains("\"type\":\"assistant\"");
				if (!user && !assistant || line.contains("\"isSidechain\":true") && source.parentId() == null) {
					continue;
				}
				boolean edits = assistant && line.contains("\"tool_use\"");
				boolean candidate = !query.empty() && mayContain(query, line);
				if (!candidate && !edits && query.excluded().isEmpty()) {
					continue;
				}
				Map<String, Object> entry = Json.object(line);
				if (entry == null) {
					continue;
				}
				if (edits) {
					scan.modified.addAll(touched(entry, true));
					scan.read.addAll(touched(entry, false));
				}
				if (query.empty() || !candidate && query.excluded().isEmpty()) {
					continue;
				}
				for (Unit unit : units(entry)) {
					if (!options.author().accepts(unit.origin())) {
						continue;
					}
					if (!query.excluded().isEmpty()) {
						String normalized = Normalized.of(unit.text()).text;
						for (String word : query.excluded()) {
							if (normalized.contains(word)) {
								scan.excluded = true;
							}
						}
					}
					if (!candidate) {
						continue;
					}
					if (words) {
						String normalized = Normalized.of(unit.text()).text;
						for (List<String> group : query.any()) {
							for (String word : group) {
								if (!scan.words.contains(word) && locate(normalized, word, query.fuzzy()) != null) {
									scan.words.add(word);
								}
							}
						}
					}
					Match match = find(query, unit.text());
					if (match == null && words) {
						match = firstWord(query, unit.text());
					}
					if (match == null) {
						continue;
					}
					scan.counts.merge(unit.origin(), 1, Integer::sum);
					int score = match.score() * unit.origin().weight;
					if (score > scan.score) {
						scan.score = score;
						int from = Math.max(0, match.start() - SNIPPET);
						int to = Math.min(unit.text().length(), match.end() + SNIPPET);
						String snippet = (from > 0 ? "…" : "") + unit.text().substring(from, to) + (to < unit.text().length() ? "…" : "");
						int offset = (from > 0 ? 1 : 0) - from;
						String flat = snippet.replaceAll("\\s", " ");
						scan.snippet = flat;
						scan.matchStart = match.start() + offset;
						scan.matchEnd = match.end() + offset;
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			if (scan.cwd == null && scan.counts.isEmpty() && scan.modified.isEmpty()) {
				return null;
			}
		}
		if ((query.kind() == Kind.PATH || query.kind() == Kind.TYPE)) {
			boolean touchedIt = scan.modified.stream().anyMatch(file -> sameFile(file, query))
					|| scan.read.stream().anyMatch(file -> sameFile(file, query));
			if (touchedIt) {
				int score = scan.modified.stream().anyMatch(file -> sameFile(file, query)) ? 100 * 4 : 100 * Origin.TOOL_USE.weight;
				if (score > scan.score) {
					scan.score = score;
				}
			}
		}
		return scan;
	}

	private static Match firstWord(Query query, String text) {
		Normalized normalized = Normalized.of(text);
		for (List<String> group : query.any()) {
			for (String word : group) {
				int[] at = locate(normalized.text, word, query.fuzzy());
				if (at != null) {
					return new Match(normalized.origin[at[0]], normalized.origin[at[1]], 50);
				}
			}
		}
		return null;
	}

	private static String unescape(String json) {
		Object value = Json.parse('"' + json + '"');
		return value instanceof String string ? string : json;
	}

}
