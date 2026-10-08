package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.core.resource.EdtResourceMetadata;
import com._1c.g5.v8.dt.core.resource.IResourceStoreManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseEqualityState;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtApplicationState;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtSyncSnapshot;
import ru.fedukhin.edt.mcp.tools.infobase.internal.ProjectSyncState;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncStateProbe;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SyncV2;

/** Чтения EDT для {@code get_infobase_sync_state}: рефлексия по менеджеру синхронизации, расширения, сервисы. */
public class SyncStateProbeTest {

    private final IInfobaseSynchronizationManager sync = mock(IInfobaseSynchronizationManager.class);
    private final SyncV2 syncV2 = mock(SyncV2.class);
    private final IV8ProjectManager projects = mock(IV8ProjectManager.class);
    private final InfobaseReference ib = mock(InfobaseReference.class);

    private SyncStateProbe probe(Supplier<Object> resources, Supplier<BundleContext> contexts) {
        return new SyncStateProbe(sync, syncV2, projects, resources, contexts);
    }

    private static IProject project(String name) {
        IProject p = mock(IProject.class);
        when(p.getName()).thenReturn(name);
        return p;
    }

    @Test
    public void read_equalityConnectedDirtiness() {
        IProject demo = project("Demo");
        when(sync.getEqualityState(demo, ib)).thenReturn(InfobaseEqualityState.NOT_EQUAL);
        when(sync.isConnected(demo, ib)).thenReturn(true);
        when(syncV2.projectDirty(demo, ib)).thenReturn(new SyncV2.Dirtiness(true, null));

        ProjectSyncState state = probe(() -> null, () -> null).read(demo, ProjectSyncState.CONFIGURATION, ib);

        assertEquals(new ProjectSyncState("Demo", "configuration", "NOT_EQUAL", true, true, null), state);
    }

    @Test
    public void read_failures_nullWithReasons() {
        IProject demo = project("Demo");
        when(sync.getEqualityState(demo, ib)).thenThrow(new IllegalStateException("хранилище недоступно"));
        when(sync.isConnected(demo, ib)).thenReturn(false);
        when(syncV2.projectDirty(demo, ib)).thenReturn(new SyncV2.Dirtiness(null, "нет API синхронизации v2"));

        ProjectSyncState state = probe(() -> null, () -> null).read(demo, ProjectSyncState.EXTENSION, ib);

        assertNull(state.equalityState());
        assertEquals(Boolean.FALSE, state.connected());
        assertNull(state.projectDirty());
        assertTrue(state.error(), state.error().contains("getEqualityState: хранилище недоступно"));
        assertTrue(state.error(), state.error().contains("isProjectDirty: нет API синхронизации v2"));
    }

    @Test
    public void extensionProjects_onlyDependentsOfThisConfiguration() {
        IProject demo = project("Demo");
        IProject other = project("Other");
        IProject a = project("Demo.A");
        IProject b = project("Other.B");
        IExtensionProject extA = mock(IExtensionProject.class);
        when(extA.getParentProject()).thenReturn(demo);
        when(extA.getProject()).thenReturn(a);
        IExtensionProject extB = mock(IExtensionProject.class);
        when(extB.getParentProject()).thenReturn(other);
        when(extB.getProject()).thenReturn(b);
        when(projects.getProjects(IExtensionProject.class)).thenReturn(List.of(extA, extB));

        assertEquals(List.of(a), probe(() -> null, () -> null).extensionProjects(demo));
        assertEquals(List.of(a), SyncStateProbe.dependentOf(demo, List.of(extA, extB)));
    }

    /**
     * OSGi-контекст на {@link Proxy}: один сервис {@link EdtApplicationState#MANAGER} и бандл приложений-баз, отдающий
     * класс приложения ({@code null} — бандла нет). Mockito в рантайме тестов не мокает типы {@code org.osgi.framework}
     * (его бандл их не видит).
     */
    private static final class FakeContext implements InvocationHandler {
        private final Object service;
        private final ServiceReference<?> reference;
        private final Bundle[] bundles;
        int ungets;

        FakeContext(Object service, Class<?> applicationClass) {
            this.service = service;
            this.reference = service == null ? null : (ServiceReference<?>) Proxy.newProxyInstance(
                SyncStateProbeTest.class.getClassLoader(), new Class<?>[] {ServiceReference.class},
                (proxy, method, args) -> objectMethod(proxy, method, args));
            this.bundles = applicationClass == null ? new Bundle[0] : new Bundle[] {(Bundle) Proxy.newProxyInstance(
                SyncStateProbeTest.class.getClassLoader(), new Class<?>[] {Bundle.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getSymbolicName":
                            return EdtApplicationState.INFOBASES_BUNDLE;
                        case "loadClass":
                            if (EdtApplicationState.INFOBASE_APPLICATION.equals(args[0])) return applicationClass;
                            throw new ClassNotFoundException(String.valueOf(args[0]));
                        default:
                            return objectMethod(proxy, method, args);
                    }
                })};
        }

        BundleContext context() {
            return (BundleContext) Proxy.newProxyInstance(SyncStateProbeTest.class.getClassLoader(),
                new Class<?>[] {BundleContext.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getServiceReference":
                    return EdtApplicationState.MANAGER.equals(args[0]) ? reference : null;
                case "getService":
                    return args[0] == reference ? service : null;
                case "ungetService":
                    if (args[0] == reference) ungets++;
                    return Boolean.TRUE;
                case "getBundles":
                    return bundles;
                default:
                    return objectMethod(proxy, method, args);
            }
        }

        private static Object objectMethod(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "FakeContext";
                default:
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return Boolean.FALSE;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
            }
        }
    }

    @Test
    public void applicationState_asksServiceAndReleasesIt() {
        when(ib.getName()).thenReturn("DemoIB");
        EdtApplicationStateTest.FakeManager manager = new EdtApplicationStateTest.FakeManager();
        FakeContext context = new FakeContext(manager, EdtApplicationStateTest.FakeInfobaseApplication.class);

        EdtApplicationState.Result result = probe(() -> null, context::context).applicationState(project("Demo"), ib);

        assertEquals("UPDATED", result.state());
        assertTrue("приложение собрано классом из бандла приложений-баз",
            manager.asked instanceof EdtApplicationStateTest.FakeInfobaseApplication);
        assertEquals("сервис освобождён после вызова", 1, context.ungets);
    }

    @Test
    public void applicationState_missingServiceContextOrBundle_reason() {
        FakeContext noService = new FakeContext(null, EdtApplicationStateTest.FakeInfobaseApplication.class);
        FakeContext noBundle = new FakeContext(new EdtApplicationStateTest.FakeManager(), null);

        assertTrue(probe(() -> null, noService::context).applicationState(project("Demo"), ib).error()
            .contains("не зарегистрирован"));
        assertTrue(probe(() -> null, () -> null).applicationState(project("Demo"), ib).error()
            .contains("OSGi"));
        assertTrue(probe(() -> null, noBundle::context).applicationState(project("Demo"), ib).error()
            .contains(EdtApplicationState.INFOBASE_APPLICATION));
        assertEquals("сервис освобождён и без бандла", 1, noBundle.ungets);
    }

    /**
     * Сервис приложений EDT берётся через контекст собственного бандла. Без {@code Bundle-ActivationPolicy: lazy}
     * EDT бандл не активирует (в {@code bundles.info} autostart=false), контекста у него нет — в живой EDT
     * {@code applicationUpdateState} приходил {@code null} с «нет OSGi-контекста бандла».
     */
    @Test
    public void ownBundle_activatedOnClassLoad() {
        Bundle bundle = FrameworkUtil.getBundle(SyncStateProbe.class);

        assertNotNull("бандл tools.infobase не активирован загрузкой класса (Bundle-ActivationPolicy: lazy)",
            bundle.getBundleContext());
    }

    @Test
    public void currentSignatures_effectiveMetadataOfDtProject() {
        IProject demo = project("Demo");
        IV8Project v8 = mock(IV8Project.class);
        IDtProject dt = mock(IDtProject.class);
        when(projects.getProject(demo)).thenReturn(v8);
        when(v8.getDtProject()).thenReturn(dt);
        IResourceStoreManager store = mock(IResourceStoreManager.class);
        when(store.getEffectiveResourceMetadata(dt))
            .thenReturn(Map.of("src/a.bsl", new EdtResourceMetadata(new byte[] {7}, UUID.randomUUID())));

        SyncStateProbe.Current current = probe(() -> store, () -> null).currentSignatures(demo);

        assertNull(current.failure());
        assertArrayEquals(new byte[] {7}, current.files().get("src/a.bsl"));
    }

    @Test
    public void currentSignatures_failures_reason() {
        IProject demo = project("Demo");
        SyncStateProbe.Current noService = probe(() -> {
            throw new NoClassDefFoundError("IResourceStoreManager");
        }, () -> null).currentSignatures(demo);
        assertNull(noService.files());
        assertTrue(noService.failure(), noService.failure().contains("сервис ресурсов EDT недоступен"));

        SyncStateProbe.Current notLoaded = probe(() -> mock(IResourceStoreManager.class), () -> null)
            .currentSignatures(demo);
        assertTrue(notLoaded.failure(), notLoaded.failure().contains("не загружен"));
    }

    @Test
    public void snapshot_withDisk_throughSyncV2() {
        IProject demo = project("Demo");
        EdtSyncSnapshot.Read expected = new EdtSyncSnapshot.Read(Map.of(), null, Map.of(), null);
        when(syncV2.readSnapshot(demo, ib, List.of("Demo.A"), true)).thenReturn(expected);

        assertSame(expected, probe(() -> null, () -> null).snapshot(demo, ib, List.of("Demo.A")));
    }
}
