package ru.fedukhin.edt.mcp.tools.form.internal;

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
 *
 * Copied verbatim from tools.md.internal.MdObjectLocator — package changed only.
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

    /**
     * Найти форму по FQN. Stage 6 fix (2026-05-17): формы — inline-nested
     * в parent .mdo, не top-objects в BM. {@code txn.getTopObjectByFqn(formFqn)}
     * для {@code Kind.Name.Form.FormName} всегда возвращает null. Корректный путь —
     * найти parent как top-object, затем walk {@code parent.getForms()}.
     *
     * <p>Поддерживает два варианта:
     * <ul>
     *   <li>{@code Kind.Name.Form.FormName} — Catalog/Document/InformationRegister/
     *       AccumulationRegister/DataProcessor/Report — parent traversal</li>
     *   <li>{@code CommonForm.Name} — top-object lookup (общие формы хранятся
     *       как top-objects)</li>
     * </ul>
     *
     * @return {@link IBmObject} формы (BasicForm subclass — CatalogForm/DocumentForm/…
     *         или CommonForm). Бросает {@link ToolException}, если parent/form не найден
     *         или FQN не соответствует ожидаемому формату.
     */
    public IBmObject findForm(IBmTransaction txn, String fqn, String projectName)
            throws ToolException {
        if (fqn.startsWith("CommonForm.")) {
            return findTop(txn, fqn, projectName);
        }
        String[] parts = fqn.split("\\.");
        if (parts.length != 4 || !"Form".equals(parts[2])) {
            throw new ToolException("form fqn must be '<Kind>.<Name>.Form.<FormName>' "
                    + "or 'CommonForm.<Name>': '" + fqn + "'");
        }
        String parentFqn = parts[0] + "." + parts[1];
        String formName  = parts[3];
        IBmObject parent = findTop(txn, parentFqn, projectName);
        try {
            Object list = parent.getClass().getMethod("getForms").invoke(parent);
            if (list instanceof Iterable<?>) {
                for (Object f : (Iterable<?>) list) {
                    if (f instanceof EObject) {
                        Object n = ((EObject) f).getClass().getMethod("getName").invoke(f);
                        if (formName.equals(n) && f instanceof IBmObject) {
                            return (IBmObject) f;
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new ToolException("failed to traverse getForms() on '" + parentFqn
                    + "': " + e.getMessage());
        }
        throw new ToolException("form '" + formName + "' not found under '" + parentFqn
                + "' in project '" + projectName + "'");
    }

    private static Object invokeGetName(EObject obj) {
        try {
            return obj.getClass().getMethod("getName").invoke(obj);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("expected getName() on " + obj.getClass(), e);
        }
    }
}
