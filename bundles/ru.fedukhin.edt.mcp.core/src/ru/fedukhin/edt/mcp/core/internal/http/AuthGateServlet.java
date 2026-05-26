package ru.fedukhin.edt.mcp.core.internal.http;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

class AuthGateServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private final HttpServlet delegate;
    private final BearerAuthFilter authFilter;

    AuthGateServlet(HttpServlet delegate, BearerAuthFilter authFilter) {
        this.delegate = delegate;
        this.authFilter = authFilter;
    }

    @Override public void init(ServletConfig cfg) throws ServletException {
        super.init(cfg);
        delegate.init(cfg);
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        AtomicBoolean passed = new AtomicBoolean(false);
        authFilter.doFilter(req, resp, (ServletRequest r, ServletResponse p) -> passed.set(true));
        if (passed.get()) {
            delegate.service(req, resp);
        }
    }

    @Override public void destroy() {
        delegate.destroy();
        super.destroy();
    }
}
