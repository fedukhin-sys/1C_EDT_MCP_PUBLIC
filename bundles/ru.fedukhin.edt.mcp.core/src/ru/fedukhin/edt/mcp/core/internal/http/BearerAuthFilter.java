package ru.fedukhin.edt.mcp.core.internal.http;

import java.io.IOException;
import java.util.function.Supplier;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class BearerAuthFilter implements Filter {

    private static final String PREFIX = "Bearer ";

    private final Supplier<String> tokenSupplier;

    public BearerAuthFilter(Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
    }

    @Override public void init(FilterConfig filterConfig) {}
    @Override public void destroy() {}

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest hreq = (HttpServletRequest) req;
        HttpServletResponse hresp = (HttpServletResponse) resp;
        String header = hreq.getHeader("Authorization");
        if (header == null || !header.startsWith(PREFIX)) {
            hresp.sendError(401);
            return;
        }
        String presented = header.substring(PREFIX.length());
        String expected = tokenSupplier.get();
        if (!constantTimeEquals(presented, expected)) {
            hresp.sendError(401);
            return;
        }
        chain.doFilter(req, resp);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
