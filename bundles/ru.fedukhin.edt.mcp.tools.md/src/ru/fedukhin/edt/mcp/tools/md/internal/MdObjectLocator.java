package ru.fedukhin.edt.mcp.tools.md.internal;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import jakarta.inject.Inject;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Резолверы FQN/имени в активной BM-транзакции.
 *
 * Spike 6 amendment: {@link IBmTransaction#getTopObjectByFqn(String)} takes only FQN
 * (no namespace parameter — wiki was wrong about a 2-arg variant). Transaction is
 * already namespace-scoped via the project passed to {@code executeReadWriteTask}.
 *
 * Все методы stateless; биндится как обычный non-singleton.
 */
public final class MdObjectLocator {

    @Inject public MdObjectLocator() {}

    /**
     * Найти top-object по FQN в текущей транзакции. Бросает {@link ToolException},
     * если не найден.
     */
    public IBmObject findTop(IBmTransaction txn, String fqn, String projectName)
            throws ToolException {
        IBmObject obj = txn.getTopObjectByFqn(fqn);
        if (obj == null) {
            throw new ToolException("md object '" + fqn + "' not found in project '" + projectName + "'");
        }
        return obj;
    }

    /**
     * Найти вложенный EObject в EList parent'а по getName(). Бросает {@link ToolException},
     * если совпадения нет.
     */
    public <T> T findInList(Iterable<T> list, String name) throws ToolException {
        for (T it : list) {
            if (it instanceof EObject) {
                Object n = invokeGetName((EObject) it);
                if (name.equals(n)) return it;
            }
        }
        throw new ToolException("element '" + name + "' not found in collection");
    }

    private static Object invokeGetName(EObject obj) {
        try {
            return obj.getClass().getMethod("getName").invoke(obj);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("expected getName() on " + obj.getClass(), e);
        }
    }
}
