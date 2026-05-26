package ru.fedukhin.edt.mcp.core.internal.registry;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.IToolRegistry;

public class ToolRegistry implements IToolRegistry {

    private static final ILog LOG = Platform.getLog(ToolRegistry.class);

    private final Map<String, IMcpTool> byName = new LinkedHashMap<>();

    public ToolRegistry(Collection<IMcpTool> tools) {
        for (IMcpTool t : tools) {
            String n = t.name();
            if (byName.containsKey(n)) {
                LOG.log(Status.warning("Duplicate MCP tool name '" + n + "' — overriding."));
            }
            byName.put(n, t);
        }
    }

    @Override public Collection<IMcpTool> tools() {
        return Collections.unmodifiableCollection(byName.values());
    }

    @Override public Optional<IMcpTool> byName(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
