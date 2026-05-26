package ru.fedukhin.edt.mcp.tools.testrun.internal;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmSingleNamespaceTask;
import com._1c.g5.v8.dt.metadata.mdclass.CommonModule;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import ru.fedukhin.edt.mcp.core.api.ToolException;
import ru.fedukhin.edt.mcp.tools.md.internal.BmPersistentExecutor;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectFactory;
import ru.fedukhin.edt.mcp.tools.md.internal.MdObjectLocator;
import ru.fedukhin.edt.mcp.tools.md.internal.ModuleFileBootstrap;
import ru.fedukhin.edt.mcp.tools.md.internal.PropertyAccessor;

/**
 * Реализация {@link TestRunnerInstaller.ModuleScaffolder} через BM Framework и Eclipse IProject.
 *
 * <p>Делегирует создание MdObject в {@link MdObjectFactory}, bootstrap пустого Module.bsl —
 * в {@link ModuleFileBootstrap}, установку флагов server/client — в {@link PropertyAccessor}.
 * Проверка существования ({@link #exists}) реализована через файловую систему (папка
 * {@code src/CommonModules/<Name>/}) — без BM-транзакции, что безопасно читать в любой момент.
 * Удаление ({@link #deleteModule}) удаляет папку CommonModule из файловой системы;
 * BM-индекс перестраивается при обновлении workspace в IDE.
 */
@Singleton
public class IdeModuleScaffolder implements TestRunnerInstaller.ModuleScaffolder {

    private final BmPersistentExecutor executor;
    private final MdObjectFactory  factory;
    private final ModuleFileBootstrap bootstrap;
    private final PropertyAccessor properties;
    private final MdObjectLocator  locator;

    @Inject
    public IdeModuleScaffolder(BmPersistentExecutor executor,
                                MdObjectFactory factory,
                                ModuleFileBootstrap bootstrap,
                                PropertyAccessor properties,
                                MdObjectLocator locator) {
        this.executor       = executor;
        this.factory        = factory;
        this.bootstrap      = bootstrap;
        this.properties     = properties;
        this.locator        = locator;
    }

    /**
     * Проверяет существование CommonModule по файловой системе.
     * fqn вида {@code "CommonModule.<Name>"} — извлекает имя после точки.
     */
    @Override
    public boolean exists(IProject project, String fqn) {
        String name = moduleNameFromFqn(fqn);
        if (name == null) return false;
        Path folder = commonModuleFolder(project, name);
        return Files.exists(folder);
    }

    /**
     * Task #10: проверяет наличие MdObject в BM-индексе (включая zombie от partial
     * install'ов до Fix B). Используется uninstall'ом, чтобы найти и почистить
     * остатки даже когда disk-folder уже отсутствует.
     */
    @Override
    public boolean existsInBm(IProject project, String fqn) {
        if (exists(project, fqn)) return true;
        boolean[] found = new boolean[1];
        executor.execute(project, "MCP check zombie " + fqn,
                (IBmSingleNamespaceTask<Void>) txn -> {
            found[0] = txn.getTopObjectByFqn(fqn) != null;
            return null;
        });
        return found[0];
    }

    @Override
    public void createClientModule(IProject project, String name) throws ToolException {
        createModuleWithRollback(project, name, /*server*/ false, /*client*/ true,
                BslRunnerTemplates.clientModuleBody());
    }

    @Override
    public void createServerModule(IProject project, String name) throws ToolException {
        createModuleWithRollback(project, name, /*server*/ true, /*client*/ false,
                BslRunnerTemplates.serverModuleBody());
        // Серверный модуль должен быть виден клиенту: без serverCall=true
        // тонкий клиент при старте падает «Переменная не определена
        // (EDT_MCP_TestRunner_Сервер)». Чиним отдельным шагом после
        // setFlags, чтобы PropertyAccessor.set успел распарсить FQN.
        setServerCall(project, "CommonModule." + name);
    }

    /**
     * Task #10 fix: atomic create — если любой шаг после {@code factory.create}
     * упадёт, откатываем BM-MdObject через {@link #deleteModule}. Без этого
     * partial install оставлял zombie BM-объект, и
     * {@code uninstall_test_runner}/{@code list_md_objects} его не видели
     * (потому что disk-folder отсутствовал), но повторный {@code install}
     * падал «name already exists». См. stage-8c-status §«Pre-existing bug».
     */
    private void createModuleWithRollback(IProject project, String name,
                                          boolean server, boolean client,
                                          String body) throws ToolException {
        String fqn = factory.create(project, "CommonModule", name, null, null);
        try {
            String modulePath = bootstrap.ensureModuleBsl(project, fqn);
            writeBody(project, modulePath, body);
            setFlags(project, fqn, server, client);
        } catch (ToolException | RuntimeException e) {
            try {
                deleteModule(project, fqn);
            } catch (Throwable rollbackErr) {
                // Best-effort rollback; surface ORIGINAL error wrapped с подсказкой про cleanup.
                throw new ToolException("create CommonModule '" + name + "' failed: "
                        + e.getMessage() + "; rollback also failed: "
                        + rollbackErr.getMessage());
            }
            throw e instanceof ToolException te ? te
                    : new ToolException("create CommonModule '" + name + "' failed: " + e.getMessage());
        }
    }

    /**
     * Удаляет CommonModule: detach из BM (через глобальный контекст — auto-save
     * .mdo) + удаление папки CommonModule с диска. Без detach из BM
     * {@link MdObjectFactory#create} при последующем install_test_runner
     * получал бы «name already exists» от BM-индекса при том, что .mdo на
     * диске не было — старая bug Stage 7b Task #9.
     *
     * <p>Идемпотентен: если top-object нет в BM — просто чистит файлы;
     * если файлов нет — просто чистит BM.
     */
    @Override
    public void deleteModule(IProject project, String fqn) throws ToolException {
        String name = moduleNameFromFqn(fqn);
        if (name == null) return;

        Throwable[] err = new Throwable[1];
        executor.execute(project, "MCP delete " + fqn, (IBmSingleNamespaceTask<Void>) txn -> {
            try {
                IBmObject obj = txn.getTopObjectByFqn(fqn);
                if (obj == null) return null;
                // Убираем из Configuration.commonModules чтобы парент тоже
                // re-serialized без FQN-ссылки на удалённый модуль. Live smoke
                // 2026-05-17 показал: iterator.remove() на этой EList silent
                // no-op (под-капотом — BM proxy view); прямой remove(EObject)
                // должен работать т.к. EList API явно мутирующий.
                IBmObject cfgObj = txn.getTopObjectByFqn("Configuration");
                if (cfgObj instanceof Configuration cfg && obj instanceof CommonModule cm) {
                    cfg.getCommonModules().remove(cm);
                }
                txn.detachTopObject(obj);
            } catch (Throwable t) {
                err[0] = t;
            }
            return null;
        });
        if (err[0] instanceof ToolException te) throw te;
        if (err[0] != null) {
            throw new ToolException("BM detach '" + fqn + "' failed: " + err[0].getMessage(),
                    err[0] instanceof Exception ex ? ex : new RuntimeException(err[0]));
        }

        Path folder = commonModuleFolder(project, name);
        if (!Files.exists(folder)) return;
        try (Stream<Path> stream = Files.walk(folder)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> {
                      try { Files.delete(p); } catch (IOException ignored) { /* best-effort */ }
                  });
        } catch (IOException e) {
            throw new ToolException("delete CommonModule '" + name + "' failed: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------

    private void writeBody(IProject project, String modulePath, String body) throws ToolException {
        Path absolute = Path.of(project.getLocation().toOSString()).resolve(modulePath);
        try {
            Files.createDirectories(absolute.getParent());
            Files.writeString(absolute, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("write module body '" + modulePath + "' failed: " + e.getMessage(), e);
        }
    }

    private void setFlags(IProject project, String fqn,
                          boolean server, boolean client) throws ToolException {
        Throwable[] err = new Throwable[1];
        executor.execute(project, "MCP set flags " + fqn, (IBmSingleNamespaceTask<Void>) txn -> {
            try {
                IBmObject bmObj = locator.findTop(txn, fqn, project.getName());
                EObject eobj = (EObject) bmObj;
                properties.set(eobj, "CommonModule", project, "server", server);
                properties.set(eobj, "CommonModule", project, "client", client);
            } catch (ToolException te) {
                err[0] = te;
            }
            return null;
        });
        if (err[0] instanceof ToolException te) throw te;
    }

    private void setServerCall(IProject project, String fqn) throws ToolException {
        Throwable[] err = new Throwable[1];
        executor.execute(project, "MCP set serverCall " + fqn, (IBmSingleNamespaceTask<Void>) txn -> {
            try {
                IBmObject bmObj = locator.findTop(txn, fqn, project.getName());
                EObject eobj = (EObject) bmObj;
                // Whitelisted property name is "serverCallsAllowed" (PropertyAccessor maps
                // it to CommonModule.setServerCall) — "serverCall" is rejected by the whitelist.
                properties.set(eobj, "CommonModule", project, "serverCallsAllowed", true);
            } catch (ToolException te) {
                err[0] = te;
            }
            return null;
        });
        if (err[0] instanceof ToolException te) throw te;
    }

    private static String moduleNameFromFqn(String fqn) {
        if (fqn == null) return null;
        int dot = fqn.indexOf('.');
        if (dot < 0 || dot == fqn.length() - 1) return null;
        return fqn.substring(dot + 1);
    }

    private static Path commonModuleFolder(IProject project, String name) {
        return Path.of(project.getLocation().toOSString(), "src", "CommonModules", name);
    }
}
