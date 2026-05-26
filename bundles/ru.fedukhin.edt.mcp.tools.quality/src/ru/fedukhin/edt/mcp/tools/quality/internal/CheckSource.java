package ru.fedukhin.edt.mcp.tools.quality.internal;

/**
 * Классифицирует проверку по OSGi-бандлу, который её поставляет.
 *
 * <p>Метка источника используется в поле {@code CheckEntry.source} и позволяет
 * фильтровать проверки по поставщику через MCP-инструмент {@code check_catalog}.
 */
public final class CheckSource {

    public static final String V8CODESTYLE = "v8codestyle";
    public static final String DT_CHECK    = "dt.check";
    public static final String EDT         = "edt";
    public static final String OTHER       = "other";

    private CheckSource() {
    }

    /**
     * Возвращает метку источника для символьного имени бандла.
     *
     * @param bundleSymbolicName символьное имя OSGi-бандла или {@code null}
     * @return одна из четырёх меток: "v8codestyle", "dt.check", "edt", "other"
     */
    public static String fromBundle(String bundleSymbolicName) {
        if (bundleSymbolicName == null || bundleSymbolicName.isBlank()) {
            return OTHER;
        }
        if (bundleSymbolicName.startsWith("com.e1c.v8codestyle")) {
            return V8CODESTYLE;
        }
        if (bundleSymbolicName.startsWith("com.e1c.dt.check")) {
            return DT_CHECK;
        }
        // EDT built-in checks ship under several prefixes (live smoke 2026-05-15):
        //   com._1c.g5.v8.dt.{bsl,md,form,bp.scheme,…}
        //   com.e1c.g5.v8.dt.{bsl,md,form,…}[.check[.extension]]
        //   com.e1c.g5.dt.core.legacy
        if (bundleSymbolicName.startsWith("com._1c.g5.v8.dt.") ||
            bundleSymbolicName.startsWith("com.e1c.g5.v8.dt.") ||
            bundleSymbolicName.startsWith("com.e1c.g5.dt.")) {
            return EDT;
        }
        return OTHER;
    }
}
