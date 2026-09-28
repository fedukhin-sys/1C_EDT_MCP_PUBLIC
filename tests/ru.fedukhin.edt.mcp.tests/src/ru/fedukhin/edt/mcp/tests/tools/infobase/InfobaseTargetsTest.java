package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociation;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.util.List;
import java.util.Optional;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseRegistry;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseTargets;

public class InfobaseTargetsTest {

    private final IWorkspaceRoot root = mock(IWorkspaceRoot.class);
    private final InfobaseRegistry registry = mock(InfobaseRegistry.class);
    private final IInfobaseAssociationManager associations = mock(IInfobaseAssociationManager.class);
    private final IV8ProjectManager projects = mock(IV8ProjectManager.class);
    private final InfobaseTargets targets = new InfobaseTargets(() -> root, registry, associations, projects);

    private static IProject project(String name) {
        IProject p = mock(IProject.class);
        when(p.getName()).thenReturn(name);
        when(p.exists()).thenReturn(true);
        when(p.isOpen()).thenReturn(true);
        return p;
    }

    private static InfobaseReference infobase(String name) {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn(name);
        return ref;
    }

    private void associate(IProject project, InfobaseReference... refs) throws Exception {
        IInfobaseAssociation a = mock(IInfobaseAssociation.class);
        when(a.getInfobases()).thenReturn(List.of(refs));
        when(a.getDefaultInfobase()).thenReturn(refs.length == 0 ? null : refs[0]);
        when(associations.getAssociation(project)).thenReturn(Optional.of(a));
    }

    private static IExtensionProject extension(IProject parent, String projectName, String extensionName) {
        IExtensionProject ext = mock(IExtensionProject.class);
        IProject p = project(projectName);
        when(ext.getProject()).thenReturn(p);
        when(ext.getParentProject()).thenReturn(parent);
        Configuration c = mock(Configuration.class);
        when(c.getName()).thenReturn(extensionName);
        when(ext.getConfiguration()).thenReturn(c);
        return ext;
    }

    @Test
    public void resolve_withoutName_takesDefaultAssociation() throws Exception {
        IProject beta = project("Beta");
        InfobaseReference ib = infobase("Beta");
        associate(beta, ib);

        InfobaseTargets.Target t = targets.resolve(beta, null, false);

        assertSame(ib, t.infobase());
        assertNull(t.warning());
    }

    @Test
    public void resolve_withoutAssociation_explainsHowToFix() throws Exception {
        IProject beta = project("Beta");
        when(associations.getAssociation(beta)).thenReturn(Optional.empty());
        try {
            targets.resolve(beta, null, false);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("associate_infobase"));
        }
    }

    @Test
    public void resolve_foreignName_refusedWithoutFlag() throws Exception {
        IProject beta = project("Beta");
        associate(beta, infobase("Beta"));
        InfobaseReference other = infobase("Alpha");
        when(registry.findByName("Alpha")).thenReturn(Optional.of(other));
        try {
            targets.resolve(beta, "Alpha", false);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("allowForeignInfobase"));
        }
        assertSame(other, targets.resolve(beta, "Alpha", true).infobase());
    }

    @Test
    public void configurationProject_rejectsExtensionProject() {
        IProject ext = project("Beta.Склад");
        when(projects.getProject(ext)).thenReturn(mock(IExtensionProject.class));
        try {
            targets.configurationProject(ext);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("расширения"));
        }
    }

    /**
     * Fix round 7 (R7-3): «проект ← база» — только проекты конфигурации и расширений. Состояние проекта внешних
     * отчётов и обработок EDT хранит у родительской конфигурации, и сброс по нему задел бы родителя.
     */
    @Test
    public void configurationOrExtensionProject_acceptsBoth_refusesExternalObjectsAndUnknown() throws Exception {
        IProject beta = project("Beta");
        IConfigurationProject conf = mock(IConfigurationProject.class);
        when(projects.getProject(beta)).thenReturn(conf);
        IProject sklad = project("Beta.Склад");
        IExtensionProject ext = mock(IExtensionProject.class);
        when(projects.getProject(sklad)).thenReturn(ext);
        IProject reports = project("Beta.Отчёты");
        when(projects.getProject(reports)).thenReturn(mock(IExternalObjectProject.class));
        IProject unknown = project("Нечто");

        assertSame(conf, targets.configurationOrExtensionProject(beta));
        assertSame(ext, targets.configurationOrExtensionProject(sklad));
        for (IProject refused : List.of(reports, unknown)) {
            try {
                targets.configurationOrExtensionProject(refused);
                fail("expected ToolException for " + refused.getName());
            } catch (ToolException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("'" + refused.getName() + "'"));
                assertTrue(e.getMessage(), e.getMessage().contains("только проект конфигурации или проект расширения"));
            }
        }
        try {
            targets.configurationOrExtensionProject(reports);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("внешних отчётов и обработок"));
        }
    }

    @Test
    public void configurationProject_acceptsConfiguration() throws Exception {
        IProject beta = project("Beta");
        IConfigurationProject conf = mock(IConfigurationProject.class);
        when(projects.getProject(beta)).thenReturn(conf);

        assertSame(conf, targets.configurationProject(beta));
    }

    @Test
    public void extensionProjects_filterByParent_andMatchNameIgnoringCase() {
        IProject beta = project("Beta");
        IProject alpha = project("Alpha");
        IExtensionProject sklad = extension(beta, "Beta.Склад", "Beta_Склад");
        IExtensionProject foreign = extension(alpha, "Alpha.Логистика", "Логистика");
        when(projects.getProjects(IExtensionProject.class)).thenReturn(List.of(sklad, foreign));

        assertEquals(List.of(sklad), targets.extensionProjects(beta));
        assertSame(sklad, targets.extensionProject(beta, "BETA_СКЛАД").orElseThrow());
        assertTrue(targets.extensionProject(beta, "Логистика").isEmpty());
    }

    @Test
    public void associatedInfobases_listsNames() throws Exception {
        IProject beta = project("Beta");
        associate(beta, infobase("Beta"));

        assertEquals(List.of("Beta"), targets.associatedInfobases(beta));
    }

    /**
     * L2: EDT 2026.1 связывает базу только с одним проектом — с конфигурацией; проект расширения своей
     * связи не имеет и не может получить («Infobase X is already associated with project P»). Проект
     * расширения принадлежит базе, если связан с ней явно или не связан ни с одной (тогда он следует за
     * родительской конфигурацией); связанный только с другими базами — не принадлежит.
     */
    @Test
    public void belongsTo_explicitAssociationOrNone_butNotOnlyOtherInfobases() throws Exception {
        InfobaseReference betaIb = infobase("Beta");
        IProject inherits = project("Beta.Склад");
        when(associations.getAssociation(inherits)).thenReturn(Optional.empty());
        IProject explicit = project("Beta.Тест");
        associate(explicit, betaIb);
        IProject foreign = project("Beta.Чужое");
        associate(foreign, infobase("Alpha"));

        assertTrue("без своей связи — следует за родительской конфигурацией", targets.belongsTo(inherits, betaIb));
        assertTrue(targets.belongsTo(explicit, betaIb));
        assertFalse("связан только с другой базой", targets.belongsTo(foreign, betaIb));
    }

    /** Родитель проекта расширения без своей связи. */
    private IProject extensionWithoutOwnAssociation(String name, IProject parent) throws Exception {
        IProject extProject = project(name);
        IExtensionProject ext = mock(IExtensionProject.class);
        when(ext.getProject()).thenReturn(extProject);
        when(ext.getParentProject()).thenReturn(parent);
        when(projects.getProject(extProject)).thenReturn(ext);
        when(associations.getAssociation(extProject)).thenReturn(Optional.empty());
        return extProject;
    }

    /** L2: примитив на проекте расширения без своей связи берёт базу родительской конфигурации. */
    @Test
    public void resolve_extensionWithoutOwnAssociation_takesParentsDefaultInfobase() throws Exception {
        IProject beta = project("Beta");
        InfobaseReference ib = infobase("Beta");
        associate(beta, ib);
        IProject sklad = extensionWithoutOwnAssociation("Beta.Склад", beta);

        InfobaseTargets.Target t = targets.resolve(sklad, null, false);

        assertSame(ib, t.infobase());
        assertNull(t.warning());
    }

    /**
     * Явное имя базы у проекта расширения без своей связи сверяется со связями родителя: своя база —
     * без ложного «не связан ни с одной базой», чужая — отказ, как у проекта конфигурации.
     */
    @Test
    public void resolve_extensionWithoutOwnAssociation_explicitNameCheckedAgainstParent() throws Exception {
        IProject beta = project("Beta");
        InfobaseReference ib = infobase("Beta");
        associate(beta, ib);
        IProject sklad = extensionWithoutOwnAssociation("Beta.Склад", beta);
        when(registry.findByName("Beta")).thenReturn(Optional.of(ib));
        InfobaseReference alpha = infobase("Alpha");
        when(registry.findByName("Alpha")).thenReturn(Optional.of(alpha));

        InfobaseTargets.Target own = targets.resolve(sklad, "Beta", false);
        assertSame(ib, own.infobase());
        assertNull("своя база родителя — без предупреждения: " + own.warning(), own.warning());

        try {
            targets.resolve(sklad, "Alpha", false);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("allowForeignInfobase"));
            assertTrue(e.getMessage(), e.getMessage().contains("родительской конфигурацией 'Beta'"));
        }
        InfobaseTargets.Target foreign = targets.resolve(sklad, "Alpha", true);
        assertSame(alpha, foreign.infobase());
        assertTrue(foreign.warning(), foreign.warning().contains("allowForeignInfobase"));
    }

    @Test
    public void resolve_extensionWithoutOwnAssociation_parentUnassociated_explains() throws Exception {
        IProject beta = project("Beta");
        when(associations.getAssociation(beta)).thenReturn(Optional.empty());
        IProject sklad = extensionWithoutOwnAssociation("Beta.Склад", beta);
        try {
            targets.resolve(sklad, null, false);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("родительской конфигурацией 'Beta'"));
            assertTrue(e.getMessage(), e.getMessage().contains("associate_infobase"));
        }
    }

    /** Проект расширения, связанный с базой сам, работает со своей связью. */
    @Test
    public void resolve_extensionWithOwnAssociation_usesOwn() throws Exception {
        IProject beta = project("Beta");
        associate(beta, infobase("Beta"));
        IProject test = project("Beta.Тест");
        IExtensionProject ext = mock(IExtensionProject.class);
        when(ext.getParentProject()).thenReturn(beta);
        when(projects.getProject(test)).thenReturn(ext);
        InfobaseReference testIb = infobase("BetaTest");
        associate(test, testIb);

        assertSame(testIb, targets.resolve(test, null, false).infobase());
    }

    @Test
    public void openProject_rejectsClosed() {
        IProject closed = project("Closed");
        when(closed.isOpen()).thenReturn(false);
        when(root.getProject("Closed")).thenReturn(closed);
        try {
            targets.openProject("Closed");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Closed"));
        }
    }
}
