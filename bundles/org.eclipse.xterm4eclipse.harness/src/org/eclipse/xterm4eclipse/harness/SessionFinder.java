package org.eclipse.xterm4eclipse.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the conversation that an agent has started in a terminal, when the agent chooses its ids
 * itself: among the conversations that it lists for the folder, the one that started after the
 * program of the terminal, the nearest to the line that the user entered in it.
 */
final class SessionFinder {

	/** The conversations of the terminals of this Eclipse: each one belongs to a single terminal. */
	private static final Set<String> TAKEN = ConcurrentHashMap.newKeySet();

	/** How long after a line of the user the agent is asked for its conversations; a test may change it. */
	static long delayMillis = 3000;

	/**
	 * A conversation in the list of an agent.
	 *
	 * @param created
	 *            when it started, in milliseconds
	 */
	record Listed(String id, long created) {
	}

	private SessionFinder() {
	}

	/** @return the conversations of a list printed by the agent, as JSON */
	static List<Listed> parse(String output, Harness.Sessions sessions) {
		List<Listed> listed = new ArrayList<>();
		List<?> array = Json.firstArray(output);
		if (array != null) {
			for (Object element : array) {
				if (element instanceof Map<?, ?> object && object.get("id") instanceof String id && sessions.isId(id) //$NON-NLS-1$
						&& object.get("created") instanceof Number created) { //$NON-NLS-1$
					listed.add(new Listed(id, created.longValue()));
				}
			}
		}
		return listed;
	}

	/**
	 * Gives a conversation to a terminal, which no other terminal gets after it.
	 *
	 * @param started
	 *            when the program of the terminal started
	 * @param entered
	 *            when the user entered a line in it
	 * @return the id of the conversation, or {@code null} if none of the list is the one of the terminal
	 */
	static String choose(List<Listed> listed, long started, long entered) {
		Listed best = null;
		for (Listed candidate : listed) {
			if (candidate.created() < started || TAKEN.contains(candidate.id())) {
				continue;
			}
			if (best == null || Math.abs(candidate.created() - entered) < Math.abs(best.created() - entered)) {
				best = candidate;
			}
		}
		return best != null && TAKEN.add(best.id()) ? best.id() : null;
	}

	/** The conversation that a terminal comes back with. */
	static void take(String id) {
		TAKEN.add(id);
	}

	/** The terminal starts another conversation. */
	static void release(String id) {
		if (id != null) {
			TAKEN.remove(id);
		}
	}
}
