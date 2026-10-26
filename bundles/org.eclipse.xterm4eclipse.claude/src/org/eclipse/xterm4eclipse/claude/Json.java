package org.eclipse.xterm4eclipse.claude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON of Claude Code, which it prints and reads one message per line: objects as maps, arrays
 * as lists, numbers as doubles.
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

	/** @return the object of a line, or {@code null} if it is not a JSON object */
	@SuppressWarnings("unchecked")
	static Map<String, Object> object(String line) {
		return parse(line) instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
	}

	/**
	 * Writes a value as JSON, which is also a JavaScript literal that a page can be given in a
	 * script: the characters that would end a script or a string of JavaScript are escaped.
	 */
	static String write(Object value) {
		StringBuilder out = new StringBuilder();
		write(value, out);
		return out.toString();
	}

	/** A JSON string literal. */
	static String string(String text) {
		StringBuilder out = new StringBuilder();
		string(text, out);
		return out.toString();
	}

	private static void write(Object value, StringBuilder out) {
		if (value == null) {
			out.append("null"); //$NON-NLS-1$
		} else if (value instanceof String string) {
			string(string, out);
		} else if (value instanceof Boolean) {
			out.append(value);
		} else if (value instanceof Number number) {
			double d = number.doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) {
				out.append("null"); //$NON-NLS-1$
			} else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
				out.append((long) d);
			} else {
				out.append(d);
			}
		} else if (value instanceof Map<?, ?> map) {
			out.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!first) {
					out.append(',');
				}
				first = false;
				string(String.valueOf(entry.getKey()), out);
				out.append(':');
				write(entry.getValue(), out);
			}
			out.append('}');
		} else if (value instanceof List<?> list) {
			out.append('[');
			for (int i = 0; i < list.size(); i++) {
				if (i > 0) {
					out.append(',');
				}
				write(list.get(i), out);
			}
			out.append(']');
		} else {
			string(value.toString(), out);
		}
	}

	private static void string(String text, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
			case '"' -> out.append("\\\""); //$NON-NLS-1$
			case '\\' -> out.append("\\\\"); //$NON-NLS-1$
			case '\n' -> out.append("\\n"); //$NON-NLS-1$
			case '\r' -> out.append("\\r"); //$NON-NLS-1$
			case '\t' -> out.append("\\t"); //$NON-NLS-1$
			default -> {
				// < and > so that no </script> ends the script that holds the value.
				if (c < 0x20 || c == '<' || c == '>' || c == ' ' || c == ' ') {
					out.append(String.format("\\u%04x", (int) c)); //$NON-NLS-1$
				} else {
					out.append(c);
				}
			}
			}
		}
		out.append('"');
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
		for (String literal : new String[] {"true", "false", "null"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			if (text.startsWith(literal, position)) {
				position += literal.length();
				return literal.equals("null") ? null : Boolean.valueOf(literal); //$NON-NLS-1$
			}
		}
		int start = position;
		while (position < text.length() && "+-.eE0123456789".indexOf(text.charAt(position)) >= 0) { //$NON-NLS-1$
			position++;
		}
		return Double.valueOf(text.substring(start, position));
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

	// Reading the messages.

	/** @return the object under this key, or {@code null} */
	@SuppressWarnings("unchecked")
	static Map<String, Object> object(Map<String, Object> object, String key) {
		return object != null && object.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
	}

	/** @return the string under this key, or {@code null} */
	static String string(Map<String, Object> object, String key) {
		return object != null && object.get(key) instanceof String string ? string : null;
	}

	/** @return the list under this key, or an empty one */
	static List<?> list(Map<String, Object> object, String key) {
		return object != null && object.get(key) instanceof List<?> list ? list : List.of();
	}
}
