package co.medistock.inventory;

import co.medistock.inventory.adapters.Errors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestContext extends OncePerRequestFilter {
    private final Errors errors;
    public RequestContext(Errors errors) { this.errors = errors; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Correlation-Id");
        boolean invalid = supplied != null && !supplied.matches("[A-Za-z0-9._:-]{1,128}");
        String trace = supplied == null || invalid ? UUID.randomUUID().toString() : supplied;
        request.setAttribute("traceId", trace); response.setHeader("X-Correlation-Id", trace);
        org.slf4j.MDC.put("traceId", trace);
        try {
            if (invalid) { errors.write(request, response, 400, "VALIDATION_ERROR", "Correlacion invalida."); return; }
            if (request.getContentLengthLong() > 65536) {
                errors.write(request, response, 413, "PAYLOAD_TOO_LARGE", "Cuerpo demasiado grande."); return;
            }
            if ("POST".equals(request.getMethod())) {
                byte[] data = request.getInputStream().readNBytes(65537);
                if (data.length > 65536) { errors.write(request, response, 413, "PAYLOAD_TOO_LARGE", "Cuerpo demasiado grande."); return; }
                request = new HttpServletRequestWrapper(request) {
                    @Override public ServletInputStream getInputStream() {
                        var input = new ByteArrayInputStream(data);
                        return new ServletInputStream() {
                            public int read() { return input.read(); }
                            public boolean isFinished() { return input.available() == 0; }
                            public boolean isReady() { return true; }
                            public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                        };
                    }
                    @Override public BufferedReader getReader() {
                        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
                    }
                };
            }
            chain.doFilter(request, response);
        } finally {
            LoggerFactory.getLogger(RequestContext.class).info("request method={} status={}", request.getMethod(), response.getStatus());
            org.slf4j.MDC.remove("traceId");
        }
    }
}
