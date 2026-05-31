package ru.fedukhin.edt.mcp.ui.statusbar;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.descriptor.basic.MPartDescriptor;
import org.eclipse.e4.ui.model.application.ui.MUIElement;
import org.eclipse.e4.ui.model.application.ui.basic.MTrimBar;
import org.eclipse.e4.ui.model.application.ui.basic.MTrimmedWindow;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuFactory;
import org.eclipse.e4.ui.model.application.ui.menu.MToolControl;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import ru.fedukhin.edt.mcp.ui.McpUiPlugin;

/**
 * e4 addon: injects the MCP status-bar item into the bottom trim of every
 * trimmed window when the application model is built. Replaces the legacy
 * {@code org.eclipse.ui.menus} contribution at
 * {@code toolbar:org.eclipse.ui.trim.status}, which is not picked up by
 * Eclipse 4.x compatibility layer in 1C:EDT 2026.1.
 *
 * <p>Registered via {@code fragment.e4xmi} → {@code application:Addon}.
 */
public class McpStatusBarAddon {

    private static final String ITEM_ID =
            "ru.fedukhin.edt.mcp.ui.statusbar.item";
    private static final String CONTROL_URI =
            "bundleclass://ru.fedukhin.edt.mcp.ui/"
          + "ru.fedukhin.edt.mcp.ui.statusbar.McpStatusBarControl";

    @PostConstruct
    public void init(MApplication application, EModelService modelService) {
        try {
            for (MWindow window : application.getChildren()) {
                if (window instanceof MTrimmedWindow trimmedWindow) {
                    addToBottomTrim(trimmedWindow, modelService);
                }
            }
        } catch (RuntimeException e) {
            McpUiPlugin plugin = McpUiPlugin.getPlugin();
            if (plugin != null) {
                Platform.getLog(plugin.getBundle()).log(new Status(
                        IStatus.WARNING, McpUiPlugin.ID,
                        "Failed to inject MCP status-bar item: " + e.getMessage(), e));
            }
        }
    }

    private static void addToBottomTrim(MTrimmedWindow window, EModelService modelService) {
        for (MTrimBar trim : window.getTrimBars()) {
            for (MUIElement child : trim.getChildren()) {
                if (ITEM_ID.equals(child.getElementId())) {
                    return;
                }
            }
        }
        MTrimBar bottom = modelService.getTrim(window,
                org.eclipse.e4.ui.model.application.ui.SideValue.BOTTOM);
        MToolControl item = MMenuFactory.INSTANCE.createToolControl();
        item.setElementId(ITEM_ID);
        item.setContributionURI(CONTROL_URI);
        item.setToBeRendered(true);
        item.setVisible(true);
        bottom.getChildren().add(item);
    }
}
