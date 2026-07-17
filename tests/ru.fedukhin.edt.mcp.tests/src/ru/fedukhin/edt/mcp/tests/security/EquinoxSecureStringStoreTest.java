package ru.fedukhin.edt.mcp.tests.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.StorageException;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.internal.security.ISecureStringStore;

/**
 * Сбой защищённого хранилища обязан быть отличим от «значения нет».
 *
 * <p>Раньше {@code get} отдавал {@code null} в обоих случаях, и {@code SecureTokenStore} на этом
 * молча генерировал новые секреты: ротировался bearer-токен (все клиенты отваливались) и
 * HMAC-ключ приватности (псевдонимы ПДн переставали совпадать с ранее выданными).
 */
public class EquinoxSecureStringStoreTest {

    /** Конструктор с seam'ом — package-private, поэтому достаём рефлексией. */
    private static ISecureStringStore storeOver(ISecurePreferences node) throws Exception {
        Class<?> impl = Class.forName(
            "ru.fedukhin.edt.mcp.core.internal.security.EquinoxSecureStringStore");
        Constructor<?> ctor = impl.getDeclaredConstructor(ISecurePreferences.class);
        ctor.setAccessible(true);
        return (ISecureStringStore) ctor.newInstance(node);
    }

    @Test
    public void missingValue_returnsNull() throws Exception {
        ISecurePreferences node = mock(ISecurePreferences.class);
        when(node.get("k", null)).thenReturn(null);

        assertNull(storeOver(node).get("k"));
    }

    @Test
    public void presentValue_isReturned() throws Exception {
        ISecurePreferences node = mock(ISecurePreferences.class);
        when(node.get("k", null)).thenReturn("v");

        assertEquals("v", storeOver(node).get("k"));
    }

    @Test
    public void storageFailure_isRaised_notReportedAsMissing() throws Exception {
        ISecurePreferences node = mock(ISecurePreferences.class);
        when(node.get("k", null)).thenThrow(new StorageException(StorageException.INTERNAL_ERROR, "boom"));

        try {
            storeOver(node).get("k");
            fail("сбой хранилища обязан подниматься, иначе секреты молча ротируются");
        } catch (IllegalStateException expected) {
            assertEquals(StorageException.class, expected.getCause().getClass());
        }
    }
}
