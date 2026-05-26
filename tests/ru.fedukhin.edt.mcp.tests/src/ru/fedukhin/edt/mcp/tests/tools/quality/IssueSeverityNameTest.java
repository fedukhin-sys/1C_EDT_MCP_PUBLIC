package ru.fedukhin.edt.mcp.tests.tools.quality;

import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.quality.internal.IssueSeverityName;

import static org.junit.Assert.assertEquals;

/**
 * Spike 1 confirmed the {@link IssueSeverity} enum names: BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL.
 * Spike 2 confirmed that {@code Marker.getSeverity(): MarkerSeverity} uses the same names plus
 * NONE; the mapping is identical so both pass through {@link IssueSeverityName#fromEdtName}.
 */
public class IssueSeverityNameTest {

    @Test public void mapsAllEdtSeverityEnumNames() {
        assertEquals("error",   IssueSeverityName.fromEdtName("BLOCKER"));
        assertEquals("error",   IssueSeverityName.fromEdtName("CRITICAL"));
        assertEquals("error",   IssueSeverityName.fromEdtName("MAJOR"));
        assertEquals("warning", IssueSeverityName.fromEdtName("MINOR"));
        assertEquals("info",    IssueSeverityName.fromEdtName("TRIVIAL"));
    }

    @Test public void mapsIssueSeverityEnum() {
        // The IssueSeverity enum from com.e1c.g5.v8.dt.check.settings — directly mappable.
        assertEquals("error",   IssueSeverityName.fromEdt(IssueSeverity.BLOCKER));
        assertEquals("warning", IssueSeverityName.fromEdt(IssueSeverity.MINOR));
        assertEquals("info",    IssueSeverityName.fromEdt(IssueSeverity.TRIVIAL));
    }

    @Test public void fallsBackToInfo() {
        assertEquals("info", IssueSeverityName.fromEdtName("UNKNOWN_VALUE"));
        assertEquals("info", IssueSeverityName.fromEdtName(null));
        assertEquals("info", IssueSeverityName.fromEdt(null));
    }

    @Test public void mapsNoneIfMarkerSeverityHasIt() {
        // The MarkerSeverity enum from Spike 2 includes a NONE value that IssueSeverity does
        // not have. NONE → "info" (the safest default; markers with NONE severity are
        // informational at most).
        assertEquals("info", IssueSeverityName.fromEdtName("NONE"));
    }
}
