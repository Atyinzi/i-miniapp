package com.iminiapp.backend.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies Jackson 2 to modules that have not yet migrated to the Jackson 3
 * packages used by Spring Boot 4. The two ObjectMapper types have different
 * package names, so they can coexist without changing HTTP serialization.
 */
@Configuration(proxyBeanMethods = false)
public class LegacyJacksonConfiguration {

    @Bean
    ObjectMapper legacyObjectMapper() {
        return new ObjectMapper();
    }
}
