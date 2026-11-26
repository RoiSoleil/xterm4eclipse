package org.eclipse.xterm4eclipse.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.eclipse.core.resources.IResource;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.xterm4eclipse.TestWorkbench;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationSearchDialogTest {

	private static final Display DISPLAY = TestWorkbench.DISPLAY;
	static final long DAY = 24 * 3600 * 1000L;
	static final long NOW = 1_800_000_000_000L;

	@TempDir
	Path temp;
	File project;
	File gone;
	private Path previousHome;
	private UnaryOperator<String> previousEnvironment;
	private ConversationDialog.FileOpener previousOpener;
	private final Map<String, String> environment = new HashMap<>();

	@BeforeEach
	void isolate() throws Exception {
		previousHome = Conversations.userHome;
		previousEnvironment = ClaudeCommand.environment;
		previousOpener = ConversationDialog.fileOpener;
		Conversations.userHome = temp.resolve("home");
		ClaudeCommand.environment = environment::get;
		ConversationSearch.now = NOW;
		project = Files.createDirectories(temp.resolve("app")).toFile();
		gone = temp.resolve("deleted worktree").toFile();
		write(project, ConversationSearchTest.A, NOW - DAY,
				ConversationSearchTest.user(ConversationSearchTest.A, project, "main", NOW - DAY, "Fix PROJ-7 now"));
		write(project, ConversationSearchTest.B, NOW - 3 * DAY,
				ConversationSearchTest.user(ConversationSearchTest.B, project, "main", NOW - 3 * DAY, "Something else"),
				ConversationSearchTest.assistant(ConversationSearchTest.B, NOW - 3 * DAY, "PROJ-7 is mentioned here"));
		write(gone, ConversationSearchTest.C, NOW - 60_000,
				ConversationSearchTest.user(ConversationSearchTest.C, gone, "feature", NOW - 60_000, "PROJ-7 elsewhere"));
	}

	@AfterEach
	void restore() {
		Conversations.userHome = previousHome;
		ClaudeCommand.environment = previousEnvironment;
		ConversationDialog.fileOpener = previousOpener;
		ConversationSearch.now = -1;
	}

	void write(File directory, String id, long modified, String... lines) throws Exception {
		Path folder = Files.createDirectories(temp.resolve("home/.claude/projects").resolve(Conversations.folderName(directory)));
		Path file = folder.resolve(id + ".jsonl");
		Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
	}

	private static Table table(ConversationDialog dialog) {
		return (Table) find(dialog.getShell(), Table.class);
	}

	private static Object find(org.eclipse.swt.widgets.Composite parent, Class<?> type) {
		for (org.eclipse.swt.widgets.Control child : parent.getChildren()) {
			if (type.isInstance(child)) {
				return child;
			}
			if (child instanceof org.eclipse.swt.widgets.Composite composite) {
				Object found = find(composite, type);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	ConversationDialog open(Shell parent, String text, boolean contents, boolean both) {
		ConversationDialog dialog = new ConversationDialog(parent, project, Conversations.list(project), text, contents, both);
		dialog.setBlockOnOpen(false);
		dialog.open();
		if (contents) {
			dialog.searchNow();
		}
		return dialog;
	}

	@Test
	void searchesTheMessagesOfAllProjects() {
		Shell parent = new Shell(DISPLAY);
		ConversationDialog dialog = open(parent, "PROJ-7", true, true);
		try {
			assertEquals("Find a Conversation", dialog.getShell().getText());
			Table table = table(dialog);
			assertEquals(3, table.getItemCount());
			assertTrue(dialog.interpretation().startsWith("Ticket PROJ-7"), dialog.interpretation());
			assertTrue(dialog.interpretation().endsWith("3 conversations"), dialog.interpretation());
			assertTrue(table.getItem(0).getText().contains("in progress"), table.getItem(0).getText());
			assertTrue(table.getItem(1).getText().contains("(me 1, others 0)"), table.getItem(1).getText());
			assertTrue(dialog.details().contains("PROJ-7 elsewhere"), dialog.details());
			assertTrue(dialog.canResume());

			dialog.select(dialog.scopeCombo(), 0);
			dialog.searchNow();
			assertEquals(2, table.getItemCount(), "this folder only");
			dialog.select(dialog.authorCombo(), 2);
			dialog.searchNow();
			assertEquals(1, table.getItemCount(), "said by Claude");
			assertEquals(ConversationSearchTest.B, dialog.selectedHit().id());
			dialog.select(dialog.authorCombo(), 0);
			dialog.select(dialog.periodCombo(), 3);
			dialog.searchNow();
			assertEquals(2, table.getItemCount(), "the last 7 days");
			dialog.select(dialog.periodCombo(), 1);
			dialog.searchNow();
			dialog.select(dialog.periodCombo(), 2);
			dialog.searchNow();
			dialog.select(dialog.periodCombo(), 4);
			dialog.searchNow();
			assertEquals(2, table.getItemCount());
			dialog.branchText().setText("nothing");
			dialog.searchNow();
			assertEquals(0, table.getItemCount());
			assertTrue(dialog.interpretation().contains("no conversation found"), dialog.interpretation());
			assertFalse(dialog.canResume());
			assertEquals("", dialog.details());
			dialog.branchText().setText("");
			dialog.modifiedOnlyButton().setSelection(true);
			dialog.fuzzyButton().setSelection(true);
			dialog.subagentsButton().setSelection(true);
			dialog.select(dialog.modeCombo(), 2);
			dialog.filterText().setText("proj-\\d");
			dialog.searchNow();
			assertTrue(dialog.interpretation().startsWith("Regular expression"), dialog.interpretation());
			assertEquals(0, table.getItemCount(), "none modified a file");
		} finally {
			dialog.close();
			parent.dispose();
		}
	}

	@Test
	void searchesAsYouType() {
		Shell parent = new Shell(DISPLAY);
		ConversationDialog dialog = open(parent, "", false, false);
		try {
			Table table = table(dialog);
			assertEquals(2, table.getItemCount(), "the conversations of the folder");
			assertFalse(dialog.searching());
			assertEquals("Conversations of this folder, the latest first", dialog.interpretation());
			dialog.filterText().setText("mentioned");
			assertEquals(0, table.getItemCount(), "no title has it");
			assertTrue(dialog.interpretation().contains("Search in the messages"));
			dialog.setSearchContents(true);
			assertTrue(dialog.interpretation().endsWith("searching..."), dialog.interpretation());
			long end = System.currentTimeMillis() + 10_000;
			while (dialog.interpretation().endsWith("searching...") && System.currentTimeMillis() < end) {
				if (!DISPLAY.readAndDispatch()) {
					DISPLAY.sleep();
				}
			}
			assertEquals(1, table.getItemCount(), dialog.interpretation());
			assertEquals(ConversationSearchTest.B, dialog.selectedHit().id());
			dialog.setSearchContents(false);
			assertEquals(0, table.getItemCount(), "back to the titles");
		} finally {
			dialog.close();
			parent.dispose();
		}
	}

	@Test
	void copiesOpensAndResumesInATerminal() {
		List<Path> opened = new ArrayList<>();
		ConversationDialog.fileOpener = opened::add;
		Shell parent = new Shell(DISPLAY);
		ConversationDialog dialog = open(parent, "PROJ-7", true, true);
		try {
			dialog.buttonPressed(ConversationDialog.COPY_ID);
			Clipboard clipboard = new Clipboard(DISPLAY);
			try {
				assertEquals(ConversationSearchTest.C, clipboard.getContents(TextTransfer.getInstance()));
			} finally {
				clipboard.dispose();
			}
			dialog.buttonPressed(ConversationDialog.OPEN_FILE);
			assertEquals(1, opened.size());
			assertTrue(opened.get(0).toString().endsWith(ConversationSearchTest.C + ".jsonl"));
			assertEquals("Resume in a Terminal", dialog.getShell().getDefaultButton().getText());
			dialog.buttonPressed(ConversationDialog.TERMINAL);
			assertEquals(Window.OK, dialog.getReturnCode());
			assertTrue(dialog.inTerminal());
			assertEquals(ConversationSearchTest.C, dialog.chosen().id());
			assertEquals(gone, dialog.chosen().directory());
		} finally {
			dialog.close();
			parent.dispose();
		}
		Shell other = new Shell(DISPLAY);
		ConversationDialog list = open(other, "", false, false);
		try {
			list.buttonPressed(ConversationDialog.OPEN_FILE);
			assertEquals(2, opened.size());
			assertTrue(opened.get(1).toString().endsWith(ConversationSearchTest.A + ".jsonl"));
		} finally {
			list.close();
			other.dispose();
		}
	}

	@Test
	void resumesInATerminalInThePermissionModeOfTheChats() {
		ResumeInTerminalHandler.Opener previousOpener = ResumeInTerminalHandler.opener;
		List<String> opened = new ArrayList<>();
		Shell parent = new Shell(DISPLAY);
		try {
			ResumeInTerminalHandler.opener = (page, commandLine, directory) -> opened.add(commandLine);
			Conversations.Conversation conversation = new Conversations.Conversation(ConversationSearchTest.A, "t", 0, project);
			ChatPlugin.preferences().setValue(ChatPlugin.PREF_PERMISSION_MODE, "auto");
			ResumeInTerminalHandler.open(parent, null, conversation, project);
			ChatPlugin.preferences().setValue(ChatPlugin.PREF_PERMISSION_MODE, "default");
			ResumeInTerminalHandler.open(parent, null, conversation, project);
			ChatPlugin.preferences().setValue(ChatPlugin.PREF_PERMISSION_MODE, "auto; rm -rf x");
			ResumeInTerminalHandler.open(parent, null, conversation, project);
			assertEquals(3, opened.size());
			assertTrue(opened.get(0).endsWith(" --resume " + ConversationSearchTest.A + " --permission-mode auto"), opened.get(0));
			assertTrue(opened.get(1).endsWith(" --resume " + ConversationSearchTest.A), opened.get(1));
			assertTrue(opened.get(2).endsWith(" --resume " + ConversationSearchTest.A), opened.get(2));
		} finally {
			ChatPlugin.preferences().setToDefault(ChatPlugin.PREF_PERMISSION_MODE);
			ResumeInTerminalHandler.opener = previousOpener;
			parent.dispose();
		}
	}

	@Test
	void resumesWhereTheConversationWas() {
		ResumeHandler.MissingDirectory previous = ResumeHandler.missingDirectory;
		ResumeInTerminalHandler.Opener previousOpener = ResumeInTerminalHandler.opener;
		FindConversationHandler.Finder previousFinder = FindConversationHandler.finder;
		List<String> asked = new ArrayList<>();
		List<String> opened = new ArrayList<>();
		Shell parent = new Shell(DISPLAY);
		try {
			ResumeHandler.missingDirectory = (shell, missing) -> {
				asked.add(missing);
				return null;
			};
			ResumeInTerminalHandler.opener = (page, commandLine, directory) -> opened.add(commandLine + " in " + directory);
			assertEquals(project, ResumeHandler.target(parent, new Conversations.Conversation("x", "t", 0), project));
			assertEquals(project, ResumeHandler.target(parent, new Conversations.Conversation("x", "t", 0, project), temp.toFile()));
			assertNull(ResumeHandler.target(parent, new Conversations.Conversation("x", "t", 0, gone), project));
			assertEquals(List.of(gone.getPath()), asked);

			FindConversationHandler.finder = (shell, directory, text) -> {
				ConversationDialog dialog = new ConversationDialog(shell, directory, Conversations.list(directory), text, true, true);
				dialog.setBlockOnOpen(false);
				dialog.open();
				dialog.select(dialog.scopeCombo(), 0);
				dialog.searchNow();
				dialog.buttonPressed(ConversationDialog.TERMINAL);
				return dialog;
			};
			FindConversationHandler.find(parent, null, project, "PROJ-7");
			assertEquals(1, opened.size());
			assertTrue(opened.get(0).endsWith("--resume " + ConversationSearchTest.A + " in " + project), opened.get(0));

			FindConversationHandler.finder = (shell, directory, text) -> {
				ConversationDialog dialog = new ConversationDialog(shell, directory, List.of(), text, true, true);
				dialog.setBlockOnOpen(false);
				dialog.open();
				long end = System.currentTimeMillis() + 10_000;
				while (!dialog.canResume() && System.currentTimeMillis() < end) {
					if (!DISPLAY.readAndDispatch()) {
						DISPLAY.sleep();
					}
				}
				dialog.buttonPressed(Window.OK);
				return dialog;
			};
			FindConversationHandler.find(parent, null, project, "PROJ-7");
			assertEquals(List.of(gone.getPath(), gone.getPath()), asked, "the folder of the conversation is gone");
			FindConversationHandler.finder = (shell, directory, text) -> null;
			FindConversationHandler.find(parent, null, project, "");
			assertEquals(1, opened.size());
		} finally {
			ResumeHandler.missingDirectory = previous;
			ResumeInTerminalHandler.opener = previousOpener;
			FindConversationHandler.finder = previousFinder;
			parent.dispose();
		}
	}

	@Test
	void searchesTheSelection() {
		Document document = new Document("  PROJ-7 and more\nsecond line");
		assertEquals("PROJ-7 and more", FindConversationHandler.text(new TextSelection(document, 0, document.getLength())));
		assertEquals("", FindConversationHandler.text(new TextSelection(document, 0, 0)));
		assertEquals("", FindConversationHandler.text(StructuredSelection.EMPTY));
		assertEquals("", FindConversationHandler.text(new StructuredSelection("not a resource")));
		assertEquals("src/Foo.java", FindConversationHandler.text(new StructuredSelection(resource(IResource.FILE))));
		assertEquals("app", FindConversationHandler.text(new StructuredSelection(resource(IResource.PROJECT))));
		assertEquals("", FindConversationHandler.text(new StructuredSelection(resource(IResource.ROOT))));
		assertEquals("", FindConversationHandler.text(null));
	}

	private static IResource resource(int type) {
		return (IResource) Proxy.newProxyInstance(IResource.class.getClassLoader(), new Class<?>[] { IResource.class },
				(proxy, method, arguments) -> switch (method.getName()) {
				case "getType" -> type;
				case "getName" -> "app";
				case "getProjectRelativePath" -> org.eclipse.core.runtime.IPath.fromPortableString("src/Foo.java");
				case "getAdapter" -> null;
				case "hashCode" -> 1;
				case "equals" -> proxy == arguments[0];
				default -> null;
				});
	}
}
