package ru.fedukhin.edt.mcp.tests.tools.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.client.ListLaunchConfigurationsTool;
import ru.fedukhin.edt.mcp.tools.client.internal.InfobaseLookup;
import ru.fedukhin.edt.mcp.tools.client.internal.LaunchConfigService;

public class ListLaunchConfigurationsToolTest {

    private static final String UH_UUID = "1079bc1b-d64d-4649-a68f-19ccb763ea29";

    /** Мок конфигурации «Upiter Тонкий клиент» с атрибутами реального .launch-файла. */
    private static ILaunchConfiguration upiterThinClient() throws Exception {
        ILaunchConfiguration cfg = mock(ILaunchConfiguration.class);
        when(cfg.getName()).thenReturn("Upiter Тонкий клиент");
        when(cfg.getAttribute(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(cfg.getAttribute(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        when(cfg.getAttribute(eq(LaunchConfigService.ATTR_PROJECT_NAME), anyString()))
            .thenReturn("Upiter");
        when(cfg.getAttribute(eq(LaunchConfigService.ATTR_APPLICATION_ID), anyString()))
            .thenReturn(UH_UUID);
        when(cfg.getAttribute(eq(LaunchConfigService.ATTR_CLIENT_TYPE), anyString()))
            .thenReturn("com._1c.g5.v8.dt.platform.services.core.componentTypes.ThinClient");
        when(cfg.getAttribute(eq(LaunchConfigService.ATTR_LAUNCH_USER_NAME), anyString()))
            .thenReturn("Лебедев АЕ (доступ)");
        when(cfg.getAttribute(eq(LaunchConfigService.ATTR_LAUNCH_USER_PASSWORD), anyString()))
            .thenReturn("123");
        when(cfg.getAttribute(eq(LaunchConfigService.ATTR_RUNTIME_INSTALLATION), anyString()))
            .thenReturn("com._1c.g5.v8.dt.platform.services.core.resolvableInstallations.environments:"
                + "com._1c.g5.v8.dt.platform.services.core.runtimeType.EnterprisePlatform=8.3.27");
        return cfg;
    }

    private static InfobaseLookup lookupWithUh() {
        InfobaseReference uh = mock(InfobaseReference.class);
        when(uh.getName()).thenReturn("UH");
        InfobaseLookup lookup = mock(InfobaseLookup.class);
        when(lookup.findByUuid(UUID.fromString(UH_UUID))).thenReturn(Optional.of(uh));
        return lookup;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> callAndGet(ListLaunchConfigurationsTool tool,
                                                        Map<String, Object> args) throws Exception {
        Map<String, Object> out = (Map<String, Object>) tool.call(args);
        return (List<Map<String, Object>>) out.get("configurations");
    }

    @Test
    public void call_mapsAttributesAndResolvesInfobase() throws Exception {
        ILaunchConfiguration cfg = upiterThinClient();   // мок ДО when(): вложенное стаббинг ломает Mockito
        LaunchConfigService service = mock(LaunchConfigService.class);
        when(service.list()).thenReturn(List.of(cfg));

        List<Map<String, Object>> configs =
            callAndGet(new ListLaunchConfigurationsTool(service, lookupWithUh()), null);

        assertEquals(1, configs.size());
        Map<String, Object> entry = configs.get(0);
        assertEquals("Upiter Тонкий клиент", entry.get("name"));
        assertEquals("Upiter", entry.get("project"));
        assertEquals("UH", entry.get("infobase"));
        assertEquals("thin", entry.get("clientType"));
        assertEquals("Лебедев АЕ (доступ)", entry.get("user"));
        assertEquals(Boolean.TRUE, entry.get("hasPassword"));
        assertEquals(Boolean.FALSE, entry.get("osAuthentication"));
        assertEquals("8.3.27", entry.get("runtimeInstallation"));
    }

    /** Пароль не должен утекать в выдачу ни под каким ключом. */
    @Test
    public void call_neverReturnsPasswordValue() throws Exception {
        ILaunchConfiguration cfg = upiterThinClient();
        LaunchConfigService service = mock(LaunchConfigService.class);
        when(service.list()).thenReturn(List.of(cfg));

        List<Map<String, Object>> configs =
            callAndGet(new ListLaunchConfigurationsTool(service, lookupWithUh()), null);

        assertFalse("пароль утёк в выдачу list_launch_configurations",
            configs.get(0).values().toString().contains("123"));
    }

    @Test
    public void call_projectFilter_skipsOtherProjects() throws Exception {
        ILaunchConfiguration cfg = upiterThinClient();
        LaunchConfigService service = mock(LaunchConfigService.class);
        when(service.list()).thenReturn(List.of(cfg));

        Map<String, Object> args = new HashMap<>();
        args.put("project", "Dandy");
        List<Map<String, Object>> configs =
            callAndGet(new ListLaunchConfigurationsTool(service, lookupWithUh()), args);

        assertTrue(configs.isEmpty());
    }

    @Test
    public void call_unknownApplicationId_infobaseNull() throws Exception {
        ILaunchConfiguration cfg = upiterThinClient();
        LaunchConfigService service = mock(LaunchConfigService.class);
        when(service.list()).thenReturn(List.of(cfg));
        InfobaseLookup lookup = mock(InfobaseLookup.class);
        when(lookup.findByUuid(UUID.fromString(UH_UUID))).thenReturn(Optional.empty());

        List<Map<String, Object>> configs =
            callAndGet(new ListLaunchConfigurationsTool(service, lookup), null);

        assertNull(configs.get(0).get("infobase"));
    }

    @Test
    public void mapClientType_knownAndUnknown() {
        assertEquals("thin", ListLaunchConfigurationsTool.mapClientType(
            "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThinClient"));
        assertEquals("thick", ListLaunchConfigurationsTool.mapClientType(
            "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient"));
        assertEquals("web", ListLaunchConfigurationsTool.mapClientType(
            "com._1c.g5.v8.dt.platform.services.core.componentTypes.WebClient"));
        assertEquals("x.y.Mobile", ListLaunchConfigurationsTool.mapClientType("x.y.Mobile"));
        assertNull(ListLaunchConfigurationsTool.mapClientType(""));
    }

    @Test
    public void mapRuntime_extractsVersionSuffix() {
        assertEquals("8.3.27", ListLaunchConfigurationsTool.mapRuntime("a:b.EnterprisePlatform=8.3.27"));
        assertNull(ListLaunchConfigurationsTool.mapRuntime(""));
        assertEquals("raw", ListLaunchConfigurationsTool.mapRuntime("raw"));
    }
}
