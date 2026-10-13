package org.eclipse.xterm4eclipse.harness;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.widgets.Shell;
import org.eclipse.xterm4eclipse.spi.Terminal;

/** A terminal without a view, for what an agent does with its command line. */
final class FakeTerminal implements Terminal {

	final String commandLine;
	final File directory;
	/** What the agent keeps with the terminal. */
	final Map<String, String> state = new LinkedHashMap<>();
	final List<String> printed = new ArrayList<>();
	boolean disposed;

	FakeTerminal(String commandLine, File directory) {
		this.commandLine = commandLine;
		this.directory = directory;
	}

	@Override
	public String commandLine() {
		return commandLine;
	}

	@Override
	public File directory() {
		return directory;
	}

	@Override
	public String state(String key) {
		return state.get(key);
	}

	@Override
	public void setState(String key, String value) {
		if (value == null) {
			state.remove(key);
		} else {
			state.put(key, value);
		}
	}

	@Override
	public void print(String message) {
		printed.add(message);
	}

	@Override
	public void printError(String message) {
		printed.add("error: " + message);
	}

	@Override
	public Shell shell() {
		return null;
	}

	@Override
	public String[] environment() {
		List<String> environment = new ArrayList<>();
		System.getenv().forEach((name, value) -> environment.add(name + '=' + value));
		return environment.toArray(String[]::new);
	}

	@Override
	public boolean isDisposed() {
		return disposed;
	}
}
