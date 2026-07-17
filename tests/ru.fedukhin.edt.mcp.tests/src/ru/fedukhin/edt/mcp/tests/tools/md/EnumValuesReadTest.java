package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Enum;
import com._1c.g5.v8.dt.metadata.mdclass.EnumValue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.BasicEMap;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.EMap;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.GetMdObjectTool;
import ru.fedukhin.edt.mcp.tools.md.ListAttributesTool;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectRegistry;
import ru.fedukhin.edt.mcp.tools.md.internal.MdoFileEditor;
import ru.fedukhin.edt.mcp.tools.md.internal.TypeStringFormatter;

/**
 * Значения перечислений обязаны читаться обоими маршрутами: раньше {@code get_md_object} на Enum
 * молча отдавал {@code attributes: []}, а {@code list_attributes} — тихий пустой список
 * («kind has no attribute-bearing .mdo»), из-за чего состав перечисления был недоступен вовсе.
 *
 * <p>Фикстура повторяет формат типовой конфигурации: {@code <enumValues uuid>} с {@code name} и
 * {@code synonym}.
 */
public class EnumValuesReadTest {

    private static final String ENUM_MDO =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
      + "<mdclass:Enum xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"e0\">\n"
      + "  <producedTypes>\n"
      + "    <refType typeId=\"t1\" valueTypeId=\"t2\"/>\n"
      + "  </producedTypes>\n"
      + "  <name>СтатусыЗаказов</name>\n"
      + "  <synonym><key>ru</key><value>Статусы заказов</value></synonym>\n"
      + "  <enumValues uuid=\"v1\">\n"
      + "    <name>Новый</name>\n"
      + "    <synonym><key>ru</key><value>Новый заказ</value></synonym>\n"
      + "    <comment>стартовый статус</comment>\n"
      + "  </enumValues>\n"
      + "  <enumValues uuid=\"v2\">\n"
      + "    <name>Закрыт</name>\n"
      + "  </enumValues>\n"
      + "</mdclass:Enum>\n";

    // --- disk-route (list_attributes) ---

    @Test
    public void listAttributes_readsEnumValuesFromMdo() throws Exception {
        IFile mdoFile = mock(IFile.class);
        when(mdoFile.exists()).thenReturn(true);
        when(mdoFile.getContents()).thenAnswer(inv ->
            new ByteArrayInputStream(ENUM_MDO.getBytes(StandardCharsets.UTF_8)));
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getFile("src/Enums/СтатусыЗаказов/СтатусыЗаказов.mdo")).thenReturn(mdoFile);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        ListAttributesTool tool = new ListAttributesTool(() -> root, new MdoFileEditor());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
            Map.of("project", "Demo", "fqn", "Enum.СтатусыЗаказов"));

        List<?> values = (List<?>) result.get("values");
        assertNotNull("values обязаны присутствовать для Enum", values);
        assertEquals(2, values.size());

        Map<?, ?> first = (Map<?, ?>) values.get(0);
        assertEquals("Новый", first.get("name"));
        assertEquals("Новый заказ", first.get("synonym"));
        assertEquals("стартовый статус", first.get("comment"));

        Map<?, ?> second = (Map<?, ?>) values.get(1);
        assertEquals("Закрыт", second.get("name"));
        assertEquals("", second.get("synonym"));
        assertEquals("", second.get("comment"));
    }

    /** У не-перечислений ключ values обязан быть пустым, а не отсутствовать. */
    @Test
    public void listAttributes_nonEnum_reportsEmptyValues() throws Exception {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        ListAttributesTool tool = new ListAttributesTool(() -> root, new MdoFileEditor());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
            Map.of("project", "Demo", "fqn", "CommonModule.Helper"));

        assertTrue(((List<?>) result.get("values")).isEmpty());
    }

    // --- BM-route (get_md_object) ---

    private static EnumValue enumValue(String name, String synonym, String comment) {
        EnumValue v = mock(EnumValue.class);
        when(v.getName()).thenReturn(name);
        EMap<String, String> syn = new BasicEMap<>();
        if (synonym != null) {
            syn.put("ru", synonym);
        }
        when(v.getSynonym()).thenReturn(syn);
        when(v.getComment()).thenReturn(comment);
        return v;
    }

    @Test
    public void getMdObject_readsEnumValues() throws Exception {
        Enum theEnum = mock(Enum.class, withSettings().extraInterfaces(IBmObject.class));
        when(theEnum.getName()).thenReturn("СтатусыЗаказов");
        when(theEnum.getSynonym()).thenReturn(new BasicEMap<>());
        when(theEnum.getComment()).thenReturn("");
        EList<EnumValue> values = new BasicEList<>(List.of(
            enumValue("Новый", "Новый заказ", "стартовый статус"),
            enumValue("Закрыт", null, "")));
        when(theEnum.getEnumValues()).thenReturn(values);

        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("Demo");
        IWorkspaceRoot root = mock(IWorkspaceRoot.class);
        when(root.getProject("Demo")).thenReturn(project);

        IBmTransaction txn = mock(IBmTransaction.class);
        when(txn.getTopObjectByFqn("Enum.СтатусыЗаказов")).thenReturn((IBmObject) theEnum);
        IBmModelManager bm = mock(IBmModelManager.class);
        doAnswer(inv -> {
            IBmSingleNamespaceTask<?> task = inv.getArgument(1);
            task.execute(txn);
            return null;
        }).when(bm).executeReadOnlyTask(eq(project), any());

        GetMdObjectTool tool = new GetMdObjectTool(() -> root, bm,
            mock(IConfigurationProvider.class), mock(IV8ProjectManager.class),
            new MdObjectLocator(), new MdObjectRegistry(), new TypeStringFormatter());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.call(
            Map.of("project", "Demo", "fqn", "Enum.СтатусыЗаказов"));

        List<?> read = (List<?>) result.get("values");
        assertNotNull("values обязаны присутствовать для Enum", read);
        assertEquals(2, read.size());
        assertEquals("Новый", ((Map<?, ?>) read.get(0)).get("name"));
        assertEquals("Новый заказ", ((Map<?, ?>) read.get(0)).get("synonym"));
        assertEquals("стартовый статус", ((Map<?, ?>) read.get(0)).get("comment"));
        assertEquals("Закрыт", ((Map<?, ?>) read.get(1)).get("name"));
    }
}
