package org.eclipse.xterm4eclipse.claude;

import java.io.File;
import java.text.DateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
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

	private static final int LINES = 15;
	private static final int WIDTH_IN_CHARACTERS = 90;

	private final File directory;
	private final List<Conversations.Conversation> conversations;
	private final DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
	private Text filter;
	private TableViewer viewer;
	private Conversations.Conversation chosen;

	ConversationDialog(Shell shell, File directory, List<Conversations.Conversation> conversations) {
		super(shell);
		this.directory = directory;
		this.conversations = conversations.stream()
				.sorted(Comparator.comparingLong(Conversations.Conversation::modified).reversed()).toList();
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

	/** What the list shows of a conversation: its title, and when it was last used. */
	String label(Conversations.Conversation conversation) {
		return conversation.title() + "  \u2014  " + format.format(new Date(conversation.modified())); //$NON-NLS-1$
	}

	/** What the filter looks in: the title and the date, in lower case, but not what separates them. */
	private String searched(Conversations.Conversation conversation) {
		return (conversation.title() + ' ' + format.format(new Date(conversation.modified()))).toLowerCase(Locale.ROOT);
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText("Resume a Conversation"); //$NON-NLS-1$
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		Label message = new Label(area, SWT.WRAP);
		// An ampersand of the path is not the mnemonic of a label.
		message.setText("Conversations of Claude Code in " + directory.getPath().replace("&", "&&") + ':'); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		GridDataFactory.fillDefaults().grab(true, false).applyTo(message);

		filter = new Text(area, SWT.SINGLE | SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
		filter.setMessage("Filter"); //$NON-NLS-1$
		GridDataFactory.fillDefaults().grab(true, false).applyTo(filter);

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
				String text = filter.getText().strip().toLowerCase(Locale.ROOT);
				return text.isEmpty() || searched((Conversations.Conversation) element).contains(text);
			}
		});
		viewer.setInput(conversations);
		viewer.addDoubleClickListener(event -> okPressed());
		viewer.addSelectionChangedListener(event -> updateOk());
		GridDataFactory.fillDefaults().grab(true, true).hint(convertWidthInCharsToPixels(WIDTH_IN_CHARACTERS),
				convertHeightInCharsToPixels(LINES)).applyTo(viewer.getControl());

		filter.addModifyListener(event -> {
			viewer.refresh();
			selectFirst();
		});
		// The arrow down goes from the filter to the list, as in the dialogs of the workbench.
		filter.addListener(SWT.KeyDown, event -> {
			if (event.keyCode == SWT.ARROW_DOWN) {
				event.doit = false; // the key is not for the text field too
				viewer.getControl().setFocus();
			}
		});
		// The latest, which Enter resumes.
		selectFirst();
		return area;
	}

	@Override
	protected Control createContents(Composite parent) {
		Control contents = super.createContents(parent);
		updateOk();
		filter.setFocus();
		return contents;
	}

	private void selectFirst() {
		if (viewer.getTable().getItemCount() > 0) {
			viewer.getTable().setSelection(0);
		}
		updateOk();
	}

	private void updateOk() {
		if (getButton(OK) != null) {
			getButton(OK).setEnabled(viewer.getTable().getSelectionCount() > 0);
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
}
