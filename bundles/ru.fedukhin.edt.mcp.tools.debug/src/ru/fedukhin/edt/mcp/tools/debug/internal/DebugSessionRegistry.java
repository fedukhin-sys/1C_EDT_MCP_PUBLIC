package ru.fedukhin.edt.mcp.tools.debug.internal;

import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * In-memory registry of live debug sessions. Mirrors {@code ClientProcessRegistry}
 * from {@code tools.client}: a {@link ConcurrentHashMap}, atomic register/remove,
 * and prune-on-list of terminated sessions.
 *
 * <p>{@code @Singleton} so every debug tool sees the same registry within one Guice
 * injector. Plan 2's {@code ToolsDebugExecutableExtensionFactory} MUST cache its
 * injector statically (double-checked locking) — without that the tools get
 * separate registry instances (Stage 3b learned this the hard way: commit a07e660).
 */
@Singleton
public class DebugSessionRegistry {

    private final ConcurrentHashMap<UUID, DebugSession> sessions = new ConcurrentHashMap<>();

    public DebugSession register(DebugSession session) {
        sessions.put(session.id(), session);
        return session;
    }

    public Optional<DebugSession> get(UUID id) {
        return Optional.ofNullable(sessions.get(id));
    }

    /** Like {@link #get(UUID)} but throws a {@link ToolException} if the session is absent. */
    public DebugSession require(UUID id) throws ToolException {
        DebugSession session = sessions.get(id);
        if (session == null) {
            throw new ToolException("debug session '" + id + "' not found");
        }
        return session;
    }

    /**
     * Returns the live sessions; prunes terminated ones from the map as a side effect.
     *
     * <p>Уборка симметрична {@code stop_debug}: {@link DebugSession#dispose()} снимает
     * слушателя событий, {@link DebugSession#terminate()} — отпускает target/launch,
     * убирает launch из {@code ILaunchManager} и снимает catch-all breakpoint. Одного
     * {@code dispose()} мало: сессия, завершённая самим EDT (пользователь закрыл клиент),
     * иначе оставляет launch в менеджере и breakpoint в workspace.
     */
    public List<DebugSession> list() {
        sessions.values().removeIf(session -> {
            if (session.isTerminated()) {
                session.dispose();
                session.terminate();
                return true;
            }
            return false;
        });
        return new ArrayList<>(sessions.values());
    }

    public Optional<DebugSession> remove(UUID id) {
        DebugSession session = sessions.remove(id);
        if (session != null) {
            session.dispose();
        }
        return Optional.ofNullable(session);
    }
}
