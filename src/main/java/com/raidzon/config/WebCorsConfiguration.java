package com.raidzon.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebCorsConfiguration implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebCorsConfiguration(
        @Value("${RAIDZON_CORS_ORIGINS}") String origins) {

        this.allowedOrigins = Arrays.stream(origins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {

        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins)
            .allowedMethods(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
            )
            .allowedHeaders(
                "Authorization",
                "Content-Type",
                "Accept",
                "Origin",
                "X-Requested-With"
            )
            .maxAge(3600);
    }
}
