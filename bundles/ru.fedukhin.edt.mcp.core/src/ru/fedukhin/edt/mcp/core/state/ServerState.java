package ru.fedukhin.edt.mcp.core.state;

import java.util.Objects;

public final class ServerState {
    public enum Phase { STOPPED, STARTING, RUNNING, ERROR }

    private final Phase phase;
    private final int port;
    private final String errorMessage;

    private ServerState(Phase phase, int port, String errorMessage) {
        this.phase = phase;
        this.port = port;
        this.errorMessage = errorMessage;
    }

    public static ServerState stopped() { return new ServerState(Phase.STOPPED, 0, null); }
    public static ServerState starting(int port) { return new ServerState(Phase.STARTING, port, null); }
    public static ServerState running(int port) { return new ServerState(Phase.RUNNING, port, null); }
    public static ServerState error(String message) { return new ServerState(Phase.ERROR, 0, message); }

    public Phase phase() { return phase; }
    public int port() { return port; }
    public String errorMessage() { return errorMessage; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ServerState)) return false;
        ServerState s = (ServerState) o;
        return port == s.port && phase == s.phase && Objects.equals(errorMessage, s.errorMessage);
    }
    @Override public int hashCode() { return Objects.hash(phase, port, errorMessage); }
    @Override public String toString() {
        return "ServerState{" + phase + (port > 0 ? ":" + port : "")
            + (errorMessage != null ? " err=" + errorMessage : "") + "}";
    }
}
