package ru.fedukhin.edt.mcp.tools.testrun.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Состав тестов для {@code run_tests}: модуль или все тестовые модули проекта, по исходникам.
 *
 * <p>Серверный раннер умеет выполнять готовый список «Модуль.Метод», а перечислять тесты в
 * рантайме ему нечем (текста модулей в 1С нет). Поэтому список собирается здесь, той же
 * конвенцией, что у {@code create_test_module}/{@code add_test_method}:
 * <ul>
 *   <li>тестовый модуль — общий модуль с процедурой {@code ИсполняемыеСценарии}/{@code ExecutableScenarios};</li>
 *   <li>тесты — зарегистрированные в ней {@code ЮнитТесты.ДобавитьТест("Тест_…")} /
 *       {@code AddTest(...)}; если модуль ничего не регистрирует — экспортные процедуры без
 *       параметров с префиксом {@code Тест_}/{@code Test_} (регистр не важен).</li>
 * </ul>
 * Модули «…Клиент» выполняются только на клиенте и по одному ({@code run_test_method}, протокол
 * клиентских тестов), модули раннера — не тесты.
 */
public final class TestCatalog {

    /**
     * @param tests                тесты в порядке модулей и объявления, «Модуль.Метод»
     * @param skippedClientModules клиентские тестовые модули, пропущенные при прогоне всех
     */
    public record Selection(List<String> tests, List<String> skippedClientModules) { }

    private static final Pattern SCENARIOS = Pattern.compile(
        "(?im)^[ \\t]*(?:Процедура|Procedure)[ \\t]+(?:ИсполняемыеСценарии|ExecutableScenarios)[ \\t]*\\(");

    private static final Pattern REGISTRATION = Pattern.compile(
        "(?iu)(?:ДобавитьТест|AddTest)\\s*\\(\\s*\"([^\"]+)\"");

    private static final Pattern EXPORTED_TEST = Pattern.compile(
        "(?imU)^[ \\t]*(?:Процедура|Procedure)[ \\t]+((?:Тест_|Test_)\\w*)[ \\t]*\\([ \\t]*\\)[ \\t]*(?:Экспорт|Export)\\b");

    private static final String RUNNER_PREFIX = "EDT_MCP_TestRunner_";
    private static final String CLIENT_SUFFIX = "Клиент";

    private TestCatalog() {}

    /** Тесты модуля по его тексту (без имени модуля). */
    public static List<String> testsInModule(String text) {
        Set<String> registered = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            if (line.stripLeading().startsWith("//")) continue;
            Matcher m = REGISTRATION.matcher(line);
            while (m.find()) registered.add(m.group(1).trim());
        }
        if (!registered.isEmpty()) return new ArrayList<>(registered);
        Set<String> exported = new LinkedHashSet<>();
        Matcher m = EXPORTED_TEST.matcher(text);
        while (m.find()) exported.add(m.group(1));
        return new ArrayList<>(exported);
    }

    /**
     * @param projectDir каталог проекта (в нём {@code src/CommonModules})
     * @param moduleFqn  {@code CommonModule.X} или {@code X}; {@code null} — все тестовые модули
     * @throws ToolException набор пуст (вместо молчаливых «0 тестов»), модуля нет или он клиентский
     */
    public static Selection collect(Path projectDir, String moduleFqn) throws ToolException {
        Path modules = projectDir.resolve("src").resolve("CommonModules");
        if (moduleFqn != null) {
            String name = moduleFqn.startsWith("CommonModule.")
                ? moduleFqn.substring("CommonModule.".length()) : moduleFqn;
            Path file = modules.resolve(name).resolve("Module.bsl");
            if (!Files.isRegularFile(file)) {
                throw new ToolException("модуль '" + name + "' не найден в проекте (" + file + ")");
            }
            if (name.endsWith(CLIENT_SUFFIX)) {
                throw new ToolException("модуль '" + name + "' клиентский: его тесты выполняются на"
                    + " клиенте по одному — run_test_method");
            }
            List<String> tests = qualified(name, testsInModule(read(file)));
            if (tests.isEmpty()) {
                throw new ToolException("в модуле '" + name + "' нет тестов: ни регистраций"
                    + " ЮнитТесты.ДобавитьТест(\"…\") в ИсполняемыеСценарии, ни экспортных процедур"
                    + " без параметров с префиксом Тест_/Test_");
            }
            return new Selection(tests, List.of());
        }
        List<String> tests = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Path dir : moduleDirs(modules)) {
            String name = dir.getFileName().toString();
            Path file = dir.resolve("Module.bsl");
            if (name.startsWith(RUNNER_PREFIX) || !Files.isRegularFile(file)) continue;
            String text = read(file);
            if (!SCENARIOS.matcher(text).find()) continue;
            List<String> own = testsInModule(text);
            if (own.isEmpty()) continue;
            if (name.endsWith(CLIENT_SUFFIX)) {
                skipped.add(name);
                continue;
            }
            tests.addAll(qualified(name, own));
        }
        if (tests.isEmpty()) {
            throw new ToolException("в проекте нет серверных тестовых модулей с тестами (общий модуль"
                + " с процедурой ИсполняемыеСценарии и тестами Тест_…)"
                + (skipped.isEmpty() ? "" : "; клиентские модули — по одному через run_test_method: "
                    + String.join(", ", skipped)));
        }
        return new Selection(tests, skipped);
    }

    private static List<String> qualified(String module, List<String> methods) {
        List<String> out = new ArrayList<>();
        for (String m : methods) out.add(module + "." + m);
        return out;
    }

    private static List<Path> moduleDirs(Path modules) throws ToolException {
        if (!Files.isDirectory(modules)) return List.of();
        try (Stream<Path> dirs = Files.list(modules)) {
            return dirs.filter(Files::isDirectory)
                       .sorted((a, b) -> a.getFileName().toString().toLowerCase(Locale.ROOT)
                           .compareTo(b.getFileName().toString().toLowerCase(Locale.ROOT)))
                       .toList();
        } catch (IOException e) {
            throw new ToolException("не удалось перечислить общие модули: " + e.getMessage(), e);
        }
    }

    private static String read(Path file) throws ToolException {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return !text.isEmpty() && text.charAt(0) == (char) 0xFEFF ? text.substring(1) : text;
        } catch (IOException e) {
            throw new ToolException("не удалось прочитать " + file + ": " + e.getMessage(), e);
        }
    }
}
