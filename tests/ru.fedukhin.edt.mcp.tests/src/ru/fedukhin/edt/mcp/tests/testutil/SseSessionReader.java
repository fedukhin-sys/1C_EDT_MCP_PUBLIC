package ru.fedukhin.edt.mcp.tests.testutil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Minimal SSE client for tests: connects to an SSE endpoint, parses
 * `event:`/`data:` blocks separated by blank lines, and pushes them on a queue.
 *
 * <p>Uses {@link HttpClient} (java.net.http) rather than {@link java.net.HttpURLConnection},
 * because HttpURLConnection.disconnect() can deadlock against a reader thread
 * that's blocked inside readLine() — disconnect waits for a connection-internal
 * lock the reader holds. With HttpClient we own the InputStream directly and
 * close it ourselves; the reader's readLine then fails fast with IOException.
 */
public class SseSessionReader implements AutoCloseable {

    public static final class Event {
        public final String event;
        public final String data;
        public Event(String event, String data) { this.event = event; this.data = data; }
    }

    private final HttpResponse<InputStream> response;
    private final InputStream body;
    private final Thread reader;
    public final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private volatile boolean stopped;

    public SseSessionReader(String url, String token) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "text/event-stream")
                .GET();
        if (token != null) rb.header("Authorization", "Bearer " + token);
        this.response = HttpClient.newHttpClient().send(rb.build(), BodyHandlers.ofInputStream());
        this.body = response.body();

        this.reader = new Thread(this::readLoop, "sse-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public int responseCode() { return response.statusCode(); }
    public String contentType() {
        return response.headers().firstValue("content-type").orElse(null);
    }

    private void readLoop() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            Map<String, StringBuilder> current = new HashMap<>();
            String line;
            while (!stopped && (line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!current.isEmpty()) {
                        events.put(new Event(
                                current.getOrDefault("event", new StringBuilder("message")).toString(),
                                current.getOrDefault("data", new StringBuilder()).toString()));
                        current.clear();
                    }
                } else {
                    int sep = line.indexOf(':');
                    if (sep <= 0) continue;
                    String field = line.substring(0, sep);
                    String value = line.length() > sep + 1 && line.charAt(sep + 1) == ' '
                            ? line.substring(sep + 2) : line.substring(sep + 1);
                    current.computeIfAbsent(field, k -> new StringBuilder())
                           .append(value);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public Event nextEvent(long timeoutMs) throws InterruptedException {
        return events.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override public void close() {
        stopped = true;
        try { body.close(); } catch (Exception ignore) {}
        try { reader.join(500); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
    }
}
