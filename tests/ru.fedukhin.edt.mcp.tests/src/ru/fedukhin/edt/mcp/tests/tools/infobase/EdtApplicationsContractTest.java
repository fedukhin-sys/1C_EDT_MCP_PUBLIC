package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Platform;
import org.junit.Test;
import org.osgi.framework.Bundle;
import ru.fedukhin.edt.mcp.tools.infobase.internal.EdtApplicationState;

/**
 * API приложений EDT, на которое опирается {@code EdtApplicationState} (только рефлексией, без {@code Import-Package}),
 * — в НАСТОЯЩИХ бандлах target platform: публичный конструктор внутреннего {@code InfobaseApplication}, тип
 * приложения-базы в {@code plugin.xml}, методы {@code IApplicationManager}, константы {@code ApplicationUpdateState}.
 * Бандлы у тестов — необязательная зависимость: нет их в рантайме — тест пропускается.
 */
public class EdtApplicationsContractTest {

    private static final String APPLICATIONS = "com.e1c.g5.dt.applications";

    private static Bundle bundle(String symbolicName) {
        Bundle bundle = Platform.getBundle(symbolicName);
        assumeNotNull(bundle);
        return bundle;
    }

    @Test
    public void infobaseType_declaredInPluginXml() throws Exception {
        URL pluginXml = bundle(EdtApplicationState.INFOBASES_BUNDLE).getEntry("plugin.xml");
        String text;
        try (InputStream in = pluginXml.openStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(text.contains("id=\"" + EdtApplicationState.INFOBASE_TYPE + "\""));
    }

    @Test
    public void updateStateConstants_usedByUpdateStateRule() throws Exception {
        Class<?> state = bundle(APPLICATIONS).loadClass(APPLICATIONS + ".ApplicationUpdateState");

        Set<String> names = Arrays.stream(state.getEnumConstants()).map(c -> ((Enum<?>) c).name())
            .collect(Collectors.toSet());
        assertEquals(Set.of("UNKNOWN", "INCREMENTAL_UPDATE_REQUIRED", "FULL_UPDATE_REQUIRED", "UPDATED",
            "BEING_UPDATED"), names);
    }

    /**
     * Сквозной путь {@code EdtApplicationState.query} на настоящих классах: {@link Proxy} настоящего
     * {@code IApplicationManager} (методы ищутся у интерфейса) отдаёт настоящий тип, приложение собирается
     * настоящим конструктором {@code InfobaseApplication} и адаптируется к своей базе, как у EDT.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void query_realInterfacesAndInfobaseApplication() throws Exception {
        Bundle applications = bundle(APPLICATIONS);
        Class<?> managerApi = applications.loadClass(EdtApplicationState.MANAGER);
        Class<?> typeApi = applications.loadClass(APPLICATIONS + ".IApplicationType");
        Class<? extends Enum> updateState =
            (Class<? extends Enum>) applications.loadClass(APPLICATIONS + ".ApplicationUpdateState");
        Class<?> applicationClass = bundle(EdtApplicationState.INFOBASES_BUNDLE)
            .loadClass(EdtApplicationState.INFOBASE_APPLICATION);
        Object type = Proxy.newProxyInstance(typeApi.getClassLoader(), new Class<?>[] {typeApi},
            (proxy, method, args) -> switch (method.getName()) {
                case "getId" -> EdtApplicationState.INFOBASE_TYPE;
                case "getName" -> "Информационная база";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
        List<Object> asked = new ArrayList<>();
        Object manager = Proxy.newProxyInstance(managerApi.getClassLoader(), new Class<?>[] {managerApi},
            (proxy, method, args) -> switch (method.getName()) {
                case "getApplicationTypes" -> List.of(type);
                case "getUpdateState" -> {
                    asked.add(args[0]);
                    yield Enum.valueOf(updateState, "INCREMENTAL_UPDATE_REQUIRED");
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
        InfobaseReference infobase = mock(InfobaseReference.class);
        when(infobase.getName()).thenReturn("DemoIB");
        when(infobase.getUuid()).thenReturn(UUID.randomUUID());

        EdtApplicationState.Result result = EdtApplicationState.query(manager, applicationClass, mock(IProject.class),
            infobase);

        assertNull(result.error());
        assertEquals("INCREMENTAL_UPDATE_REQUIRED", result.state());
        assertEquals(1, asked.size());
        assertSame(applicationClass, asked.get(0).getClass());
        assertSame(infobase, ((IAdaptable) asked.get(0)).getAdapter(InfobaseReference.class));
    }
}
