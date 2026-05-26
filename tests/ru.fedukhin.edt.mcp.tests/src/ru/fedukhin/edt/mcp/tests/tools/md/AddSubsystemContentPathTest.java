package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.AddSubsystemContentTool;

/**
 * Unit-тесты для {@link AddSubsystemContentTool#subsystemMdoPath} —
 * парсинг fqn (включая nested) в относительный .mdo path.
 */
public class AddSubsystemContentPathTest {

    @Test
    public void topLevel() throws Exception {
        assertEquals("src/Subsystems/X/X.mdo", invokePath("X"));
    }

    @Test
    public void oneLevelNested() throws Exception {
        assertEquals(
                "src/Subsystems/СтандартныеПодсистемы/Subsystems/УправлениеДоступом/УправлениеДоступом.mdo",
                invokePath("СтандартныеПодсистемы.УправлениеДоступом"));
    }

    @Test
    public void deepNested() throws Exception {
        assertEquals(
                "src/Subsystems/A/Subsystems/B/Subsystems/C/Subsystems/D/D.mdo",
                invokePath("A.B.C.D"));
    }

    @Test
    public void singleCharNested() throws Exception {
        assertEquals("src/Subsystems/A/Subsystems/B/B.mdo", invokePath("A.B"));
    }

    private static String invokePath(String subsystemPath) throws Exception {
        Method m = AddSubsystemContentTool.class.getDeclaredMethod("subsystemMdoPath", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, subsystemPath);
    }
}
