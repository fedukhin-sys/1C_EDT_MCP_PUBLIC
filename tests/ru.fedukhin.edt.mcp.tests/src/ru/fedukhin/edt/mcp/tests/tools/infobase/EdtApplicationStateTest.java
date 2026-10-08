package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtApplicationState;

/**
 * Итог обновления приложения базы у самой EDT: тип приложения-базы из {@code getApplicationTypes} → приложение тем же
 * конструктором, что у EDT, → {@code getUpdateState}. {@code getApplications} (с записью порта отладки) не зовётся.
 */
public class EdtApplicationStateTest {

    /** Как {@code ApplicationUpdateState}. */
    public enum FakeUpdateState { UNKNOWN, INCREMENTAL_UPDATE_REQUIRED, UPDATED }

    /** Как {@code IApplicationType}. */
    public static final class FakeType {
        private final String id;

        public FakeType(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    /** Как {@code InfobaseApplication}: публичный конструктор {@code (String, тип, InfobaseReference, IProject)}. */
    public static final class FakeInfobaseApplication {
        public final String name;
        public final Object type;
        public final InfobaseReference infobase;
        public final IProject project;

        public FakeInfobaseApplication(String name, Object type, InfobaseReference infobase, IProject project) {
            this.name = name;
            this.type = type;
            this.infobase = infobase;
            this.project = project;
        }
    }

    /** Как {@code IApplicationManager}: {@code getApplicationTypes()}, {@code getUpdateState(приложение)}. */
    public static final class FakeManager {
        public final FakeType infobaseType = new FakeType(EdtApplicationState.INFOBASE_TYPE);
        public List<Object> types = new ArrayList<>(List.of(new FakeType("com.e1c.g5.dt.applications.type.server"),
            infobaseType));
        public Object state = FakeUpdateState.UPDATED;
        public RuntimeException failure;
        public Object asked;
        public int getApplicationsCalls;

        public List<Object> getApplicationTypes() {
            return types;
        }

        public List<Object> getApplications(IProject project) {
            getApplicationsCalls++;
            return List.of();
        }

        public Object getUpdateState(Object application) {
            asked = application;
            if (failure != null) throw failure;
            return state;
        }
    }

    static InfobaseReference infobase(String name, UUID uuid) {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn(name);
        when(ref.getUuid()).thenReturn(uuid);
        return ref;
    }

    private static IProject project(String name) {
        IProject p = mock(IProject.class);
        when(p.getName()).thenReturn(name);
        return p;
    }

    @Test
    public void builtLikeEdt_asksUpdateState_neverGetApplications() {
        FakeManager manager = new FakeManager();
        manager.state = FakeUpdateState.INCREMENTAL_UPDATE_REQUIRED;
        InfobaseReference ib = infobase("DemoIB", UUID.randomUUID());
        IProject demo = project("Demo");

        EdtApplicationState.Result result = EdtApplicationState.query(manager, FakeInfobaseApplication.class, demo, ib);

        assertEquals("INCREMENTAL_UPDATE_REQUIRED", result.state());
        assertNull(result.error());
        FakeInfobaseApplication application = (FakeInfobaseApplication) manager.asked;
        assertEquals("DemoIB", application.name);
        assertSame(manager.infobaseType, application.type);
        assertSame(ib, application.infobase);
        assertSame(demo, application.project);
        assertEquals("getApplications назначает порт отладки — не зовём", 0, manager.getApplicationsCalls);
    }

    @Test
    public void noInfobaseType_reason() {
        FakeManager manager = new FakeManager();
        manager.types = List.of(new FakeType("com.e1c.g5.dt.applications.type.server"));

        EdtApplicationState.Result result = EdtApplicationState.query(manager, FakeInfobaseApplication.class,
            project("Demo"), infobase("DemoIB", UUID.randomUUID()));

        assertNull(result.state());
        assertTrue(result.error(), result.error().contains(EdtApplicationState.INFOBASE_TYPE));
    }

    @Test
    public void edtThrows_reasonCarriesMessage() {
        FakeManager manager = new FakeManager();
        manager.failure = new IllegalStateException("Ошибка EDT");

        EdtApplicationState.Result result = EdtApplicationState.query(manager, FakeInfobaseApplication.class,
            project("Demo"), infobase("DemoIB", UUID.randomUUID()));

        assertNull(result.state());
        assertTrue(result.error(), result.error().contains("Ошибка EDT"));
    }

    @Test
    public void missingPieces_reason() {
        InfobaseReference ib = infobase("DemoIB", UUID.randomUUID());
        assertTrue(EdtApplicationState.query(null, FakeInfobaseApplication.class, project("Demo"), ib).error()
            .contains("недоступен"));
        assertTrue(EdtApplicationState.query(new FakeManager(), null, project("Demo"), ib).error()
            .contains(EdtApplicationState.INFOBASE_APPLICATION));
        String noMethods = EdtApplicationState.query(new Object(), FakeInfobaseApplication.class, project("Demo"), ib)
            .error();
        assertTrue(noMethods, noMethods.contains("нет метода"));
        String noConstructor = EdtApplicationState.query(new FakeManager(), String.class, project("Demo"), ib).error();
        assertTrue(noConstructor, noConstructor.contains("нет метода"));
    }
}
