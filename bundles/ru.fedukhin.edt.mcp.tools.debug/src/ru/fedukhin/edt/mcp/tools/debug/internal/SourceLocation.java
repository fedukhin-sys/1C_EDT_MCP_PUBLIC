package ru.fedukhin.edt.mcp.tools.debug.internal;

/**
 * A BSL source position: project name, project-relative module path, 1-based line, method name.
 *
 * @param line 1-based source line number; {@code -1} means the line number could not be determined
 *             (the debug model threw {@link org.eclipse.debug.core.DebugException}).
 */
public record SourceLocation(String project, String path, int line, String method) {}
