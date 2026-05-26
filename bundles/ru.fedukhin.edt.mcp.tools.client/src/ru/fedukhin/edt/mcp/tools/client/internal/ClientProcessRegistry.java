package ru.fedukhin.edt.mcp.tools.client.internal;

import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory tracker of client {@link Process} instances launched by {@link ClientLauncher}.
 * Single instance per Guice injector; not persisted across IDE / MCP-server restart.
 */
@Singleton
public class ClientProcessRegistry {

    public record ClientSession(UUID sessionId, long pid, String infobaseName,
                                String clientType, Instant startedAt, Process process) {
        public boolean alive() { return process.isAlive(); }
    }

    private final ConcurrentHashMap<UUID, ClientSession> sessions = new ConcurrentHashMap<>();

    public ClientSession register(String infobaseName, String clientType, Process p) {
        UUID id = UUID.randomUUID();
        ClientSession s = new ClientSession(id, p.pid(), infobaseName, clientType, Instant.now(), p);
        sessions.put(id, s);
        return s;
    }

    public Optional<ClientSession> get(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** Returns alive sessions; prunes dead ones from the map as a side effect. */
    public List<ClientSession> list() {
        sessions.entrySet().removeIf(e -> !e.getValue().alive());
        return new ArrayList<>(sessions.values());
    }

    public Optional<ClientSession> remove(UUID sessionId) {
        return Optional.ofNullable(sessions.remove(sessionId));
    }
}
