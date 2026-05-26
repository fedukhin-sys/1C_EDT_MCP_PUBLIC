package ru.fedukhin.edt.mcp.tools.tests.internal;

import jakarta.inject.Singleton;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.fedukhin.edt.mcp.tools.tests.internal.XUnitTemplates.Language;

@Singleton
public class BslTestMethodAppender {

    private static final Pattern RU_END_PROC = Pattern.compile(
            "(?is)Процедура\\s+ИсполняемыеСценарии\\s*\\(.*?\\)\\s*Экспорт\\s*\\r?\\n(.*?)(КонецПроцедуры)");
    private static final Pattern EN_END_PROC = Pattern.compile(
            "(?is)Procedure\\s+ExecutableScenarios\\s*\\(.*?\\)\\s*Export\\s*\\r?\\n(.*?)(EndProcedure)");

    public static final class AppendResult {
        public final String newText;
        public final boolean alreadyExisted;
        public AppendResult(String t, boolean e) { this.newText = t; this.alreadyExisted = e; }
    }

    public AppendResult append(String moduleText, String methodName, Language lang, String userBody) {
        String prefix = XUnitTemplates.prefix(lang);
        String fqn = prefix + methodName;
        // Check method already exists
        Pattern methodExists = Pattern.compile("(?im)^\\s*(Процедура|Procedure)\\s+" + Pattern.quote(fqn) + "\\s*\\(");
        if (methodExists.matcher(moduleText).find()) {
            return new AppendResult(moduleText, true);
        }

        // Add ДобавитьТест/AddTest to ExecutableScenarios body — insert right before
        // КонецПроцедуры/EndProcedure (group(2)). We splice on absolute offsets
        // instead of String.replace(existing, …): when the body is empty (fresh
        // scaffold), String.replace("", X) would insert X between every character
        // of the surrounding match and corrupt the file.
        Pattern endProc = (lang == Language.RU) ? RU_END_PROC : EN_END_PROC;
        Matcher m = endProc.matcher(moduleText);
        String afterRegister;
        if (m.find()) {
            String registerLine = (lang == Language.RU)
                ? "\tЮнитТесты.ДобавитьТест(\"" + fqn + "\");\r\n"
                : "\tUnitTests.AddTest(\"" + fqn + "\");\r\n";
            int insertAt = m.start(2);
            afterRegister = moduleText.substring(0, insertAt) + registerLine + moduleText.substring(insertAt);
        } else {
            afterRegister = moduleText;
        }
        // Append method body at the end
        String methodBody = XUnitTemplates.methodBody(methodName, lang, userBody);
        String separator = afterRegister.endsWith("\r\n") ? "\r\n" : "\r\n\r\n";
        return new AppendResult(afterRegister + separator + methodBody, false);
    }
}
