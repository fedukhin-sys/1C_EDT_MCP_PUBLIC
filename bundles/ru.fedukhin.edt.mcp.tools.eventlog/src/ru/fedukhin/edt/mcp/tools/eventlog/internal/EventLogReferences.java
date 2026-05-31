package ru.fedukhin.edt.mcp.tools.eventlog.internal;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolved reference tables loaded from {@code 1Cv8.lgf}. Each .lgp record refers
 * to entries here by sequential index (the last integer in each .lgf record).
 *
 * <p>Type codes (first integer of each .lgf record):
 * <ul>
 *   <li>1 = User: {@code {1, UUID, "name", seq}} — uuid + display name</li>
 *   <li>2 = Computer: {@code {2, "name", seq}}</li>
 *   <li>3 = Application: {@code {3, "name", seq}} — e.g. "1CV8C", "Designer", "BackgroundJob"</li>
 *   <li>4 = Event: {@code {4, "name", seq}} — e.g. "_$Session$_.Start"</li>
 *   <li>5 = Metadata: {@code {5, UUID, "fullName", seq}}</li>
 *   <li>6 = WorkServer: {@code {6, "name", seq}}</li>
 *   <li>7 = MainPort: {@code {7, port, seq}}</li>
 *   <li>8 = SecondaryPort: {@code {8, port, seq}}</li>
 *   <li>9 = DataSeparator: {@code {9, UUID, "name", seq}}</li>
 *   <li>10–13 = housekeeping / internal indices, ignored</li>
 * </ul>
 */
public final class EventLogReferences {

    public static final class User {
        public final String uuid;
        public final String name;
        public User(String uuid, String name) { this.uuid = uuid; this.name = name; }
    }

    public static final class Metadata {
        public final String uuid;
        public final String fullName;
        public Metadata(String uuid, String fullName) { this.uuid = uuid; this.fullName = fullName; }
    }

    public final Map<Integer, User> users = new HashMap<>();
    public final Map<Integer, String> computers = new HashMap<>();
    public final Map<Integer, String> applications = new HashMap<>();
    public final Map<Integer, String> events = new HashMap<>();
    public final Map<Integer, Metadata> metadata = new HashMap<>();
    public final Map<Integer, String> servers = new HashMap<>();
    public final Map<Integer, Integer> mainPorts = new HashMap<>();
    public final Map<Integer, Integer> secondaryPorts = new HashMap<>();

    public String userName(int seq) {
        User u = users.get(seq);
        return u == null ? null : u.name;
    }

    public String userUuid(int seq) {
        User u = users.get(seq);
        return u == null ? null : u.uuid;
    }

    public String metadataName(int seq) {
        Metadata m = metadata.get(seq);
        return m == null ? null : m.fullName;
    }

    public String metadataUuid(int seq) {
        Metadata m = metadata.get(seq);
        return m == null ? null : m.uuid;
    }
}
