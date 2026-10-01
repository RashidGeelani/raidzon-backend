package com.raidzon.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import static org.junit.jupiter.api.Assertions.*;

class WebCorsConfigurationTest {
    static class Registry extends CorsRegistry {
        org.springframework.web.cors.CorsConfiguration api() { return getCorsConfigurations().get("/api/**"); }
    }
    @Test void permitsOnlyConfiguredOriginsAndAuthenticationHeaders() {
        var registry = new Registry();
        new WebCorsConfiguration("https://raidzon.com, https://www.raidzon.com").addCorsMappings(registry);
        var cors = registry.api();
        assertEquals("https://www.raidzon.com", cors.checkOrigin("https://www.raidzon.com"));
        assertEquals("https://raidzon.com", cors.checkOrigin("https://raidzon.com"));
        assertNull(cors.checkOrigin("https://unrelated.example"));
        assertTrue(cors.getAllowedHeaders().contains("Authorization"));
    }
}
