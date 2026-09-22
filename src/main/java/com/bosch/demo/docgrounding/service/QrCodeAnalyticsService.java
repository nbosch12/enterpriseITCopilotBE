package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsBucket;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsDataSource;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsDimension;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsMetric;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsPeriod;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsQuery;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsResult;
import com.bosch.demo.docgrounding.model.analytics.CoverageReport;
import com.bosch.demo.docgrounding.model.analytics.QrProcessingFact;
import com.bosch.demo.docgrounding.support.DateValueSupport;
import com.bosch.demo.docgrounding.support.PeriodSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Exact answers to "which X had the most Y in period Z".
 *
 * <p>These questions were previously answered by vector retrieval, which is the wrong tool: the
 * retriever hands the model the top few chunks, so for a 31-day month with several plants the model
 * sees a fraction of the data and guesses a winner. Ranking is done here over the full range and the
 * result is a computed number, not a generated one.</p>
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "app.cosmos-mongo", name = "enabled", havingValue = "true")
public class QrCodeAnalyticsService {

    private final QrCodeFactRepository factRepository;
    private final AppProperties properties;

    public QrCodeAnalyticsService(QrCodeFactRepository factRepository, AppProperties properties) {
        this.factRepository = factRepository;
        this.properties = properties;
    }

    private AppProperties.Analytics config() {
        AppProperties.Analytics analytics = properties.getAnalytics();
        return analytics == null ? new AppProperties.Analytics() : analytics;
    }

    public String weekOfMonthMode() {
        return config().getWeekOfMonthMode();
    }

    public CoverageReport coverage(LocalDate from, LocalDate to) {
        return factRepository.coverage(from, to);
    }

    /**
     * Run a ranking query and produce both the ordered breakdown and a plain-language answer.
     */
    public AnalyticsResult rank(AnalyticsQuery query) {
        AnalyticsDataSource source = resolveDataSource(query);
        return switch (source) {
            case PROCESSING_REPORT -> rankFromProcessingReport(query);
            case TRACKING -> rankFromGroupedCounts(query, AnalyticsDataSource.TRACKING);
            case SCANLOG -> rankFromGroupedCounts(query, AnalyticsDataSource.SCANLOG);
        };
    }

    /**
     * Pick the collection that actually holds the requested metric.
     *
     * <p>"Which sourceName received the highest number of QR codes" names a dimension from
     * qrCodesTracking but a metric ({@code qrCodesReceived}) that only exists in
     * qrcodeProcessingReport, where the plantReports keys are the source/plant names. Answering it
     * from the tracking collection would count tracked documents instead of received codes and give
     * a different winner, so the metric decides the collection and the chosen source travels back in
     * the result.</p>
     */
    private AnalyticsDataSource resolveDataSource(AnalyticsQuery query) {
        return switch (query.metric()) {
            case RECEIVED, SAVED, FAILED -> AnalyticsDataSource.PROCESSING_REPORT;
            case SCANS -> AnalyticsDataSource.SCANLOG;
            case RECORDS -> query.dimension() == AnalyticsDimension.COUNTRY
                    ? AnalyticsDataSource.SCANLOG
                    : AnalyticsDataSource.TRACKING;
        };
    }

    private AnalyticsResult rankFromProcessingReport(AnalyticsQuery query) {
        AnalyticsPeriod period = query.period();
        QrCodeFactRepository.ProcessingScan scan =
                factRepository.scanProcessingReports(period.from(), period.to());

        Map<String, long[]> totals = new LinkedHashMap<>();
        Map<String, Set<LocalDate>> daysPerKey = new LinkedHashMap<>();
        Set<LocalDate> daysWithData = new LinkedHashSet<>();

        for (QrProcessingFact fact : scan.facts()) {
            if (!period.contains(fact.date())) {
                continue;
            }
            if (query.filterKey() != null && !query.filterKey().isBlank()
                    && !fact.plant().equalsIgnoreCase(query.filterKey().trim())) {
                continue;
            }
            daysWithData.add(fact.date());
            long[] acc = totals.computeIfAbsent(fact.plant(), key -> new long[4]);
            acc[0] += fact.received();
            acc[1] += fact.saved();
            acc[2] += fact.failed();
            acc[3] += 1;
            daysPerKey.computeIfAbsent(fact.plant(), key -> new LinkedHashSet<>()).add(fact.date());
        }

        List<AnalyticsBucket> buckets = new ArrayList<>();
        totals.forEach((key, acc) -> buckets.add(new AnalyticsBucket(
                key, acc[0], acc[1], acc[2], acc[3],
                daysPerKey.getOrDefault(key, Set.of()).size())));

        sort(buckets, query);

        List<String> missing = new ArrayList<>();
        for (LocalDate day : DateValueSupport.daysBetween(period.from(), period.to())) {
            if (!daysWithData.contains(day)) {
                missing.add(day.toString());
            }
        }

        List<String> notes = new ArrayList<>(scan.notes());
        if (!scan.problems().isEmpty()) {
            notes.add(scan.problems().size() + " document(s) could not be parsed into plant rows; "
                    + "see /api/mongodb/diagnostics/coverage for details.");
        }

        long total = buckets.stream().mapToLong(bucket -> bucket.metric(query.metric())).sum();
        List<AnalyticsBucket> ranked = limit(buckets, query.topN());

        return new AnalyticsResult(
                query.originalQuestion(),
                query.dimension(),
                query.metric(),
                period,
                AnalyticsDataSource.PROCESSING_REPORT,
                ranked,
                ranked.isEmpty() ? null : ranked.get(0),
                total,
                scan.documentsScanned(),
                daysWithData.size(),
                missing,
                notes,
                buildAnswer(query, ranked, total, daysWithData.size(), missing, AnalyticsDataSource.PROCESSING_REPORT));
    }

    private AnalyticsResult rankFromGroupedCounts(AnalyticsQuery query, AnalyticsDataSource source) {
        AnalyticsPeriod period = query.period();
        String groupField = groupFieldFor(query.dimension(), source);

        QrCodeFactRepository.GroupedCount grouped = source == AnalyticsDataSource.SCANLOG
                ? factRepository.countScansBy(groupField, period.from(), period.to())
                : factRepository.countTrackingBy(groupField, period.from(), period.to());

        List<AnalyticsBucket> buckets = new ArrayList<>(grouped.buckets());
        if (query.filterKey() != null && !query.filterKey().isBlank()) {
            String wanted = query.filterKey().trim();
            buckets.removeIf(bucket -> !bucket.key().equalsIgnoreCase(wanted));
        }
        sort(buckets, query);

        long total = buckets.stream().mapToLong(bucket -> bucket.metric(query.metric())).sum();
        List<AnalyticsBucket> ranked = limit(buckets, query.topN());

        return new AnalyticsResult(
                query.originalQuestion(),
                query.dimension(),
                query.metric(),
                period,
                source,
                ranked,
                ranked.isEmpty() ? null : ranked.get(0),
                total,
                grouped.documentsScanned(),
                period.days(),
                List.of(),
                grouped.notes(),
                buildAnswer(query, ranked, total, period.days(), List.of(), source));
    }

    private String groupFieldFor(AnalyticsDimension dimension, AnalyticsDataSource source) {
        if (source == AnalyticsDataSource.SCANLOG) {
            // scanlog carries no plant field; country is the closest meaningful grouping.
            return switch (dimension) {
                case COUNTRY, PLANT -> "location.country";
                default -> "application";
            };
        }
        return switch (dimension) {
            case SOURCE_NAME, PLANT -> "sourceName";
            case ARTICLE_NUMBER -> "articleNumber";
            case APPLICATION_ID -> "applicationid";
            case COUNTRY -> "sourceName";
        };
    }

    private void sort(List<AnalyticsBucket> buckets, AnalyticsQuery query) {
        Comparator<AnalyticsBucket> comparator =
                Comparator.comparingLong((AnalyticsBucket bucket) -> bucket.metric(query.metric()));
        if (query.descending()) {
            comparator = comparator.reversed();
        }
        buckets.sort(comparator.thenComparing(AnalyticsBucket::key));
    }

    private List<AnalyticsBucket> limit(List<AnalyticsBucket> buckets, int topN) {
        int max = topN > 0 ? topN : config().getMaxRankedResults();
        max = Math.min(max, Math.max(1, config().getMaxRankedResults()));
        return buckets.size() <= max ? List.copyOf(buckets) : List.copyOf(buckets.subList(0, max));
    }

    private String buildAnswer(
            AnalyticsQuery query,
            List<AnalyticsBucket> ranked,
            long total,
            int daysCovered,
            List<String> missing,
            AnalyticsDataSource source) {

        String dimensionLabel = query.dimension().label();
        String metricLabel = metricPhrase(query.metric());

        if (ranked.isEmpty()) {
            return "No %s data found for %s for %s.".formatted(
                    metricLabel, dimensionLabel, query.period().label());
        }

        AnalyticsBucket winner = ranked.get(0);
        StringBuilder answer = new StringBuilder();
        answer.append(query.descending() ? "Highest" : "Lowest")
                .append(" ").append(metricLabel)
                .append(" by ").append(dimensionLabel)
                .append(query.period().granularity() == com.bosch.demo.docgrounding.model.analytics.AnalyticsGranularity.DAY
                        ? " on " : " in ")
                .append(query.period().label())
                .append(": ").append(winner.key())
                .append(" with ").append(format(winner.metric(query.metric())))
                .append(".");

        if (source == AnalyticsDataSource.PROCESSING_REPORT) {
            answer.append(" (received ").append(format(winner.received()))
                    .append(", saved ").append(format(winner.saved()))
                    .append(", failed ").append(format(winner.failed()))
                    .append(", over ").append(winner.daysWithData()).append(" day(s) with data.)");
        }

        if (ranked.size() > 1) {
            answer.append(" Full ranking: ");
            List<String> parts = new ArrayList<>();
            for (AnalyticsBucket bucket : ranked) {
                parts.add(bucket.key() + " = " + format(bucket.metric(query.metric())));
            }
            answer.append(String.join(", ", parts)).append(".");
        }

        answer.append(" Total across all ").append(dimensionLabel).append("s: ")
                .append(format(total)).append(".");
        answer.append(" Source: ").append(source.collection())
                .append(", ").append(daysCovered).append(" day(s) with data in the period.");

        if (!missing.isEmpty()) {
            answer.append(" Note: no data present for ").append(missing.size()).append(" day(s): ")
                    .append(String.join(", ", missing.size() > 8 ? missing.subList(0, 8) : missing))
                    .append(missing.size() > 8 ? ", ..." : "")
                    .append(".");
        }
        return answer.toString();
    }

    private String metricPhrase(AnalyticsMetric metric) {
        return switch (metric) {
            case RECEIVED -> "QR codes received";
            case SAVED -> "QR codes saved";
            case FAILED -> "QR codes failed";
            case RECORDS -> "QR code records";
            case SCANS -> "scans";
        };
    }

    private String format(long value) {
        return String.format("%,d", value);
    }

    // ------------------------------------------------------------------
    // Rollups used both for answers and for grounding chunks
    // ------------------------------------------------------------------

    /**
     * Totals per dimension value for every day, week of month and month in the range.
     *
     * <p>Uploading these as their own chunks means a retrieval-only question can still land on a
     * single chunk that already contains the ranked answer, instead of needing the model to add up
     * 31 daily chunks it was never shown.</p>
     */
    public List<Rollup> buildProcessingRollups(LocalDate from, LocalDate to) {
        QrCodeFactRepository.ProcessingScan scan = factRepository.scanProcessingReports(from, to);
        List<Rollup> rollups = new ArrayList<>();

        Map<LocalDate, List<QrProcessingFact>> byDay = new TreeMap<>();
        for (QrProcessingFact fact : scan.facts()) {
            byDay.computeIfAbsent(fact.date(), key -> new ArrayList<>()).add(fact);
        }

        // Daily
        byDay.forEach((day, facts) ->
                rollups.add(new Rollup(PeriodSupport.day(day), "daily", day.toString(), aggregate(facts))));

        // Weekly and monthly, per calendar month touched by the range
        Set<YearMonth> months = new LinkedHashSet<>();
        byDay.keySet().forEach(day -> months.add(YearMonth.from(day)));

        String weekMode = weekOfMonthMode();
        for (YearMonth month : months) {
            List<QrProcessingFact> monthFacts = new ArrayList<>();
            byDay.forEach((day, facts) -> {
                if (YearMonth.from(day).equals(month)) {
                    monthFacts.addAll(facts);
                }
            });
            rollups.add(new Rollup(
                    PeriodSupport.month(month), "monthly", month.toString(), aggregate(monthFacts)));

            List<AnalyticsPeriod> weeks = PeriodSupport.weeksOfMonth(month, weekMode);
            for (int i = 0; i < weeks.size(); i++) {
                AnalyticsPeriod week = weeks.get(i);
                List<QrProcessingFact> weekFacts = new ArrayList<>();
                byDay.forEach((day, facts) -> {
                    if (week.contains(day)) {
                        weekFacts.addAll(facts);
                    }
                });
                if (weekFacts.isEmpty()) {
                    continue;
                }
                rollups.add(new Rollup(
                        week, "weekly", PeriodSupport.weekKey(month, i + 1), aggregate(weekFacts)));
            }
        }
        return rollups;
    }

    private List<AnalyticsBucket> aggregate(List<QrProcessingFact> facts) {
        Map<String, long[]> totals = new LinkedHashMap<>();
        Map<String, Set<LocalDate>> days = new LinkedHashMap<>();
        for (QrProcessingFact fact : facts) {
            long[] acc = totals.computeIfAbsent(fact.plant(), key -> new long[4]);
            acc[0] += fact.received();
            acc[1] += fact.saved();
            acc[2] += fact.failed();
            acc[3] += 1;
            days.computeIfAbsent(fact.plant(), key -> new LinkedHashSet<>()).add(fact.date());
        }
        List<AnalyticsBucket> buckets = new ArrayList<>();
        totals.forEach((key, acc) -> buckets.add(new AnalyticsBucket(
                key, acc[0], acc[1], acc[2], acc[3], days.getOrDefault(key, Set.of()).size())));
        buckets.sort(Comparator.comparingLong(AnalyticsBucket::received).reversed()
                .thenComparing(AnalyticsBucket::key));
        return buckets;
    }

    /** One pre-computed time bucket with its per-plant totals, ordered by received descending. */
    public record Rollup(AnalyticsPeriod period, String kind, String key, List<AnalyticsBucket> buckets) {
    }
}
