package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Qwen Code of Alibaba: a conversation of its own for each terminal. */
class QwenCodeTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.QWEN;
	}

	@Test
	void isRecognizedAndOffered() throws Exception {
		assertRecognized("qwen", "icons/qwen.png");
		assertOffered("qwen");
	}

	@Test
	void getsTheSessionOfTheTerminalAndResumesItOnceKept() throws Exception {
		assertArrayEquals(line("qwen", "--session-id", ID), new Run("qwen").with(ID).start());
		assertArrayEquals(line("qwen", "--session-id", ID), new Run("qwen").with(ID).resume(),
				"no conversation before the first message: the same one starts");
		// projects/<project>/chats/<id>.jsonl
		write(home.resolve(".qwen/projects/-home-me-project/chats/" + ID + ".jsonl"), "{}\n");
		assertArrayEquals(line("qwen", "--resume", ID), new Run("qwen").with(ID).resume());
		assertArrayEquals(line("cmd.exe", "/c", "C:\\npm\\qwen.cmd", "--resume", ID),
				new Run("cmd.exe", "/c", "C:\\npm\\qwen.cmd").with(ID).resume());
		environment.put("QWEN_HOME", temp.resolve("moved").toString());
		assertArrayEquals(line("qwen", "--session-id", ID), new Run("qwen").with(ID).resume(), "moved by the user");
		write(temp.resolve("moved/projects/p/chats/" + ID + ".jsonl"), "{}\n");
		assertArrayEquals(line("qwen", "--resume", ID), new Run("qwen").with(ID).resume());
		assertLeftAlone(line("qwen", "-c"), line("qwen", "--continue"), line("qwen", "-r", ID), line("qwen", "--resume"),
				line("qwen", "--session-id", ID), line("qwen", "-p", "explain"), line("qwen", "mcp", "list"),
				line("qwen", "sessions", "list"));
	}

	@Test
	void addsTheServerOfEclipseForTheUser() throws Exception {
		Harness.Mcp mcp = Harnesses.QWEN.mcp();
		assertEquals(List.of("mcp", "add", "-s", "user", "-t", "http", NAME, "http://127.0.0.1:8642/mcp", "-H",
				"Authorization: Bearer " + TOKEN), mcp.adding(new EclipseMcp.Endpoint("http://127.0.0.1:8642/mcp", TOKEN)));
		assertNull(mcp.removing(), "Qwen Code updates a server of the same name");
		String server = "{\"mcpServers\": {\"e\": {\"httpUrl\": \"{url}\"}}}";
		assertKnownFrom(home.resolve(".qwen/settings.json").toString(), server);
		assertKnownFrom(".qwen/settings.json", server);
	}

	@Test
	void comesBackToItsOwnConversationInItsTerminal() throws Exception {
		assertComesBackToItsOwnConversation("qwen", "--session-id {id}", "--resume {id}",
				".qwen/projects/-the-project/chats/{id}.jsonl");
	}

	@Test
	void isOfferedTheMcpServerOfEclipse() throws Exception {
		assertOfferedTheMcpServerOfEclipse("qwen",
				"mcp add -s user -t http " + NAME + " {url} -H Authorization: Bearer " + TOKEN, null, null,
				home.resolve(".qwen/settings.json"));
	}
}
