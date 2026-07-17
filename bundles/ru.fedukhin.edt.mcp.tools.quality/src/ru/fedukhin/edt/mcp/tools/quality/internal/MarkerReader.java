package ru.fedukhin.edt.mcp.tools.quality.internal;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerReader;
import jakarta.inject.Inject;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Читает EDT-маркеры движка проверок и преобразует их в MCP-DTO {@link CheckMarker}.
 *
 * <p>NB: это НЕ Eclipse {@code IMarker} — EDT использует проприетарное BM-binary хранилище маркеров
 * (Spike 2). Доступ выполняется через
 * {@link IMarkerManagerV2#createReader(java.util.Collection)} →
 * {@link IMarkerReader#markers(MarkerFilter...)}.
 *
 * <p>Spike 2 §126: ключ строки в {@link Marker#getExtraInfo()} — {@code "line"}
 * (см. {@link StandardExtraInfo#TEXT_LINE}); карта типизирована как
 * {@link IExtraInfoMap} extends {@code Map<String,String>}, поэтому числовое значение приходит
 * строкой и парсится явно.
 *
 * <p>Фильтры серьёзности / списка checkIds / источника применяются после чтения, потому что
 * пост-преобразование делается над небольшим потоком; фильтр scope по проекту выполняет уже сам
 * reader (Spike 2 §90 {@code MarkerIndex.CHECK_ID}).
 */
public class MarkerReader {

    private final IMarkerManagerV2 markerManager;
    private final CheckCatalog     catalog;
    private final IResourceLookup  resourceLookup;

    @Inject
    public MarkerReader(IMarkerManagerV2 markerManager, CheckCatalog catalog,
                        IResourceLookup resourceLookup) {
        this.markerManager   = markerManager;
        this.catalog         = catalog;
        this.resourceLookup  = resourceLookup;
    }

    /**
     * Читает маркеры для {@code project}, опционально фильтруя их по пути файла,
     * серьёзности, множеству {@code checkIds} (полная форма {@code CheckUid}) и метке источника.
     *
     * @param project   целевой {@link IProject} (обязателен)
     * @param path      опциональный префикс project-relative пути ({@code "src/CommonModules"},
     *                  {@code "src/Catalogs/Товары/Форма/Форма.form"}); маркеры, не резолвящиеся
     *                  в файл, фильтр не проходят
     * @param severity  опциональный фильтр серьёзности ("error" / "warning" / "info")
     * @param checkIds  опциональное множество полных идентификаторов проверок ({@code CheckUid.toString()});
     *                  {@code null} или пустое — без фильтра
     * @param source    опциональный фильтр источника ("v8codestyle" / "dt.check" / "edt" / "other")
     * @return неизменяемая упорядоченная коллекция DTO маркеров, прошедших фильтры
     */
    public List<CheckMarker> read(IProject project,
                                  String path,
                                  String severity,
                                  Set<String> checkIds,
                                  String source) {
        String pathPrefix = normalizePath(path);
        IMarkerReader reader = markerManager.createReader(Collections.singleton(project));
        List<CheckMarker> hits = new ArrayList<>();
        reader.markers(new MarkerFilter[0]).forEach(m -> {
            CheckMarker dto = toDto(m, project.getName());
            if (pathPrefix != null && !dto.path().regionMatches(true, 0, pathPrefix, 0, pathPrefix.length())) return;
            if (severity != null && !severity.equals(dto.severity())) return;
            if (checkIds != null && !checkIds.isEmpty() && !checkIds.contains(dto.checkId())) return;
            if (source   != null && !source  .equals(dto.source()))   return;
            hits.add(dto);
        });
        return hits;
    }

    /**
     * Приводит клиентский {@code path} к форме project-relative пути EDT: разделители «/»,
     * без ведущего слэша. {@code null} / пустая строка — фильтра нет.
     */
    private static String normalizePath(String path) {
        if (path == null) return null;
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("/")) p = p.substring(1);
        return p.isEmpty() ? null : p;
    }

    /**
     * Реальный project-relative путь файла маркера.
     *
     * <p>EDT-маркер — не Eclipse {@code IMarker}, у него нет {@code getResource()}: он привязан
     * к объекту модели, а не к ресурсу. Путь получается в два шага —
     * {@link Marker#provideObject(java.util.function.Function)} резолвит EObject проверяемого
     * объекта, {@link IResourceLookup#getPlatformResource(org.eclipse.emf.ecore.EObject)} —
     * его {@link IFile}. Маркеры уровня конфигурации и маркеры на удалённых объектах в файл
     * не резолвятся — для них путь пуст.
     */
    private String resolvePath(Marker m) {
        if (resourceLookup == null) return "";
        try {
            IFile file = m.provideObject(
                    eObject -> eObject == null ? null : resourceLookup.getPlatformResource(eObject));
            if (file == null) return "";
            IPath rel = file.getProjectRelativePath();
            return rel == null ? "" : rel.toString();
        } catch (RuntimeException e) {
            // Резолв идёт через BM: объект мог исчезнуть между чтением маркера и резолвом.
            // Путь — вспомогательное поле, ронять из-за него весь check_list_markers нельзя.
            return "";
        }
    }

    private CheckMarker toDto(Marker m, String projectName) {
        String sourceType = m.getSourceType();
        Optional<CheckEntry> entry = catalog.get(sourceType);
        String source = entry.map(CheckEntry::source).orElse(CheckSource.OTHER);

        int line = extractLine(m);

        String severity = IssueSeverityName.fromEdtName(
                m.getSeverity() == null ? null : m.getSeverity().name());

        String message = m.getMessage() == null ? "" : m.getMessage();

        return new CheckMarker(
                m.getMarkerId(),
                projectName,
                resolvePath(m),
                line,
                severity,
                sourceType,
                source,
                message,
                null /* issueType — Stage 4+ */);
    }

    /**
     * Достаёт номер строки из {@link Marker#getExtraInfo()} по ключу
     * {@link StandardExtraInfo#TEXT_LINE} ({@code "line"}).
     * {@link IExtraInfoMap} наследует {@code Map<String,String>}, поэтому значение приходит
     * строкой; нечисловое или отсутствующее значение даёт 0.
     */
    private static int extractLine(Marker m) {
        IExtraInfoMap extra = m.getExtraInfo();
        if (extra == null) return 0;
        String raw = extra.get(StandardExtraInfo.TEXT_LINE.getKey());
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
