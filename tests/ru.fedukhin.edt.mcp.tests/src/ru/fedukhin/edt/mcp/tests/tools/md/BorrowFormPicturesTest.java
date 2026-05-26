package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.util.Set;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.BorrowFormPicturesTool;

/**
 * Unit-тесты static helper'ов {@link BorrowFormPicturesTool}:
 * <ul>
 *   <li>{@code scanPictureRefs} парсит уникальные CommonPicture refs из Form.form;</li>
 *   <li>{@code resolveFormPath} раскрывает fqn (CommonForm.X или Kind.Owner.Form.Y) в workspace-relative путь.</li>
 * </ul>
 */
public class BorrowFormPicturesTest {

    @Test
    public void scanPictureRefs_findsAllUnique() throws Exception {
        String form = """
                <Form xmlns="...">
                  <items>
                    <item><picture>CommonPicture.Сохранить</picture></item>
                    <item><picture>CommonPicture.Удалить</picture></item>
                    <item><picture>CommonPicture.Сохранить</picture></item>
                  </items>
                </Form>
                """;
        Set<String> result = BorrowFormPicturesTool.scanPictureRefs(
                new ByteArrayInputStream(form.getBytes("UTF-8")));
        assertEquals("must dedupe", 2, result.size());
        assertTrue(result.contains("Сохранить"));
        assertTrue(result.contains("Удалить"));
    }

    @Test
    public void scanPictureRefs_ignoresPicturesWithoutCommonPrefix() throws Exception {
        String form = """
                <Form>
                  <picture>NotCommonPicture.X</picture>
                  <picture>Picture.Y</picture>
                  <picture>CommonPicture.Корректная</picture>
                </Form>
                """;
        Set<String> result = BorrowFormPicturesTool.scanPictureRefs(
                new ByteArrayInputStream(form.getBytes("UTF-8")));
        assertEquals(1, result.size());
        assertTrue(result.contains("Корректная"));
    }

    @Test
    public void scanPictureRefs_handlesWhitespaceAroundTag() throws Exception {
        String form = "<Form><a><picture>  CommonPicture.Тест  </picture></a></Form>";
        Set<String> result = BorrowFormPicturesTool.scanPictureRefs(
                new ByteArrayInputStream(form.getBytes("UTF-8")));
        assertEquals(1, result.size());
        assertTrue(result.contains("Тест"));
    }

    @Test
    public void scanPictureRefs_emptyFormReturnsEmptySet() throws Exception {
        String form = "<Form/>";
        Set<String> result = BorrowFormPicturesTool.scanPictureRefs(
                new ByteArrayInputStream(form.getBytes("UTF-8")));
        assertTrue(result.isEmpty());
    }

    @Test
    public void resolveFormPath_commonForm() throws Exception {
        assertEquals("src/CommonForms/X/Form.form",
                BorrowFormPicturesTool.resolveFormPath("CommonForm.X"));
    }

    @Test
    public void resolveFormPath_catalogForm() throws Exception {
        assertEquals("src/Catalogs/Номенклатура/Forms/ФормаЭлемента/Form.form",
                BorrowFormPicturesTool.resolveFormPath("Catalog.Номенклатура.Form.ФормаЭлемента"));
    }

    @Test
    public void resolveFormPath_documentForm() throws Exception {
        assertEquals("src/Documents/ЗаказКлиента/Forms/ФормаДокумента/Form.form",
                BorrowFormPicturesTool.resolveFormPath("Document.ЗаказКлиента.Form.ФормаДокумента"));
    }

    @Test
    public void resolveFormPath_informationRegisterForm() throws Exception {
        assertEquals("src/InformationRegisters/Курсы/Forms/ФормаСписка/Form.form",
                BorrowFormPicturesTool.resolveFormPath("InformationRegister.Курсы.Form.ФормаСписка"));
    }

    @Test(expected = ToolException.class)
    public void resolveFormPath_failsOnBadFqn() throws Exception {
        BorrowFormPicturesTool.resolveFormPath("Catalog.X");
    }

    @Test(expected = ToolException.class)
    public void resolveFormPath_failsOnUnknownKind() throws Exception {
        BorrowFormPicturesTool.resolveFormPath("UnknownKind.X.Form.Y");
    }
}
