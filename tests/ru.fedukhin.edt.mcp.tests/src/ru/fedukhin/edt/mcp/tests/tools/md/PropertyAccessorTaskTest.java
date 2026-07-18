package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.BusinessProcess;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.Task;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.PropertyAccessor;

/**
 * 1.20.x: свойство {@code task} у BusinessProcess — без задачи созданный БП несёт error
 * MdValidationChecker «Не выбрана задача бизнес-процесса», и до этого фикса задать её
 * инструментами было нечем. SetMdPropertyTool резолвит FQN до Task-EObject.
 */
public class PropertyAccessorTaskTest {

    private PropertyAccessor accessor() {
        return new PropertyAccessor(mock(IV8ProjectManager.class));
    }

    @Test public void businessProcess_task_callsSetTask() throws Exception {
        BusinessProcess bp = mock(BusinessProcess.class);
        Task task = mock(Task.class);
        accessor().set(bp, "BusinessProcess", mock(IProject.class), "task", task);
        verify(bp).setTask(task);
    }

    @Test public void task_rejectsUnresolvedStringValue() {
        BusinessProcess bp = mock(BusinessProcess.class);
        try {
            accessor().set(bp, "BusinessProcess", mock(IProject.class), "task", "Task.X");
            fail("строка вместо EObject обязана отбиваться");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("resolved Task EObject"));
        }
    }

    @Test public void task_notWhitelistedForOtherKinds() {
        Catalog cat = mock(Catalog.class);
        try {
            accessor().set(cat, "Catalog", mock(IProject.class), "task", mock(Task.class));
            fail("task на Catalog обязан отбиваться whitelist'ом");
        } catch (ToolException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not whitelisted"));
        }
    }
}
