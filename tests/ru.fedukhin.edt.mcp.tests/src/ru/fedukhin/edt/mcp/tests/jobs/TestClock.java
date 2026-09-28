package ru.fedukhin.edt.mcp.tests.jobs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Часы, которые двигает сам тест: время вытеснения заданий проверяется без ожидания. */
public final class TestClock extends Clock {

    private volatile Instant now = Instant.parse("2026-09-26T10:00:00Z");

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override public Clock withZone(ZoneId zone) { return this; }

    @Override public Instant instant() { return now; }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }
}
