package com.bosch.demo.docgrounding.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@ConditionalOnProperty(prefix = "app.cosmos-mongo", name = "enabled", havingValue = "true")
public class MongoConfig {

    @Bean(destroyMethod = "close")
    public MongoClient mongoClient(AppProperties properties) {
        String uri = properties.getCosmosMongo().getUri();
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException("COSMOS_MONGO_URI/app.cosmos-mongo.uri must be configured when Mongo integration is enabled");
        }

        ConnectionString connectionString = new ConnectionString(uri);
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToSocketSettings(builder -> builder
                        .connectTimeout(properties.getCosmosMongo().getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                        .readTimeout(properties.getCosmosMongo().getReadTimeoutSeconds(), TimeUnit.SECONDS))
                .build();

        return MongoClients.create(settings);
    }
}
