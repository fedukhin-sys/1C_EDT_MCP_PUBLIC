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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * (matches the {@code ragent.exe -d} argument observed across 1C 8.3.x installs).
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

    private Path srvinfoOverride;
    private int clusterPort = 1541;

    public EventLogLocator setSrvinfoDir(Path p) { this.srvinfoOverride = p; return this; }
    public EventLogLocator setClusterPort(int port) { this.clusterPort = port; return this; }

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
            Path srvinfo = findSrvinfo();
            int port = clusterPort;
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

    private Path findSrvinfo() throws ToolException {
        if (srvinfoOverride != null) {
            if (!Files.isDirectory(srvinfoOverride)) {
                throw new ToolException("srvinfoDir override not a directory: " + srvinfoOverride);
            }
            return srvinfoOverride;
        }
        for (Path p : DEFAULT_SRVINFO_PATHS) {
            if (Files.isDirectory(p)) return p;
        }
        throw new ToolException("srvinfo directory not found; pass 'srvinfoDir' explicitly. Tried: "
            + DEFAULT_SRVINFO_PATHS);
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
