package org.eclipse.xterm4eclipse.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The files that a message names by their name. */
class FileFinderTest {

	@TempDir
	Path temp;

	private File file(String path) throws Exception {
		Path file = temp.resolve(path);
		Files.createDirectories(file.getParent());
		return Files.writeString(file, "x").toFile();
	}

	@Test
	void findsAFileByItsNameOrTheEndOfItsPath() throws Exception {
		File deep = file("fonctionnel/commun/echange/frontend/src/js/abonnement/AbonnementFiche.js");
		file("fonctionnel/commun/echange/frontend/src/js/abonnement/Other.js");
		assertEquals(List.of(deep), FileFinder.find(temp.toFile(), "AbonnementFiche.js"));
		assertEquals(List.of(deep), FileFinder.find(temp.toFile(), "abonnement/AbonnementFiche.js"));
		assertEquals(List.of(deep), FileFinder.find(temp.toFile(), "./abonnement\\AbonnementFiche.js"), "slashes of both kinds");
		assertTrue(FileFinder.find(temp.toFile(), "Fiche.js").isEmpty(), "the name, not the end of the name");
		assertTrue(FileFinder.find(temp.toFile(), "missing.js").isEmpty());
	}

	@Test
	void doesNotLookWhereFilesAreBuiltOrFetched() throws Exception {
		File source = file("app/src/Fiche.js");
		file("app/node_modules/lib/Fiche.js");
		file("app/target/classes/Fiche.js");
		file(".git/objects/Fiche.js");
		assertEquals(List.of(source), FileFinder.find(temp.toFile(), "Fiche.js"));
	}

	@Test
	void looksInTheFoldersThatThePathGoesThrough() throws Exception {
		File generated = file("app/build/generated/Foo.java");
		file("other/node_modules/lib/Foo.java");
		assertEquals(List.of(generated), FileFinder.find(temp.toFile(), "build/generated/Foo.java"), "named, so looked for");
		assertTrue(FileFinder.find(temp.toFile(), "generated/Foo.java").isEmpty(), "a path that does not go through build: not in it");
		assertTrue(FileFinder.find(temp.toFile(), "Foo.java").isEmpty(), "by its name only: not in what is built or fetched");
		file("c/node_modules/bin");
		assertTrue(FileFinder.find(temp.toFile(), "bin").isEmpty(), "the name of the file does not open the folders that are left out");
		File dots = file("a/v1..2.txt");
		assertEquals(List.of(dots), FileFinder.find(temp.toFile(), "v1..2.txt"), "two dots are not a parent folder");
	}

	@Test
	void givesAllTheFilesOfTheSameNameInOrder() throws Exception {
		File second = file("b/Same.js");
		File first = file("a/Same.js");
		assertEquals(List.of(first, second), FileFinder.find(temp.toFile(), "Same.js"));
	}

	@Test
	void refusesWhatLeavesTheFolder() throws Exception {
		file("a/Fiche.js");
		assertTrue(FileFinder.find(temp.toFile(), "../Fiche.js").isEmpty());
		assertTrue(FileFinder.find(temp.toFile(), "/a/Fiche.js").isEmpty(), "an absolute path is not a name");
		assertTrue(FileFinder.find(temp.toFile(), "").isEmpty());
		assertTrue(FileFinder.find(null, "Fiche.js").isEmpty());
		assertTrue(FileFinder.find(temp.resolve("none").toFile(), "Fiche.js").isEmpty());
	}
}
