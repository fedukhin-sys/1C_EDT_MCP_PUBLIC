package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.internal.McoreTypeReader;

/**
 * Unit tests for {@link McoreTypeReader} (BUG-03/BUG-04 fix). The reader is
 * fully reflective, so the stubs are plain public classes exposing the same
 * method names as the mcore {@code TypeDescription}/{@code TypeItem} API.
 */
public class McoreTypeReaderTest {

    /** Stub TypeDescription. */
    public static class TD {
        private final List<TI> types;
        private final SQ sq;
        private final NQ nq;
        TD(List<TI> types, SQ sq, NQ nq) { this.types = types; this.sq = sq; this.nq = nq; }
        public List<TI> getTypes() { return types; }
        public SQ getStringQualifiers() { return sq; }
        public NQ getNumberQualifiers() { return nq; }
    }

    /** Stub TypeItem. */
    public static class TI {
        private final String n;
        TI(String n) { this.n = n; }
        public String getName() { return n; }
    }

    /** Stub StringQualifiers. */
    public static class SQ {
        private final int len;
        SQ(int len) { this.len = len; }
        public int getLength() { return len; }
    }

    /** Stub NumberQualifiers. */
    public static class NQ {
        private final int precision;
        private final int scale;
        NQ(int precision, int scale) { this.precision = precision; this.scale = scale; }
        public int getPrecision() { return precision; }
        public int getScale() { return scale; }
    }

    private static TD td(List<TI> types, SQ sq, NQ nq) { return new TD(types, sq, nq); }
    private static TI ti(String name) { return new TI(name); }

    @Test
    public void nullReturnsEmpty() {
        assertEquals("", McoreTypeReader.format(null));
    }

    @Test
    public void stringWithLength() {
        assertEquals("String(50)",
                McoreTypeReader.format(td(List.of(ti("String")), new SQ(50), null)));
    }

    @Test
    public void stringWithoutQualifier() {
        assertEquals("String",
                McoreTypeReader.format(td(List.of(ti("String")), null, null)));
    }

    @Test
    public void numberWithPrecisionAndScale() {
        assertEquals("Number(10,2)",
                McoreTypeReader.format(td(List.of(ti("Number")), null, new NQ(10, 2))));
    }

    @Test
    public void numberWithScaleZero() {
        assertEquals("Number(10)",
                McoreTypeReader.format(td(List.of(ti("Number")), null, new NQ(10, 0))));
    }

    @Test
    public void dateAndBoolean() {
        assertEquals("Date", McoreTypeReader.format(td(List.of(ti("Date")), null, null)));
        assertEquals("Boolean", McoreTypeReader.format(td(List.of(ti("Boolean")), null, null)));
    }

    @Test
    public void russianPrimitiveNamesAccepted() {
        assertEquals("Date", McoreTypeReader.format(td(List.of(ti("Дата")), null, null)));
        assertEquals("Boolean", McoreTypeReader.format(td(List.of(ti("Булево")), null, null)));
    }

    @Test
    public void referenceTypeEmittedVerbatim() {
        assertEquals("CatalogRef.Номенклатура",
                McoreTypeReader.format(td(List.of(ti("CatalogRef.Номенклатура")), null, null)));
    }

    @Test
    public void compositeTypeReturnsList() {
        Object result = McoreTypeReader.format(
                td(List.of(ti("CatalogRef.A"), ti("DocumentRef.B")), null, null));
        assertTrue(result instanceof List);
        assertEquals(List.of("CatalogRef.A", "DocumentRef.B"), result);
    }

    @Test
    public void noTypesFallsBackToQualifier() {
        assertEquals("String(20)", McoreTypeReader.format(td(List.of(), new SQ(20), null)));
        assertEquals("", McoreTypeReader.format(td(List.of(), null, null)));
    }
}
