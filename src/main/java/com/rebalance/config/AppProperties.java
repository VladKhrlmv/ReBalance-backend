package com.rebalance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Cors cors, Storage storage) {
    /**
     * @param secret base64-encoded HMAC key, at least 32 bytes after decoding
     * @param ttl    lifetime of issued tokens
     */
    public record Jwt(String secret, @DefaultValue("30d") Duration ttl) {
    }

    /**
     * @param allowedOrigins browser origins allowed to call the API cross-origin; empty in production,
     *                       where the SPA and the API share one origin
     */
    public record Cors(@DefaultValue List<String> allowedOrigins) {
        public List<String> nonBlankOrigins() {
            return allowedOrigins == null ? List.of() : allowedOrigins.stream()
                    .map(String::trim)
                    .filter(origin -> !origin.isEmpty())
                    .toList();
        }
    }

    /**
     * @param path directory for receipt images
     */
    public record Storage(@DefaultValue("./data/images") String path) {
    }
}
