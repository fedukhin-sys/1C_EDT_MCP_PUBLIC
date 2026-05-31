package ru.fedukhin.edt.mcp.tools.eventlog.internal;

/**
 * One parsed event-log record. All seq-indexed fields are pre-resolved to display
 * names ({@link #user}, {@link #event}, etc.); the raw seqs are also kept for
 * filters and as a debugging fallback.
 */
public final class EventRecord {

    public long dateRaw;         // YYYYMMDDhhmmss
    public String dateIso;       // "2026-04-06T12:34:56"
    public String txStatus;      // N/C/U/R
    public String txId;          // "<hexTimestamp>/<hexId>" or null

    public int userSeq;
    public String user;
    public String userUuid;

    public int computerSeq;
    public String computer;

    public int applicationSeq;
    public String application;

    public long connectionId;

    public int eventSeq;
    public String event;

    public String severity;      // Information/Warning/Error/Note (decoded from I/W/E/N)
    public String severityCode;  // raw I/W/E/N

    public String comment;

    public int metadataSeq;
    public String metadata;
    public String metadataUuid;

    public String dataType;      // raw type code from {"X",val} (e.g. "N","B","U","S","R")
    public String dataValue;     // raw value (UUID, bool, number, string)
    public String dataPresentation;

    public int serverSeq;
    public String server;

    public int mainPortSeq;
    public Integer mainPort;

    public int secondaryPortSeq;
    public Integer secondaryPort;

    public long session;
}
