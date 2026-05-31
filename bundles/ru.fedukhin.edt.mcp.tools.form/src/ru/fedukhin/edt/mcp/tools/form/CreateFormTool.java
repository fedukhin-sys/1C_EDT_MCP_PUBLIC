package ru.fedukhin.edt.mcp.tools.form;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.FormType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.form.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;

/**
 * {@code create_form} — создаёт пустую форму под parent MdObject (Stage 8a).
 *
 * <p>Args: {@code { project, parentFqn, name, formType? }}
 * <p>Result: {@code { fqn, parentFqn, name, formType }}
 *
 * <p>Stage 8a v1: создаётся только BasicForm-metadata (.mdo). Inner UI
 * {@link com._1c.g5.v8.dt.form.model.Form} (с item-tree, attributes, commands,
 * handlers) живёт в отдельном EMF Resource (.form) — это не containment,
 * а cross-reference. Создание Form через {@code FormFactory.createForm()} +
 * {@code basicForm.setForm(form)} в одной BM-транзакции падает с
 * «Failed to persist reference value FormImpl» — orphan EObject не может
 * быть сериализован как cross-ref. Stage 8a оставляет {@code basicForm.form}
 * пустым; EDT-редактор формы лениво создаёт inner Form при первом открытии.
 *
 * <p>Layout EDT автогенерирует на основе attributes родительского MdObject
 * (для управляемой формы). Дальнейшие add_form_attribute / add_form_item /
 * add_form_command — Stages 8b/c (нужен отдельный mechanism для записи
 * в .form Resource).
 *
 * <p>Mech: {@code MdClassFactory.create<Kind>Form()} → kind-specific
 * {@link com._1c.g5.v8.dt.metadata.mdclass.BasicForm};
 * {@code parent.getForms().add(basicForm)} вкладывает в parent;
 * {@code txn.attachTopObject(basicForm, fqn)} регистрирует top-object.
 * {@code BmPersistentExecutor} обеспечивает persistence в .mdo через
 * global editing context auto-save.
 */
public final class CreateFormTool implements IMcpTool {

    /** Parent kinds для которых поддерживается create_form в Stage 8a. */
    private static final Set<String> SUPPORTED_KINDS = Set.of(
            "Catalog", "Document", "DataProcessor", "Report",
            "InformationRegister", "AccumulationRegister"
    );

    /**
     * Mapping parent-kind → form extInfo type (used in Form.form root XML).
     * Без extInfo EDT-редактор зависает на «Загрузка...» (Stage 8a v2 live smoke
     * 2026-05-17 показал). Конкретные FormExtInfo субтайпы — из
     * {@code com._1c.g5.v8.dt.form.model.FormFactory}.
     */
    private static final Map<String, String> EXT_INFO_TYPE = Map.of(
            "Catalog",              "form:CatalogFormExtInfo",
            "Document",             "form:DocumentFormExtInfo",
            "Report",               "form:ReportFormExtInfo",
            "DataProcessor",        "form:ObjectFormExtInfo",
            "InformationRegister",  "form:InformationRegisterManagerFormExtInfo",
            "AccumulationRegister", "form:RecordSetFormExtInfo"
    );

    /**
     * Mapping parent-kind → main "Объект" attribute type, без которого форма не привязана
     * к редактируемому объекту. На любой форме (кроме общих CommonForm) должен быть атрибут
     * с именем "Объект" и type-выражением соответствующим parent kind.
     */
    private static final Map<String, String> OBJECT_TYPE = Map.of(
            "Catalog",              "CatalogObject",
            "Document",             "DocumentObject",
            "Report",               "ReportObject",
            "DataProcessor",        "DataProcessorObject",
            "InformationRegister",  "InformationRegisterRecord",
            "AccumulationRegister", "AccumulationRegisterRecord"
    );

    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final BmPersistentExecutor     executor;
    private final MdObjectLocator          locator;
    private final IBmModelManager          bmModelManager;

    @Inject
    public CreateFormTool(BmPersistentExecutor executor, MdObjectLocator locator,
                          IBmModelManager bmModelManager) {
        this(() -> ResourcesPlugin.getWorkspace().getRoot(), executor, locator, bmModelManager);
    }

    /** Test seam. */
    public CreateFormTool(Supplier<IWorkspaceRoot> rootSupplier,
                          BmPersistentExecutor executor, MdObjectLocator locator,
                          IBmModelManager bmModelManager) {
        this.rootSupplier   = rootSupplier;
        this.executor       = executor;
        this.locator        = locator;
        this.bmModelManager = bmModelManager;
    }

    @Override public String name()        { return "create_form"; }
    @Override public String description() {
        return "Create an empty managed form under a parent MdObject (Catalog/Document/Report/DataProcessor/...). "
             + "Layout autogenerates on first open in EDT editor.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> strType = Map.of("type", "string");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("project",   strType);
        props.put("parentFqn", strType);
        props.put("name",      strType);
        props.put("formType",  Map.of("type", "string", "enum", List.of("MANAGED", "ORDINARY")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("project", "parentFqn", "name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public Object call(Map<String, Object> args) throws ToolException {
        String projectName = requireString(args, "project");
        String parentFqn   = requireString(args, "parentFqn");
        String name        = requireString(args, "name");
        String formTypeArg = args.get("formType") instanceof String s ? s : "MANAGED";

        int dot = parentFqn.indexOf('.');
        if (dot <= 0 || dot == parentFqn.length() - 1) {
            throw new ToolException("parentFqn '" + parentFqn + "' must be in form Kind.Name");
        }
        String parentKind = parentFqn.substring(0, dot);
        if (!SUPPORTED_KINDS.contains(parentKind)) {
            throw new ToolException("parent kind '" + parentKind
                    + "' is not supported for create_form (Stage 8a); supported: " + SUPPORTED_KINDS);
        }
        FormType formType = "ORDINARY".equalsIgnoreCase(formTypeArg) ? FormType.ORDINARY : FormType.MANAGED;

        IProject project = rootSupplier.get().getProject(projectName);
        if (project == null || !project.exists() || !project.isOpen()) {
            throw new ToolException("project '" + projectName + "' not found or not open");
        }

        // Task #11/race-fix: дождаться, чтобы все предыдущие BM-задачи (например
        // только что закончившийся add_attribute) полностью попали в BM-индекс.
        // Без этого create_form сразу после add_attribute падает «failed: null»
        // (NPE из FormFactory) из-за того, что Configuration.catalogs ещё не
        // содержит свежесозданный/обновлённый MdObject (Stage 8c smoke 2026-05-18).
        if (bmModelManager != null) {
            try {
                bmModelManager.waitModelSynchronization(project);
            } catch (Throwable ignored) { /* best-effort */ }
        }

        String fqn = parentFqn + ".Form." + name;
        Throwable[] err = new Throwable[1];

        executor.execute(project, "MCP create_form " + fqn,
                (IBmSingleNamespaceTask<Void>) txn -> {
            try {
                IBmObject parentObj = locator.findTop(txn, parentFqn, projectName);

                // Step 1: parent's forms collection (Catalog/Document/... .getForms())
                @SuppressWarnings("unchecked")
                List<EObject> parentForms = (List<EObject>) invokeListGetter(parentObj, "getForms");

                // Duplicate check via parent traversal — Stage 8a live smoke 2026-05-17
                // показал, что формы ХРАНЯТСЯ как inline-nested в parent .mdo
                // (`<forms><name>X</name></forms>`), а не как top-objects в BM.
                // {@code txn.getTopObjectByFqn(fqn)} для form FQN всегда возвращает null,
                // и {@code txn.attachTopObject(...)} падает с «already attached» т.к.
                // BasicForm уже в parent EContainer'е после parentForms.add.
                for (EObject existing : parentForms) {
                    if (name.equals(invokeGetName(existing))) {
                        throw new ToolException("form '" + name + "' already exists under '"
                                + parentFqn + "'");
                    }
                }

                // Step 2: kind-specific BasicForm via MdClassFactory.create<Kind>Form()
                EObject basicForm = createBasicForm(parentKind);
                invokeSetName(basicForm, name);
                invokeSetter(basicForm, "setFormType", FormType.class, formType);
                // Каждому MdObject (включая BasicForm) нужен uuid — иначе при
                // deploy_project 1cv8 export падает «Ошибка записи XML
                // реквизита с именем "uuid" и значением "null"». EDT-UI
                // присваивает UUID авто; MdClassFactory.create* оставляет null
                // (live smoke 2026-05-17 на v1.5.2 показал).
                invokeSetter(basicForm, "setUuid", java.util.UUID.class,
                        java.util.UUID.randomUUID());

                // Step 3: add to parent's forms — это и есть полная attachment-семантика
                // для form'ы. Inner UI Form (.form file, item-tree) создаётся
                // EDT-редактором лениво при первом открытии.
                parentForms.add(basicForm);

                // BUG-13: register the new form as the owner's default form so
                // 1С actually opens it (and its handlers fire) instead of the
                // auto-generated one. Only when no default form is set yet —
                // a second create_form must not clobber the first.
                setDefaultFormIfUnset(parentObj, parentKind, basicForm);
            } catch (ToolException te) {
                err[0] = te;
            } catch (Throwable t) {
                err[0] = new ToolException("create_form '" + fqn + "' failed: " + t.getMessage());
            }
            return null;
        });

        if (err[0] instanceof ToolException te) throw te;

        // Step 4: write minimal Form.form. Без файла EDT-редактор формы зависает
        // на «Загрузка...» (live smoke 2026-05-17 на v1.4.2 показал). Layout
        // здесь содержит обязательный "Объект"-атрибут (kind-specific type)
        // плюс form-level properties + extInfo.
        String parentN = parentName(parentFqn);
        String formDir    = "src/" + parentKind + "s/" + parentN + "/Forms/" + name + "/";
        String formPath   = formDir + "Form.form";
        String modulePath = formDir + "Module.bsl";
        writeMinimalFormFile(project, formPath, parentKind, parentN);
        // BUG-05 fix: also create the form Module.bsl — EDT does not reliably
        // auto-create it for MCP-driven forms, and a bound handler needs a module
        // file to live in. Empty module is valid; body is written via write_module.
        writeEmptyModuleFile(project, modulePath);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fqn",        fqn);
        result.put("parentFqn",  parentFqn);
        result.put("name",       name);
        result.put("formType",   formType.getName());
        result.put("formPath",   formPath);
        result.put("modulePath", modulePath);
        return result;
    }

    /** Creates an empty form Module.bsl if it does not already exist (BUG-05). */
    private static void writeEmptyModuleFile(IProject project, String relPath) throws ToolException {
        IFile target = project.getFile(relPath);
        if (target.exists()) return;
        try {
            if (target.getParent() instanceof IFolder folder) {
                ensureFolder(folder);
            }
            target.create(new ByteArrayInputStream(new byte[0]), true, new NullProgressMonitor());
        } catch (CoreException e) {
            throw new ToolException("failed to write " + relPath + ": " + e.getMessage());
        }
    }

    private static String parentName(String parentFqn) {
        int dot = parentFqn.indexOf('.');
        return parentFqn.substring(dot + 1);
    }

    /**
     * Записывает минимальный Form.form с обязательным «Объект»-атрибутом и
     * правильным extInfo для kind. Без extInfo EDT-редактор зависает на
     * «Загрузка...» (Stage 8a v2). Без «Объекта» форма не привязана к
     * редактируемому объекту и для Catalog/Document/etc. не имеет смысла
     * (Stage 8a v4 feedback — «Объект должен быть на всех формах»).
     */
    private static void writeMinimalFormFile(IProject project, String relPath,
                                              String parentKind, String parentName)
                                              throws ToolException {
        String extInfo   = EXT_INFO_TYPE.get(parentKind);
        String objType   = OBJECT_TYPE.get(parentKind);
        if (extInfo == null || objType == null) {
            throw new ToolException("no FormExtInfo/ObjectType mapped for kind " + parentKind);
        }
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <form:Form xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:form="http://g5.1c.ru/v8/dt/form">
                  <autoCommandBar>
                    <name>ФормаКоманднаяПанель</name>
                    <id>-1</id>
                    <horizontalAlign>Left</horizontalAlign>
                    <autoFill>true</autoFill>
                  </autoCommandBar>
                  <windowOpeningMode>LockOwnerWindow</windowOpeningMode>
                  <autoTitle>true</autoTitle>
                  <autoUrl>true</autoUrl>
                  <group>Vertical</group>
                  <enabled>true</enabled>
                  <showTitle>true</showTitle>
                  <showCloseButton>true</showCloseButton>
                  <attributes>
                    <name>Объект</name>
                    <id>1</id>
                    <valueType>
                      <types>%s.%s</types>
                    </valueType>
                    <view>
                      <common>true</common>
                    </view>
                    <edit>
                      <common>true</common>
                    </edit>
                    <main>true</main>
                    <savedData>true</savedData>
                  </attributes>
                  <commandInterface>
                    <navigationPanel/>
                    <commandBar/>
                  </commandInterface>
                  <extInfo xsi:type="%s"/>
                </form:Form>
                """.formatted(objType, parentName, extInfo);
        IFile target = project.getFile(relPath);
        IFolder folder = (IFolder) target.getParent();
        try {
            ensureFolder(folder);
            if (target.exists()) {
                target.setContents(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                        true, true, new NullProgressMonitor());
            } else {
                target.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                        true, new NullProgressMonitor());
            }
        } catch (CoreException e) {
            throw new ToolException("failed to write " + relPath + ": " + e.getMessage());
        }
    }

    private static void ensureFolder(IFolder folder) throws CoreException {
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parent) {
            ensureFolder(parent);
        }
        folder.create(/*force*/ false, /*local*/ true, new NullProgressMonitor());
    }

    /**
     * BUG-13: sets the owner's default form to {@code basicForm} when the owner
     * has no default form yet. The property name is kind-specific; reflection
     * keeps this independent of the concrete mdclass interfaces. A second
     * create_form on the same owner does not override an existing default form.
     */
    private static void setDefaultFormIfUnset(Object parentObj, String parentKind, EObject basicForm) {
        String prop = switch (parentKind) {
            case "Catalog", "Document"     -> "DefaultObjectForm";
            case "DataProcessor", "Report" -> "DefaultForm";
            case "InformationRegister"     -> "DefaultRecordForm";
            case "AccumulationRegister"    -> "DefaultListForm";
            default                        -> null;
        };
        if (prop == null) {
            return;
        }
        try {
            Object current = parentObj.getClass().getMethod("get" + prop).invoke(parentObj);
            if (current != null) {
                return;   // owner already has a default form — do not override it
            }
        } catch (ReflectiveOperationException e) {
            return;       // kind has no such property — skip
        }
        for (java.lang.reflect.Method m : parentObj.getClass().getMethods()) {
            if (m.getName().equals("set" + prop) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(basicForm)) {
                try {
                    m.invoke(parentObj, basicForm);
                } catch (ReflectiveOperationException ignored) {
                    /* leave default form unset — non-fatal */
                }
                return;
            }
        }
    }

    private static EObject createBasicForm(String parentKind) throws ToolException {
        String methodName = "create" + parentKind + "Form";
        try {
            return (EObject) MdClassFactory.eINSTANCE.getClass()
                    .getMethod(methodName).invoke(MdClassFactory.eINSTANCE);
        } catch (ReflectiveOperationException e) {
            throw new ToolException("MdClassFactory has no " + methodName + "(): " + e.getMessage());
        }
    }

    private static void invokeSetName(EObject obj, String name) {
        try {
            obj.getClass().getMethod("setName", String.class).invoke(obj, name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("setName missing on " + obj.getClass(), e);
        }
    }

    private static <T> void invokeSetter(EObject obj, String methodName, Class<T> paramType, T value) {
        try {
            obj.getClass().getMethod(methodName, paramType).invoke(obj, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(methodName + " missing on " + obj.getClass(), e);
        }
    }

    private static Object invokeListGetter(Object obj, String accessor) {
        try {
            return obj.getClass().getMethod(accessor).invoke(obj);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(accessor + " missing on " + obj.getClass(), e);
        }
    }

    private static String invokeGetName(Object obj) {
        if (obj == null) return null;
        try {
            Object n = obj.getClass().getMethod("getName").invoke(obj);
            return n instanceof String ? (String) n : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String requireString(Map<String, Object> args, String key) throws ToolException {
        Object v = args.get(key);
        if (!(v instanceof String s) || s.isEmpty()) {
            throw new ToolException("'" + key + "' must be a non-empty string");
        }
        return (String) v;
    }
}
