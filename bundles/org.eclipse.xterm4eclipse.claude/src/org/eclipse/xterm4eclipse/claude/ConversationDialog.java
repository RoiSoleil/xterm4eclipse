package org.eclipse.xterm4eclipse.claude;

import java.io.File;
import java.text.DateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Asks which conversation to resume, the latest first. The list of elements of the workbench sorts
 * them by name, whatever their order, so this one is a plain table sorted by date.
 */
final class ConversationDialog extends Dialog {

	static final int TERMINAL = IDialogConstants.CLIENT_ID + 1;
	static final int COPY_ID = IDialogConstants.CLIENT_ID + 2;
	static final int OPEN_FILE = IDialogConstants.CLIENT_ID + 3;
	static final int DELAY = 300;
	static final String[] SCOPES = { "This folder", "All projects" };
	static final String[] PERIODS = { "Any time", "Today", "Yesterday", "Last 7 days", "Last 30 days" };
	static final String[] AUTHORS = { "Anyone", "Me", "Claude", "Tools" };
	static final String[] MODES = { "Automatic", "Exact text", "Regular expression" };

	interface FileOpener {
		void open(java.nio.file.Path file);
	}

	static FileOpener fileOpener = file -> {
		try {
			org.eclipse.ui.ide.IDE.openEditorOnFileStore(
					org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(),
					org.eclipse.core.filesystem.EFS.getLocalFileSystem().getStore(file.toUri()));
		} catch (org.eclipse.ui.PartInitException e) {
			ChatPlugin.log("Could not open " + file, e);
		}
	};

	private static final int LINES = 15;
	private static final int WIDTH_IN_CHARACTERS = 110;

	private final File directory;
	private final List<Conversations.Conversation> conversations;
	private final DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
	private final String initialText;
	private final boolean initialContents;
	private final boolean bothTargets;
	private final Map<Conversations.Conversation, ConversationSearch.Hit> hits = new IdentityHashMap<>();
	private Text filter;
	private Label interpretation;
	private Button contents;
	private Combo scope;
	private Combo period;
	private Combo author;
	private Combo mode;
	private Text branch;
	private Button modifiedOnly;
	private Button fuzzy;
	private Button subagents;
	private TableViewer viewer;
	private StyledText details;
	private Conversations.Conversation chosen;
	private boolean inTerminal;
	private Job job;
	private Runnable pending;
	private int generation;

	ConversationDialog(Shell shell, File directory, List<Conversations.Conversation> conversations) {
		this(shell, directory, conversations, "", false, false);
	}

	ConversationDialog(Shell shell, File directory, List<Conversations.Conversation> conversations, String text,
			boolean searchContents, boolean bothTargets) {
		super(shell);
		this.directory = directory;
		this.conversations = conversations.stream()
				.sorted(Comparator.comparingLong(Conversations.Conversation::modified).reversed()).toList();
		this.initialText = text == null ? "" : text;
		this.initialContents = searchContents;
		this.bothTargets = bothTargets;
		setShellStyle(getShellStyle() | SWT.RESIZE);
	}

	/** @return {@code true} if OK resumes a conversation: one is selected */
	boolean canResume() {
		return getButton(OK) != null && getButton(OK).isEnabled();
	}

	/** @return the conversation chosen with OK, or {@code null} */
	Conversations.Conversation chosen() {
		return chosen;
	}

	boolean inTerminal() {
		return inTerminal;
	}

	String interpretation() {
		return interpretation.getText();
	}

	String details() {
		return details.getText();
	}

	/** What the list shows of a conversation: its title, and when it was last used. */
	String label(Conversations.Conversation conversation) {
		ConversationSearch.Hit hit = hits.get(conversation);
		String label = conversation.title() + "  —  " + format.format(new Date(conversation.modified()));
		if (hit == null) {
			return label;
		}
		StringBuilder more = new StringBuilder(label);
		if (hit.cwd() != null) {
			more.append("  —  ").append(hit.cwd());
		}
		if (hit.branch() != null) {
			more.append(" [").append(hit.branch()).append(']');
		}
		if (hit.mine() + hit.others() > 0) {
			more.append("  (me ").append(hit.mine()).append(", others ").append(hit.others()).append(')');
		}
		if (hit.recent()) {
			more.append("  ● in progress");
		}
		return more.toString();
	}

	/** What the filter looks in: the title and the date, in lower case, but not what separates them. */
	private String searched(Conversations.Conversation conversation) {
		return (conversation.title() + ' ' + format.format(new Date(conversation.modified()))).toLowerCase(Locale.ROOT);
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(bothTargets ? "Find a Conversation" : "Resume a Conversation");
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		Label message = new Label(area, SWT.WRAP);
		// An ampersand of the path is not the mnemonic of a label.
		message.setText("Conversations of Claude Code in " + directory.getPath().replace("&", "&&") + ':'); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		GridDataFactory.fillDefaults().grab(true, false).applyTo(message);

		filter = new Text(area, SWT.SINGLE | SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
		filter.setMessage("Filter, or search the messages: a pull request, a ticket, a commit, a file, words...");
		GridDataFactory.fillDefaults().grab(true, false).applyTo(filter);
		interpretation = new Label(area, SWT.WRAP);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(interpretation);

		Composite options = new Composite(area, SWT.NONE);
		GridLayoutFactory.fillDefaults().numColumns(5).applyTo(options);
		GridDataFactory.fillDefaults().grab(true, false).applyTo(options);
		contents = check(options, "Search in the messages", initialContents);
		scope = combo(options, SCOPES, initialContents && bothTargets ? 1 : 0);
		period = combo(options, PERIODS, 0);
		author = combo(options, AUTHORS, 0);
		mode = combo(options, MODES, 0);
		branch = new Text(options, SWT.SINGLE | SWT.BORDER | SWT.SEARCH);
		branch.setMessage("Branch");
		GridDataFactory.fillDefaults().grab(true, false).applyTo(branch);
		modifiedOnly = check(options, "Modified files only", false);
		fuzzy = check(options, "Approximate", false);
		subagents = check(options, "Subagents", false);
		branch.addModifyListener(event -> optionsChanged());

		viewer = new TableViewer(area, SWT.SINGLE | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
		viewer.setContentProvider(ArrayContentProvider.getInstance());
		viewer.setLabelProvider(new LabelProvider() {
			@Override
			public String getText(Object element) {
				return label((Conversations.Conversation) element);
			}
		});
		// No sorter: the list is in the order of the dates.
		viewer.addFilter(new ViewerFilter() {
			@Override
			public boolean select(Viewer v, Object parentElement, Object element) {
				if (searching()) {
					return true;
				}
				String text = filter.getText().strip().toLowerCase(Locale.ROOT);
				return text.isEmpty() || searched((Conversations.Conversation) element).contains(text);
			}
		});
		viewer.setInput(conversations);
		viewer.addDoubleClickListener(event -> buttonPressed(bothTargets ? TERMINAL : OK));
		viewer.addSelectionChangedListener(event -> {
			updateOk();
			showDetails();
		});
		GridDataFactory.fillDefaults().grab(true, true).hint(convertWidthInCharsToPixels(WIDTH_IN_CHARACTERS),
				convertHeightInCharsToPixels(LINES)).applyTo(viewer.getControl());

		details = new StyledText(area, SWT.READ_ONLY | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL);
		GridDataFactory.fillDefaults().grab(true, false).hint(SWT.DEFAULT, convertHeightInCharsToPixels(3)).applyTo(details);

		filter.addModifyListener(event -> {
			if (searching()) {
				schedule();
			} else {
				viewer.refresh();
				selectFirst();
				describe();
			}
		});
		// The arrow down goes from the filter to the list, as in the dialogs of the workbench.
		filter.addListener(SWT.KeyDown, event -> {
			if (event.keyCode == SWT.ARROW_DOWN) {
				event.doit = false; // the key is not for the text field too
				viewer.getControl().setFocus();
			}
		});
		filter.setText(initialText);
		if (searching()) {
			schedule(0);
		} else {
			describe();
		}
		// The latest, which Enter resumes.
		selectFirst();
		return area;
	}

	private Button check(Composite parent, String text, boolean selected) {
		Button button = new Button(parent, SWT.CHECK);
		button.setText(text);
		button.setSelection(selected);
		button.addListener(SWT.Selection, event -> optionsChanged());
		return button;
	}

	private Combo combo(Composite parent, String[] items, int selected) {
		Combo combo = new Combo(parent, SWT.READ_ONLY | SWT.DROP_DOWN);
		combo.setItems(items);
		combo.select(selected);
		combo.addListener(SWT.Selection, event -> optionsChanged());
		return combo;
	}

	boolean searching() {
		return contents.getSelection() || scope.getSelectionIndex() > 0 || period.getSelectionIndex() > 0
				|| author.getSelectionIndex() > 0 || mode.getSelectionIndex() > 0 || modifiedOnly.getSelection()
				|| fuzzy.getSelection() || subagents.getSelection() || !branch.getText().isBlank();
	}

	private void optionsChanged() {
		if (searching()) {
			schedule();
		} else {
			cancel();
			hits.clear();
			viewer.setInput(conversations);
			selectFirst();
			describe();
		}
	}

	private void describe() {
		String text = filter.getText().strip();
		interpretation.setText(text.isEmpty() ? "Conversations of this folder, the latest first"
				: "Titles and dates that contain “" + text + "”; check “Search in the messages” to look further");
		interpretation.getParent().layout(true);
	}

	ConversationSearch.Options options() {
		ConversationSearch.Mode chosenMode = ConversationSearch.Mode.values()[Math.max(0, mode.getSelectionIndex())];
		ConversationSearch.Options options = ConversationSearch.Options
				.of(ConversationSearch.parse(filter.getText(), chosenMode, fuzzy.getSelection()))
				.by(ConversationSearch.Author.values()[Math.max(0, author.getSelectionIndex())])
				.modifiedOnly(modifiedOnly.getSelection()).withSubagents(subagents.getSelection())
				.onBranch(branch.getText().isBlank() ? null : branch.getText().strip());
		if (scope.getSelectionIndex() <= 0) {
			options = options.in(directory);
		}
		long now = ConversationSearch.now >= 0 ? ConversationSearch.now : System.currentTimeMillis();
		ZoneId zone = ZoneId.systemDefault();
		long today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone).atStartOfDay(zone).toInstant().toEpochMilli();
		long day = 24L * 3600 * 1000;
		return switch (period.getSelectionIndex()) {
		case 1 -> options.between(today, Long.MAX_VALUE);
		case 2 -> options.between(today - day, today - 1);
		case 3 -> options.between(now - 7 * day, Long.MAX_VALUE);
		case 4 -> options.between(now - 30 * day, Long.MAX_VALUE);
		default -> options;
		};
	}

	private void schedule() {
		schedule(DELAY);
	}

	private void schedule(int delay) {
		cancel();
		Runnable run = this::searchInBackground;
		pending = run;
		viewer.getControl().getDisplay().timerExec(delay, () -> {
			if (pending == run && !viewer.getControl().isDisposed()) {
				pending = null;
				run.run();
			}
		});
		interpretation.setText(options().query().description() + " — searching...");
	}

	private void cancel() {
		pending = null;
		generation++;
		if (job != null) {
			job.cancel();
			job = null;
		}
	}

	private void searchInBackground() {
		ConversationSearch.Options options = options();
		int current = ++generation;
		job = new Job("Searching the conversations of Claude Code") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				List<ConversationSearch.Hit> found = ConversationSearch.search(options, monitor::isCanceled);
				if (!monitor.isCanceled() && !viewer.getControl().isDisposed()) {
					viewer.getControl().getDisplay().asyncExec(() -> {
						if (current == generation && !viewer.getControl().isDisposed()) {
							show(options, found);
						}
					});
				}
				return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
			}
		};
		job.setSystem(true);
		job.schedule();
	}

	void searchNow() {
		cancel();
		ConversationSearch.Options options = options();
		show(options, ConversationSearch.search(options, () -> false));
	}

	private void show(ConversationSearch.Options options, List<ConversationSearch.Hit> found) {
		hits.clear();
		List<Conversations.Conversation> shown = new ArrayList<>();
		for (ConversationSearch.Hit hit : found) {
			Conversations.Conversation conversation = hit.conversation();
			hits.put(conversation, hit);
			shown.add(conversation);
		}
		viewer.setInput(shown);
		String description = options.query().description();
		interpretation.setText(found.isEmpty()
				? description + " — no conversation found: remove filters, try “Approximate” or “Subagents”"
				: description + " — " + found.size() + (found.size() == 1 ? " conversation" : " conversations"));
		interpretation.getParent().layout(true);
		selectFirst();
	}

	private void showDetails() {
		IStructuredSelection selection = viewer.getStructuredSelection();
		ConversationSearch.Hit hit = selection.isEmpty() ? null : hits.get(selection.getFirstElement());
		if (hit == null) {
			details.setText("");
			return;
		}
		String snippet = hit.snippet() == null ? "" : hit.snippet();
		String text = snippet + (snippet.isEmpty() ? "" : "\n") + "Session " + hit.id() + "  —  " + hit.file();
		details.setText(text);
		if (hit.matchEnd() > hit.matchStart() && hit.matchEnd() <= snippet.length() && hit.matchStart() >= 0) {
			details.setStyleRange(new StyleRange(hit.matchStart(), hit.matchEnd() - hit.matchStart(), null, null, SWT.BOLD));
		}
	}

	ConversationSearch.Hit selectedHit() {
		IStructuredSelection selection = viewer.getStructuredSelection();
		return selection.isEmpty() ? null : hits.get(selection.getFirstElement());
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, COPY_ID, "Copy Session Id", false);
		createButton(parent, OPEN_FILE, "Open File", false);
		if (bothTargets) {
			createButton(parent, TERMINAL, "Resume in a Terminal", true);
			createButton(parent, OK, "Resume in a Chat", false);
		} else {
			createButton(parent, OK, IDialogConstants.OK_LABEL, true);
		}
		createButton(parent, CANCEL, IDialogConstants.CANCEL_LABEL, false);
	}

	@Override
	protected void buttonPressed(int buttonId) {
		IStructuredSelection selection = viewer.getStructuredSelection();
		Conversations.Conversation selected = selection.isEmpty() ? null : (Conversations.Conversation) selection.getFirstElement();
		if (buttonId == TERMINAL) {
			inTerminal = true;
			okPressed();
		} else if (buttonId == COPY_ID && selected != null) {
			Clipboard clipboard = new Clipboard(getShell().getDisplay());
			try {
				clipboard.setContents(new Object[] { selected.id() }, new Transfer[] { TextTransfer.getInstance() });
			} finally {
				clipboard.dispose();
			}
		} else if (buttonId == OPEN_FILE && selected != null) {
			ConversationSearch.Hit hit = hits.get(selected);
			java.nio.file.Path file = hit != null ? hit.file() : file(selected);
			if (file != null) {
				fileOpener.open(file);
			}
		} else {
			super.buttonPressed(buttonId);
		}
	}

	private java.nio.file.Path file(Conversations.Conversation conversation) {
		java.nio.file.Path folder = Conversations.folder(directory);
		return folder == null ? null : folder.resolve(conversation.id() + ".jsonl");
	}

	@Override
	protected Control createContents(Composite parent) {
		Control contentsControl = super.createContents(parent);
		updateOk();
		filter.setFocus();
		return contentsControl;
	}

	@Override
	public boolean close() {
		cancel();
		return super.close();
	}

	private void selectFirst() {
		if (viewer.getTable().getItemCount() > 0) {
			viewer.getTable().setSelection(0);
		}
		updateOk();
		showDetails();
	}

	private void updateOk() {
		boolean any = viewer.getTable().getSelectionCount() > 0;
		for (int id : new int[] { OK, TERMINAL, COPY_ID, OPEN_FILE }) {
			if (getButton(id) != null) {
				getButton(id).setEnabled(any);
			}
		}
	}

	@Override
	protected void okPressed() {
		IStructuredSelection selection = viewer.getStructuredSelection();
		if (selection.isEmpty()) {
			return;
		}
		chosen = (Conversations.Conversation) selection.getFirstElement();
		super.okPressed();
	}

	void setSearchContents(boolean selected) {
		contents.setSelection(selected);
		optionsChanged();
	}

	void select(Combo which, int index) {
		which.select(index);
		optionsChanged();
	}

	Combo scopeCombo() {
		return scope;
	}

	Combo periodCombo() {
		return period;
	}

	Combo authorCombo() {
		return author;
	}

	Combo modeCombo() {
		return mode;
	}

	Button modifiedOnlyButton() {
		return modifiedOnly;
	}

	Button fuzzyButton() {
		return fuzzy;
	}

	Button subagentsButton() {
		return subagents;
	}

	Text branchText() {
		return branch;
	}

	Text filterText() {
		return filter;
	}
}
