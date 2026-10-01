package org.eclipse.xterm4eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebPageTest {

	private static final Map<String, String> FILES = Map.of( //
			"index.html", "<head><link rel=\"stylesheet\" href=\"a.css\"></head><script src=\"a.js\"></script><p>end</p>",
			"a.css", "body { color: red; }", //
			"a.js", "var s = '</script>';");

	private static String read(String name) throws IOException {
		String content = FILES.get(name);
		if (content == null) {
			throw new IOException("missing " + name);
		}
		return content;
	}

	@Test
	void styleSheetsAndScriptsAreInlined() throws Exception {
		String html = WebPage.assemble(WebPageTest::read);
		assertTrue(html.contains("<style>\nbody { color: red; }\n</style>"), html);
		assertTrue(html.contains("<script>\nvar s = '<\\/script>';\n</script>"), html);
		assertTrue(html.endsWith("<p>end</p>"));
		assertFalse(html.contains("href="));
	}

	@Test
	void missingResourceFails() {
		assertThrows(IOException.class, () -> WebPage.assemble(name -> {
			throw new IOException("missing");
		}));
	}

	@Test
	void pageIsWrittenOncePerRunAndRestoredWhenDeleted(@TempDir Path temp) throws Exception {
		Path file = temp.resolve("nested/page.html");
		int[] reads = {0};
		WebPage.Resources counting = name -> {
			reads[0]++;
			return read(name);
		};
		assertEquals(file, WebPage.materialize(file, counting));
		assertTrue(Files.readString(file).contains("color: red"));
		int afterFirst = reads[0];

		WebPage.materialize(file, counting);
		assertEquals(afterFirst, reads[0], "not rebuilt while the file exists");

		Files.delete(file);
		WebPage.materialize(file, counting);
		assertTrue(Files.isRegularFile(file));
	}

	@Test
	void theRealPageIsSelfContained() throws Exception {
		String html = WebPage.assemble(name -> XtermPlugin.resource("web/" + name));
		assertFalse(html.contains("<script src="));
		assertFalse(html.contains("<link rel=\"stylesheet\""));
		assertTrue(html.contains("xtermInit"));
		assertThrows(IOException.class, () -> XtermPlugin.resource("web/absent.js"));
	}
}
