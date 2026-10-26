package org.eclipse.xterm4eclipse.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

	@Test
	void readsTheMessagesOfClaudeCode() {
		Map<String, Object> message = Json.object("{\"type\":\"result\",\"is_error\":false,\"total_cost_usd\":0.0125,"
				+ "\"result\":\"pong \\\"quoted\\\"\\n\\u00e9\",\"errors\":[],\"usage\":{\"input_tokens\":6},\"stop\":null}");
		assertEquals("result", Json.string(message, "type"));
		assertEquals(Boolean.FALSE, message.get("is_error"));
		assertEquals(0.0125, message.get("total_cost_usd"));
		assertEquals("pong \"quoted\"\n\u00e9", message.get("result"));
		assertEquals(List.of(), Json.list(message, "errors"));
		assertEquals(6.0, Json.object(message, "usage").get("input_tokens"));
		assertTrue(message.containsKey("stop"));
		assertNull(message.get("stop"));
	}

	@Test
	void isTolerantOfWhatIsNotAnObject() {
		assertNull(Json.object("Welcome to the shell"));
		assertNull(Json.object("[1, 2]"));
		assertNull(Json.object("{\"type\": "));
		assertNull(Json.object("{\"a\":1} trailing"));
		assertNull(Json.string(null, "type"));
		assertNull(Json.object(Map.of("type", "x"), "type"));
		assertEquals(List.of(), Json.list(Map.of("a", "b"), "a"));
	}

	@Test
	void writesWhatAPageCanReadInAScript() {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("text", "</script><b>\"\\\u2028\n\t\u0001");
		value.put("count", 3);
		value.put("cost", 0.5);
		value.put("none", null);
		value.put("list", Arrays.asList(true, "x", Double.NaN));
		String json = Json.write(value);
		assertEquals("{\"text\":\"\\u003c/script\\u003e\\u003cb\\u003e\\\"\\\\\\u2028\\n\\t\\u0001\",\"count\":3,"
				+ "\"cost\":0.5,\"none\":null,\"list\":[true,\"x\",null]}", json);
		assertFalse(json.contains("<"));
		assertEquals(value.get("text"), Json.object(json).get("text"), "read back as it was");
	}
}
