package ru.fedukhin.edt.mcp.tools.debug.internal;

/**
 * Result of an {@code evaluate} call. On success {@code ok==true}, {@code value}/{@code type}
 * set, {@code error==null}. On a bad expression / engine error / timeout {@code ok==false},
 * {@code error} set, {@code value}/{@code type==null}. A bad BSL expression is NOT a
 * {@code ToolException} — it is a normal {@code ok:false} payload (spec §8).
 */
public record EvalOutcome(boolean ok, String value, String type, String error) {

    public static EvalOutcome success(String value, String type) {
        return new EvalOutcome(true, value, type, null);
    }

    public static EvalOutcome failure(String error) {
        return new EvalOutcome(false, null, null, error);
    }
}
