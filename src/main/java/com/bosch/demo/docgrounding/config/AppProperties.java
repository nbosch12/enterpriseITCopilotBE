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
    private Analytics analytics = new Analytics();
    private Grounding grounding = new Grounding();

    @Setter
    @Getter
    public static class Docupedia {
        private String baseUrl;
        private String bearerToken;
        private int pageSize = 50;
        private String defaultRepositoryId = "default";
        private String defaultS3Prefix = "default";
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
    public static class AzureMonitor {
        private String workspaceId;
        private String tenantId;
        private String clientId;
        private String clientSecret;
        private boolean enabled = false;
    }

    /**
     * Azure Cosmos DB for MongoDB connection behaviour.
     *
     * <p>Credentials and host live under {@code spring.data.mongodb.*} (normally supplied as the
     * {@code MONGODB_URI} environment variable), never in source. The settings here only control
     * how the QR code collections are read.</p>
     */
    @Setter
    @Getter
    public static class CosmosMongo {
        /**
         * Master switch. When false the Cosmos beans are not registered at all, so the rest of the
         * backend starts and serves questions exactly as it does today.
         */
        private boolean enabled = false;
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 30;
        /** Caps the records returned by one curated query. */
        private int maxQueryResults = 500;
        /**
         * Upper bound for analytics scans. Separate from {@code maxQueryResults}, which caps a
         * single response; a monthly total must never be silently truncated to the first N
         * documents.
         */
        private int maxScanDocuments = 50000;
        /**
         * AUTO - typed server-side filter first, client-side rescan when days are missing (default).
         * SERVER - server-side filter only.
         * CLIENT_SCAN - always scan and filter in the application; slowest but format-proof.
         */
        private String dateFilterStrategy = "AUTO";
        /** Primary date field of qrcodeProcessingReport. */
        private String processingDateField = "storageDate";
    }

    /**
     * Deterministic aggregation over the QR code collections.
     *
     * <p>Top-k vector retrieval cannot answer "which plant received the most in August", because the
     * retriever only ever shows the model a handful of chunks. Those questions are computed in
     * MongoDB and the exact totals are handed to the grounding model as context.</p>
     */
    @Setter
    @Getter
    public static class Analytics {
        private boolean enabled = true;
        /**
         * When exporting MongoDB data to the object store, also upload pre-computed
         * monthly/weekly/daily rollup chunks so retrieval can find totals in a single chunk.
         */
        private boolean uploadRollups = true;
        /** DAY_BLOCKS (1-7, 8-14, ...) or CALENDAR (Monday-Sunday weeks). */
        private String weekOfMonthMode = "DAY_BLOCKS";
        /** Date field used when grouping qrCodesTracking. */
        private String trackingDateField = "packagingDate";
        /** Fallback when the primary tracking date field is absent. */
        private String trackingFallbackDateField = "lastModifiedDate";
        private int maxRankedResults = 25;
        /** Hard cap on the characters of retrieved context handed to the grounding model. */
        private int maxContextCharacters = 12000;
        /** Hard cap on the number of records rendered into that context. */
        private int maxContextRecords = 60;
    }

    /**
     * Where grounding data is written in the object store.
     *
     * <p>Used by the MongoDB-to-S3 ingestion path so every export lands under one include path
     * instead of a new random folder per call.</p>
     */
    @Setter
    @Getter
    public static class Grounding {
        /** Optional vector data repository that the exported MongoDB chunks belong to. */
        private String repositoryId;
        /**
         * Object-store folder that exported grounding data nests under. Empty means the bucket root.
         * It must sit inside the include path of the pipeline that indexes the bucket, or the
         * exported files are written but never indexed.
         */
        private String rootPrefix = "";
    }
}
