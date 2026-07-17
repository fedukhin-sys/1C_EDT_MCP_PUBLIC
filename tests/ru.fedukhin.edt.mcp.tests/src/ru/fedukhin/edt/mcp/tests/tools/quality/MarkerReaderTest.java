package ru.fedukhin.edt.mcp.tests.tools.quality;

import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerManagerV2;
import com._1c.g5.v8.dt.validation.marker.v2.IMarkerReader;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckCatalog;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckEntry;
import ru.fedukhin.edt.mcp.tools.quality.internal.CheckMarker;
import ru.fedukhin.edt.mcp.tools.quality.internal.MarkerReader;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты {@link MarkerReader}: маппинг {@link Marker} → {@link CheckMarker} и фильтры
 * пост-обработки (severity / checkIds / source).
 *
 * <p>Spike 2: {@code Marker.getExtraInfo()} возвращает {@link IExtraInfoMap extends Map&lt;String,String&gt;},
 * поэтому строка "line" приходит как {@code String}; в тестах мокаем {@link IExtraInfoMap} и
 * стабим {@code get("line")} → {@code "42"}.
 */
public class MarkerReaderTest {

    @Test public void mapsMarkerToCheckMarkerDto() {
        IExtraInfoMap extra = mock(IExtraInfoMap.class);
        when(extra.get("line")).thenReturn("42");

        Marker m = mock(Marker.class);
        when(m.getMarkerId()).thenReturn("uuid-1");
        when(m.getSourceType()).thenReturn("com.e1c.v8codestyle.bsl.check.QueryInLoopCheck");
        when(m.getCheckId()).thenReturn("short-uid-1");
        when(m.getSeverity()).thenReturn(MarkerSeverity.MAJOR);
        when(m.getMessage()).thenReturn("Query in loop");
        when(m.getExtraInfo()).thenReturn(extra);

        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.get("com.e1c.v8codestyle.bsl.check.QueryInLoopCheck")).thenReturn(Optional.of(
                new CheckEntry("com.e1c.v8codestyle.bsl.check.QueryInLoopCheck", "Query in loop",
                               "", "error", "v8codestyle", "BSL", true)));

        IMarkerReader reader = mock(IMarkerReader.class);
        when(reader.markers(any(MarkerFilter[].class))).thenReturn(Stream.of(m));

        IMarkerManagerV2 mgr = mock(IMarkerManagerV2.class);
        when(mgr.createReader(anyCollection())).thenReturn(reader);

        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");

        MarkerReader markerReader = new MarkerReader(mgr, catalog, mock(IResourceLookup.class));

        List<CheckMarker> markers = markerReader.read(project, null, null, null, null);

        assertEquals(1, markers.size());
        CheckMarker cm = markers.get(0);
        assertEquals("uuid-1", cm.markerId());
        assertEquals("Demo", cm.project());
        assertEquals(42, cm.line());
        assertEquals("error", cm.severity());
        assertEquals("com.e1c.v8codestyle.bsl.check.QueryInLoopCheck", cm.checkId());
        assertEquals("v8codestyle", cm.source());
        assertEquals("Query in loop", cm.message());
    }

    @Test public void filtersBySeverityAfterReading() {
        Marker error = mock(Marker.class);
        when(error.getMarkerId()).thenReturn("e");
        when(error.getSourceType()).thenReturn("c.a");
        when(error.getCheckId()).thenReturn("s.a");
        when(error.getSeverity()).thenReturn(MarkerSeverity.MAJOR);
        when(error.getMessage()).thenReturn("");
        when(error.getExtraInfo()).thenReturn(mock(IExtraInfoMap.class));
        Marker warn = mock(Marker.class);
        when(warn.getMarkerId()).thenReturn("w");
        when(warn.getSourceType()).thenReturn("c.b");
        when(warn.getCheckId()).thenReturn("s.b");
        when(warn.getSeverity()).thenReturn(MarkerSeverity.MINOR);
        when(warn.getMessage()).thenReturn("");
        when(warn.getExtraInfo()).thenReturn(mock(IExtraInfoMap.class));

        IMarkerReader reader = mock(IMarkerReader.class);
        when(reader.markers(any(MarkerFilter[].class))).thenReturn(Stream.of(error, warn));
        IMarkerManagerV2 mgr = mock(IMarkerManagerV2.class);
        when(mgr.createReader(anyCollection())).thenReturn(reader);

        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.get(any())).thenReturn(Optional.empty());

        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");

        MarkerReader markerReader = new MarkerReader(mgr, catalog, mock(IResourceLookup.class));

        List<CheckMarker> errs = markerReader.read(project, null, "error", null, null);
        assertEquals(1, errs.size());
        assertEquals("e", errs.get(0).markerId());
    }

    @Test public void filtersByCheckIdSetAndSource() {
        Marker keep = mock(Marker.class);
        when(keep.getMarkerId()).thenReturn("k");
        when(keep.getSourceType()).thenReturn("com.e1c.v8codestyle.bsl.check.Wanted");
        when(keep.getCheckId()).thenReturn("s.k");
        when(keep.getSeverity()).thenReturn(MarkerSeverity.MAJOR);
        when(keep.getMessage()).thenReturn("");
        when(keep.getExtraInfo()).thenReturn(mock(IExtraInfoMap.class));
        Marker drop = mock(Marker.class);
        when(drop.getMarkerId()).thenReturn("d");
        when(drop.getSourceType()).thenReturn("com.e1c.v8codestyle.bsl.check.Unwanted");
        when(drop.getCheckId()).thenReturn("s.d");
        when(drop.getSeverity()).thenReturn(MarkerSeverity.MAJOR);
        when(drop.getMessage()).thenReturn("");
        when(drop.getExtraInfo()).thenReturn(mock(IExtraInfoMap.class));

        IMarkerReader reader = mock(IMarkerReader.class);
        when(reader.markers(any(MarkerFilter[].class))).thenReturn(Stream.of(keep, drop));
        IMarkerManagerV2 mgr = mock(IMarkerManagerV2.class);
        when(mgr.createReader(anyCollection())).thenReturn(reader);

        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.get("com.e1c.v8codestyle.bsl.check.Wanted")).thenReturn(Optional.of(
                new CheckEntry("com.e1c.v8codestyle.bsl.check.Wanted", "", "", "error",
                               "v8codestyle", null, true)));
        when(catalog.get("com.e1c.v8codestyle.bsl.check.Unwanted")).thenReturn(Optional.of(
                new CheckEntry("com.e1c.v8codestyle.bsl.check.Unwanted", "", "", "error",
                               "v8codestyle", null, true)));

        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");

        MarkerReader markerReader = new MarkerReader(mgr, catalog, mock(IResourceLookup.class));

        List<CheckMarker> hits = markerReader.read(
                project, null, null,
                java.util.Set.of("com.e1c.v8codestyle.bsl.check.Wanted"),
                "v8codestyle");
        assertEquals(1, hits.size());
        assertEquals("k", hits.get(0).markerId());
    }

    /**
     * Маркер, чей объект резолвится в {@code file} — как это делает EDT в проде:
     * {@code Marker.provideObject(Function)} отдаёт EObject проверяемого объекта,
     * {@code IResourceLookup.getPlatformResource(EObject)} — его {@link IFile}.
     */
    private static Marker markerAt(String id, String projectRelativePath, IResourceLookup lookup) {
        Marker m = mock(Marker.class);
        when(m.getMarkerId()).thenReturn(id);
        when(m.getSourceType()).thenReturn("c." + id);
        when(m.getCheckId()).thenReturn("s." + id);
        when(m.getSeverity()).thenReturn(MarkerSeverity.MAJOR);
        when(m.getMessage()).thenReturn("");
        when(m.getExtraInfo()).thenReturn(mock(IExtraInfoMap.class));

        EObject eObject = mock(EObject.class);
        IFile file = mock(IFile.class);
        when(file.getProjectRelativePath()).thenReturn(new org.eclipse.core.runtime.Path(projectRelativePath));
        when(lookup.getPlatformResource(eObject)).thenReturn(file);
        when(m.<IFile>provideObject(ArgumentMatchers.<Function<EObject, IFile>>any()))
                .thenAnswer(inv -> inv.<Function<EObject, IFile>>getArgument(0).apply(eObject));
        return m;
    }

    @Test public void dtoCarriesRealResourcePathOfMarker() {
        // До фикса поле path DTO было копией входного фильтра: без параметра — пустая строка,
        // то есть реального файла маркера в ответе не было вовсе.
        IResourceLookup lookup = mock(IResourceLookup.class);
        Marker m = markerAt("m1", "src/CommonModules/Расчёты/Module.bsl", lookup);

        IMarkerReader reader = mock(IMarkerReader.class);
        when(reader.markers(any(MarkerFilter[].class))).thenReturn(Stream.of(m));
        IMarkerManagerV2 mgr = mock(IMarkerManagerV2.class);
        when(mgr.createReader(anyCollection())).thenReturn(reader);
        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.get(any())).thenReturn(Optional.empty());
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");

        List<CheckMarker> hits = new MarkerReader(mgr, catalog, lookup)
                .read(project, null, null, null, null);

        assertEquals(1, hits.size());
        assertEquals("src/CommonModules/Расчёты/Module.bsl", hits.get(0).path());
    }

    @Test public void pathFiltersMarkersByResourcePathPrefix() {
        // path объявлен в inputSchema и в description как фильтр — до фикса он ничего
        // не фильтровал, а просто копировался в каждый DTO.
        IResourceLookup lookup = mock(IResourceLookup.class);
        Marker keep = markerAt("k", "src/CommonModules/Первый/Module.bsl", lookup);
        Marker drop = markerAt("d", "src/CommonModules/Второй/Module.bsl", lookup);

        IMarkerReader reader = mock(IMarkerReader.class);
        when(reader.markers(any(MarkerFilter[].class))).thenReturn(Stream.of(keep, drop));
        IMarkerManagerV2 mgr = mock(IMarkerManagerV2.class);
        when(mgr.createReader(anyCollection())).thenReturn(reader);
        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.get(any())).thenReturn(Optional.empty());
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");

        List<CheckMarker> hits = new MarkerReader(mgr, catalog, lookup)
                .read(project, "src/CommonModules/Первый/Module.bsl", null, null, null);

        assertEquals(1, hits.size());
        assertEquals("k", hits.get(0).markerId());
    }

    @Test public void pathFilterAcceptsFolderPrefix() {
        IResourceLookup lookup = mock(IResourceLookup.class);
        Marker keep = markerAt("k", "src/Catalogs/Товары/Форма/Форма.form", lookup);
        Marker drop = markerAt("d", "src/CommonModules/Второй/Module.bsl", lookup);

        IMarkerReader reader = mock(IMarkerReader.class);
        when(reader.markers(any(MarkerFilter[].class))).thenReturn(Stream.of(keep, drop));
        IMarkerManagerV2 mgr = mock(IMarkerManagerV2.class);
        when(mgr.createReader(anyCollection())).thenReturn(reader);
        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.get(any())).thenReturn(Optional.empty());
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");

        List<CheckMarker> hits = new MarkerReader(mgr, catalog, lookup)
                .read(project, "src/Catalogs", null, null, null);

        assertEquals(1, hits.size());
        assertEquals("k", hits.get(0).markerId());
    }

    @Test public void markerWithUnresolvableResourceGetsEmptyPathAndIsDroppedByPathFilter() {
        // Маркер уровня конфигурации / удалённого объекта не резолвится в файл. Честный
        // ответ — пустой path; и он не должен проходить фильтр по конкретному пути.
        IResourceLookup lookup = mock(IResourceLookup.class);
        Marker m = mock(Marker.class);
        when(m.getMarkerId()).thenReturn("orphan");
        when(m.getSourceType()).thenReturn("c.orphan");
        when(m.getCheckId()).thenReturn("s.orphan");
        when(m.getSeverity()).thenReturn(MarkerSeverity.MAJOR);
        when(m.getMessage()).thenReturn("");
        when(m.getExtraInfo()).thenReturn(mock(IExtraInfoMap.class));
        when(m.<IFile>provideObject(ArgumentMatchers.<Function<EObject, IFile>>any())).thenReturn(null);

        IMarkerReader reader = mock(IMarkerReader.class);
        when(reader.markers(any(MarkerFilter[].class))).thenReturn(Stream.of(m), Stream.of(m));
        IMarkerManagerV2 mgr = mock(IMarkerManagerV2.class);
        when(mgr.createReader(anyCollection())).thenReturn(reader);
        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.get(any())).thenReturn(Optional.empty());
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");

        MarkerReader markerReader = new MarkerReader(mgr, catalog, lookup);

        assertEquals("", markerReader.read(project, null, null, null, null).get(0).path());
        assertEquals(0, markerReader.read(project, "src/CommonModules", null, null, null).size());
    }

    @Test public void nullSourceFromUnknownCheckClassifiesOther() {
        Marker m = mock(Marker.class);
        when(m.getMarkerId()).thenReturn("u");
        when(m.getSourceType()).thenReturn("com.example.thirdparty.Check");
        when(m.getCheckId()).thenReturn("s.u");
        when(m.getSeverity()).thenReturn(MarkerSeverity.MINOR);
        when(m.getMessage()).thenReturn("");
        when(m.getExtraInfo()).thenReturn(mock(IExtraInfoMap.class));

        IMarkerReader reader = mock(IMarkerReader.class);
        when(reader.markers(any(MarkerFilter[].class))).thenReturn(Stream.of(m));
        IMarkerManagerV2 mgr = mock(IMarkerManagerV2.class);
        when(mgr.createReader(anyCollection())).thenReturn(reader);

        CheckCatalog catalog = mock(CheckCatalog.class);
        when(catalog.get(any())).thenReturn(Optional.empty());

        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Demo");

        MarkerReader markerReader = new MarkerReader(mgr, catalog, mock(IResourceLookup.class));

        CheckMarker cm = markerReader.read(project, null, null, null, null).get(0);
        assertEquals("other", cm.source());
    }
}
