package ru.fedukhin.edt.mcp.tools.debug.internal;

/** One variable in a frame: name, BSL type name, and its detail-string value (empty if not yet evaluated). */
public record VariableDto(String name, String type, String value) {}
