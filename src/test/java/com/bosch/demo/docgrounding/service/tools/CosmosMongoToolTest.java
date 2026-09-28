package com.bosch.demo.docgrounding.service.tools;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.ToolRequest;
import com.bosch.demo.docgrounding.model.ToolResult;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsBucket;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsDataSource;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsDimension;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsMetric;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsPeriod;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsQuery;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsResult;
import com.bosch.demo.docgrounding.model.MongoGroundingQueryRequest;
import com.bosch.demo.docgrounding.service.AnalyticsQuestionParser;
import com.bosch.demo.docgrounding.service.CosmosMongoGroundingService;
import com.bosch.demo.docgrounding.service.QrCodeAnalyticsService;
import com.bosch.demo.docgrounding.support.PeriodSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers Cosmos DB query selection and how retrieved rows are mapped into grounding context.
 *
 * <p>Collaborators are hand-written stubs rather than mocks: the point of these tests is what the
 * tool does with the rows it gets back, so the stubs record what they were asked for and hand back
 * fixed data.</p>
 */
class CosmosMongoToolTest {

    private StubGroundingService groundingService;
    private StubAnalyticsService analyticsService;
    private CosmosMongoTool tool;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getCosmosMongo().setEnabled(true);

        groundingService = new StubGroundingService();
        analyticsService = new StubAnalyticsService(properties);

        tool = new CosmosMongoTool(
                groundingService,
                analyticsService,
                new AnalyticsQuestionParser(),
                properties,
                new ObjectMapper());
    }

    private ToolResult run(String question) {
        return tool.execute(new ToolRequest(question, "session-1", Map.of())).block();
    }

    // -------------------------------------------------------------------------
    // Query selection
    // -------------------------------------------------------------------------

    @Test
    void selectsProcessingReportForPlantQuestions() {
        assertEquals("PROCESSING_SUMMARY", tool.resolveQueryType("how many QR codes did the plant receive"));
    }

    @Test
    void selectsScanActivityForScanQuestions() {
        assertEquals("SCAN_ACTIVITY", tool.resolveQueryType("show me the scans from Germany"));
    }

    @Test
    void selectsTrackingForArticleAndSourceQuestions() {
        assertEquals("QR_TRACKING", tool.resolveQueryType("list QR codes for article number 0204114896EE9"));
        assertEquals("QR_TRACKING", tool.resolveQueryType("which sourceName produced these codes"));
    }

    @Test
    void selectsJoinedViewWhenQuestionNamesBothScansAndTracking() {
        assertEquals("SCAN_WITH_TRACKING", tool.resolveQueryType("scan events with their tracking records"));
    }

    @Test
    void selectsPackagingForPackagingQuestions() {
        assertEquals("PACKAGING_SUMMARY", tool.resolveQueryType("packaging summary for last month"));
    }

    // -------------------------------------------------------------------------
    // Record retrieval and mapping
    // -------------------------------------------------------------------------

    @Test
    void recordQuestionPassesResolvedPeriodAndLimitToTheQuery() {
        groundingService.records = List.of(
                new CosmosMongoGroundingService.RetrievedRecord("scanlog", "k1", "uuid: abc\nscanDate: 2026-07-04"),
                new CosmosMongoGroundingService.RetrievedRecord("scanlog", "k2", "uuid: def\nscanDate: 2026-07-09"));

        ToolResult result = run("show me the scans from Germany in July 2026");

        assertNotNull(groundingService.lastRequest, "the tool should have run a Cosmos query");
        assertEquals("SCAN_ACTIVITY", groundingService.lastRequest.queryType());
        assertEquals("2026-07-01", groundingService.lastRequest.fromDate());
        assertEquals("2026-07-31", groundingService.lastRequest.toDate());
        assertEquals(Integer.valueOf(60), groundingService.lastRequest.limit(),
                "limit should come from maxContextRecords");
        // Retrieval must not write anything back to the object store.
        assertEquals(Boolean.FALSE, groundingService.lastRequest.replaceExisting());
    }

    @Test
    void retrievedRecordsAreMappedIntoTheGroundingContext() {
        groundingService.records = List.of(
                new CosmosMongoGroundingService.RetrievedRecord("scanlog", "k1", "uuid: abc\nscanDate: 2026-07-04"),
                new CosmosMongoGroundingService.RetrievedRecord("scanlog", "k2", "uuid: def\nscanDate: 2026-07-09"));

        ToolResult result = run("show me the scans from Germany in July 2026");

        assertTrue(result.success());
        assertTrue(result.summary().contains("uuid: abc"), "record content should reach the context");
        assertTrue(result.summary().contains("uuid: def"), "every record should reach the context");
        assertTrue(result.summary().contains("Records returned: 2"));
        assertEquals(2, result.metadata().get("recordCount"));
        assertEquals("RECORDS", result.metadata().get("retrievalMode"));
        assertEquals(List.of("scanlog"), result.metadata().get("collections"));
    }

    @Test
    void aggregateQuestionIsAnsweredFromComputedTotalsNotRecords() {
        analyticsService.result = rankingResult();

        ToolResult result = run("which plant received the highest number of QR codes in August 2026");

        assertTrue(result.success());
        assertEquals("AGGREGATE", result.metadata().get("retrievalMode"));
        assertTrue(result.summary().contains("packit"), "winner should be present");
        assertTrue(result.summary().contains("30980"), "exact total should be present");
        assertTrue(result.summary().contains("plantA"), "full ranking should be present");
        assertTrue(result.summary().contains("qrcodeProcessingReport"), "source collection should be named");
        assertTrue(groundingService.lastRequest == null,
                "an aggregate question should not fall through to a record query");
    }

    @Test
    void missingDaysTravelWithTheTotalsSoTheAnswerCanQualifyThem() {
        analyticsService.result = rankingResult();

        ToolResult result = run("which plant received the highest number of QR codes in August 2026");

        assertTrue(result.summary().contains("2026-08-20"), "missing dates belong in the context");
        assertEquals(List.of("2026-08-20"), result.metadata().get("missingDates"));
    }

    // -------------------------------------------------------------------------
    // Grounding discipline
    // -------------------------------------------------------------------------

    @Test
    void everyRetrievalIsFlaggedForStrictGrounding() {
        analyticsService.result = rankingResult();
        assertEquals(Boolean.TRUE,
                run("which plant received the highest number of QR codes in August 2026")
                        .metadata().get(CosmosMongoTool.STRICT_GROUNDING_FLAG));

        groundingService.records = List.of(
                new CosmosMongoGroundingService.RetrievedRecord("scanlog", "k1", "uuid: abc"));
        assertEquals(Boolean.TRUE,
                run("show me the scans in July 2026").metadata().get(CosmosMongoTool.STRICT_GROUNDING_FLAG));
    }

    @Test
    void contextIsCappedSoABroadQuestionCannotFloodThePrompt() {
        List<CosmosMongoGroundingService.RetrievedRecord> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            many.add(new CosmosMongoGroundingService.RetrievedRecord(
                    "scanlog", "k" + i, "uuid: " + i + "\n" + "x".repeat(200)));
        }
        groundingService.records = many;

        ToolResult result = run("show me the scans in July 2026");

        assertTrue(result.summary().length() <= 12_400,
                "context should respect the configured character budget, was " + result.summary().length());
        assertTrue(result.summary().contains("truncated"), "truncation should be stated, not silent");
    }

    // -------------------------------------------------------------------------
    // Empty results and failures
    // -------------------------------------------------------------------------

    @Test
    void emptyResultIsASuccessfulRetrievalThatSaysThereIsNoData() {
        groundingService.records = List.of();

        ToolResult result = run("show me the scans in July 2026");

        assertTrue(result.success(), "an empty result is not a failure");
        assertEquals(Boolean.TRUE, result.metadata().get("emptyResult"));
        assertEquals(0, result.metadata().get("recordCount"));
        assertTrue(result.summary().contains("no rows"), "the model must be told the query matched nothing");
        assertEquals(Boolean.TRUE, result.metadata().get(CosmosMongoTool.STRICT_GROUNDING_FLAG));
    }

    @Test
    void emptyAggregateResultAlsoReportsNoData() {
        analyticsService.result = new AnalyticsResult(
                "q", AnalyticsDimension.PLANT, AnalyticsMetric.RECEIVED,
                PeriodSupport.month(YearMonth.of(2026, 8)), AnalyticsDataSource.PROCESSING_REPORT,
                List.of(), null, 0, 0, 0, List.of(), List.of(), "");

        ToolResult result = run("which plant received the most QR codes in August 2026");

        assertTrue(result.success());
        assertEquals(Boolean.TRUE, result.metadata().get("emptyResult"));
        assertTrue(result.summary().contains("No QR code records were found"));
    }

    @Test
    void databaseFailureIsReportedWithoutLeakingInternals() {
        groundingService.failure = new IllegalStateException(
                "connection refused to mongodb://user:secret@host:10255");

        ToolResult result = run("show me the scans in July 2026");

        assertFalse(result.success(), "a database failure must not look like a successful retrieval");
        assertNotNull(result.errorMessage());
        assertFalse(result.summary().contains("secret"), "credentials must never reach the caller");
    }

    @Test
    void mongoFailureIsDescribedByClassRatherThanStackTrace() {
        groundingService.failure = new RuntimeException("boom") {
            @Override
            public String toString() {
                return "MongoTimeoutException: timed out";
            }
        };

        ToolResult result = run("show me the scans in July 2026");

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }

    // -------------------------------------------------------------------------
    // The app.analytics.enabled switch
    // -------------------------------------------------------------------------

    @Test
    void disablingAnalyticsFallsBackToRecordsRatherThanTurningCosmosOff() {
        AppProperties properties = new AppProperties();
        properties.getCosmosMongo().setEnabled(true);
        properties.getAnalytics().setEnabled(false);

        CosmosMongoTool recordsOnly = new CosmosMongoTool(
                groundingService,
                new StubAnalyticsService(properties),
                new AnalyticsQuestionParser(),
                properties,
                new ObjectMapper());

        groundingService.records = List.of(
                new CosmosMongoGroundingService.RetrievedRecord("qrcodeProcessingReport", "k", "plant: packit"));

        ToolResult result = recordsOnly.execute(new ToolRequest(
                "which plant received the highest number of QR codes in August 2026",
                "session-1", Map.of())).block();

        assertTrue(result.success());
        assertEquals("RECORDS", result.metadata().get("retrievalMode"),
                "with aggregation off the question should still be answered, from records");
        assertNotNull(groundingService.lastRequest, "a record query should have run");
    }

    @Test
    void disablingAnalyticsAlsoSkipsThePeriodClarification() {
        AppProperties properties = new AppProperties();
        properties.getCosmosMongo().setEnabled(true);
        properties.getAnalytics().setEnabled(false);

        CosmosMongoTool recordsOnly = new CosmosMongoTool(
                groundingService,
                new StubAnalyticsService(properties),
                new AnalyticsQuestionParser(),
                properties,
                new ObjectMapper());

        groundingService.records = List.of();

        ToolResult result = recordsOnly.execute(new ToolRequest(
                "which plant received the highest number of QR codes", "session-1", Map.of())).block();

        assertTrue(result.success());
        assertEquals(Boolean.TRUE, result.metadata().get("emptyResult"));
    }

    @Test
    void rankingQuestionWithoutAPeriodAsksForOneInsteadOfGuessing() {
        ToolResult result = run("which plant received the highest number of QR codes");

        assertTrue(result.success());
        assertEquals("CLARIFICATION_NEEDED", result.metadata().get("retrievalMode"));
        assertTrue(result.summary().contains("does not name a time period"));
        assertTrue(result.summary().contains("Do not guess"), "the model must be told not to invent a period");
        assertTrue(groundingService.lastRequest == null, "no query should run without a period");
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private AnalyticsResult rankingResult() {
        AnalyticsPeriod period = PeriodSupport.month(YearMonth.of(2026, 8));
        List<AnalyticsBucket> ranked = List.of(
                new AnalyticsBucket("packit", 30980, 30830, 150, 30, 30),
                new AnalyticsBucket("plantA", 25300, 25200, 100, 30, 30));
        return new AnalyticsResult(
                "which plant received the highest number of QR codes in August 2026",
                AnalyticsDimension.PLANT,
                AnalyticsMetric.RECEIVED,
                period,
                AnalyticsDataSource.PROCESSING_REPORT,
                ranked,
                ranked.get(0),
                56280,
                30,
                30,
                List.of("2026-08-20"),
                List.of("Server-side date filter matched 30 documents."),
                "precomputed answer");
    }

    /** Records what was asked for and returns fixed rows, or throws when a failure is configured. */
    private static final class StubGroundingService extends CosmosMongoGroundingService {
        List<RetrievedRecord> records = List.of();
        MongoGroundingQueryRequest lastRequest;
        RuntimeException failure;

        StubGroundingService() {
            super(null, null, null, null, null, new AppProperties());
        }

        @Override
        public List<RetrievedRecord> retrieveRecords(MongoGroundingQueryRequest request) {
            this.lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return records;
        }
    }

    /** Returns a fixed ranking so the test controls exactly what the tool renders. */
    private static final class StubAnalyticsService extends QrCodeAnalyticsService {
        AnalyticsResult result;

        StubAnalyticsService(AppProperties properties) {
            super(null, properties);
        }

        @Override
        public AnalyticsResult rank(AnalyticsQuery query) {
            if (result == null) {
                throw new IllegalStateException("no ranking configured for this test");
            }
            return result;
        }

        @Override
        public String weekOfMonthMode() {
            return PeriodSupport.MODE_DAY_BLOCKS;
        }
    }
}
