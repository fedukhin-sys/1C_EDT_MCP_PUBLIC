package ru.fedukhin.edt.mcp.tests.privacy;

import static org.junit.Assert.*;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.privacy.PrivacyState;

/** Проверяет, что PrivacyState отдаёт один и тот же процесс-синглтон при повторных вызовах. */
public class PrivacyStateSharedInstanceTest {
    @Test public void flagsIsSameInstance() {
        assertSame(PrivacyState.flags(), PrivacyState.flags());
    }
    @Test public void auditIsSameInstance() {
        assertSame(PrivacyState.audit(), PrivacyState.audit());
    }
}
