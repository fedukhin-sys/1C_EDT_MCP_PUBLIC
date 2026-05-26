package ru.fedukhin.edt.mcp.tests.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Collections;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.api.IToolRegistry;
import ru.fedukhin.edt.mcp.core.internal.http.BearerAuthFilter;
import ru.fedukhin.edt.mcp.core.internal.http.McpHttpService;
import ru.fedukhin.edt.mcp.core.internal.preferences.McpPreferences;
import ru.fedukhin.edt.mcp.core.internal.protocol.McpServerLifecycle;
import ru.fedukhin.edt.mcp.core.internal.protocol.ToolSpecAdapter;
import ru.fedukhin.edt.mcp.core.internal.registry.ToolRegistry;
import ru.fedukhin.edt.mcp.tests.testutil.SseSessionReader;

/**
 * End-to-end smoke test for the MCP server. Wires the relevant pieces directly
 * (no McpCorePlugin / Guice), so the headless test runtime never touches the
 * 1C:EDT lifecycle (xtext.ui → ui.workbench → SWT activation chain hangs
 * without SWT natives). Validates Bearer auth and an `initialize` round-trip
 * over SSE.
 *
 * <p>Currently disabled in tycho-surefire: with Jackson 2.20 vendored inside
 * core via Bundle-ClassPath alongside 1C:EDT's installed Jackson 2.12, the
 * OSGi resolver hits a LinkageError on com.fasterxml.jackson.core.type.TypeReference
 * during request handling (two classloaders, same FQN). The unit tests cover
 * the protocol-independent logic; the SSE handshake should be re-enabled once
 * the third-party libs are properly relocated (jar-jar-links / shading) or
 * EDT updates its Jackson to 2.20+.
 */
@Ignore("LinkageError on com.fasterxml.jackson.core.type.TypeReference — see class javadoc")
public class McpServerIntegrationTest {

    private static final String PREF_NODE = "ru.fedukhin.edt.mcp.tests.integration";
    private static final String TOKEN = "test-bearer-token-" + Long.toHexString(System.nanoTime());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static int port;
    private static String baseUrl;
    private static McpHttpService svc;

    @BeforeClass
    public static void up() throws Exception {
        port = freePort();

        IEclipsePreferences prefsNode = InstanceScope.INSTANCE.getNode(PREF_NODE);
        prefsNode.putInt(McpPreferences.KEY_PORT, port);
        McpPreferences prefs = new McpPreferences(prefsNode);

        IToolRegistry registry = new ToolRegistry(Collections.emptyList());
        ToolSpecAdapter adapter = new ToolSpecAdapter();
        McpServerLifecycle lifecycle = new McpServerLifecycle(registry, adapter);

        BearerAuthFilter authFilter = new BearerAuthFilter(() -> TOKEN);
        svc = new McpHttpService(lifecycle, authFilter, prefs);
        svc.start();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterClass
    public static void down() throws Exception {
        if (svc != null) svc.stop();
    }

    @Test
    public void sseEndpoint_withoutAuth_returns401() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<Void> r = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/mcp/sse"))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                BodyHandlers.discarding());
        assertEquals(401, r.statusCode());
    }

    @Test
    public void initialize_roundtripsViaSse() throws Exception {
        try (SseSessionReader sse = new SseSessionReader(baseUrl + "/mcp/sse", TOKEN)) {
            assertEquals(200, sse.responseCode());
            String ct = sse.contentType();
            assertNotNull(ct);
            assertTrue("Expected SSE content-type, got: " + ct, ct.startsWith("text/event-stream"));

            SseSessionReader.Event endpoint = sse.nextEvent(5_000);
            assertNotNull("SDK should emit an `event: endpoint` on connect", endpoint);
            assertEquals("endpoint", endpoint.event);
            String sessionId = sessionIdFrom(endpoint.data);
            assertNotNull("sessionId must be present in endpoint URL: " + endpoint.data, sessionId);

            String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2024-11-05\","
                    + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}";
            HttpResponse<String> post = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(baseUrl + endpoint.data))
                            .header("Authorization", "Bearer " + TOKEN)
                            .header("Content-Type", "application/json")
                            .POST(BodyPublishers.ofString(initialize)).build(),
                    BodyHandlers.ofString());
            assertTrue("POST initialize should be accepted (2xx), got " + post.statusCode()
                    + " body: " + post.body(),
                    post.statusCode() >= 200 && post.statusCode() < 300);

            SseSessionReader.Event response = sse.nextEvent(5_000);
            assertNotNull("Expected JSON-RPC response over SSE", response);
            JsonNode body = MAPPER.readTree(response.data);
            assertEquals(1, body.get("id").asInt());
            assertTrue("Response should contain result, was: " + body,
                    body.has("result"));
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static String sessionIdFrom(String endpointPath) {
        int q = endpointPath.indexOf("sessionId=");
        if (q < 0) return null;
        int end = endpointPath.indexOf('&', q);
        return end < 0 ? endpointPath.substring(q + "sessionId=".length())
                       : endpointPath.substring(q + "sessionId=".length(), end);
    }
}
