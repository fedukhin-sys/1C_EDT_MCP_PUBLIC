package ru.fedukhin.edt.mcp.tests.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.infobase.internal.InfobaseLockKey;

/**
 * Ключ обязан совпадать у двух инстанций EDT, целящих в одну базу, и различаться
 * у разных баз — иначе замок либо не сработает, либо сериализует лишнее.
 */
public class InfobaseLockKeyTest {

    @Test
    public void uuid_winsWhenPresent() {
        assertEquals("ib:uuid:4cf26896", InfobaseLockKey.build("4cf26896", "Srvr=x;Ref=y;", "DemoBase"));
    }

    /** Написание строки подключения не должно влиять: иначе замок разъедется. */
    @Test
    public void connectionString_isCaseInsensitive() {
        assertEquals(InfobaseLockKey.build(null, "Srvr=\"S1\";Ref=\"DemoBase\";", "DemoBase"),
                     InfobaseLockKey.build(null, "srvr=\"s1\";ref=\"demobase\";", "DemoBase"));
    }

    @Test
    public void connectionString_ignoresSpacing() {
        assertEquals(InfobaseLockKey.build(null, "Srvr=\"S1\"; Ref=\"DemoBase\";", "DemoBase"),
                     InfobaseLockKey.build(null, "Srvr=\"S1\";Ref=\"DemoBase\";", "DemoBase"));
    }

    @Test
    public void differentBases_getDifferentKeys() {
        assertNotEquals(InfobaseLockKey.build(null, "File=\"E:\\a\";", "A"),
                        InfobaseLockKey.build(null, "File=\"E:\\b\";", "B"));
    }

    @Test
    public void nameIsLastResort() {
        assertEquals("ib:name:DemoBase", InfobaseLockKey.build(null, null, "DemoBase"));
        assertEquals("ib:name:DemoBase", InfobaseLockKey.build("", "  ", "DemoBase"));
    }

    @Test
    public void nullReference_hasStableKey() {
        assertEquals("ib:unknown", InfobaseLockKey.of(null));
    }
}
