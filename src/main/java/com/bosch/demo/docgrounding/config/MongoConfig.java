package com.bosch.demo.docgrounding.config;

import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * The MongoClient is created by Spring Boot auto-configuration from the standard
 * {@code spring.data.mongodb.*} properties (host / port / database / uri).
 * Here we only apply the socket timeouts configured under {@code app.cosmos-mongo}.
 */
@Configuration
public class MongoConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer mongoTimeoutCustomizer(AppProperties properties) {
        AppProperties.CosmosMongo cfg = properties.getCosmosMongo();
        return builder -> builder.applyToSocketSettings(socket -> socket
                .connectTimeout(cfg.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(cfg.getReadTimeoutSeconds(), TimeUnit.SECONDS));
    }
}
