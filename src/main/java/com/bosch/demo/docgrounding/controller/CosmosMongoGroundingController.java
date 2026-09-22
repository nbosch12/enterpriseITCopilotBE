package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.model.MongoGroundingQueryRequest;
import com.bosch.demo.docgrounding.model.MongoGroundingSyncResult;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsAskRequest;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsDimension;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsMetric;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsPeriod;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsQuery;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsRequest;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsResult;
import com.bosch.demo.docgrounding.model.analytics.CoverageReport;
import com.bosch.demo.docgrounding.service.AnalyticsQuestionParser;
import com.bosch.demo.docgrounding.service.CosmosMongoGroundingService;
import com.bosch.demo.docgrounding.service.QrCodeAnalyticsService;
import com.bosch.demo.docgrounding.support.DateValueSupport;
import com.bosch.demo.docgrounding.support.PeriodSupport;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mongodb")
@ConditionalOnProperty(prefix = "app.cosmos-mongo", name = "enabled", havingValue = "true")
public class CosmosMongoGroundingController {

    private final CosmosMongoGroundingService groundingService;
    private final QrCodeAnalyticsService analyticsService;
    private final AnalyticsQuestionParser questionParser;

    public CosmosMongoGroundingController(
            CosmosMongoGroundingService groundingService,
            QrCodeAnalyticsService analyticsService,
            AnalyticsQuestionParser questionParser) {
        this.groundingService = groundingService;
        this.analyticsService = analyticsService;
        this.questionParser = questionParser;
    }

    @GetMapping("/query-types")
    public List<String> queryTypes() {
        return Arrays.stream(CosmosMongoGroundingService.QueryType.values()).map(Enum::name).toList();
    }

    /**
     * Write MongoDB data into the single grounding location. Keys are deterministic, so running this
     * again for the same range overwrites instead of adding a second copy.
     */
    @PostMapping("/grounding/prepare")
    public Mono<MongoGroundingSyncResult> prepare(@Valid @RequestBody MongoGroundingQueryRequest request) {
        // Mongo driver + S3 client are blocking; keep work off WebFlux event-loop threads.
        return Mono.fromCallable(() -> groundingService.prepareGroundingData(request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Remove the old {@code mongodb/<queryType>/<uuid>/} folders so they stop feeding duplicate
     * copies into the single repository. Defaults to a dry run that only counts objects.
     */
    @PostMapping("/grounding/cleanup-legacy")
    public Mono<Map<String, Object>> cleanupLegacy(@RequestParam(defaultValue = "true") boolean dryRun) {
        return Mono.fromCallable(() -> {
                    Map<String, Integer> counts = groundingService.cleanupLegacyQueryFolders(dryRun);
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("dryRun", dryRun);
                    body.put(dryRun ? "objectsFound" : "objectsDeleted", counts);
                    body.put("next", dryRun
                            ? "Re-run with dryRun=false to delete, then trigger the pipeline."
                            : "Trigger the pipeline so the repository drops the deleted documents.");
                    return body;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Per-day ingestion report. Use this to see exactly why a day reports "no data": either no
     * source document exists, or it exists and appears under {@code problems} with the reason.
     */
    @GetMapping("/diagnostics/coverage")
    public Mono<CoverageReport> coverage(
            @RequestParam String fromDate,
            @RequestParam String toDate) {
        LocalDate from = requireDate(fromDate, "fromDate");
        LocalDate to = requireDate(toDate, "toDate");
        return Mono.fromCallable(() -> analyticsService.coverage(from, to))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Natural-language analytics, answered exactly from MongoDB. Same routing /api/vector/ask uses,
     * exposed directly so it can be tested in isolation.
     */
    @PostMapping("/analytics/ask")
    public Mono<AnalyticsResult> ask(@Valid @RequestBody AnalyticsAskRequest request) {
        String weekMode = analyticsService.weekOfMonthMode();
        AnalyticsQuery query = questionParser.parse(request.question(), weekMode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Could not recognise a ranking question with a period. Try e.g. "
                                + "'Which plant received the highest number of records in the "
                                + "1st week of August 2026?'"));
        return Mono.fromCallable(() -> analyticsService.rank(query))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Structured ranking, for UIs that already know the dimension, metric and period. */
    @PostMapping("/analytics/rank")
    public Mono<AnalyticsResult> rank(@RequestBody AnalyticsRequest request) {
        AnalyticsQuery query = toQuery(request);
        return Mono.fromCallable(() -> analyticsService.rank(query))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** The week boundaries used for a month, so "week 1" is never ambiguous. */
    @GetMapping("/analytics/weeks")
    public List<AnalyticsPeriod> weeks(@RequestParam String month) {
        return PeriodSupport.weeksOfMonth(requireMonth(month), analyticsService.weekOfMonthMode());
    }

    private AnalyticsQuery toQuery(AnalyticsRequest request) {
        AnalyticsPeriod period = resolvePeriod(request);
        boolean descending = request.order() == null || !"asc".equalsIgnoreCase(request.order().trim());
        return new AnalyticsQuery(
                AnalyticsDimension.from(request.dimension()),
                AnalyticsMetric.from(request.metric()),
                period,
                descending,
                request.topN() == null ? 0 : request.topN(),
                request.filterKey(),
                null);
    }

    private AnalyticsPeriod resolvePeriod(AnalyticsRequest request) {
        if (notBlank(request.date())) {
            return PeriodSupport.day(requireDate(request.date(), "date"));
        }
        if (notBlank(request.month())) {
            YearMonth month = requireMonth(request.month());
            if (request.weekOfMonth() != null) {
                if (request.weekOfMonth() < 1 || request.weekOfMonth() > 6) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "weekOfMonth must be 1-6");
                }
                return PeriodSupport.weekOfMonth(month, request.weekOfMonth(), analyticsService.weekOfMonthMode());
            }
            return PeriodSupport.month(month);
        }
        if (notBlank(request.fromDate()) && notBlank(request.toDate())) {
            LocalDate from = requireDate(request.fromDate(), "fromDate");
            LocalDate to = requireDate(request.toDate(), "toDate");
            if (from.isAfter(to)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fromDate must not be after toDate");
            }
            return PeriodSupport.range(from, to);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Provide one of: date, month (optionally with weekOfMonth), or fromDate + toDate");
    }

    private LocalDate requireDate(String value, String name) {
        return DateValueSupport.toLocalDate(value).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be a date like 2026-08-02"));
    }

    private YearMonth requireMonth(String value) {
        try {
            return YearMonth.parse(value.trim());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must look like 2026-08");
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage());
    }
}
