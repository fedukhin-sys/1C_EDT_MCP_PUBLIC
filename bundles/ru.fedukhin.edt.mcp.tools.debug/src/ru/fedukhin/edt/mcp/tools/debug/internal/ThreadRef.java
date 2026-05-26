package ru.fedukhin.edt.mcp.tools.debug.internal;

/** A debugger thread, addressable by {@code id} (its index in the target's thread list). */
public record ThreadRef(String id, String name) {}
