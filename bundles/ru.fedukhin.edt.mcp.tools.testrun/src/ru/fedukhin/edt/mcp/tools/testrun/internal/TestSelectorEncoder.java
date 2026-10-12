package ru.fedukhin.edt.mcp.tools.testrun.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes a test selector to a {@code /C} startup parameter format consumed
 * by the BSL client runner.
 *
 * <p>Format: {@code EDT_MCP_TESTS=mode=<MTD|MOD|ALL>;rf=<base64-path>[;mod=…][;mtd=…][;run=…][;lst=<base64-path>]}
 *
 * <p>{@code lst} — файл со списком тестов для MOD/ALL (собирает {@link TestCatalog}): в параметр
 * {@code /C} список всех тестов проекта может не поместиться.
 * The {@code resultFile} path is Base64-encoded to avoid breaking the
 * semicolon/equals delimiters when paths contain spaces, Cyrillic, or punctuation.
 *
 * <p>{@code run} — имя серверного модуля раннера, которому адресован прогон. В базе может
 * стоять несколько раннеров (по одному на каждое расширение с {@code install_test_runner}),
 * обработчик старта вызывается у всех; раннер, увидев чужое имя, выходит. Раннеры старого
 * образца ключа не знают и пропускают его — для них поведение прежнее. Значение — идентификатор
 * 1С (буквы, цифры, подчёркивание), разделители формата в нём встретиться не могут.
 */
public final class TestSelectorEncoder {

    private static final String PREFIX = "EDT_MCP_TESTS=";

    private TestSelectorEncoder() {}

    public static String encodeAll(String resultFilePath) {
        return encodeAll(resultFilePath, null);
    }

    public static String encodeAll(String resultFilePath, String runner) {
        return PREFIX + "mode=ALL;rf=" + base64(resultFilePath) + runnerKey(runner);
    }

    public static String encodeModule(String moduleFqn, String resultFilePath) {
        return encodeModule(moduleFqn, resultFilePath, null);
    }

    public static String encodeModule(String moduleFqn, String resultFilePath, String runner) {
        return PREFIX + "mode=MOD;rf=" + base64(resultFilePath) + ";mod=" + moduleFqn + runnerKey(runner);
    }

    public static String encodeMethod(String moduleFqn, String methodName, String resultFilePath) {
        return encodeMethod(moduleFqn, methodName, resultFilePath, null);
    }

    public static String encodeMethod(String moduleFqn, String methodName, String resultFilePath,
                                      String runner) {
        return PREFIX + "mode=MTD;rf=" + base64(resultFilePath)
            + ";mod=" + moduleFqn + ";mtd=" + methodName + runnerKey(runner);
    }

    /**
     * @param listFile файл со списком тестов «Модуль.Метод» по строке (UTF-8) — клиент раннера
     *                 читает его и отдаёт серверу; {@code null} — без списка
     */
    public static String encodeModule(String moduleFqn, String resultFilePath, String runner,
                                      String listFile) {
        return encodeModule(moduleFqn, resultFilePath, runner) + listKey(listFile);
    }

    /** @param listFile см. {@link #encodeModule(String, String, String, String)} */
    public static String encodeAll(String resultFilePath, String runner, String listFile) {
        return encodeAll(resultFilePath, runner) + listKey(listFile);
    }

    private static String listKey(String listFile) {
        return listFile == null || listFile.isBlank() ? "" : ";lst=" + base64(listFile);
    }

    private static String runnerKey(String runner) {
        return runner == null || runner.isBlank() ? "" : ";run=" + runner;
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
