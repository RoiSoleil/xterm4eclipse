package org.eclipse.xterm4eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ShellEnvironmentTest {

	@Test
	void variablesAreSetExpandedAndRemoved() {
		Map<String, String> env = new HashMap<>(Map.of("PATH", "/usr/bin", "HOME", "/h", "path", "lower"));
		ShellEnvironment.apply(env, "# comment\n\n  PATH = /opt/bin:${PATH}\nGREETING=hi ${HOME} ${MISSING}!=\n"
				+ "A=1\r\nB=${A}2\n-HOME\n-path\nbad line\n1X=no\n=x\n-\n", false);
		assertEquals(" /opt/bin:/usr/bin", env.get("PATH"), "the value is kept as written after =");
		assertEquals("hi /h !=", env.get("GREETING"));
		assertEquals("12", env.get("B"), "a line uses what the lines before it set");
		assertFalse(env.containsKey("HOME"));
		assertFalse(env.containsKey("path"), "names are exact outside of Windows");
		assertTrue(env.containsKey("PATH"));
		assertFalse(env.containsKey("bad line"));
		assertFalse(env.containsKey("1X"));
		assertEquals(4, env.size(), "PATH, GREETING, A and B");
		ShellEnvironment.apply(env, null, false);
		assertEquals(4, env.size());
	}

	@Test
	void windowsNamesDoNotDependOnTheCase() {
		Map<String, String> env = new HashMap<>(Map.of("Path", "C:\\w", "TEMP", "C:\\t"));
		ShellEnvironment.apply(env, "PATH=C:\\tools;${path}\n-temp\nProgramFiles(x86)=C:\\p", true);
		assertEquals("C:\\tools;C:\\w", env.get("Path"), "the spelling of Windows is kept");
		assertFalse(env.containsKey("PATH"));
		assertFalse(env.containsKey("TEMP"));
		assertEquals("C:\\p", env.get("ProgramFiles(x86)"));
	}

	@Test
	void wrongLinesAreReported() {
		assertNull(ShellEnvironment.errorIn(""));
		assertNull(ShellEnvironment.errorIn("# only a comment\n\nA=1\n-B\nC=\nPATH=${PATH}:x"));
		assertEquals("Line 3: expected NAME=value or -NAME", ShellEnvironment.errorIn("A=1\n\nnot a variable"));
		assertEquals("Line 1: expected NAME=value or -NAME", ShellEnvironment.errorIn("=value"));
		assertEquals("Line 1: expected NAME=value or -NAME", ShellEnvironment.errorIn("-"));
		assertEquals("Line 2: expected NAME=value or -NAME", ShellEnvironment.errorIn("A=1\n1B=2"));
	}

	@Test
	void theUserCanChangeWhatThePlugInSets() {
		List<String> env = List.of(PtySession.environment(new String[] {"/bin/sh"}, "Linux", Map.of("HOME", "/h"),
				"TERM=dumb\nEXTRA=${HOME}/x"));
		assertTrue(env.contains("TERM=dumb"));
		assertTrue(env.contains("EXTRA=/h/x"));
		assertTrue(env.contains("COLORTERM=truecolor"));
	}
}
