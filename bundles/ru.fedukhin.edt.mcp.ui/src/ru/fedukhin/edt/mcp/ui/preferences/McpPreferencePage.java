package ru.fedukhin.edt.mcp.ui.preferences;

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
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import ru.fedukhin.edt.mcp.core.internal.preferences.McpPreferences;
import ru.fedukhin.edt.mcp.core.internal.security.EquinoxSecureStringStore;
import ru.fedukhin.edt.mcp.core.internal.security.SecureTokenStore;

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
