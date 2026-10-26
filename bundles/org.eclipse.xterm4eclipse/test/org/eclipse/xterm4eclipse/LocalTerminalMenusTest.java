package org.eclipse.xterm4eclipse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.EvaluationResult;
import org.eclipse.core.expressions.Expression;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.MApplicationFactory;
import org.eclipse.e4.ui.model.application.ui.MCoreExpression;
import org.eclipse.e4.ui.model.application.ui.MUiFactory;
import org.eclipse.e4.ui.model.application.ui.menu.MMenu;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuContribution;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuFactory;
import org.junit.jupiter.api.Test;

/** The "Show in Local Terminal" menus follow the activity that hides their commands. */
class LocalTerminalMenusTest {

	private static MMenu menu(MApplication application, String id) {
		MMenuContribution contribution = MMenuFactory.INSTANCE.createMenuContribution();
		MMenu menu = MMenuFactory.INSTANCE.createMenu();
		menu.setElementId(id);
		contribution.getChildren().add(menu);
		application.getMenuContributions().add(contribution);
		return menu;
	}

	private static EvaluationResult visible(MMenu menu, IEvaluationContext context) throws Exception {
		return ((Expression) ((MCoreExpression) menu.getVisibleWhen()).getCoreExpression()).evaluate(context);
	}

	@Test
	void menusOfTheLocalTerminalAreShownOnlyWithTheirActivity() throws Exception {
		MApplication application = MApplicationFactory.INSTANCE.createApplication();
		MMenu projectExplorer = menu(application, "org.eclipse.terminal.view.ui.ProjectExplorer.menu.showIn");
		MMenu packageExplorer = menu(application, "org.eclipse.terminal.view.ui.PackageExplorer.menu.showIn");
		MMenu other = menu(application, "org.eclipse.xterm4eclipse.showIn");
		// A menu that was shown on a condition of its own keeps it.
		MCoreExpression own = MUiFactory.INSTANCE.createCoreExpression();
		own.setCoreExpression(new Expression() {
			@Override
			public EvaluationResult evaluate(IEvaluationContext context) {
				return EvaluationResult.valueOf(context.getVariable("selection") != null);
			}
		});
		packageExplorer.setVisibleWhen(own);
		boolean[] enabled = {false};

		assertEquals(2, LocalTerminalMenus.hide(application, () -> enabled[0]));
		assertNull(other.getVisibleWhen(), "not a menu of the Local Terminal");
		EvaluationContext selected = new EvaluationContext(null, new Object());
		selected.addVariable("selection", "a resource");
		EvaluationContext none = new EvaluationContext(null, new Object());
		assertEquals(EvaluationResult.FALSE, visible(projectExplorer, selected), "hidden with the activity");
		assertEquals(EvaluationResult.FALSE, visible(packageExplorer, selected));

		enabled[0] = true;
		assertEquals(EvaluationResult.TRUE, visible(projectExplorer, none), "shown again with the capability");
		assertEquals(EvaluationResult.TRUE, visible(packageExplorer, selected));
		assertEquals(EvaluationResult.FALSE, visible(packageExplorer, none), "and its own condition");
	}
}
