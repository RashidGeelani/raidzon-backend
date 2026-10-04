package com.raidzon.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import java.io.IOException;

/**
 * Reports how long each API request spent in the app and in the database.
 * Public GETs get a Server-Timing header (visible in browser dev tools); slow requests are logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TimingFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(TimingFilter.class);
    private static final long SLOW_MS = 300;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) { chain.doFilter(request, response); return; }
        // Buffer only small public reads so the header can be added after the handler runs.
        boolean header = "GET".equals(request.getMethod()) && path.startsWith("/api/v1/public/");
        var wrapped = header ? new ContentCachingResponseWrapper(response) : null;
        long started = System.nanoTime();
        RequestTiming.start();
        try {
            chain.doFilter(request, header ? wrapped : response);
        } finally {
            long[] db = RequestTiming.finish();
            double total = (System.nanoTime() - started) / 1e6;
            double dbMs = db == null ? 0 : db[0] / 1e6;
            double waitMs = db == null ? 0 : db[2] / 1e6;
            long statements = db == null ? 0 : db[1];
            if (header) {
                wrapped.setHeader("Server-Timing", String.format(java.util.Locale.ROOT,
                    "app;dur=%.1f, db;dur=%.1f;desc=\"%d statements\", pool;dur=%.1f", total, dbMs, statements, waitMs));
                wrapped.setHeader("Timing-Allow-Origin", "*");
                wrapped.copyBodyToResponse();
            }
            if (total >= SLOW_MS)
                LOG.info("timing {} {} status={} total={}ms db={}ms statements={} poolWait={}ms",
                    request.getMethod(), path, response.getStatus(), Math.round(total), Math.round(dbMs), statements, Math.round(waitMs));
        }
    }
}
