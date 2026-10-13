package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/** The ways in which the command line of a terminal runs a program. */
class InvocationTest {

	private static final Predicate<String> AGENT = "agent"::equals;

	private static String[] line(String... arguments) {
		return arguments;
	}

	@Test
	void programIsRunDirectlyThroughCmdOrThroughAShell() {
		Invocation direct = Invocation.of(line("/usr/bin/agent", "--model", "x"), AGENT);
		assertEquals("agent", direct.name());
		assertEquals(List.of("--model", "x"), direct.arguments());
		assertArrayEquals(line("/usr/bin/agent", "--model", "x", "--more", "a b"), direct.appended(List.of("--more", "a b")));
		assertArrayEquals(line("/usr/bin/agent", "list"), direct.replaced(List.of("list")));

		Invocation cmd = Invocation.of(line("cmd.exe", "/C", "C:\\npm\\Agent.CMD", "-v"), AGENT);
		assertEquals("agent", cmd.name());
		assertEquals(List.of("-v"), cmd.arguments());
		assertArrayEquals(line("cmd.exe", "/C", "C:\\npm\\Agent.CMD", "-v", "x"), cmd.appended(List.of("x")));
		assertArrayEquals(line("cmd.exe", "/C", "C:\\npm\\Agent.CMD", "list"), cmd.replaced(List.of("list")));

		Invocation script = Invocation.of(line("/bin/zsh", "-l", "-i", "-c", " '/my dir/agent'  --model  x "), AGENT);
		assertEquals("agent", script.name());
		assertEquals(List.of("--model", "x"), script.arguments());
		assertArrayEquals(line("/bin/zsh", "-l", "-i", "-c", "'/my dir/agent'  --model  x --id 1"),
				script.appended(List.of("--id", "1")));
		// Arguments that a shell would read otherwise are quoted.
		assertArrayEquals(line("/bin/zsh", "-l", "-i", "-c", "'/my dir/agent' add 'Authorization: Bearer t' '*' ''"),
				script.replaced(List.of("add", "Authorization: Bearer t", "*", "")));
		assertNull(script.replaced(List.of("it's")), "no simple way to quote it");
		assertNull(script.appended(List.of("a\nb")));
		assertEquals(List.of(), Invocation.of(line("pwsh", "-c", "agent"), AGENT).arguments());
	}

	@Test
	void otherCommandLinesRunNoKnownProgram() {
		for (String[] commandLine : List.of(line(), line("/bin/bash"), line("/usr/bin/agents"), line("/bin/zsh", "-c", "vim"),
				line("/bin/zsh", "-c", "agent; rm x"), line("/bin/zsh", "-c", "agent $(cat f)"),
				line("/bin/zsh", "-c", "agent \"quoted\""), line("/bin/zsh", "-c", "agent | tee log"),
				line("/bin/zsh", "-c", "agent", "extra"), line("cmd.exe", "/c", "other.cmd", "agent"),
				line("cmd.exe", "/c"))) {
			assertNull(Invocation.of(commandLine, AGENT), String.join(" ", commandLine));
		}
	}

	@Test
	void nameOfAProgramHasNoFolderQuotesOrLauncherExtension() {
		assertEquals("claude", Invocation.name("claude"));
		assertEquals("claude", Invocation.name("/usr/local/bin/claude"));
		assertEquals("claude", Invocation.name("'/my dir/claude'"));
		assertEquals("claude", Invocation.name("C:\\Users\\me\\.local\\bin\\Claude.EXE"));
		assertEquals("codex", Invocation.name("C:\\npm\\codex.cmd"));
		assertEquals("aider", Invocation.name("aider.bat"));
		assertEquals("claude.sh", Invocation.name("claude.sh"));
	}
}
