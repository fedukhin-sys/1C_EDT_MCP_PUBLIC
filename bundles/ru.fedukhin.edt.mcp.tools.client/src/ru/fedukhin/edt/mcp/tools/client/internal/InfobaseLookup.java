package ru.fedukhin.edt.mcp.tools.client.internal;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;

/** Read-only lookup of an infobase by name; isolates {@code tools.client} from {@code tools.infobase}. */
public class InfobaseLookup {

    private final IInfobaseManager manager;

    @Inject
    public InfobaseLookup(IInfobaseManager manager) {
        this.manager = manager;
    }

    public Optional<InfobaseReference> findByName(String name) {
        return manager.findInfobaseByName(name);
    }

    /** Резолв ИБ по uuid — им launch-конфигурации EDT ссылаются на приложение ({@code ATTR_APPLICATION_ID}). */
    public Optional<InfobaseReference> findByUuid(UUID uuid) {
        return manager.findInfobaseByUuid(uuid);
    }
}
