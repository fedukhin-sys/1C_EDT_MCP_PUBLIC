package ru.fedukhin.edt.mcp.tools.quality.internal;

import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckCategory;
import com.e1c.g5.v8.dt.check.settings.ICheckDescription;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * In-memory снимок всех проверок, зарегистрированных в {@link ICheckRepository}, обновляемый
 * лениво при первом обращении и кэшируемый на весь сеанс IDE (каталог изменяется только при
 * установке/удалении плагинов, что влечёт перезапуск IDE).
 *
 * <p>Атрибуция источника ({@code CheckEntry.source}) выводится из символьного имени бандла,
 * вносящего проверку, через сим {@code bundleResolver}.
 * Атрибуция категории ({@code CheckEntry.category}) — заголовок непосредственного родительского
 * {@code ICheckCategory}, разрешаемый через {@code categoryResolver}. В продакшне resolver
 * вызывает {@link ICheckRepository#getDescription(CheckUid)} по {@link ICheckDescription#getParentId()}
 * и берёт {@code getTitle()} результата. В тестах подставляется заглушка.
 */
@Singleton
public class CheckCatalog {

    private final Provider<ICheckRepository> repository;
    private final Function<String, String>   bundleResolver;
    private final Function<CheckUid, String> categoryResolver;

    private volatile List<CheckEntry>        snapshot;
    private volatile Map<String, CheckEntry> byId;

    /**
     * Продакшн-конструктор: bundle- и category-resolver по умолчанию.
     * category-resolver разрешает родительскую {@link ICheckCategory} через живой репозиторий
     * (см. {@link #repoCategoryResolver(ICheckRepository)}).
     */
    @Inject
    public CheckCatalog(Provider<ICheckRepository> repository) {
        this(repository,
             extensionRegistryBundleResolver(Platform.getExtensionRegistry()),
             repoCategoryResolver(repository.get()));
    }

    /**
     * Полный конструктор для тестов.
     *
     * @param repository       поставщик {@link ICheckRepository}
     * @param bundleResolver   функция checkId → символьное имя бандла-контрибьютора
     * @param categoryResolver функция checkUid родительской категории → заголовок категории (может вернуть null)
     */
    public CheckCatalog(Provider<ICheckRepository> repository,
                        Function<String, String> bundleResolver,
                        Function<CheckUid, String> categoryResolver) {
        this.repository       = repository;
        this.bundleResolver   = bundleResolver;
        this.categoryResolver = categoryResolver;
    }

    /**
     * Возвращает список проверок, удовлетворяющих всем переданным фильтрам.
     *
     * @param filter   подстрока (case-insensitive) для поиска по заголовку и описанию; {@code null} — без фильтра
     * @param severity точное совпадение по полю severity ("error"|"warning"|"info"); {@code null} — без фильтра
     * @param source   точное совпадение по полю source ("v8codestyle"|"dt.check"|"edt"|"other"); {@code null} — без фильтра
     * @return неизменяемый список совпадений (копия фильтрации снимка)
     */
    public List<CheckEntry> list(String filter, String severity, String source) {
        ensureSnapshot();
        String f = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        List<CheckEntry> hits = new ArrayList<>();
        for (CheckEntry e : snapshot) {
            if (f != null) {
                String t = e.title()       == null ? "" : e.title().toLowerCase(Locale.ROOT);
                String d = e.description() == null ? "" : e.description().toLowerCase(Locale.ROOT);
                if (!t.contains(f) && !d.contains(f)) {
                    continue;
                }
            }
            if (severity != null && !severity.equals(e.severity())) {
                continue;
            }
            if (source != null && !source.equals(e.source())) {
                continue;
            }
            hits.add(e);
        }
        return hits;
    }

    /**
     * Ищет проверку по её идентификатору ({@code CheckUid.toString()}).
     *
     * @param checkId идентификатор проверки
     * @return {@code Optional} с записью или пустой {@code Optional}
     */
    public Optional<CheckEntry> get(String checkId) {
        ensureSnapshot();
        return Optional.ofNullable(byId.get(checkId));
    }

    private void ensureSnapshot() {
        if (snapshot != null) {
            return;
        }
        synchronized (this) {
            if (snapshot != null) {
                return;
            }
            List<CheckEntry>        local      = new ArrayList<>();
            Map<String, CheckEntry> byIdLocal  = new HashMap<>();
            ICheckRepository repo = repository.get();
            for (Map.Entry<CheckUid, ICheckDescription> entry : repo.getChecksWithDescriptions().entrySet()) {
                ICheckDescription d           = entry.getValue();
                CheckUid          uid         = entry.getKey();
                String            id          = uid.toString();
                String            contributor = uid.getContributorId();
                String            sev         = IssueSeverityName.fromEdt(d.getSeverity());
                // Source classification: try the extension-registry resolver first (keyed by FQN
                // class name from Marker.getSourceType()); fall back to CheckUid.getContributorId,
                // which IS the contributing bundle's symbolic name. Live smoke 2026-05-15 found
                // the resolver doesn't index by CheckUid.toString(), so the contributorId fallback
                // is what classifies the 200+ in-repo checks.
                String            resolved    = bundleResolver.apply(id);
                String            bundle      = (resolved == null || resolved.isBlank()) ? contributor : resolved;
                String            src         = CheckSource.fromBundle(bundle);
                String            cat         = categoryResolver.apply(d.getParentId());
                CheckEntry        ce          = new CheckEntry(id, d.getTitle(), d.getDescription(),
                        sev, src, cat, d.isEnabled());
                local.add(ce);
                byIdLocal.put(id, ce);
            }
            // Use Collections.unmodifiableMap to tolerate null values (category can be null).
            this.snapshot = Collections.unmodifiableList(local);
            this.byId     = Collections.unmodifiableMap(byIdLocal);
        }
    }

    /**
     * Production category resolver: looks up the parent description in the live repository
     * and returns its title iff it is an {@link ICheckCategory}.
     */
    public static Function<CheckUid, String> repoCategoryResolver(ICheckRepository repository) {
        return parentId -> {
            if (parentId == null) return null;
            try {
                var parent = repository.getDescription(parentId);
                return parent instanceof ICheckCategory cat ? cat.getTitle() : null;
            } catch (RuntimeException e) {
                // ICheckRepository.getDescription throws "Check with id '...' not found" for
                // unknown UIDs (live smoke 2026-05-15) — treat as "no category" rather than
                // failing the entire catalog snapshot.
                return null;
            }
        };
    }

    private static final String CHECK_EXTENSION_POINT = "com.e1c.g5.v8.dt.check.checks";

    /**
     * Production bundle resolver: walks {@link IExtensionRegistry} for the {@code
     * com.e1c.g5.v8.dt.check.checks} extension point and indexes contributing-bundle
     * symbolic names by the FQN of the contributed check class.
     *
     * <p>The {@code class} attribute on a {@code <check>} element has two shapes:
     * <ul>
     *   <li>{@code "factory.Class:target.Class"} — the half after {@code :} is the check FQN;</li>
     *   <li>{@code "target.Class"} — single FQN.</li>
     * </ul>
     *
     * <p>Lookup is by the FQN form returned by {@code Marker.getSourceType()}. Unknown ids
     * resolve to {@code ""} (→ {@link CheckSource#OTHER}).
     */
    public static Function<String, String> extensionRegistryBundleResolver(IExtensionRegistry registry) {
        Map<String, String> byClass = new HashMap<>();
        IExtensionPoint xp = registry.getExtensionPoint(CHECK_EXTENSION_POINT);
        if (xp != null) {
            for (IExtension ext : xp.getExtensions()) {
                String bundle = ext.getContributor() == null ? "" : ext.getContributor().getName();
                if (bundle == null || bundle.isBlank()) continue;
                for (IConfigurationElement el : ext.getConfigurationElements()) {
                    String classAttr = el.getAttribute("class");
                    if (classAttr == null || classAttr.isBlank()) continue;
                    int colon = classAttr.indexOf(':');
                    String fqn = colon >= 0 ? classAttr.substring(colon + 1) : classAttr;
                    byClass.put(fqn, bundle);
                }
            }
        }
        Map<String, String> snapshot = Map.copyOf(byClass);
        return id -> id == null ? "" : snapshot.getOrDefault(id, "");
    }
}
