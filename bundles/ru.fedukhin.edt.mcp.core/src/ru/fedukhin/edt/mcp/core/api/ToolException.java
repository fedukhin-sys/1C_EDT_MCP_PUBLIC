package ru.fedukhin.edt.mcp.core.api;

public class ToolException extends Exception {
    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }

    public ToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
