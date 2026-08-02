package ru.fedukhin.edt.mcp.tools.client.internal;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Перечисление и запуск launch-конфигураций 1С-клиента через Eclipse Debug Framework —
 * тем же путём, что кнопка запуска/отладки в EDT ({@code RuntimeClientLaunchDelegate}
 * из {@code com._1c.g5.v8.dt.launching.core} сам строит аргументы запуска из атрибутов
 * конфигурации, включая учётные данные и версию платформы).
 *
 * <p>Ключи атрибутов — строковые литералы: это формат хранения {@code .launch}-файлов,
 * который стабильнее compile-зависимости от {@code launching.core} в условиях
 * dual-version (EDT 2023.x / 2026.x). Значения сверены с
 * {@code ILaunchConfigurationAttributes} ветки 2026.x.
 */
public class LaunchConfigService {

    /** {@code ILaunchConfigurationTypes.RUNTIME_CLIENT} — тип «Клиент 1С:Предприятия». */
    public static final String RUNTIME_CLIENT_TYPE_ID = "com._1c.g5.v8.dt.launching.core.RuntimeClient";

    public static final String ATTR_PROJECT_NAME =
        "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME";
    public static final String ATTR_APPLICATION_ID =
        "com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID";
    public static final String ATTR_RUNTIME_INSTALLATION =
        "com._1c.g5.v8.dt.debug.core.ATTR_RUNTIME_INSTALLATION";
    public static final String ATTR_CLIENT_TYPE =
        "com._1c.g5.v8.dt.launching.core.ATTR_CLIENT_TYPE";
    public static final String ATTR_CLIENT_AUTO_SELECT =
        "com._1c.g5.v8.dt.launching.core.ATTR_CLIENT_AUTO_SELECT";
    public static final String ATTR_LAUNCH_USER_NAME =
        "com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_NAME";
    public static final String ATTR_LAUNCH_USER_PASSWORD =
        "com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_PASSWORD";
    public static final String ATTR_OS_INFOBASE_ACCESS =
        "com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_OS_INFOBASE_ACCESS";

    private final ILaunchManager launchManager;

    @Inject
    public LaunchConfigService(ILaunchManager launchManager) {
        this.launchManager = launchManager;
    }

    /** Все конфигурации типа {@link #RUNTIME_CLIENT_TYPE_ID} из workspace. */
    public List<ILaunchConfiguration> list() throws ToolException {
        ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(RUNTIME_CLIENT_TYPE_ID);
        if (type == null) {
            throw new ToolException("launch configuration type '" + RUNTIME_CLIENT_TYPE_ID
                + "' is not registered — EDT launching bundle is missing?");
        }
        try {
            return List.of(launchManager.getLaunchConfigurations(type));
        } catch (CoreException e) {
            throw new ToolException("cannot read launch configurations: " + e.getMessage(), e);
        }
    }

    /** Конфигурация по точному имени; промах — ошибка со списком доступных имён. */
    public ILaunchConfiguration findByName(String name) throws ToolException {
        List<ILaunchConfiguration> all = list();
        for (ILaunchConfiguration cfg : all) {
            if (cfg.getName().equals(name)) {
                return cfg;
            }
        }
        List<String> names = new ArrayList<>();
        for (ILaunchConfiguration cfg : all) {
            names.add(cfg.getName());
        }
        throw new ToolException("launch configuration '" + name + "' not found; available: " + names);
    }

    /**
     * Запуск конфигурации с ожиданием завершения {@code launch()} не дольше
     * {@code timeoutSeconds}. Пустой результат — таймаут: запуск НЕ прерывается
     * (EDT может долго обновлять ИБ перед стартом клиента), он продолжается в фоне.
     */
    public Optional<ILaunch> launchWithTimeout(ILaunchConfiguration configuration, String mode,
                                               int timeoutSeconds) throws ToolException {
        CompletableFuture<ILaunch> future = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                future.complete(configuration.launch(mode, new NullProgressMonitor()));
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        }, "mcp-run-launch-configuration");
        worker.setDaemon(true);
        worker.start();
        try {
            return Optional.of(future.get(timeoutSeconds, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ToolException("launch of '" + configuration.getName() + "' failed: "
                + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("launch of '" + configuration.getName() + "' interrupted");
        }
    }
}
