package org.eclipse.xterm4eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OpenChoosingShellHandlerTest {

	@BeforeAll
	static void createDisplay() {
		// The images are created on the display of the tests.
		assertEquals(Thread.currentThread(), TestWorkbench.DISPLAY.getThread());
	}

	@Test
	void theShellChooserShowsTheIconOfEachShell() {
		OpenChoosingShellHandler.ProfileLabels labels = new OpenChoosingShellHandler.ProfileLabels();
		ShellProfiles.Profile zsh = new ShellProfiles.Profile("zsh", "/usr/bin/zsh");
		ShellProfiles.Profile gitBash = new ShellProfiles.Profile("Git Bash", "C:\\Git\\bin\\bash.exe --login -i");

		assertEquals("zsh", labels.getText(zsh));
		assertSame(XtermPlugin.image("icons/shells/zsh.png"), labels.getImage(zsh));
		assertEquals("Git Bash", labels.getText(gitBash));
		assertSame(XtermPlugin.image("icons/shells/gitbash.png"), labels.getImage(gitBash));
	}
}
