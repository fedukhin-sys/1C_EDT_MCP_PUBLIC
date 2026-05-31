package ru.fedukhin.edt.mcp.ui;

import org.eclipse.ui.IStartup;

/**
 * Forces the UI bundle to activate on workbench startup so that the
 * {@code org.eclipse.ui.menus} contribution at
 * {@code toolbar:org.eclipse.ui.trim.status} (the status-bar item)
 * registers before the workbench window builds its trim. Without an
 * early-startup hook the bundle stays lazy until something queries the
 * Window → EDT MCP menu, by which time the status-bar trim is already
 * frozen and the contribution silently never appears.
 */
public final class McpUiEarlyStartup implements IStartup {
    @Override public void earlyStartup() { /* activation side effect only */ }
}
