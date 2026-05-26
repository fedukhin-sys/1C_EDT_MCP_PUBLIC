package ru.fedukhin.edt.mcp.tests.tools.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.core.model.BslModuleReference;
import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com._1c.g5.v8.dt.debug.core.model.IBslVariable;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugClientTarget;
import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import com._1c.g5.v8.dt.debug.core.model.values.IBslValue;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugStateDto;
import ru.fedukhin.edt.mcp.tools.debug.internal.DebugStateReader;
import ru.fedukhin.edt.mcp.tools.debug.internal.SourceLocation;
import ru.fedukhin.edt.mcp.tools.debug.internal.StackFrameDto;
import ru.fedukhin.edt.mcp.tools.debug.internal.VariableDto;

public class DebugStateReaderTest {

    private final DebugStateReader reader = new DebugStateReader();
    private final UUID sid = UUID.randomUUID();

    @Test
    public void toDebugState_terminatedTarget_reportsTerminated() {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.isTerminated()).thenReturn(true);

        DebugStateDto s = reader.toDebugState(sid, target);

        assertEquals(DebugStateDto.TERMINATED, s.state());
        assertNull(s.stoppedThread());
        assertNull(s.location());
    }

    @Test
    public void toDebugState_noSuspendedThread_reportsRunning() {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.isTerminated()).thenReturn(false);
        IRuntimeDebugTargetThread t = mock(IRuntimeDebugTargetThread.class);
        when(t.isSuspended()).thenReturn(false);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[] { t });

        DebugStateDto s = reader.toDebugState(sid, target);

        assertEquals(DebugStateDto.RUNNING, s.state());
        assertNull(s.location());
    }

    @Test
    public void toDebugState_suspendedThread_reportsSuspendedWithLocation() throws Exception {
        IRuntimeDebugClientTarget target = mock(IRuntimeDebugClientTarget.class);
        when(target.isTerminated()).thenReturn(false);

        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("DemoIB");
        BslModuleReference ref = mock(BslModuleReference.class);
        when(ref.getProject()).thenReturn(project);

        IBslStackFrame top = mock(IBslStackFrame.class);
        when(top.getLineNumber()).thenReturn(42);
        when(top.getName()).thenReturn("ОбработкаПроведения");
        when(top.getReference()).thenReturn(ref);
        when(top.getSource()).thenReturn(
                URI.createPlatformResourceURI("/DemoIB/src/CommonModules/М/Module.bsl", true));

        IRuntimeDebugTargetThread t = mock(IRuntimeDebugTargetThread.class);
        when(t.isSuspended()).thenReturn(true);
        when(t.getName()).thenReturn("Основной");
        when(t.getTopStackFrame()).thenReturn(top);
        when(target.getThreads()).thenReturn(new IRuntimeDebugTargetThread[] { t });

        DebugStateDto s = reader.toDebugState(sid, target);

        assertEquals(DebugStateDto.SUSPENDED, s.state());
        assertEquals("0", s.stoppedThread().id());
        assertEquals("Основной", s.stoppedThread().name());
        SourceLocation loc = s.location();
        assertEquals("DemoIB", loc.project());
        assertEquals("src/CommonModules/М/Module.bsl", loc.path());
        assertEquals(42, loc.line());
        assertEquals("ОбработкаПроведения", loc.method());
    }

    @Test
    public void running_setsTimedOutFlag() {
        DebugStateDto s = reader.running(sid, true);
        assertEquals(DebugStateDto.RUNNING, s.state());
        assertTrue(s.timedOut());
    }

    @Test
    public void toFrameDto_usesLevelAsFrameId() throws Exception {
        IBslStackFrame f = mock(IBslStackFrame.class);
        when(f.getLevel()).thenReturn(3);
        when(f.getLineNumber()).thenReturn(7);
        when(f.getName()).thenReturn("Метод");
        when(f.getReference()).thenReturn(null);
        when(f.getSource()).thenReturn(null);

        StackFrameDto dto = reader.toFrameDto(f);

        assertEquals("3", dto.frameId());
        assertEquals(7, dto.line());
        assertEquals("Метод", dto.method());
        assertNull(dto.project());
        assertNull(dto.path());
    }

    @Test
    public void toVariableDto_readsNameTypeAndDetailString() throws Exception {
        IBslValue value = mock(IBslValue.class);
        when(value.getValueTypeName()).thenReturn("Строка");
        when(value.getDetailString()).thenReturn("Привет");
        IBslVariable v = mock(IBslVariable.class);
        when(v.getName()).thenReturn("Сообщение");
        when(v.getValue()).thenReturn(value);

        VariableDto dto = reader.toVariableDto(v);

        assertEquals("Сообщение", dto.name());
        assertEquals("Строка", dto.type());
        assertEquals("Привет", dto.value());
    }

    @Test
    public void toVariableDto_nullValue_returnsEmptyValue() {
        IBslVariable v = mock(IBslVariable.class);
        when(v.getName()).thenReturn("НеВычислено");
        when(v.getValue()).thenReturn(null);

        VariableDto dto = reader.toVariableDto(v);

        assertEquals("НеВычислено", dto.name());
        assertNull(dto.type());
        assertEquals("", dto.value());
    }

    @Test
    public void toFrameDto_nonPlatformUri_fallsBackToUriString() throws Exception {
        URI nonPlatform = URI.createURI("file:/some/path/Module.bsl");
        IBslStackFrame f = mock(IBslStackFrame.class);
        when(f.getLevel()).thenReturn(0);
        when(f.getLineNumber()).thenReturn(1);
        when(f.getName()).thenReturn("Метод");
        when(f.getReference()).thenReturn(null);
        when(f.getSource()).thenReturn(nonPlatform);

        StackFrameDto dto = reader.toFrameDto(f);

        assertEquals(nonPlatform.toString(), dto.path());
    }
}
