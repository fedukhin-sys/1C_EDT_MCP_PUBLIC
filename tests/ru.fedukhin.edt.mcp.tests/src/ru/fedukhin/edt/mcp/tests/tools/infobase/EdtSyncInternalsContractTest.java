package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com._1c.g5.v8.dt.core.resource.EdtResourceMetadata;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.InfobaseEqualityState;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Члены EDT, на которые опираются {@code EdtSyncSnapshot} и {@code SyncStateProbe}, есть в НАСТОЯЩИХ классах target
 * platform: подделки юнит-тестов ({@code SyncV2Fakes}) повторяют имена, а этот тест ловит их расхождение с EDT при
 * смене ветки. Внутренние классы берутся загрузчиком бандла {@code dt.platform.services.core} (пакет не
 * экспортируется).
 */
public class EdtSyncInternalsContractTest {

    private static final String V2 = "com._1c.g5.v8.dt.internal.platform.services.core.infobases.sync.v2.";

    private static Class<?> edt(String simpleName) throws ClassNotFoundException {
        Bundle bundle = FrameworkUtil.getBundle(IInfobaseSynchronizationManager.class);
        assertNotNull("бандл dt.platform.services.core не найден в OSGi", bundle);
        return bundle.loadClass(V2 + simpleName);
    }

    @Test
    public void delegate_findsHolderWithoutCreating_andHasLock() throws Exception {
        Class<?> delegate = edt("InfobaseSynchronizationStateManagerDelegate");

        assertNotNull(delegate.getDeclaredMethod("calculateStoreProject", IProject.class));
        Method find = delegate.getDeclaredMethod("findProjectInfobaseSynchronizationStateHolder", IProject.class,
            InfobaseReference.class);
        assertEquals(Optional.class, find.getReturnType());
        assertNotNull(delegate.getDeclaredField("lock"));
    }

    @Test
    public void stateManager_exposesDelegate() throws Exception {
        assertNotNull(edt("InfobaseSynchronizationStateManager").getMethod("getDelegate"));
    }

    @Test
    public void holder_stateAndStore() throws Exception {
        Class<?> holder = edt("InfobaseSynchronizationStateManagerDelegate$ProjectInfobaseSynchronizationStateHolder");

        assertEquals(edt("InfobaseSyncState"), holder.getDeclaredField("state").getType());
        assertEquals(edt("InfobaseSynchronizationStateStore"),
            holder.getDeclaredField("synchronizationStore").getType());
    }

    @Test
    public void syncState_gettersAndUndefined() throws Exception {
        Class<?> state = edt("InfobaseSyncState");

        assertEquals(Map.class, state.getMethod("getEdtResourceMetadata").getReturnType());
        assertEquals(Map.class, state.getMethod("getExtensionSyncStates").getReturnType());
        assertEquals(long.class, state.getMethod("getTimestamp").getReturnType());
        Field undefined = state.getField("UNDEFINED");
        assertTrue(Modifier.isStatic(undefined.getModifiers()));
    }

    @Test
    public void store_readsStateFromDisk() throws Exception {
        assertEquals(edt("InfobaseSyncState"), edt("InfobaseSynchronizationStateStore").getMethod("readState")
            .getReturnType());
    }

    @Test
    public void resourceMetadata_signature() throws Exception {
        assertEquals(byte[].class, EdtResourceMetadata.class.getMethod("getSignature").getReturnType());
    }

    @Test
    public void syncManager_equalityAndConnection() throws Exception {
        assertNotNull(IInfobaseSynchronizationManager.class.getMethod("getEqualityState", IProject.class,
            InfobaseReference.class));
        assertNotNull(IInfobaseSynchronizationManager.class.getMethod("isConnected", IProject.class,
            InfobaseReference.class));
        Set<String> names = Arrays.stream(InfobaseEqualityState.values()).map(Enum::name).collect(Collectors.toSet());
        assertEquals(Set.of("EQUAL", "NOT_EQUAL", "LOADING"), names);
    }
}
