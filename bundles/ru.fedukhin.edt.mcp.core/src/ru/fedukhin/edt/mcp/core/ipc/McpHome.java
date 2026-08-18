package ru.fedukhin.edt.mcp.core.ipc;

import java.nio.file.Path;

/**
 * Каталог межпроцессного состояния EDT_MCP — общий для всех инстанций 1C:EDT
 * одного пользователя.
 *
 * <p>Лежит в домашнем каталоге рядом с {@code ~/.eclipse}, а не в
 * {@code %LOCALAPPDATA%}: так не появляется платформенных веток, а соседство с
 * хранилищем Equinox честно отражает, что состояние здесь тоже per-user.
 *
 * <p>Путь переопределяется системным свойством {@value #SYS_PROP} — без этого
 * тесты писали бы в реальный домашний каталог пользователя.
 */
public final class McpHome {

    public static final String SYS_PROP = "mcp.discovery.dir";

    private McpHome() {}

    public static Path root() {
        String override = System.getProperty(SYS_PROP);
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".edt-mcp");
    }

    /** Маячки живых инстанций: по файлу на процесс. */
    public static Path instances() { return root().resolve("instances"); }

    /** Межпроцессные замки. */
    public static Path locks() { return root().resolve("locks"); }

    /** Состояние слоя обезличивания, общее для инстанций. */
    public static Path privacy() { return root().resolve("privacy"); }
}
