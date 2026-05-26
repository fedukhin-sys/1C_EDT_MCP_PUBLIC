package ru.fedukhin.edt.mcp.tools.bsl.internal;

/** Thrown by BslAstReader when an Xtext parse fails or is unavailable. */
public class BslParseException extends Exception {
    private static final long serialVersionUID = 1L;
    public BslParseException(String message) { super(message); }
    public BslParseException(String message, Throwable cause) { super(message, cause); }
}
