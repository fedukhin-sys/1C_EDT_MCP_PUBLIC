package ru.fedukhin.edt.mcp.core.api;

import java.util.Collection;
import java.util.Optional;

public interface IToolRegistry {
    Collection<IMcpTool> tools();
    Optional<IMcpTool> byName(String name);
}
