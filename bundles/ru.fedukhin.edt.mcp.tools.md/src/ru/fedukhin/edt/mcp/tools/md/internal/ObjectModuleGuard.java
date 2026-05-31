package ru.fedukhin.edt.mcp.tools.md.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Detects the {@code #Если}…{@code #КонецЕсли} preprocessor guard that wraps an
 * object-side BSL module, so {@code add_extension_method_override} can give an
 * override method the same compilation visibility as the base method.
 *
 * <p>BUG-17: a {@code &Перед}/{@code &После}/{@code &ИзменениеИКонтроль} method
 * placed in an adopted object's module without the base module's preprocessor
 * guard fails EDT validation with «Метод расширения имеет большую видимость».
 * Object/manager/record-set modules in SSL-based configurations are wrapped in
 * {@code #Если Сервер Или … Тогда} (sometimes nested, e.g. {@code Номенклатура}).
 */
public final class ObjectModuleGuard {

    private ObjectModuleGuard() { }

    /** Object-side module file names that carry a {@code #Если} server guard. */
    private static final Set<String> GUARDED_FILES = Set.of(
            "ObjectModule.bsl", "ManagerModule.bsl",
            "RecordSetModule.bsl", "ValueManagerModule.bsl");

    /** True when {@code modulePath} is an object-side module that may need a guard. */
    public static boolean isGuardedModule(String modulePath) {
        int slash = modulePath.lastIndexOf('/');
        String fileName = slash >= 0 ? modulePath.substring(slash + 1) : modulePath;
        return GUARDED_FILES.contains(fileName);
    }

    /**
     * Detects the leading {@code #Если…Тогда} preamble and trailing
     * {@code #КонецЕсли} postamble that wrap the whole base module.
     *
     * @return {@code {preamble, postamble}} (newline-joined), or {@code null} when
     *         the module is not wrapped or the wrapper is not a clean block
     */
    public static String[] detectGuard(String baseModuleText) {
        if (baseModuleText == null || baseModuleText.isEmpty()) {
            return null;
        }
        String[] lines = baseModuleText.split("\r\n|\r|\n", -1);

        List<String> preamble = new ArrayList<>();
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("//")) {
                continue;
            }
            if (isIf(t)) {
                preamble.add(t);
            } else {
                break;   // first real (non-preprocessor) line
            }
        }
        if (preamble.isEmpty()) {
            return null;
        }

        List<String> postamble = new ArrayList<>();
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].strip();
            if (t.isEmpty() || t.startsWith("//")) {
                continue;
            }
            if (isEndIf(t)) {
                postamble.add(0, t);
            } else {
                break;
            }
        }
        if (postamble.size() != preamble.size()) {
            return null;   // unbalanced — not a clean single (possibly nested) wrapper
        }
        return new String[] { String.join("\n", preamble), String.join("\n", postamble) };
    }

    /** Wraps {@code source} between the guard's preamble and postamble. */
    public static String wrap(String source, String[] guard) {
        return guard[0] + "\n\n" + source.strip() + "\n\n" + guard[1] + "\n";
    }

    /** True when {@code source} already opens with its own preprocessor guard. */
    public static boolean alreadyGuarded(String source) {
        for (String line : source.split("\r\n|\r|\n", -1)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("//")) {
                continue;
            }
            return isIf(t);   // the first real line decides
        }
        return false;
    }

    private static boolean isIf(String t) {
        return t.regionMatches(true, 0, "#Если", 0, 5)
            || t.regionMatches(true, 0, "#If", 0, 3);
    }

    private static boolean isEndIf(String t) {
        return t.regionMatches(true, 0, "#КонецЕсли", 0, 10)
            || t.regionMatches(true, 0, "#EndIf", 0, 6);
    }
}
