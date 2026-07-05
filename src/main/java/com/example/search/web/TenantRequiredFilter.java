package com.example.search.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class TenantRequiredFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/health")
                || path.startsWith("/actuator")
                || !path.startsWith("/documents") && !path.startsWith("/search");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String headerTenant = request.getHeader("X-Tenant-ID");
        String queryTenant = request.getParameter("tenant");
        String tenant = StringUtils.hasText(headerTenant) ? headerTenant : queryTenant;

        if (!StringUtils.hasText(tenant)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"detail\":\"Tenant ID required via X-Tenant-ID header or tenant query param\"}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}
