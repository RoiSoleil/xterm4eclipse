package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.xterm4eclipse.harness.Harness.Run;
import org.junit.jupiter.api.Test;

/** The catalog of the coding agents: each one is complete, and none hides another. */
class HarnessesTest {

	@Test
	void theKnownAgentsAreTheseInTheOrderOfTheMenus() {
		assertEquals(List.of("Claude", "Codex", "OpenCode", "Reasonix", "Gemini", "Copilot", "Cursor Agent", "Qwen Code",
				"Kilo Code", "Crush", "Amp", "goose", "Aider"), Harnesses.ALL.stream().map(Harness::label).toList());
	}

	@Test
	void everyAgentIsCompleteAndHasATestOfItsOwn() throws Exception {
		Set<String> ids = new HashSet<>();
		Set<String> programs = new HashSet<>();
		for (Harness harness : Harnesses.ALL) {
			String name = harness.product();
			assertTrue(ids.add(harness.id()), name);
			assertTrue(harness.id().matches("[a-z]+"), "a name for the files and the state of the terminals: " + harness.id());
			assertFalse(harness.label().isBlank());
			assertFalse(harness.programs().isEmpty(), name);
			for (String program : harness.programs()) {
				assertTrue(programs.add(program), program + " is the program of one agent");
				assertEquals(harness, Harnesses.named(program));
				assertEquals(harness, Harnesses.find("/bin/zsh -l -i -c " + program).harness());
			}
			assertEquals(harness.programs().get(0), harness.program());
			assertNotNull(HarnessExtension.class.getResource('/' + harness.icon()), harness.icon());
			assertNotNull(HarnessExtension.class.getResource('/' + harness.icon().replace(".png", "@2x.png")), harness.icon());

			// The conversation of an id needs its id; the other arguments have none.
			Harness.Sessions sessions = harness.sessions();
			assertEquals(sessions.fresh() != null, sessions.fresh() != null && sessions.fresh().contains("{id}"), name);
			assertEquals(sessions.resume() != null, sessions.resume() != null && sessions.resume().contains("{id}"), name);
			assertTrue(sessions.last() == null || !sessions.last().contains("{id}"), name);
			assertTrue(sessions.fresh() == null || sessions.resume() != null, name + " resumes the conversations it names");
			assertTrue(sessions.list() == null || sessions.resume() != null, name + " resumes the conversations it lists");
			// A command line that already resumes is not changed again.
			if (sessions.resume() != null) {
				assertEquals(Run.OTHER, harness.run(sessions.resume()), name);
			}
			if (sessions.last() != null) {
				assertEquals(Run.OTHER, harness.run(sessions.last()), name);
			}
			assertEquals(Run.CONVERSATION, harness.run(List.of()), name);

			Harness.Mcp mcp = harness.mcp();
			if (mcp != null) {
				List<String> add = mcp.adding(new EclipseMcp.Endpoint("http://127.0.0.1:1/mcp", "token-12345678"));
				assertTrue(add.contains(EclipseMcp.NAME) && add.contains("http://127.0.0.1:1/mcp"), name);
				assertEquals(mcp.tokenInEnvironment(), add.contains(EclipseMcp.TOKEN_VARIABLE), name);
				assertEquals(!mcp.tokenInEnvironment(), String.join(" ", add).contains("Bearer token-12345678"), name);
				assertTrue(mcp.removing() == null || mcp.removing().contains(EclipseMcp.NAME), name);
				assertFalse(mcp.userFiles().isEmpty(), name);
				assertEquals(Run.OTHER, harness.run(add), "a command of the agent, not a conversation: " + name);
			}
			// Each agent has its tests.
			String test = switch (harness.id()) {
			case "claude" -> "ClaudeCodeTest";
			case "codex" -> "CodexTest";
			case "opencode" -> "OpenCodeTest";
			case "reasonix" -> "ReasonixTest";
			case "gemini" -> "GeminiCliTest";
			case "copilot" -> "CopilotCliTest";
			case "cursor" -> "CursorCliTest";
			case "qwen" -> "QwenCodeTest";
			case "kilo" -> "KiloCodeTest";
			case "crush" -> "CrushTest";
			case "amp" -> "AmpTest";
			case "goose" -> "GooseTest";
			case "aider" -> "AiderTest";
			default -> "a test for " + name;
			};
			Class<?> tests = Class.forName(HarnessesTest.class.getPackageName() + '.' + test);
			assertEquals(harness, ((HarnessTestCase) tests.getDeclaredConstructor().newInstance()).harness(), test);
		}
	}

	@Test
	void whatACommandLineAsksFor() {
		Harness codex = Harnesses.CODEX;
		assertEquals(Run.CONVERSATION, codex.run(List.of("--model", "gpt-5", "--search", "-c", "a=b")));
		assertEquals(Run.PROMPT, codex.run(List.of("--model", "gpt-5", "fix the bug")));
		assertEquals(Run.PROMPT, codex.run(List.of("--model=gpt-5", "fix")));
		assertEquals(Run.OTHER, codex.run(List.of("--model", "gpt-5", "exec", "fix")));
		assertEquals(Run.OTHER, codex.run(List.of("--remote=ws://host", "--search")));
		assertEquals(Run.CONVERSATION, codex.run(List.of("--model", "exec")), "the value of an option, not a command");
		// The conversations of goose are those of its command "session".
		Harness goose = Harnesses.GOOSE;
		assertEquals(Run.CONVERSATION, goose.run(List.of("session", "--debug")));
		assertEquals(Run.OTHER, goose.run(List.of("--debug", "run")));
		assertEquals(Run.OTHER, goose.run(List.of("session", "session")));
		assertEquals(List.of("session", "--name", "x"), goose.conversation(List.of(), List.of("--name", "x")));
		assertEquals(List.of("--name", "x"), goose.conversation(List.of("session"), List.of("--name", "x")));
		assertEquals(List.of("--id", "x"), Harnesses.CLAUDE.conversation(List.of(), List.of("--id", "x")));
	}

	@Test
	void unknownProgramsAreNoAgents() {
		assertNull(Harnesses.named("bash"));
		assertNull(Harnesses.named("agent"));
		assertNull(Harnesses.find("/bin/bash -l"));
		assertNull(Harnesses.find(""));
	}
}
