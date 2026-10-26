package org.eclipse.xterm4eclipse;

import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import org.eclipse.core.expressions.EvaluationResult;
import org.eclipse.core.expressions.Expression;
import org.eclipse.core.expressions.ExpressionInfo;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.MCoreExpression;
import org.eclipse.e4.ui.model.application.ui.MExpression;
import org.eclipse.e4.ui.model.application.ui.MUiFactory;
import org.eclipse.e4.ui.model.application.ui.menu.MMenu;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuContribution;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuElement;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.activities.IActivityManager;

/**
 * Hides the "Show in Local Terminal" menus of the Terminal view of Eclipse with their commands, which
 * the activity {@value #ACTIVITY} hides: an activity hides the commands of a menu, not the menu
 * itself, which stayed empty in the context menu of the explorers. Enabling the capability in
 * Window > Preferences > General > Capabilities shows them again.
 */
public class LocalTerminalMenus implements IStartup {

	static final String ACTIVITY = "org.eclipse.xterm4eclipse.localTerminalMenus"; //$NON-NLS-1$
	/** The menus of the Project, Package and Bndtools explorers. */
	static final Pattern MENU = Pattern.compile("org\\.eclipse\\.terminal\\.view\\.ui\\.\\w+\\.menu\\.showIn"); //$NON-NLS-1$

	@Override
	public void earlyStartup() {
		IWorkbench workbench = PlatformUI.getWorkbench();
		workbench.getDisplay().asyncExec(() -> {
			try {
				MApplication application = workbench.getService(MApplication.class);
				IActivityManager activities = workbench.getActivitySupport().getActivityManager();
				if (application != null) {
					hide(application, () -> activities.getActivity(ACTIVITY).isEnabled());
				}
			} catch (RuntimeException | LinkageError e) {
				// No model of the workbench: the menus stay as they are.
				XtermPlugin.log("Could not hide the menus of the Local Terminal", e); //$NON-NLS-1$
			}
		});
	}

	/**
	 * Shows the menus only when the activity is enabled, and when they would have been shown.
	 *
	 * @param enabled
	 *            tells if the activity is enabled
	 * @return how many menus are hidden this way
	 */
	static int hide(MApplication application, BooleanSupplier enabled) {
		int hidden = 0;
		for (MMenuContribution contribution : application.getMenuContributions()) {
			for (MMenuElement element : contribution.getChildren()) {
				if (element instanceof MMenu menu && menu.getElementId() != null && MENU.matcher(menu.getElementId()).matches()) {
					menu.setVisibleWhen(whenEnabled(menu.getVisibleWhen(), enabled));
					hidden++;
				}
			}
		}
		return hidden;
	}

	private static MCoreExpression whenEnabled(MExpression before, BooleanSupplier enabled) {
		Expression previous = before instanceof MCoreExpression core && core.getCoreExpression() instanceof Expression expression
				? expression
				: null;
		MCoreExpression visible = MUiFactory.INSTANCE.createCoreExpression();
		visible.setCoreExpressionId(ACTIVITY);
		visible.setCoreExpression(new Expression() {
			@Override
			public EvaluationResult evaluate(IEvaluationContext context) throws CoreException {
				if (!enabled.getAsBoolean()) {
					return EvaluationResult.FALSE;
				}
				return previous == null ? EvaluationResult.TRUE : previous.evaluate(context);
			}

			@Override
			public void collectExpressionInfo(ExpressionInfo info) {
				if (previous != null) {
					previous.collectExpressionInfo(info);
				}
			}
		});
		return visible;
	}
}
