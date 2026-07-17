package ru.fedukhin.edt.mcp.tests.bsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Path;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslAstReader.MethodInfo;
import ru.fedukhin.edt.mcp.tools.bsl.internal.BslParseException;

public class BslAstReaderTest {

    private static IFile mockFile(String fullPath, String content) throws Exception {
        IFile f = mock(IFile.class);
        when(f.getFullPath()).thenReturn(new Path(fullPath));
        when(f.getName()).thenReturn(fullPath.substring(fullPath.lastIndexOf('/') + 1));
        when(f.getCharset()).thenReturn("UTF-8");
        when(f.getContents()).thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return f;
    }

    @Test
    public void listMethods_findsProcedureAndFunction() throws Exception {
        String content = "Процедура ИмяПроц() Экспорт\n  Сообщить(1);\nКонецПроцедуры\n"
                       + "\nФункция MyFunc(a, b)\n  Возврат a + b;\nКонецФункции\n";
        IFile file = mockFile("/P/src/CommonModules/Foo/Module.bsl", content);

        BslAstReader reader = new BslAstReader();
        List<MethodInfo> methods = reader.listMethods(file);

        assertEquals(2, methods.size());
        assertEquals("ИмяПроц", methods.get(0).name());
        assertEquals("procedure", methods.get(0).kind());
        assertTrue(methods.get(0).export());
        assertEquals(1, methods.get(0).lineStart());
        assertEquals("MyFunc", methods.get(1).name());
        assertEquals("function", methods.get(1).kind());
        assertFalse(methods.get(1).export());
    }

    /**
     * Буква Ё/ё лежит вне диапазона [А-Яа-я] в Unicode, поэтому методы с ней просто не
     * находились: ни list_module_methods, ни get_method их не видели.
     */
    @Test
    public void listMethods_findsNamesWithYo() throws Exception {
        String content = "Процедура ПриёмТовара() Экспорт\nКонецПроцедуры\n"
                       + "Функция УчётЗатрат(п)\n  Возврат п;\nКонецФункции\n";
        IFile file = mockFile("/P/src/M.bsl", content);

        List<MethodInfo> methods = new BslAstReader().listMethods(file);

        assertEquals(2, methods.size());
        assertEquals("ПриёмТовара", methods.get(0).name());
        assertTrue(methods.get(0).export());
        assertEquals("УчётЗатрат", methods.get(1).name());
    }

    /**
     * Флаг export определялся по {@code \)\s*(Экспорт|Export)?}: {@code \s} проглатывает перевод
     * строки, поэтому слово «Экспортировать» в начале следующей строки помечало метод экспортным.
     */
    @Test
    public void listMethods_wordStartingWithExport_onNextLine_isNotExport() throws Exception {
        String content = "Процедура Обычная()\n  ЭкспортироватьДанные();\nКонецПроцедуры\n";
        IFile file = mockFile("/P/src/M.bsl", content);

        List<MethodInfo> methods = new BslAstReader().listMethods(file);

        assertEquals(1, methods.size());
        assertFalse("метод не экспортный — «ЭкспортироватьДанные()» это вызов в теле",
            methods.get(0).export());
    }

    @Test
    public void listMethods_findsEnglishKeywords() throws Exception {
        String content = "Procedure Foo() Export\nEndProcedure\n"
                       + "Function Bar()\nEndFunction\n";
        IFile file = mockFile("/P/src/M.bsl", content);

        List<MethodInfo> methods = new BslAstReader().listMethods(file);
        assertEquals(2, methods.size());
        assertEquals("procedure", methods.get(0).kind());
        assertTrue(methods.get(0).export());
        assertEquals("function", methods.get(1).kind());
    }

    @Test
    public void listMethods_emptyFileReturnsEmpty() throws Exception {
        IFile file = mockFile("/P/src/M.bsl", "");
        assertTrue(new BslAstReader().listMethods(file).isEmpty());
    }

    @Test
    public void listMethods_unterminatedProcedureThrows() throws Exception {
        IFile file = mockFile("/P/src/M.bsl", "Процедура Hangs()\n  Сообщить(1);\n");
        try {
            new BslAstReader().listMethods(file);
            fail("expected BslParseException");
        } catch (BslParseException e) {
            assertTrue(e.getMessage().contains("unterminated"));
        }
    }

    @Test
    public void findMethod_returnsMatchedByName() throws Exception {
        String content = "Процедура Foo()\nКонецПроцедуры\n";
        IFile file = mockFile("/P/src/M.bsl", content);

        BslAstReader reader = new BslAstReader();
        Optional<MethodInfo> found = reader.findMethod(file, "Foo");
        assertTrue(found.isPresent());
        Optional<MethodInfo> missing = reader.findMethod(file, "Bar");
        assertFalse(missing.isPresent());
    }

    @Test
    public void getModuleType_recognisesCommonModule() throws Exception {
        IFile file = mockFile("/P/src/CommonModules/Foo/Module.bsl", "");
        assertEquals("COMMON_MODULE", new BslAstReader().getModuleType(file));
    }

    @Test
    public void getModuleType_recognisesFormModule() throws Exception {
        IFile file = mockFile("/P/src/Catalogs/Foo/Forms/ItemForm/Module.bsl", "");
        assertEquals("FORM_MODULE", new BslAstReader().getModuleType(file));
    }

    @Test
    public void getModuleType_recognisesObjectModule() throws Exception {
        IFile file = mockFile("/P/src/Catalogs/Foo/ObjectModule.bsl", "");
        assertEquals("OBJECT_MODULE", new BslAstReader().getModuleType(file));
    }

    @Test
    public void getModuleType_recognisesManagerModule() throws Exception {
        IFile file = mockFile("/P/src/Documents/Bar/ManagerModule.bsl", "");
        assertEquals("MANAGER_MODULE", new BslAstReader().getModuleType(file));
    }

    @Test
    public void getModuleType_recognisesOrdinaryAppModule() throws Exception {
        IFile file = mockFile("/P/src/OrdinaryApplicationModule.bsl", "");
        assertEquals("ORDINARY_APP_MODULE", new BslAstReader().getModuleType(file));
    }

    @Test
    public void getModuleType_unknownPathReturnsUnknown() throws Exception {
        IFile file = mockFile("/P/somewhere/Custom.bsl", "");
        assertEquals("UNKNOWN", new BslAstReader().getModuleType(file));
    }

    @Test
    public void validate_balancedContent_returnsNoErrors() {
        String content = "Процедура Foo()\nКонецПроцедуры\n";
        assertTrue(new BslAstReader().validate(content, null).isEmpty());
    }

    @Test
    public void validate_unterminatedProcedure_returnsError() {
        String content = "Процедура Foo()\n";
        List<String> errs = new BslAstReader().validate(content, null);
        assertEquals(1, errs.size());
        assertTrue(errs.get(0).contains("unterminated"));
    }

    @Test
    public void methodInfo_kindIsLowerCase() {
        MethodInfo proc = new MethodInfo("X", "procedure", false, 1, 2, 0, 10);
        MethodInfo func = new MethodInfo("Y", "function", true, 3, 4, 20, 30);
        assertEquals("procedure", proc.kind());
        assertEquals("function", func.kind());
    }
}
