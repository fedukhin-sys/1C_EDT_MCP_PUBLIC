package ru.fedukhin.edt.mcp.tests.tools.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.client.internal.LaunchConfigService;

public class LaunchConfigServiceTest {

    private static ILaunchManager managerWith(ILaunchConfiguration... configs) throws Exception {
        ILaunchManager manager = mock(ILaunchManager.class);
        ILaunchConfigurationType type = mock(ILaunchConfigurationType.class);
        when(manager.getLaunchConfigurationType(LaunchConfigService.RUNTIME_CLIENT_TYPE_ID))
            .thenReturn(type);
        when(manager.getLaunchConfigurations(type)).thenReturn(configs);
        return manager;
    }

    private static ILaunchConfiguration named(String name) {
        ILaunchConfiguration cfg = mock(ILaunchConfiguration.class);
        when(cfg.getName()).thenReturn(name);
        return cfg;
    }

    @Test
    public void list_returnsOnlyRuntimeClientConfigs() throws Exception {
        ILaunchConfiguration a = named("A");
        ILaunchConfiguration b = named("B");
        LaunchConfigService service = new LaunchConfigService(managerWith(a, b));

        List<ILaunchConfiguration> got = service.list();

        assertEquals(2, got.size());
        assertSame(a, got.get(0));
        assertSame(b, got.get(1));
    }

    @Test
    public void list_typeNotRegistered_throwsWithTypeId() throws Exception {
        ILaunchManager manager = mock(ILaunchManager.class);
        when(manager.getLaunchConfigurationType(LaunchConfigService.RUNTIME_CLIENT_TYPE_ID))
            .thenReturn(null);
        try {
            new LaunchConfigService(manager).list();
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains(LaunchConfigService.RUNTIME_CLIENT_TYPE_ID));
        }
    }

    @Test
    public void findByName_exactMatch() throws Exception {
        ILaunchConfiguration target = named("Upiter Тонкий клиент");
        LaunchConfigService service = new LaunchConfigService(managerWith(named("Другая"), target));

        assertSame(target, service.findByName("Upiter Тонкий клиент"));
    }

    @Test
    public void findByName_miss_errorListsAvailableNames() throws Exception {
        LaunchConfigService service = new LaunchConfigService(managerWith(named("Upiter Тонкий клиент")));
        try {
            service.findByName("Нет такой");
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue("сообщение должно перечислять доступные конфигурации: " + e.getMessage(),
                e.getMessage().contains("Upiter Тонкий клиент"));
        }
    }

    @Test
    public void launchWithTimeout_completes_returnsLaunch() throws Exception {
        ILaunchConfiguration cfg = named("Cfg");
        ILaunch launch = mock(ILaunch.class);
        when(cfg.launch(eq("run"), any(IProgressMonitor.class))).thenReturn(launch);
        LaunchConfigService service = new LaunchConfigService(mock(ILaunchManager.class));

        Optional<ILaunch> got = service.launchWithTimeout(cfg, "run", 30);

        assertTrue(got.isPresent());
        assertSame(launch, got.get());
    }

    /** Таймаут не прерывает запуск (EDT может обновлять ИБ) — просто пустой результат. */
    @Test
    public void launchWithTimeout_timesOut_returnsEmpty() throws Exception {
        ILaunchConfiguration cfg = named("Slow");
        CountDownLatch blocker = new CountDownLatch(1);
        when(cfg.launch(eq("run"), any(IProgressMonitor.class))).thenAnswer(inv -> {
            blocker.await();   // «запуск» висит дольше таймаута
            return mock(ILaunch.class);
        });
        LaunchConfigService service = new LaunchConfigService(mock(ILaunchManager.class));

        Optional<ILaunch> got = service.launchWithTimeout(cfg, "run", 1);

        assertFalse(got.isPresent());
        blocker.countDown();
    }

    @Test
    public void launchWithTimeout_delegateThrows_wrappedAsToolException() throws Exception {
        ILaunchConfiguration cfg = named("Broken");
        when(cfg.launch(eq("run"), any(IProgressMonitor.class)))
            .thenThrow(new IllegalStateException("boom"));
        LaunchConfigService service = new LaunchConfigService(mock(ILaunchManager.class));
        try {
            service.launchWithTimeout(cfg, "run", 30);
            fail("expected ToolException");
        } catch (ToolException e) {
            assertTrue(e.getMessage().contains("boom"));
        }
    }
}
