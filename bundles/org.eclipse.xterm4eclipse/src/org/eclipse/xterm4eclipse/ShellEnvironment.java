package org.eclipse.xterm4eclipse;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The environment variables the user gives to the shells, as {@code terminal.integrated.env} in VS
 * Code. One per line:
 * <ul>
 * <li>{@code NAME=value} sets a variable; {@code ${OTHER}} in the value is the value of another
 * variable, for example {@code PATH=/opt/tools/bin:${PATH}};</li>
 * <li>{@code -NAME} removes a variable;</li>
 * <li>empty lines and lines starting with {@code #} are ignored.</li>
 * </ul>
 * On Windows the names do not depend on the case, as for Windows itself.
 */
final class ShellEnvironment {

	private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.()]*"); //$NON-NLS-1$
	private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.()]*)\\}"); //$NON-NLS-1$

	private ShellEnvironment() {
	}

	/**
	 * @return why the setting is wrong, naming the line, or {@code null} if it is right
	 */
	static String errorIn(String setting) {
		String[] lines = setting.split("\\R", -1); //$NON-NLS-1$
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i].strip();
			if (line.isEmpty() || line.startsWith("#")) { //$NON-NLS-1$
				continue;
			}
			String name = line.startsWith("-") ? line.substring(1).strip() //$NON-NLS-1$
					: line.indexOf('=') > 0 ? line.substring(0, line.indexOf('=')).strip() : null;
			if (name == null || !NAME.matcher(name).matches()) {
				return "Line " + (i + 1) + ": expected NAME=value or -NAME"; //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
		return null;
	}

	/**
	 * Applies the setting to the environment, line by line: a value can use what the lines before it
	 * set. Wrong lines are ignored.
	 */
	static void apply(Map<String, String> environment, String setting, boolean windows) {
		if (setting == null) {
			return;
		}
		for (String raw : setting.split("\\R")) { //$NON-NLS-1$
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) { //$NON-NLS-1$
				continue;
			}
			if (line.startsWith("-")) { //$NON-NLS-1$
				String name = line.substring(1).strip();
				if (NAME.matcher(name).matches()) {
					environment.remove(key(environment, name, windows));
				}
				continue;
			}
			int equals = line.indexOf('=');
			if (equals <= 0) {
				continue;
			}
			String name = line.substring(0, equals).strip();
			if (!NAME.matcher(name).matches()) {
				continue;
			}
			String value = expand(line.substring(equals + 1), environment, windows);
			// On Windows, keep the spelling the variable already has (Path, not PATH).
			String existing = key(environment, name, windows);
			environment.put(existing != null ? existing : name, value);
		}
	}

	private static String expand(String value, Map<String, String> environment, boolean windows) {
		Matcher matcher = REFERENCE.matcher(value);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String key = key(environment, matcher.group(1), windows);
			String replacement = key == null ? "" : environment.get(key); //$NON-NLS-1$
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/** @return the key of the variable in the map, or {@code null} if it is not there */
	private static String key(Map<String, String> environment, String name, boolean windows) {
		if (environment.containsKey(name)) {
			return name;
		}
		if (windows) {
			for (String key : environment.keySet()) {
				if (key.equalsIgnoreCase(name)) {
					return key;
				}
			}
		}
		return null;
	}
}
