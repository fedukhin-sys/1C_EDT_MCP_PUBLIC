package ru.fedukhin.edt.mcp.tools.infobase.internal;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Разбор аргументов инструментов. Схема объявляет типы, но значения доходят до кода как есть —
 * поэтому типы и границы проверяются здесь, с понятным текстом отказа.
 */
public final class ToolArgs {

    private ToolArgs() {}

    public static String required(Map<String, Object> args, String key) throws ToolException {
        String value = optional(args, key);
        if (value == null) throw new ToolException("missing or empty '" + key + "' argument");
        return value;
    }

    /** @return строка либо {@code null}, если аргумента нет или он пустой */
    public static String optional(Map<String, Object> args, String key) throws ToolException {
        Object value = args == null ? null : args.get(key);
        if (value == null) return null;
        if (!(value instanceof String s)) throw new ToolException("'" + key + "' must be a string");
        return s.isBlank() ? null : s;
    }

    public static boolean flag(Map<String, Object> args, String key, boolean defaultValue) throws ToolException {
        Object value = args == null ? null : args.get(key);
        if (value == null) return defaultValue;
        if (!(value instanceof Boolean b)) throw new ToolException("'" + key + "' must be a boolean");
        return b;
    }

    public static int integer(Map<String, Object> args, String key, int defaultValue, int min, int max)
            throws ToolException {
        Object value = args == null ? null : args.get(key);
        if (value == null) return defaultValue;
        if (!(value instanceof Number n)) throw new ToolException("'" + key + "' must be an integer");
        int i = n.intValue();
        if (i < min || i > max) {
            throw new ToolException("'" + key + "' must be in [" + min + ", " + max + "], got " + i);
        }
        return i;
    }

    /**
     * Абсолютный путь к существующему файлу с ожидаемым расширением. Сервер работает внутри
     * процесса EDT, и относительный путь отсчитывался бы от его рабочего каталога, а не от
     * каталога клиента — поэтому только абсолютные пути.
     *
     * @param extension расширение без точки, регистр не важен
     */
    public static Path existingFile(String key, String value, String extension) throws ToolException {
        Path path;
        try {
            path = Paths.get(value);
        } catch (InvalidPathException e) {
            throw new ToolException("'" + key + "': некорректный путь '" + value + "'");
        }
        if (!path.isAbsolute()) {
            throw new ToolException("'" + key + "': нужен абсолютный путь, получен '" + value + "'");
        }
        if (!Files.isRegularFile(path)) {
            throw new ToolException("'" + key + "': файл не найден: " + value);
        }
        if (extension != null
                && !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + extension)) {
            throw new ToolException("'" + key + "': ожидается файл ." + extension + ", получен " + path.getFileName());
        }
        return path;
    }
}
