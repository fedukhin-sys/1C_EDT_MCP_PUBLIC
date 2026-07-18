package ru.fedukhin.edt.mcp.tests.tools.md;

import static org.junit.Assert.*;

import com._1c.g5.v8.dt.metadata.mdclass.ExchangePlan;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import java.lang.reflect.Method;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectFactory;

/**
 * Kind-дефолты {@code MdObjectFactory.applyKindDefaults}: live-smoke 1.20.0 показал, что
 * ExchangePlan без {@code thisNode} даёт error MdValidationChecker «Должна быть задана
 * сущность 'thisNode'» — фабрика обязана проставлять uuid узла «ЭтотУзел» сама.
 */
public class MdObjectFactoryDefaultsTest {

    private static void applyKindDefaults(EObject obj, String kind) throws Exception {
        Method m = MdObjectFactory.class.getDeclaredMethod(
                "applyKindDefaults", EObject.class, String.class);
        m.setAccessible(true);
        m.invoke(null, obj, kind);
    }

    @Test
    public void exchangePlan_getsThisNodeUuid() throws Exception {
        ExchangePlan ep = MdClassFactory.eINSTANCE.createExchangePlan();
        assertNull("свежий EMF-объект без thisNode", ep.getThisNode());
        applyKindDefaults(ep, "ExchangePlan");
        assertNotNull("thisNode обязан быть проставлен", ep.getThisNode());
    }
}
