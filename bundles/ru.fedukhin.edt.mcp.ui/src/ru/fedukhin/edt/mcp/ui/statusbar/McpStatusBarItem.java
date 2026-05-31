package ru.fedukhin.edt.mcp.ui.statusbar;

import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;
import ru.fedukhin.edt.mcp.core.state.IServerStateBus;
import ru.fedukhin.edt.mcp.core.state.IServerStateListener;
import ru.fedukhin.edt.mcp.core.state.ServerState;
import ru.fedukhin.edt.mcp.ui.McpUiPlugin;

/**
 * @deprecated v1.15.4 — этот legacy-вариант никогда не появлялся в трим-bar'е
 *     EDT 2026.1, потому что compatibility layer Eclipse 4.x не маппит
 *     {@code toolbar:org.eclipse.ui.trim.status} ни на одну реальную {@code MTrimBar}.
 *     Замена — {@link McpStatusBarControl}, привязанный через
 *     {@link McpStatusBarAddon} к bottom-trim'у каждого {@code MTrimmedWindow}.
 *     Класс оставлен для бинарной совместимости и будет удалён в следующем
 *     major-релизе.
 */
@Deprecated(since = "1.15.4", forRemoval = true)
public class McpStatusBarItem extends WorkbenchWindowControlContribution {

    private Label label;
    private final IServerStateListener listener = this::onStateChange;
    private ServiceSupplier<IServerStateBus> supplier;

    public McpStatusBarItem() {
        super("ru.fedukhin.edt.mcp.ui.statusbar.item");
    }

    @Override
    protected Control createControl(Composite parent) {
        label = new Label(parent, SWT.BORDER);
        label.setText("MCP: ?");
        label.addListener(SWT.MouseDown, e -> openPrefs());

        try {
            supplier = ServiceAccess.supplier(IServerStateBus.class, McpUiPlugin.getPlugin());
            IServerStateBus bus = supplier.get();
            bus.subscribe(listener);
            onStateChange(bus.current());
        } catch (RuntimeException e) {
            label.setText("MCP: init err");
            label.setToolTipText(e.getMessage());
        }
        return label;
    }

    private void onStateChange(ServerState s) {
        Display.getDefault().asyncExec(() -> {
            if (label == null || label.isDisposed()) return;
            switch (s.phase()) {
                case STOPPED:  label.setText("MCP: stopped");          label.setForeground(grey());  break;
                case STARTING: label.setText("MCP: starting");         label.setForeground(grey());  break;
                case RUNNING:  label.setText("MCP :" + s.port() + " ●"); label.setForeground(green()); break;
                case ERROR:    label.setText("MCP: error"); label.setToolTipText(s.errorMessage()); label.setForeground(red()); break;
            }
        });
    }

    private Color grey()  { return Display.getDefault().getSystemColor(SWT.COLOR_GRAY); }
    private Color green() { return Display.getDefault().getSystemColor(SWT.COLOR_DARK_GREEN); }
    private Color red()   { return Display.getDefault().getSystemColor(SWT.COLOR_DARK_RED); }

    private void openPrefs() {
        PreferencesUtil.createPreferenceDialogOn(
            PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
            "ru.fedukhin.edt.mcp.ui.preferences.McpPreferencePage", null, null).open();
    }

    @Override public void dispose() {
        if (supplier != null) {
            try { supplier.get().unsubscribe(listener); } catch (Exception ignore) {}
            supplier.close();
        }
        super.dispose();
    }
}
