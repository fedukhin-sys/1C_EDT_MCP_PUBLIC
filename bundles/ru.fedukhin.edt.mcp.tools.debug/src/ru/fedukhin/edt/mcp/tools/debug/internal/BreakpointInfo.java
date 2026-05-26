package ru.fedukhin.edt.mcp.tools.debug.internal;

/** One BSL line breakpoint. {@code id} is the marker's stable workspace id (as a string). */
public record BreakpointInfo(String id, String project, String path, int line, String condition) {}
