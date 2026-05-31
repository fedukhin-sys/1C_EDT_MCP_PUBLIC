package ru.fedukhin.edt.mcp.ui.preferences;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import ru.fedukhin.edt.mcp.core.internal.preferences.McpPreferences;
import ru.fedukhin.edt.mcp.core.internal.security.EquinoxSecureStringStore;
import ru.fedukhin.edt.mcp.core.internal.security.SecureTokenStore;
import ru.fedukhin.edt.mcp.ui.McpUiPlugin;

public class McpPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private SecureTokenStore tokens;
    private Text tokenField;
    private boolean tokenVisible;
    private char defaultEchoChar;

    public McpPreferencePage() {
        super(GRID);
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, "ru.fedukhin.edt.mcp.core"));
        setDescription("EDT MCP server settings.");
    }

    @Override public void init(IWorkbench workbench) {
        tokens = new SecureTokenStore(new EquinoxSecureStringStore());
    }

    @Override protected void createFieldEditors() {
        addField(new IntegerFieldEditor(McpPreferences.KEY_PORT, "Port", getFieldEditorParent(), 5));
        addField(new BooleanFieldEditor(McpPreferences.KEY_AUTO_START, "Auto-start on IDE launch",
                getFieldEditorParent()));

        Composite parent = getFieldEditorParent();
        new Label(parent, SWT.NONE).setText("Bearer token:");

        // Token field — read-only password by default; Show toggles visibility.
        tokenField = new Text(parent, SWT.BORDER | SWT.READ_ONLY | SWT.PASSWORD);
        tokenField.setText(sanitize(tokens.getOrGenerate()));
        defaultEchoChar = tokenField.getEchoChar();
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.horizontalSpan = 2;
        tokenField.setLayoutData(gd);

        Composite buttons = new Composite(parent, SWT.NONE);
        GridData bgd = new GridData();
        bgd.horizontalSpan = 3;
        buttons.setLayoutData(bgd);
        buttons.setLayout(new org.eclipse.swt.layout.RowLayout());

        Button show = new Button(buttons, SWT.PUSH);
        show.setText("Show");
        show.addListener(SWT.Selection, e -> {
            tokenVisible = !tokenVisible;
            tokenField.setEchoChar(tokenVisible ? '\0' : defaultEchoChar);
            show.setText(tokenVisible ? "Hide" : "Show");
        });

        Button copy = new Button(buttons, SWT.PUSH);
        copy.setText("Copy");
        copy.addListener(SWT.Selection, e -> {
            String token = sanitize(tokenField.getText());
            Clipboard cb = new Clipboard(Display.getCurrent());
            try {
                cb.setContents(new Object[]{token},
                               new Transfer[]{TextTransfer.getInstance()});
            } finally {
                cb.dispose();
            }
        });

        Button regenerate = new Button(buttons, SWT.PUSH);
        regenerate.setText("Regenerate");
        regenerate.addListener(SWT.Selection, e -> tokenField.setText(sanitize(tokens.regenerate())));

        // ─── Server control: Start / Stop / Restart ───────────────────────
        Label serverLabel = new Label(parent, SWT.NONE);
        serverLabel.setText("Server:");
        GridData slgd = new GridData();
        slgd.horizontalSpan = 3;
        serverLabel.setLayoutData(slgd);

        Composite serverButtons = new Composite(parent, SWT.NONE);
        GridData sbgd = new GridData();
        sbgd.horizontalSpan = 3;
        serverButtons.setLayoutData(sbgd);
        serverButtons.setLayout(new org.eclipse.swt.layout.RowLayout());

        Button startBtn = new Button(serverButtons, SWT.PUSH);
        startBtn.setText("Start");
        startBtn.addListener(SWT.Selection, e ->
                runCommand("ru.fedukhin.edt.mcp.ui.commands.start"));

        Button stopBtn = new Button(serverButtons, SWT.PUSH);
        stopBtn.setText("Stop");
        stopBtn.addListener(SWT.Selection, e ->
                runCommand("ru.fedukhin.edt.mcp.ui.commands.stop"));

        Button restartBtn = new Button(serverButtons, SWT.PUSH);
        restartBtn.setText("Restart");
        restartBtn.addListener(SWT.Selection, e ->
                runCommand("ru.fedukhin.edt.mcp.ui.commands.restart"));
    }

    /**
     * Executes the named Eclipse {@code Command} through the workbench
     * {@code ICommandService}/{@code IHandlerService}. Same code path as the
     * {@code Window → EDT MCP} menu entries — keeps a single source of truth for
     * the server lifecycle. Errors land in the Eclipse log; no UI dialog so the
     * preference page does not steal focus.
     */
    private static void runCommand(String commandId) {
        try {
            IWorkbench workbench = PlatformUI.getWorkbench();
            ICommandService commandService = workbench.getService(ICommandService.class);
            IHandlerService handlerService = workbench.getService(IHandlerService.class);
            if (commandService == null || handlerService == null) {
                throw new IllegalStateException("workbench services unavailable");
            }
            Command command = commandService.getCommand(commandId);
            handlerService.executeCommand(command.getId(), null);
        } catch (ExecutionException | RuntimeException e) {
            Platform.getLog(McpUiPlugin.getPlugin().getBundle()).log(new Status(
                    IStatus.ERROR, McpUiPlugin.ID,
                    "Failed to execute " + commandId + ": " + e.getMessage(), e));
        } catch (Exception e) {
            Platform.getLog(McpUiPlugin.getPlugin().getBundle()).log(new Status(
                    IStatus.ERROR, McpUiPlugin.ID,
                    "Failed to execute " + commandId + ": " + e.getMessage(), e));
        }
    }

    /**
     * SWT Text with SWT.PASSWORD style on Windows may return strings with trailing
     * control characters (NUL, CR) that String.strip() does not remove. Strip both
     * whitespace and Unicode control characters from both ends defensively.
     */
    static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("(?U)^[\\p{Cntrl}\\s]+|[\\p{Cntrl}\\s]+$", "");
    }
}
