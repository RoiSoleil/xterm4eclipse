package org.eclipse.xterm4eclipse;

import static org.eclipse.xterm4eclipse.TestWorkbench.FOLDER;
import static org.eclipse.xterm4eclipse.TestWorkbench.await;
import static org.eclipse.xterm4eclipse.TestWorkbench.pump;
import static org.eclipse.xterm4eclipse.TestWorkbench.screen;
import static org.eclipse.xterm4eclipse.TestWorkbench.shellPath;
import static org.eclipse.xterm4eclipse.TestWorkbench.type;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.eclipse.ui.XMLMemento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Git Bash as the shell menu of Windows starts it: through the launcher Git\bin\bash.exe. */
@EnabledOnOs(OS.WINDOWS)
class GitBashTest {

	private TestWorkbench workbench;

	@BeforeEach
	void setUp() {
		workbench = new TestWorkbench();
	}

	@AfterEach
	void tearDown() {
		workbench.close();
		XtermPreferencePageTest.resetPreferences();
	}

	@Test
	void gitBashOfTheShellMenuIsIdleAtItsPrompt() throws Exception {
		String gitBash = ShellProfiles.detect().stream().filter(profile -> profile.name().equals("Git Bash")).findFirst()
				.orElseThrow(() -> new AssertionError("Git Bash is installed on the machines of the tests")).commandLine();
		ShellProfiles.setDefaultCommandLine(gitBash);
		XtermView view = new XtermView();
		workbench.open(view, null, null);
		await("prompt", () -> screen(view).contains("$"));

		// The launcher has the real bash as its child process: that is no command.
		pump(2000);
		assertFalse(view.isDirty(), "nothing runs at the prompt");

		type(view, "sleep 2\r");
		await("running", view::isDirty);
		await("finished", () -> !view.isDirty());

		// The directory is announced through the launcher too.
		type(view, "cd '" + shellPath(FOLDER) + "'\r");
		await("directory announced", () -> {
			XMLMemento memento = XMLMemento.createWriteRoot("view");
			view.saveState(memento);
			return FOLDER.getPath().equals(memento.getString("directory"));
		});
		pump(2000);
		assertFalse(view.isDirty(), "back at the prompt");
	}
}
