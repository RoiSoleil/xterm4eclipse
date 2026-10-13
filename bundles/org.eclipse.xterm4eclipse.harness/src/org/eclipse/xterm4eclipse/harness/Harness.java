package org.eclipse.xterm4eclipse.harness;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A coding agent that runs in a terminal ("harness"): Claude Code, Codex, OpenCode... What its
 * command line looks like, how it comes back to a conversation, and how it is given the MCP server
 * of Eclipse. The agents are listed in {@link Harnesses}.
 *
 * @param id
 *            names the agent in the files: its icon, what a terminal keeps of it
 * @param label
 *            the name of the agent in the menus
 * @param product
 *            its name in the messages
 * @param programs
 *            the names of its program on the PATH, without the extension of a launcher of Windows
 * @param interactive
 *            the subcommand that runs a conversation in the terminal, when the program needs one
 *            ({@code goose session}), else {@code null}
 * @param commands
 *            the other subcommands of the program: a command line that runs one is left as it is
 * @param chosen
 *            the options with which a command line chooses its conversation itself, or has none
 * @param valued
 *            options followed by a value, which is neither a subcommand nor a prompt
 * @param sessions
 *            how the agent comes back to a conversation
 * @param mcp
 *            how the agent is given an MCP server, or {@code null} if it has no command for it
 */
record Harness(String id, String label, String product, List<String> programs, String interactive, Set<String> commands,
		Set<String> chosen, Set<String> valued, Sessions sessions, Mcp mcp) {

	/** What the arguments of the program ask for. */
	enum Run {
		/** A conversation in the terminal, with options only. */
		CONVERSATION,
		/** A conversation in the terminal that starts with a prompt. */
		PROMPT,
		/** Something else, or a conversation that the command line chooses itself. */
		OTHER
	}

	/** The icon of the agent, a resource of the plug-in. */
	String icon() {
		return "icons/" + id + ".png"; //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Where a terminal keeps the conversation of the agent. */
	String sessionKey() {
		return id + "Session"; //$NON-NLS-1$
	}

	/** Where a terminal keeps that the user has talked to the agent. */
	String conversedKey() {
		return id + "Conversed"; //$NON-NLS-1$
	}

	/** The name of the program in the messages, for example {@code codex}. */
	String program() {
		return programs.get(0);
	}

	Run run(List<String> arguments) {
		boolean prompt = false;
		boolean first = true;
		for (int i = 0; i < arguments.size(); i++) {
			String argument = arguments.get(i);
			if (argument.startsWith("-")) { //$NON-NLS-1$
				int equals = argument.startsWith("--") ? argument.indexOf('=') : -1; //$NON-NLS-1$
				if (chosen.contains(equals < 0 ? argument : argument.substring(0, equals))) {
					return Run.OTHER;
				}
				if (valued.contains(argument)) {
					i++;
				}
				continue;
			}
			boolean conversation = first && argument.equals(interactive);
			first = false;
			if (conversation) {
				continue;
			}
			if (interactive != null || commands.contains(argument)) {
				// Another command of the program, or of its conversations (goose session list).
				return Run.OTHER;
			}
			prompt = true;
		}
		return prompt ? Run.PROMPT : Run.CONVERSATION;
	}

	/**
	 * @return the arguments to add to those of the command line for the conversation, with its
	 *         subcommand first if the command line does not have it
	 */
	List<String> conversation(List<String> arguments, List<String> added) {
		if (interactive == null || arguments.contains(interactive)) {
			return added;
		}
		List<String> result = new ArrayList<>();
		result.add(interactive);
		result.addAll(added);
		return result;
	}

	/**
	 * How an agent comes back to a conversation. In the arguments, <code>{id}</code> stands for the
	 * conversation.
	 *
	 * @param fresh
	 *            starts a conversation with an id that the terminal chooses, or {@code null} if the
	 *            agent chooses its ids itself
	 * @param resume
	 *            resumes the conversation of an id, or {@code null}
	 * @param last
	 *            resumes the last conversation of the folder, or {@code null}
	 * @param store
	 *            tells if the agent has kept the conversation of an id, or {@code null} if this is
	 *            not known
	 * @param list
	 *            the arguments that list the conversations of the folder as JSON, the newest first,
	 *            each with its {@code id} and the time it was {@code created}; or {@code null}
	 * @param id
	 *            what an id of the agent looks like: nothing that a shell would interpret
	 */
	record Sessions(List<String> fresh, List<String> resume, List<String> last, Store store, List<String> list,
			String id) {

		static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"; //$NON-NLS-1$

		/** The terminal chooses the id of the conversation, and resumes it by this id. */
		static Sessions own(List<String> fresh, List<String> resume, Store store) {
			return new Sessions(fresh, resume, null, store, null, UUID);
		}

		/** Only the last conversation of the folder can be resumed. */
		static Sessions last(String... last) {
			return new Sessions(null, null, List.of(last), null, null, UUID);
		}

		/** The agent does not resume a conversation from its command line. */
		static Sessions none() {
			return new Sessions(null, null, null, null, null, UUID);
		}

		boolean isId(String value) {
			return value != null && value.matches(id);
		}

		/** @return the arguments with the id of the conversation */
		static List<String> with(List<String> arguments, String id) {
			return arguments.stream().map(argument -> argument.replace("{id}", id)).toList(); //$NON-NLS-1$
		}
	}

	/** Where an agent keeps its conversations: tells if it has kept one. */
	@FunctionalInterface
	interface Store {
		boolean exists(String id);
	}

	/**
	 * How an agent is given the MCP server of Eclipse. In the arguments, <code>{name}</code>,
	 * <code>{url}</code> and <code>{token}</code> stand for the server, and <code>{variable}</code>
	 * for the variable of the environment that holds its token.
	 *
	 * @param add
	 *            the arguments that add the server for the user
	 * @param remove
	 *            the arguments that remove it, to add it again when the agent refuses to replace a
	 *            server; or {@code null} if it replaces it
	 * @param userFiles
	 *            the files in which the agent keeps the servers of the user
	 * @param projectFiles
	 *            the files in which it keeps those of a project, from the folder of the project
	 * @param tokenInEnvironment
	 *            the agent reads the token in the environment of its terminal, not in its files
	 */
	record Mcp(List<String> add, List<String> remove, List<Supplier<Path>> userFiles, List<String> projectFiles,
			boolean tokenInEnvironment) {

		/** @return the arguments that add this server */
		List<String> adding(EclipseMcp.Endpoint endpoint) {
			return fill(add, endpoint);
		}

		/** @return the arguments that remove the server, or {@code null} */
		List<String> removing() {
			return remove == null ? null : fill(remove, null);
		}

		private static List<String> fill(List<String> arguments, EclipseMcp.Endpoint endpoint) {
			return arguments.stream()
					.map(argument -> argument.replace("{name}", EclipseMcp.NAME) //$NON-NLS-1$
							.replace("{variable}", EclipseMcp.TOKEN_VARIABLE) //$NON-NLS-1$
							.replace("{url}", endpoint == null ? "" : endpoint.url()) //$NON-NLS-1$ //$NON-NLS-2$
							.replace("{token}", endpoint == null ? "" : endpoint.token())) //$NON-NLS-1$ //$NON-NLS-2$
					.toList();
		}
	}
}
