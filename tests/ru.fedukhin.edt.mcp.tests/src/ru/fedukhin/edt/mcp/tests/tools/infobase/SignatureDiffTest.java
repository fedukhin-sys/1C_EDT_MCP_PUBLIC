package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.infobase.internal.SignatureDiff;

/** Правило {@code checkAndUpdateSynchronizationState} EDT 2026.1: размеры, пустая карта, {@code Arrays.equals}. */
public class SignatureDiffTest {

    static byte[] sig(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        return bytes;
    }

    @Test
    public void equalMaps_match() {
        Map<String, byte[]> current = Map.of("src/a.bsl", sig(1, 2), "src/b.mdo", sig(3));
        Map<String, byte[]> snapshot = Map.of("src/a.bsl", sig(1, 2), "src/b.mdo", sig(3));

        SignatureDiff.Result result = SignatureDiff.compare(current, snapshot, 10);

        assertTrue(result.matches());
        assertEquals(2, result.currentFiles());
        assertEquals(2, result.snapshotFiles());
        assertEquals(0, result.addedCount() + result.deletedCount() + result.changedCount());
        assertFalse(result.truncated());
    }

    @Test
    public void addedDeletedChanged_sortedWithHexPrefixes() {
        Map<String, byte[]> current = new HashMap<>();
        current.put("src/b.bsl", sig(0xAB, 0xCD));
        current.put("src/a.bsl", sig(1));
        current.put("src/new.bsl", sig(9));
        Map<String, byte[]> snapshot = new HashMap<>();
        snapshot.put("src/a.bsl", sig(2));
        snapshot.put("src/b.bsl", sig(0xAB, 0xCD));
        snapshot.put("src/gone.bsl", sig(5));

        SignatureDiff.Result result = SignatureDiff.compare(current, snapshot, 10);

        assertFalse(result.matches());
        assertEquals(List.of("src/new.bsl"), result.added());
        assertEquals(List.of("src/gone.bsl"), result.deleted());
        assertEquals(List.of(new SignatureDiff.Changed("src/a.bsl", "02", "01")), result.changed());
    }

    @Test
    public void lists_truncated_countsFull() {
        Map<String, byte[]> current = new HashMap<>();
        for (int i = 0; i < 200; i++) current.put(String.format("src/f%03d.bsl", i), sig(i));

        SignatureDiff.Result result = SignatureDiff.compare(current, Map.of(), 5);

        assertEquals(200, result.addedCount());
        assertEquals(List.of("src/f000.bsl", "src/f001.bsl", "src/f002.bsl", "src/f003.bsl", "src/f004.bsl"),
            result.added());
        assertTrue(result.truncated());
    }

    /** Пустая текущая карта у EDT — всегда «грязный». */
    @Test
    public void emptyCurrent_neverMatches() {
        assertFalse(SignatureDiff.compare(Map.of(), Map.of(), 10).matches());
    }

    /** Две пустые подписи равны ({@code Arrays.equals(null, null)}), пустая против настоящей — нет. */
    @Test
    public void emptySignatures_likeArraysEquals_andCounted() {
        Map<String, byte[]> bothNull = new HashMap<>();
        bothNull.put("src/a.bsl", null);
        SignatureDiff.Result same = SignatureDiff.compare(bothNull, new HashMap<>(bothNull), 10);
        assertTrue(same.matches());
        assertEquals(1, same.emptySignatures());

        Map<String, byte[]> snapshot = new HashMap<>();
        snapshot.put("src/a.bsl", new byte[0]);
        SignatureDiff.Result differs = SignatureDiff.compare(Map.of("src/a.bsl", sig(1)), snapshot, 10);
        assertFalse(differs.matches());
        assertEquals(List.of(new SignatureDiff.Changed("src/a.bsl", "", "01")), differs.changed());
        assertEquals(1, differs.emptySignatures());
    }

    @Test
    public void hexPrefix_twelveChars() {
        byte[] full = new byte[32];
        Arrays.fill(full, (byte) 0xFF);
        assertEquals("ffffffffffff", SignatureDiff.hexPrefix(full));
        assertEquals("", SignatureDiff.hexPrefix(null));
    }

    @Test
    public void sameSignatures_pathsAndBytes() {
        assertTrue(SignatureDiff.sameSignatures(Map.of("a", sig(1)), Map.of("a", sig(1))));
        assertFalse(SignatureDiff.sameSignatures(Map.of("a", sig(1)), Map.of("a", sig(2))));
        assertFalse(SignatureDiff.sameSignatures(Map.of("a", sig(1)), Map.of("b", sig(1))));
        assertFalse(SignatureDiff.sameSignatures(Map.of("a", sig(1)), Map.of()));
    }
}
