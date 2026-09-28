package com.bosch.demo.docgrounding.service.tools;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.MongoGroundingQueryRequest;
import com.bosch.demo.docgrounding.model.ToolRequest;
import com.bosch.demo.docgrounding.model.ToolResult;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsBucket;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsPeriod;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsQuery;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsResult;
import com.bosch.demo.docgrounding.model.analytics.CoverageReport;
import com.bosch.demo.docgrounding.service.AnalyticsQuestionParser;
import com.bosch.demo.docgrounding.service.CosmosMongoGroundingService;
import com.bosch.demo.docgrounding.service.QrCodeAnalyticsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestration tool that answers questions about the QR code data held in Azure Cosmos DB for
 * MongoDB.
 *
 * <p>The tool only <em>retrieves</em>. It queries Cosmos DB, turns the matching records into a
 * bounded block of grounding context, and hands that back to the orchestrator, which sends the
 * original question plus this context to the grounding model. The model writes the answer; raw
 * query output is never returned to the user as the answer. The structured results still travel in
 * {@link ToolResult#rawPayload()} for UI drill-down, which the existing response contract already
 * carries for every tool.</p>
 *
 * <p>Two retrieval shapes are supported, because the two kinds of question need different data:</p>
 * <ul>
 *   <li><b>Counting and ranking</b> ("which plant received the most QR codes in August 2026") is
 *       computed over the whole period in MongoDB. Top-k similarity cannot answer these: it shows
 *       the model a handful of chunks out of a month, so the model picks a winner from a sample.
 *       Here the totals arrive already summed and ranked.</li>
 *   <li><b>Record lookup</b> ("show me the scans from Germany in July 2026") runs the curated query
 *       for the collection in question and renders the matching records as facts.</li>
 * </ul>
 *
 * <p>Registered only when {@code app.cosmos-mongo.enabled=true}. When Cosmos DB is switched off the
 * bean is absent, the registry simply never offers it, and every other question type behaves
 * exactly as before.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.cosmos-mongo", name = "enabled", havingValue = "true")
public class CosmosMongoTool implements CopilotTool {

    private static final String TOOL_NAME = "COSMOS_QRCODE";

    /**
     * Marker read by the orchestrator. A tool that sets this is supplying retrieved records as
     * context, so the model must be told to answer from those records only.
     */
    public static final String STRICT_GROUNDING_FLAG = "strictGrounding";

    private final CosmosMongoGroundingService groundingService;
    private final QrCodeAnalyticsService analyticsService;
    private final AnalyticsQuestionParser questionParser;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public CosmosMongoTool(CosmosMongoGroundingService groundingService,
                           QrCodeAnalyticsService analyticsService,
                           AnalyticsQuestionParser questionParser,
                           AppProperties properties,
                           ObjectMapper objectMapper) {
        this.groundingService = groundingService;
        this.analyticsService = analyticsService;
        this.questionParser = questionParser;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "Queries the QR code collections in Azure Cosmos DB for MongoDB (processing reports, "
                + "packaging reports, scan events, QR code tracking) and returns the matching records "
                + "as grounding context.";
    }

    /**
     * Domain nouns only. Generic verbs such as "received" or "failed" are deliberately left out:
     * every real question about this data also names the domain ("QR codes", "plant", "scans"), so
     * the bare verbs would add nothing here while pulling unrelated questions away from the logs and
     * documentation tools.
     */
    @Override
    public String[] keywords() {
        return new String[]{
                "plant", "plants", "which", "many", "highest", "lowest", "count",
                "packaging", "packaged", "tracking", "labelling",
                "sourcename", "source name",
                "storagedate", "storage date",
                "cosmos", "mongo", "mongodb"
        };
    }

    @Override
    public Mono<ToolResult> execute(ToolRequest request) {
        // The Mongo driver is blocking; keep it off the WebFlux event-loop threads.
        return Mono.fromCallable(() -> retrieve(request))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> {
                    String detail = redact(describeFailure(ex));
                    // Log the redacted message with the stack trace attached separately: a driver or
                    // configuration error can carry the connection string, and that must reach
                    // neither the log nor the caller.
                    log.error("[{}] Cosmos DB retrieval failed: {}", TOOL_NAME, detail, redacted(ex));
                    return Mono.just(ToolResult.error(TOOL_NAME, detail));
                });
    }

    // -------------------------------------------------------------------------
    // Retrieval
    // -------------------------------------------------------------------------

    private ToolResult retrieve(ToolRequest request) {
        String question = request.question();
        String weekMode = analyticsService.weekOfMonthMode();

        // app.analytics.enabled=false turns off the aggregation path only. Cosmos DB questions are
        // still answered, from the underlying records instead of computed totals - an escape hatch
        // if a ranking result ever looks wrong, without losing access to the data itself.
        if (analyticsConfig().isEnabled()) {
            Optional<AnalyticsQuery> analyticsQuery = questionParser.parse(question, weekMode);
            if (analyticsQuery.isPresent()) {
                return rankingContext(analyticsQuery.get());
            }

            // A ranking question that named no period: say what is missing rather than guessing one.
            if (questionParser.requiresPeriodClarification(question, weekMode)) {
                return missingPeriodResult();
            }
        } else {
            log.debug("[{}] Aggregation disabled (app.analytics.enabled=false); answering from records",
                    TOOL_NAME);
        }

        return recordContext(request, question, weekMode);
    }

    /**
     * Counting and ranking questions: totals computed across the whole period.
     */
    private ToolResult rankingContext(AnalyticsQuery query) {
        AnalyticsResult result = analyticsService.rank(query);

        if (result.ranked() == null || result.ranked().isEmpty()) {
            return emptyResult(
                    "No QR code records were found in " + query.period().label() + ".",
                    Map.of("period", query.period().label(),
                           "collection", result.dataSource().collection()));
        }

        StringBuilder context = new StringBuilder();
        context.append("Aggregated QR code totals computed over every record in the period.\n")
               .append("Collection: ").append(result.dataSource().collection()).append("\n")
               .append("Period: ").append(result.period().label())
               .append(" (").append(result.period().from()).append(" to ").append(result.period().to()).append(")\n")
               .append("Grouped by: ").append(result.dimension().label()).append("\n")
               .append("Measure: ").append(result.metric().label()).append("\n")
               .append("Days with data in this period: ").append(result.daysCovered()).append("\n");

        if (result.missingDates() != null && !result.missingDates().isEmpty()) {
            context.append("Days with no source record at all: ")
                   .append(String.join(", ", result.missingDates())).append("\n");
        }

        context.append("\nRanked totals (highest first unless stated otherwise):\n");
        int rank = 1;
        for (AnalyticsBucket bucket : result.ranked()) {
            context.append("  ").append(rank++).append(". ").append(bucket.key())
                   .append(" - ").append(result.metric().label()).append(": ").append(bucket.metric(result.metric()));
            if (result.dataSource() == com.bosch.demo.docgrounding.model.analytics.AnalyticsDataSource.PROCESSING_REPORT) {
                context.append(" (received ").append(bucket.received())
                       .append(", saved ").append(bucket.saved())
                       .append(", failed ").append(bucket.failed())
                       .append(", days with data ").append(bucket.daysWithData()).append(")");
            }
            context.append("\n");
        }
        context.append("\nTotal across all ").append(result.dimension().label())
               .append(" values: ").append(result.total()).append("\n");

        if (result.notes() != null && !result.notes().isEmpty()) {
            context.append("\nRetrieval notes:\n");
            for (String note : result.notes()) {
                context.append("  - ").append(note).append("\n");
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(STRICT_GROUNDING_FLAG, true);
        metadata.put("retrievalMode", "AGGREGATE");
        metadata.put("collection", result.dataSource().collection());
        metadata.put("period", result.period().label());
        metadata.put("periodStart", String.valueOf(result.period().from()));
        metadata.put("periodEnd", String.valueOf(result.period().to()));
        metadata.put("groupedBy", result.dimension().label());
        metadata.put("metric", result.metric().label());
        metadata.put("rowCount", result.ranked().size());
        metadata.put("documentsScanned", result.documentsScanned());
        metadata.put("daysCovered", result.daysCovered());
        if (result.missingDates() != null && !result.missingDates().isEmpty()) {
            metadata.put("missingDates", result.missingDates());
        }

        return ToolResult.ok(TOOL_NAME, bound(context.toString()), toJson(result), metadata);
    }

    /**
     * Record-level questions: run the curated query for the collection the question is about.
     */
    private ToolResult recordContext(ToolRequest request, String question, String weekMode) {
        String queryType = resolveQueryType(question);
        Optional<AnalyticsPeriod> period = questionParser.parsePeriodOnly(question, weekMode);

        LocalDate from = period.map(AnalyticsPeriod::from).orElse(null);
        LocalDate to = period.map(AnalyticsPeriod::to).orElse(null);

        MongoGroundingQueryRequest query = new MongoGroundingQueryRequest(
                queryType,
                from == null ? null : from.toString(),
                to == null ? null : to.toString(),
                request.param("plant"),
                request.param("uuid"),
                request.param("articleNumber"),
                request.param("applicationId"),
                request.param("sourceName"),
                request.param("country"),
                maxContextRecords(),
                Boolean.FALSE,
                Boolean.FALSE);

        List<CosmosMongoGroundingService.RetrievedRecord> records = groundingService.retrieveRecords(query);

        if (records.isEmpty()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("queryType", queryType);
            if (period.isPresent()) {
                meta.put("period", period.get().label());
            }
            return emptyResult(
                    "No records matched this query in the QR code collections"
                            + period.map(p -> " for " + p.label()).orElse("") + ".",
                    meta);
        }

        StringBuilder context = new StringBuilder();
        context.append("Records retrieved from the QR code collections.\n")
               .append("Query type: ").append(queryType).append("\n");
        period.ifPresent(p -> context.append("Period: ").append(p.label())
                .append(" (").append(p.from()).append(" to ").append(p.to()).append(")\n"));
        context.append("Records returned: ").append(records.size()).append("\n\n");

        int index = 1;
        for (CosmosMongoGroundingService.RetrievedRecord record : records) {
            context.append("--- record ").append(index++)
                   .append(" (").append(record.collection()).append(") ---\n")
                   .append(record.content().trim()).append("\n\n");
        }

        List<String> collections = records.stream().map(CosmosMongoGroundingService.RetrievedRecord::collection)
                .distinct().toList();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(STRICT_GROUNDING_FLAG, true);
        metadata.put("retrievalMode", "RECORDS");
        metadata.put("queryType", queryType);
        metadata.put("collections", collections);
        metadata.put("recordCount", records.size());
        period.ifPresent(p -> {
            metadata.put("period", p.label());
            metadata.put("periodStart", String.valueOf(p.from()));
            metadata.put("periodEnd", String.valueOf(p.to()));
        });

        return ToolResult.ok(TOOL_NAME, bound(context.toString()), toJson(records), metadata);
    }

    /**
     * Pick the curated query that matches the collection the question is about.
     *
     * <p>Order matters: a question naming both scans and tracking wants the joined view, and a bare
     * mention of plants or received counts belongs to the daily processing report.</p>
     */
    String resolveQueryType(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);

        boolean mentionsScan = text.contains("scan");
        boolean mentionsTracking = text.contains("tracking") || text.contains("uuid")
                || text.contains("labelling") || text.contains("label level");

        if (mentionsScan && mentionsTracking) {
            return "SCAN_WITH_TRACKING";
        }
        if (mentionsScan) {
            return "SCAN_ACTIVITY";
        }
        if (mentionsTracking || text.contains("article") || text.contains("application id")
                || text.contains("applicationid") || text.contains("sourcename")
                || text.contains("source name")) {
            return "QR_TRACKING";
        }
        if (text.contains("packaging") || text.contains("packaged") || text.contains("storage item")) {
            return "PACKAGING_SUMMARY";
        }
        return "PROCESSING_SUMMARY";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * An empty result is still a successful retrieval: the model is told plainly that the query
     * matched nothing, so it can say so instead of filling the gap.
     */
    private ToolResult emptyResult(String message, Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>(extraMetadata);
        metadata.put(STRICT_GROUNDING_FLAG, true);
        metadata.put("recordCount", 0);
        metadata.put("emptyResult", true);

        String context = message
                + "\nThe query ran successfully against the QR code collections and returned no rows. "
                + "There is no data available to answer this question for the period or filters requested.";

        return ToolResult.ok(TOOL_NAME, context, "[]", metadata);
    }

    private ToolResult missingPeriodResult() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(STRICT_GROUNDING_FLAG, true);
        metadata.put("retrievalMode", "CLARIFICATION_NEEDED");
        metadata.put("recordCount", 0);

        String context = """
                No query was run because the question does not name a time period, and these totals \
                are only meaningful for a specific window.
                Ask the user which period they mean, and offer these forms:
                  - a month, for example "August 2026" or "2026-08"
                  - a week of a month, for example "the 1st week of August 2026"
                  - a single day, for example "2 August 2026" or "2026-08-02"
                Do not guess a period and do not provide any figures.""";

        return ToolResult.ok(TOOL_NAME, context, "[]", metadata);
    }

    /**
     * Keep the context inside the configured budget so a broad question cannot push an unbounded
     * payload into the model prompt.
     */
    private String bound(String context) {
        int max = Math.max(1000, analyticsConfig().getMaxContextCharacters());
        if (context.length() <= max) {
            return context.trim();
        }
        return context.substring(0, max).trim()
                + "\n\n[Context truncated at " + max + " characters. "
                + "The records above are complete; later records were omitted.]";
    }

    private int maxContextRecords() {
        return Math.max(1, analyticsConfig().getMaxContextRecords());
    }

    private AppProperties.Analytics analyticsConfig() {
        AppProperties.Analytics analytics = properties.getAnalytics();
        return analytics == null ? new AppProperties.Analytics() : analytics;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.debug("[{}] Could not serialise payload for UI drill-down", TOOL_NAME, ex);
            return String.valueOf(value);
        }
    }

    /**
     * Strip credentials out of anything derived from an exception message.
     *
     * <p>A connection error can carry the whole Cosmos DB connection string, account key included.
     * Everything returned to the caller or written to the log passes through here first.</p>
     */
    static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String cleaned = text
                // scheme://user:password@host -> scheme://***:***@host
                .replaceAll("(?i)([a-z][a-z0-9+.\\-]*://)[^/\\s:@]+:[^/\\s@]+@", "$1***:***@")
                // key=value secrets in query strings or key-value text
                .replaceAll("(?i)(accountkey|password|secret|apikey|api-key|token|sig)=[^;&\\s]+", "$1=***");
        // Any surviving connection string is replaced wholesale rather than picked apart.
        return cleaned.replaceAll("(?i)mongodb(\\+srv)?://\\S+", "mongodb://***");
    }

    /**
     * A copy of the exception carrying the original stack trace but a redacted message, so the log
     * keeps its diagnostic value without recording a secret.
     */
    private static Throwable redacted(Throwable ex) {
        if (ex == null) {
            return null;
        }
        Throwable copy = new RuntimeException(
                ex.getClass().getName() + ": " + redact(ex.getMessage()),
                ex.getCause() == null ? null : redacted(ex.getCause()));
        copy.setStackTrace(ex.getStackTrace());
        return copy;
    }

    /**
     * Turn an exception into a message that names the failure class without leaking connection
     * details, credentials or stack frames.
     */
    private String describeFailure(Throwable ex) {
        String type = ex.getClass().getSimpleName();
        if (type.isEmpty()) {
            // Anonymous and lambda classes have no simple name; fall back to the nearest named type.
            Class<?> named = ex.getClass().getSuperclass();
            type = named == null ? "Exception" : named.getSimpleName();
        }
        if (type.contains("Timeout")) {
            return "The QR code database did not respond in time. The configured read timeout is "
                    + properties.getCosmosMongo().getReadTimeoutSeconds() + " seconds.";
        }
        if (type.startsWith("Mongo")) {
            return "The QR code database could not be queried (" + type + "). "
                    + "Check that the Cosmos DB connection is configured and reachable.";
        }
        String message = ex.getMessage();
        if (ex instanceof IllegalArgumentException
                || (message != null && message.contains("must be configured"))) {
            // Our own configuration errors are actionable, so they are passed through as written.
            return message == null ? type : message;
        }
        if (ex instanceof IllegalStateException) {
            // Driver-level state errors ("state should be: open") mean nothing on their own.
            return "The QR code database could not be queried"
                    + (message == null ? "" : " (" + message + ")")
                    + ". Check that the Cosmos DB connection is configured and reachable.";
        }
        return "QR code data retrieval failed (" + type + ").";
    }

    /**
     * Exposed so the orchestrator can describe what was retrieved without re-reading the payload.
     */
    static List<String> describeCollections(List<CosmosMongoGroundingService.RetrievedRecord> records) {
        List<String> names = new ArrayList<>();
        for (CosmosMongoGroundingService.RetrievedRecord record : records) {
            if (!names.contains(record.collection())) {
                names.add(record.collection());
            }
        }
        return names;
    }

    /** Coverage diagnostics, kept available for callers that ask which days are missing. */
    CoverageReport coverage(LocalDate from, LocalDate to) {
        return analyticsService.coverage(from, to);
    }
}
