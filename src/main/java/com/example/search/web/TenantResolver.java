package com.example.search.web;

import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

public final class TenantResolver {

    private TenantResolver() {}

    public static String resolve(
            @RequestHeader(value = "X-Tenant-ID", required = false) String headerTenant,
            @RequestParam(value = "tenant", required = false) String queryTenant
    ) {
        String tenant = StringUtils.hasText(headerTenant) ? headerTenant : queryTenant;
        if (!StringUtils.hasText(tenant)) {
            throw new IllegalArgumentException("Tenant ID required via X-Tenant-ID header or tenant query param");
        }
        return tenant.trim();
    }
}
