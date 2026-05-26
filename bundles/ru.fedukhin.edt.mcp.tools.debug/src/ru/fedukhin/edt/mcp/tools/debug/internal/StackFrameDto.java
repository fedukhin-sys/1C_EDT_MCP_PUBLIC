package ru.fedukhin.edt.mcp.tools.debug.internal;

/**
 * One call-stack frame. {@code frameId} is the frame's stack level (see IBslStackFrame.getLevel()).
 *
 * @param line 1-based source line number; {@code -1} means the line number could not be determined
 *             (the debug model threw {@link org.eclipse.debug.core.DebugException}).
 */
public record StackFrameDto(String frameId, String project, String path, int line, String method) {}
