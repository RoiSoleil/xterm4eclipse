package org.eclipse.xterm4eclipse.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.XMLMemento;
import org.eclipse.xterm4eclipse.TestWorkbench;
import org.eclipse.xterm4eclipse.XtermView;
import org.junit.jupiter.api.Test;

/** The answers of the user when a new terminal of an agent offers the MCP server of Eclipse. */
class McpOfferTest extends HarnessTestCase {

	@Override
	Harness harness() {
		return Harnesses.CLAUDE;
	}

	@Test
	void notNowAsksAgainOnlyAfterARestartOfEclipse() throws Exception {
		eclipseMcpRuns();
		String commandLine = program("claude");
		answer = McpOffer.NOT_NOW;
		started(open(commandLine), 1);
		started(open(commandLine), 1);
		assertEquals(1, asked.size(), "asked once");
		assertTrue(mcpCalls().isEmpty(), "nothing added without a yes");
		assertTrue(HarnessPlugin.preferences().getBoolean(HarnessPlugin.PREF_OFFER_ECLIPSE_MCP));
		// Eclipse restarts.
		McpOffer.postponed = false;
		started(open(commandLine), 1);
		assertEquals(2, asked.size());
	}

	@Test
	void neverIsAPreference() throws Exception {
		eclipseMcpRuns();
		String commandLine = program("claude");
		answer = McpOffer.NEVER;
		started(open(commandLine), 1);
		assertFalse(HarnessPlugin.preferences().getBoolean(HarnessPlugin.PREF_OFFER_ECLIPSE_MCP));
		started(open(commandLine), 1);
		assertEquals(1, asked.size());
		assertTrue(mcpCalls().isEmpty());

		// The preference page shows it, and brings the question back.
		Shell shell = new Shell(DISPLAY);
		try {
			HarnessPreferencePage page = new HarnessPreferencePage();
			page.init(null);
			page.createControl(shell);
			assertTrue(page.getDescription().contains("Claude Code, Codex, OpenCode, Reasonix"), page.getDescription());
			List<Button> checkboxes = new ArrayList<>();
			collect(page.getControl(), checkboxes);
			assertEquals(2, checkboxes.size());
			assertTrue(checkboxes.get(0).getText().contains("MCP server of Eclipse"));
			assertFalse(checkboxes.get(0).getSelection());
			checkboxes.get(0).setSelection(true);
			checkboxes.get(0).notifyListeners(SWT.Selection, new Event());
			assertTrue(page.performOk());
		} finally {
			shell.dispose();
		}
		assertTrue(HarnessPlugin.preferences().getBoolean(HarnessPlugin.PREF_OFFER_ECLIPSE_MCP));
		answer = McpOffer.ADD;
		started(open(commandLine), 1);
		assertEquals(2, asked.size());
		assertEquals(1, mcpCalls().size());
	}

	@Test
	void alwaysAddsTheServerAndTheNextTimesWithoutAsking() throws Exception {
		eclipseMcpRuns();
		String commandLine = program("claude");
		answer = McpOffer.ALWAYS;
		started(open(commandLine), 1);
		assertEquals(1, asked.size());
		assertEquals(1, mcpCalls().size(), "added at once");
		assertTrue(HarnessPlugin.preferences().getBoolean(HarnessPlugin.PREF_ADD_ECLIPSE_MCP_WITHOUT_ASKING));

		// The stand-in of the agent does not keep the server: missing again, added without asking.
		answer = McpOffer.NEVER;
		started(open(commandLine), 1);
		assertEquals(1, asked.size(), "not asked again");
		assertEquals(2, mcpCalls().size(), "added again");

		// The preference page shows it, and brings the question back.
		Shell shell = new Shell(DISPLAY);
		try {
			HarnessPreferencePage page = new HarnessPreferencePage();
			page.init(null);
			page.createControl(shell);
			List<Button> checkboxes = new ArrayList<>();
			collect(page.getControl(), checkboxes);
			Button withoutAsking = checkboxes.get(1);
			assertTrue(withoutAsking.getText().contains("without asking"), withoutAsking.getText());
			assertTrue(withoutAsking.getSelection());
			withoutAsking.setSelection(false);
			withoutAsking.notifyListeners(SWT.Selection, new Event());
			assertTrue(page.performOk());
		} finally {
			shell.dispose();
		}
		assertFalse(HarnessPlugin.preferences().getBoolean(HarnessPlugin.PREF_ADD_ECLIPSE_MCP_WITHOUT_ASKING));
		answer = McpOffer.NOT_NOW;
		started(open(commandLine), 1);
		assertEquals(2, asked.size(), "asked again");
	}

	private static void collect(Control control, List<Button> checkboxes) {
		if (control instanceof Button button && (button.getStyle() & SWT.CHECK) != 0) {
			checkboxes.add(button);
		} else if (control instanceof Composite composite) {
			for (Control child : composite.getChildren()) {
				collect(child, checkboxes);
			}
		}
	}

	@Test
	void terminalThatEclipseRestoresNeverAsks() throws Exception {
		eclipseMcpRuns();
		XMLMemento memento = XMLMemento.createWriteRoot("view");
		memento.putString("shell", program("claude"));
		started(reopen(memento), 1);
		assertTrue(asked.isEmpty());
		assertTrue(mcpCalls().isEmpty());
	}

	@Test
	void agentThatCannotAddTheServerStartsAllTheSame() throws Exception {
		eclipseMcpRuns();
		String commandLine = program("claude");
		refuseTheFirstAdd("Invalid\nconfiguration");
		XtermView view = open(commandLine);
		started(view, 1);
		String text = TestWorkbench.text(view).replace("\n", "");
		assertTrue(text.contains("[Could not add the MCP server of Eclipse: Invalid configuration]"), text);
		assertFalse(text.contains("can now use Eclipse"));
		assertEquals(1, mcpCalls().size(), "not a server to replace: not removed");
	}

	@Test
	void nothingIsAskedWithoutServerOrOnceTheTerminalIsClosed() throws Exception {
		// No server of Eclipse.
		String commandLine = program("claude");
		started(open(commandLine), 1);
		assertTrue(asked.isEmpty());
		// A server, and a terminal that the user closes before the question comes.
		eclipseMcpRuns();
		FakeTerminal terminal = new FakeTerminal("claude", temp.toFile());
		Harnesses.Found found = Harnesses.find("claude");
		boolean[] started = {false};
		assertTrue(McpOffer.offer(terminal, Harnesses.CLAUDE, found.invocation(), DISPLAY, () -> started[0] = true));
		terminal.disposed = true;
		TestWorkbench.pump(200);
		assertTrue(asked.isEmpty(), "the terminal was closed meanwhile");
		assertFalse(started[0]);
		// Turned off in the preferences, or an agent without a command for its MCP servers.
		HarnessPlugin.preferences().setValue(HarnessPlugin.PREF_OFFER_ECLIPSE_MCP, false);
		assertFalse(McpOffer.offer(terminal, Harnesses.CLAUDE, found.invocation(), DISPLAY, () -> started[0] = true));
		assertFalse(McpOffer.offer(terminal, Harnesses.CURSOR, found.invocation(), DISPLAY, () -> started[0] = true));
	}
}
