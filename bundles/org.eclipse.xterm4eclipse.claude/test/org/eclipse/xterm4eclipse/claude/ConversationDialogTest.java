package org.eclipse.xterm4eclipse.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.xterm4eclipse.TestWorkbench;
import org.junit.jupiter.api.Test;

class ConversationDialogTest {

	private static final Display DISPLAY = TestWorkbench.DISPLAY;

	/** The first table with items, in the controls of a shell. */
	private static Table table(Composite parent) {
		for (Control child : parent.getChildren()) {
			if (child instanceof Table table && table.getItemCount() > 0) {
				return table;
			}
			if (child instanceof Composite composite) {
				Table found = table(composite);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/** The first text field, in the controls of a shell. */
	private static Text text(Composite parent) {
		for (Control child : parent.getChildren()) {
			if (child instanceof Text text) {
				return text;
			}
			if (child instanceof Composite composite) {
				Text found = text(composite);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	@Test
	void listsTheLatestConversationFirst() {
		// Given in an order that is neither the one of the dates nor the alphabetical one.
		List<Conversations.Conversation> conversations = List.of(new Conversations.Conversation("b", "b second", 2000),
				new Conversations.Conversation("a", "a oldest", 1000), new Conversations.Conversation("c", "c latest", 3000),
				new Conversations.Conversation("d", "d between", 2500));
		Shell parent = new Shell(DISPLAY);
		ConversationDialog dialog = new ConversationDialog(parent, new File("."), conversations);
		dialog.setBlockOnOpen(false);
		dialog.open();
		try {
			Table table = table(dialog.getShell());
			assertTrue(table != null && table.getItemCount() == 4, "the conversations are listed");
			List<String> titles = new ArrayList<>();
			for (TableItem item : table.getItems()) {
				titles.add(item.getText().substring(0, item.getText().indexOf("  ")));
			}
			assertEquals(List.of("c latest", "d between", "b second", "a oldest"), titles, "by date, not by name");
			assertEquals(1, table.getSelectionCount(), "the latest is selected: Enter resumes it");
			assertEquals(0, table.getSelectionIndex());
			assertTrue(dialog.canResume(), "OK resumes it");

			// The filter keeps the order, and selects the first of what is left.
			Text filter = text(dialog.getShell());
			filter.setText("BETWEEN");
			assertEquals(1, table.getItemCount());
			assertEquals(0, table.getSelectionIndex());
			filter.setText("nothing like it");
			assertEquals(0, table.getItemCount());
			assertFalse(dialog.canResume(), "nothing to resume");
			filter.setText("");
			assertEquals(4, table.getItemCount());
			// The title and the date are filtered, not what separates them.
			filter.setText("\u2014");
			assertEquals(0, table.getItemCount());
			filter.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(3000)).substring(0, 4));
			assertEquals(4, table.getItemCount(), "the dates of the test are all in 1970");
			filter.setText("");

			// A double click on a conversation resumes it, whatever the filter showed before.
			table.setSelection(2);
			table.notifyListeners(SWT.Selection, new Event());
			table.notifyListeners(SWT.DefaultSelection, new Event());
			assertEquals(Window.OK, dialog.getReturnCode());
			assertEquals("b", dialog.chosen().id());
		} finally {
			dialog.close();
			parent.dispose();
		}
	}
}
