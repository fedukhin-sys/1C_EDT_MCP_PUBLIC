package ru.fedukhin.edt.mcp.ui.statusbar;

import com._1c.g5.wiring.ServiceAccess;
import com._1c.g5.wiring.ServiceSupplier;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import ru.fedukhin.edt.mcp.core.state.IServerStateBus;
import ru.fedukhin.edt.mcp.core.state.IServerStateListener;
import ru.fedukhin.edt.mcp.core.state.ServerState;
import ru.fedukhin.edt.mcp.ui.McpUiPlugin;

/**
 * e4 status-bar control: renders the MCP server state (stopped / starting /
 * running:port / error) as a coloured label in the workbench trim. Click
 * opens Window → Preferences → EDT MCP.
 *
 * <p>Wired by {@link McpStatusBarAddon} as an {@code MToolControl}, instantiated
 * by the e4 DI container with the parent {@link Composite} injected via
 * {@code @PostConstruct}.
 */
public class McpStatusBarControl {

    private Label label;
    private ServiceSupplier<IServerStateBus> supplier;
    private IServerStateListener listener;

    @PostConstruct
    public void create(Composite parent) {
        // Horizontal FillLayout: a single label takes the natural height of the
        // status-trim row without stretching vertically. GridLayout (with vertical
        // FILL) бы заставил Label занять всю свободную высоту — на узких трим-bar'ах
        // это давало многострочную картинку (текст уходил в перенос).
        FillLayout layout = new FillLayout(SWT.HORIZONTAL);
        layout.marginHeight = 0;
        layout.marginWidth  = 2;
        parent.setLayout(layout);

        label = new Label(parent, SWT.BORDER | SWT.CENTER);
        label.setText("MCP: ?");
        label.addListener(SWT.MouseDown, e -> openPrefs());

        try {
            supplier = ServiceAccess.supplier(IServerStateBus.class, McpUiPlugin.getPlugin());
            IServerStateBus bus = supplier.get();
            listener = this::onStateChange;
            bus.subscribe(listener);
            onStateChange(bus.current());
        } catch (RuntimeException e) {
            label.setText("MCP: init err");
            label.setToolTipText(e.getMessage());
        }
    }

    @PreDestroy
    public void dispose() {
        if (supplier != null) {
            try {
                if (listener != null) {
                    supplier.get().unsubscribe(listener);
                }
            } catch (RuntimeException ignored) {
                /* shutdown — нечего разруливать */
            }
            supplier.close();
            supplier = null;
        }
    }

    private void onStateChange(ServerState s) {
        Display.getDefault().asyncExec(() -> {
            if (label == null || label.isDisposed()) return;
            switch (s.phase()) {
                case STOPPED:
                    label.setText("MCP: stopped");
                    label.setForeground(grey());
                    label.setToolTipText("MCP server stopped — click to open Preferences");
                    break;
                case STARTING:
                    label.setText("MCP: starting");
                    label.setForeground(grey());
                    label.setToolTipText("MCP server is starting…");
                    break;
                case RUNNING:
                    label.setText("MCP :" + s.port() + " ●");
                    label.setForeground(green());
                    label.setToolTipText("MCP server running on port " + s.port());
                    break;
                case ERROR:
                    label.setText("MCP: error");
                    label.setForeground(red());
                    label.setToolTipText(s.errorMessage());
                    break;
            }
            // Pack to natural width so the label takes only the space needed for
            // current text (предотвращает «протягивание» по ширине трим-bar'а).
            label.pack();
            label.getParent().layout();
        });
    }

    private static Color grey()  { return Display.getDefault().getSystemColor(SWT.COLOR_GRAY); }
    private static Color green() { return Display.getDefault().getSystemColor(SWT.COLOR_DARK_GREEN); }
    private static Color red()   { return Display.getDefault().getSystemColor(SWT.COLOR_DARK_RED); }

    private static void openPrefs() {
        PreferencesUtil.createPreferenceDialogOn(
                PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                "ru.fedukhin.edt.mcp.ui.preferences.McpPreferencePage",
                null, null).open();
    }
}
