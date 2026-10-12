package ru.fedukhin.edt.mcp.tools.eventlog.internal;

import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com._1c.g5.v8.dt.platform.services.model.InfobaseType;
import com._1c.g5.v8.dt.platform.services.model.ServerConnectionString;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Locates the {@code 1Cv8Log} directory for an {@link InfobaseReference}.
 *
 * <p><b>FILE infobases</b>: {@code <ConnectionString.File>/1Cv8Log/}.
 *
 * <p><b>SERVER infobases</b>: must resolve the cluster-internal infobase UUID from
 * {@code <srvinfo>/reg_<port>/1CV8Clst.lst}, then build
 * {@code <srvinfo>/reg_<port>/<clusterIbUuid>/1Cv8Log/}.
 *
 * <p>The {@code srvinfo} directory is auto-discovered from common installation paths
 * (matches the {@code ragent.exe -d} argument observed across 1C 8.3.x installs) and from
 * {@code srvinfo*} subdirectories next to them ({@code C:\ProgramData\1C\srvinfo8327} — кластер
 * другой версии рядом): выбирается тот, где есть {@code reg_<port>}; среди {@code srvinfo*} —
 * только однозначно (см. {@link #findSrvinfo}). Порт — явный
 * ({@link #setClusterPort}) или из {@code Srvr=host:port} строки соединения, иначе 1541.
 * Callers may supply an explicit override via {@link #setSrvinfoDir(Path)} when the
 * installation is non-standard or the agent runs on a different machine.
 */
public final class EventLogLocator {

    public static final class ResolvedLog {
        public final Path logDir;
        public final boolean exists;
        public final String infobaseType; // FILE/SERVER/WEB
        public final String clusterRef;   // server-side ref (e.g. "ESS") or null
        public final String clusterIbUuid; // cluster-internal uuid or null
        public final Path srvinfoDir;     // null for FILE
        public final int clusterPort;     // 0 for FILE
        public ResolvedLog(Path logDir, boolean exists, String type,
                           String clusterRef, String clusterIbUuid, Path srvinfo, int port) {
            this.logDir = logDir;
            this.exists = exists;
            this.infobaseType = type;
            this.clusterRef = clusterRef;
            this.clusterIbUuid = clusterIbUuid;
            this.srvinfoDir = srvinfo;
            this.clusterPort = port;
        }
    }

    private static final List<Path> DEFAULT_SRVINFO_PATHS = List.of(
        Paths.get("C:\\Program Files (x86)\\1cv8\\srvinfo"),
        Paths.get("C:\\Program Files\\1cv8\\srvinfo"),
        Paths.get("/opt/1cv8/srvinfo"),
        Paths.get("/var/1C/1cv8/srvinfo")
    );

    /**
     * Каталоги, в которых лежат {@code srvinfo*}: так рядом ставятся кластеры разных версий
     * (например, 8.3.27 с {@code ragent -d C:\ProgramData\1C\srvinfo8327 -port 2540}).
     */
    private static final List<Path> DEFAULT_SRVINFO_PARENTS = List.of(
        Paths.get("C:\\ProgramData\\1C"),
        Paths.get("C:\\Program Files\\1cv8"),
        Paths.get("C:\\Program Files (x86)\\1cv8")
    );

    private static final int DEFAULT_CLUSTER_PORT = 1541;

    private Path srvinfoOverride;
    private Integer clusterPortOverride;
    private List<Path> trustedSrvinfoDirs = DEFAULT_SRVINFO_PATHS;
    private List<Path> srvinfoParents = DEFAULT_SRVINFO_PARENTS;

    public EventLogLocator setSrvinfoDir(Path p) { this.srvinfoOverride = p; return this; }

    /** Явный порт менеджера кластера; без него порт берётся из строки соединения базы. */
    public EventLogLocator setClusterPort(int port) { this.clusterPortOverride = port; return this; }

    /**
     * Шов для тестов: искать {@code srvinfo*} только в этих каталогах, без стандартных путей
     * установки.
     */
    public EventLogLocator setSrvinfoSearchRoots(List<Path> parents) {
        this.trustedSrvinfoDirs = List.of();
        this.srvinfoParents = List.copyOf(parents);
        return this;
    }

    /**
     * Порт менеджера кластера из {@code Srvr} строки соединения: {@code host:port}, список
     * серверов через запятую (берётся первый), префикс {@code tcp://}. Без порта — 1541.
     * Реестр кластера лежит в {@code <srvinfo>/reg_<порт>}.
     */
    public static int clusterPortOf(String server) {
        if (server == null) return DEFAULT_CLUSTER_PORT;
        String first = server.split(",")[0].trim();
        int scheme = first.indexOf("://");
        if (scheme >= 0) first = first.substring(scheme + 3);
        int colon = first.lastIndexOf(':');
        if (colon < 0) return DEFAULT_CLUSTER_PORT;
        try {
            int port = Integer.parseInt(first.substring(colon + 1).trim());
            return port > 0 ? port : DEFAULT_CLUSTER_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_CLUSTER_PORT;
        }
    }

    public ResolvedLog locate(InfobaseReference ref) throws ToolException {
        InfobaseType type = ref.getInfobaseType();
        IConnectionString cs = ref.getConnectionString();
        if (cs instanceof FileConnectionString fcs) {
            Path log = Paths.get(fcs.getFile()).resolve("1Cv8Log");
            return new ResolvedLog(log, Files.isDirectory(log), "FILE", null, null, null, 0);
        }
        if (cs instanceof ServerConnectionString scs) {
            String clusterRef = scs.getReference();
            if (clusterRef == null || clusterRef.isBlank()) {
                throw new ToolException("server connection string has no reference name");
            }
            int port = clusterPortOverride != null ? clusterPortOverride : clusterPortOf(scs.getServer());
            Path srvinfo = findSrvinfo(port);
            Path reg = srvinfo.resolve("reg_" + port);
            if (!Files.isDirectory(reg)) {
                throw new ToolException("cluster registry not found: " + reg);
            }
            String clusterIbUuid = lookupClusterIbUuid(reg.resolve("1CV8Clst.lst"), clusterRef);
            if (clusterIbUuid == null) {
                throw new ToolException("cluster IB uuid for ref '" + clusterRef + "' not found in " + reg);
            }
            Path log = reg.resolve(clusterIbUuid).resolve("1Cv8Log");
            return new ResolvedLog(log, Files.isDirectory(log), "SERVER", clusterRef, clusterIbUuid, srvinfo, port);
        }
        // Web/other types fall through with no log path
        String typeName = type == null ? "UNKNOWN" : type.name();
        throw new ToolException("event log lookup is not supported for infobase type " + typeName);
    }

    /** Lists .lgp partition files inside the log directory, smallest date first. */
    public static List<Map<String, Object>> listPartitions(Path logDir) throws ToolException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(logDir)) return out;
        try (var stream = Files.list(logDir)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".lgp"))
                  .sorted()
                  .forEach(p -> {
                      Map<String, Object> e = new LinkedHashMap<>();
                      String name = p.getFileName().toString();
                      e.put("file", name);
                      e.put("date", parsePartitionDate(name));
                      try { e.put("sizeBytes", Files.size(p)); } catch (IOException ignored) { e.put("sizeBytes", -1); }
                      out.add(e);
                  });
        } catch (IOException e) {
            throw new ToolException("failed to list log dir: " + e.getMessage(), e);
        }
        return out;
    }

    /** Parses {@code 20260406000000.lgp} → {@code "2026-04-06"}. Returns null on mismatch. */
    public static String parsePartitionDate(String fileName) {
        if (fileName.length() < 14) return null;
        try {
            String d = fileName.substring(0, 8);
            return d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Каталог {@code srvinfo} кластера с портом {@code port}.
     *
     * <p>Стандартные пути установки (туда пишет только администратор) — первое совпадение с
     * {@code reg_<port>}, как раньше. Каталоги {@code srvinfo*} рядом — только однозначное
     * совпадение: в {@code C:\ProgramData\1C} каталог может создать любой пользователь машины,
     * и подложенный {@code srvinfo*} с тем же {@code reg_<port>} подменил бы журнал. Совпадений
     * нет — первый существующий каталог (ошибка про реестр назовёт путь).
     */
    private Path findSrvinfo(int port) throws ToolException {
        if (srvinfoOverride != null) {
            if (!Files.isDirectory(srvinfoOverride)) {
                throw new ToolException("srvinfoDir override not a directory: " + srvinfoOverride);
            }
            return srvinfoOverride;
        }
        String reg = "reg_" + port;
        List<Path> trusted = new ArrayList<>();
        for (Path p : trustedSrvinfoDirs) {
            if (!Files.isDirectory(p)) continue;
            if (Files.isDirectory(p.resolve(reg))) return p;
            trusted.add(p);
        }
        List<Path> discovered = discoveredSrvinfoDirs();
        List<Path> matches = new ArrayList<>();
        for (Path p : discovered) {
            if (Files.isDirectory(p.resolve(reg))) matches.add(p);
        }
        if (matches.size() == 1) return matches.get(0);
        if (matches.size() > 1) {
            throw new ToolException("several srvinfo directories hold " + reg + ": " + matches
                + "; pass 'srvinfoDir' explicitly");
        }
        if (!trusted.isEmpty()) return trusted.get(0);
        if (!discovered.isEmpty()) return discovered.get(0);
        throw new ToolException("srvinfo directory not found; pass 'srvinfoDir' explicitly. Tried: "
            + trustedSrvinfoDirs + " and srvinfo* in " + srvinfoParents);
    }

    /** Существующие подкаталоги {@code srvinfo*} каталогов-родителей, кроме стандартных путей. */
    private List<Path> discoveredSrvinfoDirs() {
        Set<Path> out = new LinkedHashSet<>();
        for (Path parent : srvinfoParents) {
            if (!Files.isDirectory(parent)) continue;
            try (Stream<Path> children = Files.list(parent)) {
                children.filter(Files::isDirectory)
                        .filter(EventLogLocator::isSrvinfoName)
                        .sorted()
                        .forEach(out::add);
            } catch (IOException | RuntimeException ignored) {
                // Нет прав на каталог — просто не кандидат.
            }
        }
        out.removeAll(trustedSrvinfoDirs);
        return new ArrayList<>(out);
    }

    private static boolean isSrvinfoName(Path dir) {
        Path name = dir.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).startsWith("srvinfo");
    }

    /**
     * Reads {@code 1CV8Clst.lst} (cluster registry, cp1251) and finds the cluster-internal
     * UUID for the given infobase reference name. Format is a brace-nested tuple list;
     * each infobase entry looks like {@code {<uuid>,"<ref>","",...}}. We use a regex
     * over the cp1251-decoded text — robust enough for the well-known structure.
     */
    static String lookupClusterIbUuid(Path lstFile, String ref) throws ToolException {
        if (!Files.isRegularFile(lstFile)) {
            throw new ToolException("cluster registry file missing: " + lstFile);
        }
        String content;
        try {
            byte[] bytes = Files.readAllBytes(lstFile);
            content = new String(bytes, Charset.forName("windows-1251"));
        } catch (IOException e) {
            throw new ToolException("failed to read " + lstFile + ": " + e.getMessage(), e);
        }
        // Match {<uuid>,"<ref>", ...   where uuid = 8-4-4-4-12 hex
        Pattern p = Pattern.compile("\\{([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}),\"([^\"]*)\"");
        Matcher m = p.matcher(content);
        while (m.find()) {
            if (ref.equalsIgnoreCase(m.group(2))) return m.group(1);
        }
        return null;
    }

    /** Encoding hint exposed so tools can include it in their JSON response. */
    public static String registryEncoding() { return "windows-1251"; }
    public static String logEncoding() { return StandardCharsets.UTF_8.name(); }
}
