package com.example.search.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantResolverTest {

    @Test
    void resolvesHeaderTenant() {
        assertEquals("tenant_acme", TenantResolver.resolve("tenant_acme", null));
    }

    @Test
    void resolvesQueryTenantWhenHeaderMissing() {
        assertEquals("tenant_beta", TenantResolver.resolve(null, "tenant_beta"));
    }

    @Test
    void rejectsMissingTenant() {
        assertThrows(IllegalArgumentException.class, () -> TenantResolver.resolve(null, null));
    }
}
