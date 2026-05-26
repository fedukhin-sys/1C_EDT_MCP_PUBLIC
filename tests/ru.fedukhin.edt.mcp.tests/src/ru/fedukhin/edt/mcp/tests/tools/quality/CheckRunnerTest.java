package ru.fedukhin.edt.mcp.tests.tools.quality;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com.e1c.g5.v8.dt.check.ICheckScheduler;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckMarker;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckRunResult;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckRunner;
import ru.fedukhin.edt.mcp.tools.quality.internal.MarkerReader;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты {@link CheckRunner}: scheduleClearance/scheduleValidation/waitAllComputations
 * последовательность; пропуск scheduleClearance при пустом наборе ids; пропуск scheduleClearance
 * при {@code clearFirst=false}; флаг completed=false при таймауте {@code waitAllComputations}.
 *
 * <p>API-сноска: {@code IDerivedDataManager} находится в пакете {@code com._1c.g5.v8.derived},
 * а провайдер — в {@code com._1c.g5.v8.dt.core.platform}; метод провайдера — {@code get(IProject)}.
 * {@code waitAllComputations(long)} объявляет {@code InterruptedException}.
 */
public class CheckRunnerTest {

    @Test public void scheduleClearanceAndValidationCalledThenWait() throws InterruptedException {
        ICheckScheduler scheduler = mock(ICheckScheduler.class);
        IDerivedDataManager dd = mock(IDerivedDataManager.class);
        when(dd.waitAllComputations(anyLong())).thenReturn(true);
        IDerivedDataManagerProvider provider = mock(IDerivedDataManagerProvider.class);
        when(provider.get(any(IProject.class))).thenReturn(dd);
        MarkerReader reader = mock(MarkerReader.class);
        when(reader.read(any(), any(), any(), any(), any())).thenReturn(List.of());
        IProject project = mock(IProject.class);

        CheckRunner runner = new CheckRunner(scheduler, provider, reader);
        CheckRunResult result = runner.run(project, null, Set.of("check.a"), 30, true);

        verify(scheduler, times(1)).scheduleClearance(eq(project), eq(Set.of("check.a")), any());
        verify(scheduler, times(1)).scheduleValidation(eq(project), eq(Set.of("check.a")),
                any(), any(IProgressMonitor.class));
        verify(dd, times(1)).waitAllComputations(30_000L);
        assertTrue(result.completed());
    }

    @Test public void clearFirstFalseSkipsClearance() throws InterruptedException {
        ICheckScheduler scheduler = mock(ICheckScheduler.class);
        IDerivedDataManager dd = mock(IDerivedDataManager.class);
        when(dd.waitAllComputations(anyLong())).thenReturn(true);
        IDerivedDataManagerProvider provider = mock(IDerivedDataManagerProvider.class);
        when(provider.get(any(IProject.class))).thenReturn(dd);
        MarkerReader reader = mock(MarkerReader.class);
        when(reader.read(any(), any(), any(), any(), any())).thenReturn(List.of());
        IProject project = mock(IProject.class);

        CheckRunner runner = new CheckRunner(scheduler, provider, reader);
        runner.run(project, null, Set.of("check.a"), 30, false);

        verify(scheduler, never()).scheduleClearance(any(), any(), any());
        verify(scheduler, times(1)).scheduleValidation(any(), any(), any(), any());
    }

    @Test public void emptyCheckIdsPassedThroughForFullProjectRun() throws InterruptedException {
        ICheckScheduler scheduler = mock(ICheckScheduler.class);
        IDerivedDataManager dd = mock(IDerivedDataManager.class);
        when(dd.waitAllComputations(anyLong())).thenReturn(true);
        IDerivedDataManagerProvider provider = mock(IDerivedDataManagerProvider.class);
        when(provider.get(any(IProject.class))).thenReturn(dd);
        MarkerReader reader = mock(MarkerReader.class);
        when(reader.read(any(), any(), any(), any(), any())).thenReturn(List.of());
        IProject project = mock(IProject.class);

        CheckRunner runner = new CheckRunner(scheduler, provider, reader);
        runner.run(project, null, Collections.emptySet(), 60, true);

        verify(scheduler, never()).scheduleClearance(any(), any(), any());   // no ids → no clearance
        verify(scheduler, times(1)).scheduleValidation(eq(project), eq(Collections.emptySet()),
                eq(Collections.emptyList()), any());
    }

    @Test public void waitTimeoutReturnsCompletedFalse() throws InterruptedException {
        ICheckScheduler scheduler = mock(ICheckScheduler.class);
        IDerivedDataManager dd = mock(IDerivedDataManager.class);
        when(dd.waitAllComputations(anyLong())).thenReturn(false);
        IDerivedDataManagerProvider provider = mock(IDerivedDataManagerProvider.class);
        when(provider.get(any(IProject.class))).thenReturn(dd);
        MarkerReader reader = mock(MarkerReader.class);
        when(reader.read(any(), any(), any(), any(), any())).thenReturn(List.of());
        IProject project = mock(IProject.class);

        CheckRunner runner = new CheckRunner(scheduler, provider, reader);
        CheckRunResult result = runner.run(project, null, Set.of("check.a"), 5, true);

        assertFalse(result.completed());
        assertEquals(5, result.waitedSeconds());
    }
}
