package ru.fedukhin.edt.mcp.ui;

import org.eclipse.ui.IStartup;

/**
 * @deprecated v1.15.4 — статус-бар переведён на e4 model fragment
 *     ({@link ru.fedukhin.edt.mcp.ui.statusbar.McpStatusBarAddon}), который не
 *     требует ранней активации bundle. Этот {@link IStartup} больше не
 *     зарегистрирован в {@code plugin.xml} и не вызывается; класс оставлен
 *     только чтобы не сломать бинарную совместимость для downstream'а, который
 *     случайно мог сослаться на него через рефлексию.
 */
@Deprecated(since = "1.15.4", forRemoval = true)
public final class McpUiEarlyStartup implements IStartup {
    @Override public void earlyStartup() { /* dead code — см. javadoc */ }
}
