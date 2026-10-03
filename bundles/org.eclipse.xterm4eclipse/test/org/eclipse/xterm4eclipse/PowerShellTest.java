package org.eclipse.xterm4eclipse;

import static org.eclipse.xterm4eclipse.TestWorkbench.FOLDER;
import static org.eclipse.xterm4eclipse.TestWorkbench.await;
import static org.eclipse.xterm4eclipse.TestWorkbench.screen;
import static org.eclipse.xterm4eclipse.TestWorkbench.text;
import static org.eclipse.xterm4eclipse.TestWorkbench.type;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.ui.XMLMemento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Windows PowerShell 5.1 and PowerShell 7 in the real view, as the shell of the terminal. */
@EnabledOnOs(OS.WINDOWS)
class PowerShellTest {

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

	private static void run(XtermView view, String command, String expected) {
		type(view, command + "\r");
		await("output of " + command, () -> screen(view).contains(expected));
	}

	private static String savedDirectory(XtermView view) {
		XMLMemento memento = XMLMemento.createWriteRoot("view");
		view.saveState(memento);
		return memento.getString("directory");
	}

	@ParameterizedTest
	@ValueSource(strings = {"powershell.exe", "pwsh.exe"})
	void powerShellIsTheShellOfTheTerminal(String program) throws Exception {
		ShellProfiles.setDefaultCommandLine(program + " -NoLogo -NoProfile");
		XtermView view = new XtermView();
		workbench.open(view, null, null);
		await("prompt", () -> screen(view).contains("PS ") && screen(view).contains(">"));
		assertEquals(program.substring(0, program.length() - 4), view.getPartName(), "named after the shell");

		run(view, "Write-Output ('r' + 6*7)", "\nr42\n");
		run(view, "Write-Output 'été'", "\nété\n");

		// The prompt announces the directory: it is saved, and a restart opens there.
		run(view, "Set-Location '" + FOLDER + "'; Write-Output moved", "\nmoved\n");
		await("directory announced", () -> FOLDER.getPath().equals(savedDirectory(view)));

		// A command runs: the view is busy until the next prompt.
		type(view, "Start-Sleep -Seconds 2\r");
		await("running", view::isDirty);
		await("finished", () -> !view.isDirty());

		view.restart();
		await("restarted", () -> text(view).contains("[Restarted]"));
		await("new prompt", () -> text(view).substring(text(view).indexOf("[Restarted]")).contains("PS "));
		run(view, "Write-Output ('in=' + (Get-Location).Path)", "\nin=" + FOLDER.getPath() + "\n");

		type(view, "exit\r");
		await("view closed", () -> workbench.page.count("hideView") == 1);
	}
}
