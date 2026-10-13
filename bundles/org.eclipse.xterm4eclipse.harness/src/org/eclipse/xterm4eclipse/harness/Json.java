package org.eclipse.xterm4eclipse.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the JSON that a coding agent prints, for example the list of its conversations: objects as
 * maps, arrays as lists, numbers as doubles.
 */
final class Json {

	private final String text;
	private int position;

	private Json(String text) {
		this.text = text;
	}

	/** @return the value, or {@code null} if the text is not JSON */
	static Object parse(String text) {
		Json json = new Json(text);
		try {
			Object value = json.value();
			json.blanks();
			return json.position == text.length() ? value : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * @return the first array of the text, which may come with other lines around it (the messages
	 *         of a login shell), or {@code null}
	 */
	static List<?> firstArray(String text) {
		for (int start = text.indexOf('['); start >= 0; start = text.indexOf('[', start + 1)) {
			Json json = new Json(text);
			json.position = start;
			try {
				if (json.value() instanceof List<?> list) {
					return list;
				}
			} catch (RuntimeException e) {
				// Not here.
			}
		}
		return null;
	}

	private void blanks() {
		while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
			position++;
		}
	}

	private Object value() {
		blanks();
		char c = text.charAt(position);
		if (c == '{') {
			position++;
			Map<String, Object> object = new LinkedHashMap<>();
			blanks();
			if (text.charAt(position) == '}') {
				position++;
				return object;
			}
			while (true) {
				blanks();
				String key = string();
				blanks();
				expect(':');
				object.put(key, value());
				blanks();
				if (text.charAt(position) == ',') {
					position++;
				} else {
					expect('}');
					return object;
				}
			}
		}
		if (c == '[') {
			position++;
			List<Object> array = new ArrayList<>();
			blanks();
			if (text.charAt(position) == ']') {
				position++;
				return array;
			}
			while (true) {
				array.add(value());
				blanks();
				if (text.charAt(position) == ',') {
					position++;
				} else {
					expect(']');
					return array;
				}
			}
		}
		if (c == '"') {
			return string();
		}
		int start = position;
		while (position < text.length() && "+-.eE0123456789truefalsn".indexOf(text.charAt(position)) >= 0) { //$NON-NLS-1$
			position++;
		}
		String literal = text.substring(start, position);
		return switch (literal) {
		case "true" -> Boolean.TRUE; //$NON-NLS-1$
		case "false" -> Boolean.FALSE; //$NON-NLS-1$
		case "null" -> null; //$NON-NLS-1$
		default -> Double.valueOf(literal);
		};
	}

	private void expect(char c) {
		if (text.charAt(position) != c) {
			throw new IllegalArgumentException("Expected " + c + " at " + position); //$NON-NLS-1$ //$NON-NLS-2$
		}
		position++;
	}

	private String string() {
		expect('"');
		StringBuilder result = new StringBuilder();
		while (true) {
			char c = text.charAt(position++);
			if (c == '"') {
				return result.toString();
			}
			if (c != '\\') {
				result.append(c);
				continue;
			}
			char escaped = text.charAt(position++);
			switch (escaped) {
			case 'n' -> result.append('\n');
			case 't' -> result.append('\t');
			case 'r' -> result.append('\r');
			case 'b' -> result.append('\b');
			case 'f' -> result.append('\f');
			case 'u' -> {
				result.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
				position += 4;
			}
			default -> result.append(escaped);
			}
		}
	}
}
