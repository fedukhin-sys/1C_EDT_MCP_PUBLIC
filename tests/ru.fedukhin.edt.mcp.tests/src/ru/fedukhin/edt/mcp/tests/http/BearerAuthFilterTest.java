package ru.fedukhin.edt.mcp.tests.http;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.Test;
import ru.fedukhin.edt.mcp.core.internal.http.BearerAuthFilter;

public class BearerAuthFilterTest {

    private final HttpServletRequest req = mock(HttpServletRequest.class);
    private final HttpServletResponse resp = mock(HttpServletResponse.class);
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    public void missingHeader_returns401() throws Exception {
        when(req.getHeader("Authorization")).thenReturn(null);
        new BearerAuthFilter(() -> "good-token").doFilter(req, resp, chain);
        verify(resp).sendError(401);
        verify(chain, never()).doFilter(req, resp);
    }

    @Test
    public void wrongScheme_returns401() throws Exception {
        when(req.getHeader("Authorization")).thenReturn("Basic xyz");
        new BearerAuthFilter(() -> "good-token").doFilter(req, resp, chain);
        verify(resp).sendError(401);
    }

    @Test
    public void wrongToken_returns401() throws Exception {
        when(req.getHeader("Authorization")).thenReturn("Bearer bad");
        new BearerAuthFilter(() -> "good-token").doFilter(req, resp, chain);
        verify(resp).sendError(401);
    }

    @Test
    public void correctToken_passes() throws Exception {
        when(req.getHeader("Authorization")).thenReturn("Bearer good-token");
        new BearerAuthFilter(() -> "good-token").doFilter(req, resp, chain);
        verify(chain, times(1)).doFilter(req, resp);
        verify(resp, never()).sendError(anyInt());
    }
}
