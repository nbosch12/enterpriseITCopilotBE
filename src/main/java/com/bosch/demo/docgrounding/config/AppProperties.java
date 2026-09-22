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
    private TrustStore trustStore = new TrustStore();
    private Grounding grounding = new Grounding();
    private Analytics analytics = new Analytics();

    /**
     * One vector data repository for everything that gets grounded, Docupedia and MongoDB alike.
     *
     * <p>Previously every MongoDB prepare call wrote to a fresh {@code mongodb/<type>/<uuid>} prefix
     * and needed its own pipeline, so the same August data ended up in several repositories at once.
     * Pinning a single repository and a single include path keeps retrieval over one consistent
     * index.</p>
     */
    @Setter
    @Getter
    public static class Grounding {
        /** The single SAP AI Core vector data repository used by every /api/vector/ask call. */
        private String repositoryId;
        /** Optional S3 folder that all grounding data is nested under. Empty means the bucket root. */
        private String rootPrefix = "";
        /** Optional: the pipeline feeding {@link #repositoryId}, for status and trigger calls. */
        private String pipelineId;
        /** When true, a repositoryId supplied on a request is ignored in favour of the configured one. */
        private boolean enforceSingleRepository = true;
    }

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
        private Proxy proxy = new Proxy();
    }

    /** Optional outbound HTTP proxy (corporate networks answering with HTTP 407). */
    @Setter
    @Getter
    public static class Proxy {
        private boolean enabled = false;
        private String host;
        private int port = 8080;
        private String username;
        private String password;
        /** Comma separated hosts that bypass the proxy. */
        private String nonProxyHosts;
    }

    /**
     * Truststore holding the CA of a TLS-intercepting proxy.
     * Without it the JDK reports "PKIX path building failed".
     */
    @Setter
    @Getter
    public static class TrustStore {
        private String path;
        private String password = "changeit";
        private String type = "JKS";
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
        /** Connection details live under spring.data.mongodb.* (host/port/database/uri). */
        private boolean enabled = false;
        private int connectTimeoutSeconds = 10;
        private int readTimeoutSeconds = 30;
        private int maxQueryResults = 500;
        /**
         * Upper bound for analytics/ingestion scans. Separate from {@code maxQueryResults}, which
         * caps a single grounding response; a monthly total must never be silently truncated to the
         * first N documents.
         */
        private int maxScanDocuments = 50000;
        /**
         * AUTO   - typed server-side filter first, client-side rescan when days are missing (default).
         * SERVER - server-side filter only.
         * CLIENT_SCAN - always scan and filter in Java; slowest but format-proof.
         */
        private String dateFilterStrategy = "AUTO";
        /** Primary date field of qrcodeProcessingReport. */
        private String processingDateField = "storageDate";
    }

    /**
     * Deterministic aggregation over the QR code collections.
     *
     * <p>Top-k vector retrieval cannot answer "which plant received the most in August" because the
     * retriever only ever shows the model a handful of chunks. These questions are computed in
     * MongoDB instead and the exact result is returned.</p>
     */
    @Setter
    @Getter
    public static class Analytics {
        private boolean enabled = true;
        /** Let /api/vector/ask answer recognised analytics questions from MongoDB directly. */
        private boolean routeVectorAsk = true;
        /** Also upload pre-computed monthly/weekly/daily rollup chunks so RAG can find totals. */
        private boolean uploadRollups = true;
        /** DAY_BLOCKS (1-7, 8-14, ...) or CALENDAR (Monday-Sunday weeks). */
        private String weekOfMonthMode = "DAY_BLOCKS";
        /** Date field used when grouping qrCodesTracking. */
        private String trackingDateField = "packagingDate";
        /** Fallback when the primary tracking date field is absent. */
        private String trackingFallbackDateField = "lastModifiedDate";
        private int maxRankedResults = 25;
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
