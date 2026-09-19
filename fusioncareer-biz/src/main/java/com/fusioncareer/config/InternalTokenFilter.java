package com.fusioncareer.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@RequiredArgsConstructor
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final InternalServiceProperties readProperties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest readRequest) {
        return !readRequest.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest readRequest,
            HttpServletResponse updateResponse,
            FilterChain updateChain) throws ServletException, IOException {
        String readExpected = readProperties.getToken();
        String readActual = readRequest.getHeader(TOKEN_HEADER);
        if (readExpected == null || readExpected.isBlank()) {
            writeError(updateResponse, 503, "internal service token is not configured");
            return;
        }
        if (readActual == null || !MessageDigest.isEqual(
                readExpected.getBytes(StandardCharsets.UTF_8),
                readActual.getBytes(StandardCharsets.UTF_8))) {
            writeError(updateResponse, 403, "invalid internal service token");
            return;
        }
        updateChain.doFilter(readRequest, updateResponse);
    }

    private void writeError(
            HttpServletResponse updateResponse,
            int updateStatus,
            String updateMessage) throws IOException {
        updateResponse.setStatus(updateStatus);
        updateResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        updateResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        updateResponse.getWriter().write(
                "{\"code\":" + updateStatus + ",\"message\":\"" + updateMessage
                        + "\",\"data\":null}");
    }
}
