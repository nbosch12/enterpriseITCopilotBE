package com.bosch.demo.docgrounding.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private Docupedia docupedia = new Docupedia();
    private Chunking chunking = new Chunking();
    private S3 s3 = new S3();
    private SapAiCore sapAiCore = new SapAiCore();
    private AzureMonitor azureMonitor = new AzureMonitor();
    private CosmosMongo cosmosMongo = new CosmosMongo();

    @Setter
    @Getter
    public static class Docupedia {
        private String baseUrl;
        private String bearerToken;
        private int pageSize = 50;
    }

    @Setter
    @Getter
    public static class Chunking {
        private int maxChars = 3000;
        private int overlapChars = 300;
    }

    @Setter
    @Getter
    public static class S3 {
        private String endpoint;
        private String region;
        private String bucket;
        private String accessKey;
        private String secretKey;
        private boolean pathStyleAccess = true;
    }

    @Setter
    @Getter
    public static class SapAiCore {
        private String tokenUrl;
        private String clientId;
        private String clientSecret;
        private String apiUrl;
        private String resourceGroup;
        private String s3GenericSecretName;
        private String orchestrationDeploymentId;
        private String embeddingDeploymentId;
        /** LLM model name used inside orchestration_config (e.g. gpt-4o, gpt-4o-mini). */
        private String llmModelName = "gpt-4o";
        private String embeddingModelName = "text-embedding-3-large";
        private String embeddingModelVersion = "latest";
        private String vectorSearchPrefix = "chunk-";
    }

    @Setter
    @Getter
    public static class CosmosMongo {
        private boolean enabled = false;
        private String uri;
        private String database;
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 30;
        private int maxQueryResults = 500;
    }

    @Setter
    @Getter
    public static class AzureMonitor {
        private String workspaceId;
        private String tenantId;
        private String clientId;
        private String clientSecret;
        private boolean enabled = false;
    }
}
