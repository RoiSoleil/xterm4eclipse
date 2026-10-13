package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The JSON that the agents print. */
class JsonTest {

	@Test
	void valuesAreRead() {
		assertEquals(Map.of("id", "ses_1", "created", 1789832330563.0, "nested", Map.of("list", List.of(1.0, -2.5e3, true, false)),
				"text", "a \"b\" \\ / \n\t\r\b\f \u00e9 {x} [y]"),
				Json.parse(" {\"id\": \"ses_1\", \"created\": 1789832330563, \"nested\": {\"list\": [1, -2.5e3, true, false]},"
						+ " \"text\": \"a \\\"b\\\" \\\\ \\/ \\n\\t\\r\\b\\f \\u00e9 {x} [y]\"} "));
		assertEquals(Arrays.asList("a", null), Json.parse("[\"a\", null]"));
		assertEquals(List.of(), Json.parse("[]"));
		assertEquals(Map.of(), Json.parse("{ }"));
		assertEquals("text", Json.parse("\"text\""));
	}

	@Test
	void whatIsNotJsonIsNothing() {
		for (String text : List.of("", "hello", "{", "[1, 2", "{\"a\" 1}", "{\"a\": 1,}", "[1] trailing", "{\"a\": \"open}", "[1 2]",
				"{a: 1}")) {
			assertNull(Json.parse(text), text);
		}
	}

	@Test
	void arrayIsFoundAmongOtherLines() {
		assertEquals(List.of(Map.of("id", "x")), Json.firstArray("Last login [tty1]\nwelcome\n[{\"id\": \"x\"}]\nbye\n"));
		assertEquals(List.of(), Json.firstArray("[]"));
		assertNull(Json.firstArray("nothing here"));
		assertNull(Json.firstArray("[broken"));
	}
}
