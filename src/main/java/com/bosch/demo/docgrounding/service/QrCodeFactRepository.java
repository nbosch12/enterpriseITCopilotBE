package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsBucket;
import com.bosch.demo.docgrounding.model.analytics.CoverageReport;
import com.bosch.demo.docgrounding.model.analytics.QrProcessingFact;
import com.bosch.demo.docgrounding.support.DateValueSupport;
import com.bosch.demo.docgrounding.support.DocumentValueSupport;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads the QR code collections completely.
 *
 * <p>The reason some days reported totals and others reported "no data" is that
 * {@code storageDate} was filtered with a plain string comparison. That works only when every
 * document stores the date as an ISO string; a BSON {@code Date}, an epoch number or a
 * {@code dd-MM-yyyy} string matches nothing, and a value like {@code 2026-08-31T22:10:00} falls
 * outside an inclusive {@code $lte "2026-08-31"} bound. This class filters on the server where it
 * can, verifies day-by-day coverage afterwards, and rescans client-side with a tolerant parser when
 * anything is missing.</p>
 */
@Repository
@Slf4j
@ConditionalOnProperty(prefix = "app.cosmos-mongo", name = "enabled", havingValue = "true")
public class QrCodeFactRepository {

    public static final String PROCESSING = "qrcodeProcessingReport";
    public static final String PACKAGING = "qrcodePackagingReport";
    public static final String SCANLOG = "scanlog";
    public static final String TRACKING = "qrCodesTracking";

    private static final String STRATEGY_SERVER = "SERVER";
    private static final String STRATEGY_CLIENT_SCAN = "CLIENT_SCAN";

    private final MongoDatabase database;
    private final AppProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public QrCodeFactRepository(
            MongoClient mongoClient,
            MongoProperties mongoProperties,
            AppProperties properties) {
        this.properties = properties;
        String databaseName = resolveDatabaseName(mongoProperties);
        this.database = mongoClient.getDatabase(databaseName);
        log.info("QR code fact repository bound to database '{}'", databaseName);
    }

    /** For tests: subclasses override the scan methods and never touch the database. */
    protected QrCodeFactRepository(MongoDatabase database, AppProperties properties) {
        this.database = database;
        this.properties = properties;
    }

    private static String resolveDatabaseName(MongoProperties mongoProperties) {
        String databaseName = mongoProperties.getDatabase();
        if ((databaseName == null || databaseName.isBlank()) && mongoProperties.getUri() != null) {
            databaseName = new ConnectionString(mongoProperties.getUri()).getDatabase();
        }
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalStateException(
                    "spring.data.mongodb.database (or a database in spring.data.mongodb.uri) must be configured");
        }
        return databaseName;
    }

    public MongoDatabase database() {
        return database;
    }

    private AppProperties.CosmosMongo mongoConfig() {
        AppProperties.CosmosMongo config = properties.getCosmosMongo();
        return config == null ? new AppProperties.CosmosMongo() : config;
    }

    private AppProperties.Analytics analyticsConfig() {
        AppProperties.Analytics config = properties.getAnalytics();
        return config == null ? new AppProperties.Analytics() : config;
    }

    // ------------------------------------------------------------------
    // qrcodeProcessingReport
    // ------------------------------------------------------------------

    /**
     * Load every plant row for the inclusive date range, together with the diagnostics that explain
     * how the rows were found and what could not be read.
     */
    public ProcessingScan scanProcessingReports(LocalDate from, LocalDate to) {
        AppProperties.CosmosMongo config = mongoConfig();
        String strategy = config.getDateFilterStrategy() == null
                ? "AUTO"
                : config.getDateFilterStrategy().trim().toUpperCase(Locale.ROOT);
        int scanLimit = Math.max(1, config.getMaxScanDocuments());
        String dateField = config.getProcessingDateField() == null || config.getProcessingDateField().isBlank()
                ? "storageDate"
                : config.getProcessingDateField().trim();

        MongoCollection<Document> collection = database.getCollection(PROCESSING);

        Map<String, Document> byId = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        int serverMatched = 0;
        int fallbackFound = 0;

        if (!STRATEGY_CLIENT_SCAN.equals(strategy)) {
            try {
                Bson filter = buildProcessingDateFilter(dateField, from, to);
                for (Document doc : collection.find(filter).limit(scanLimit)) {
                    byId.put(documentId(doc), doc);
                    serverMatched++;
                }
                notes.add("Server-side date filter matched " + serverMatched + " document(s) on '" + dateField + "'.");
            } catch (RuntimeException ex) {
                // $type or $or support varies across Cosmos DB API versions; never fail the request.
                log.warn("Server-side date filter failed on {}, falling back to a client-side scan: {}",
                        PROCESSING, ex.getMessage());
                notes.add("Server-side date filter unavailable (" + ex.getClass().getSimpleName()
                        + "); used a client-side scan instead.");
            }
        } else {
            notes.add("dateFilterStrategy=CLIENT_SCAN: every document is parsed in the application.");
        }

        boolean needsFallback = STRATEGY_CLIENT_SCAN.equals(strategy)
                || (!STRATEGY_SERVER.equals(strategy) && !coversEveryDay(byId.values(), dateField, from, to));

        if (needsFallback) {
            int before = byId.size();
            for (Document doc : collection.find().limit(scanLimit)) {
                Optional<LocalDate> date = parseAnyDate(doc, dateField);
                if (date.isPresent() && inRange(date.get(), from, to)) {
                    byId.putIfAbsent(documentId(doc), doc);
                }
            }
            fallbackFound = byId.size() - before;
            if (fallbackFound > 0) {
                notes.add("Client-side rescan recovered " + fallbackFound
                        + " document(s) the server-side filter had missed "
                        + "(usually a date stored as a BSON Date, an epoch number, or a non-ISO string).");
            }
        }

        List<QrProcessingFact> facts = new ArrayList<>();
        List<CoverageReport.DocumentProblem> problems = new ArrayList<>();

        for (Document doc : byId.values()) {
            Optional<LocalDate> date = parseAnyDate(doc, dateField);
            Object rawDate = DocumentValueSupport.firstValue(doc, dateFieldCandidates(dateField));

            if (date.isEmpty()) {
                problems.add(new CoverageReport.DocumentProblem(
                        documentId(doc),
                        "date field could not be parsed",
                        DocumentValueSupport.stringValue(rawDate),
                        excerpt(doc)));
                continue;
            }

            List<DocumentValueSupport.PlantCounts> plantCounts = DocumentValueSupport.readPlantCounts(doc);
            if (plantCounts.isEmpty()) {
                problems.add(new CoverageReport.DocumentProblem(
                        documentId(doc),
                        "no per-plant counts found (checked " + DocumentValueSupport.PLANT_REPORTS_FIELDS + ")",
                        DocumentValueSupport.stringValue(rawDate),
                        excerpt(doc)));
                continue;
            }

            String processingTime = DocumentValueSupport.stringValue(
                    DocumentValueSupport.firstValue(doc, List.of("processingTime", "processingDuration", "duration")));

            for (DocumentValueSupport.PlantCounts counts : plantCounts) {
                facts.add(new QrProcessingFact(
                        date.get(),
                        DocumentValueSupport.stringValue(rawDate),
                        counts.plant(),
                        counts.received(),
                        counts.saved(),
                        counts.failed(),
                        counts.processingTime().isEmpty() ? processingTime : counts.processingTime()));
            }
        }

        facts.sort(Comparator.comparing(QrProcessingFact::date).thenComparing(QrProcessingFact::plant));

        if (byId.size() >= scanLimit) {
            notes.add("Scan hit the configured limit of " + scanLimit
                    + " documents; raise app.cosmos-mongo.max-scan-documents if the range is larger.");
        }

        return new ProcessingScan(facts, byId.size(), serverMatched, fallbackFound, problems, notes);
    }

    /**
     * Day-by-day ingestion report for a range, including which days have no data at all.
     */
    public CoverageReport coverage(LocalDate from, LocalDate to) {
        ProcessingScan scan = scanProcessingReports(from, to);

        Map<LocalDate, List<QrProcessingFact>> byDay = new TreeMap<>();
        for (QrProcessingFact fact : scan.facts()) {
            byDay.computeIfAbsent(fact.date(), key -> new ArrayList<>()).add(fact);
        }

        List<CoverageReport.DayCoverage> days = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (LocalDate day : DateValueSupport.daysBetween(from, to)) {
            List<QrProcessingFact> dayFacts = byDay.getOrDefault(day, List.of());
            if (dayFacts.isEmpty()) {
                missing.add(day.toString());
                days.add(new CoverageReport.DayCoverage(day.toString(), 0, 0, 0, 0, 0, List.of()));
                continue;
            }
            long received = dayFacts.stream().mapToLong(QrProcessingFact::received).sum();
            long saved = dayFacts.stream().mapToLong(QrProcessingFact::saved).sum();
            long failed = dayFacts.stream().mapToLong(QrProcessingFact::failed).sum();
            List<String> plants = dayFacts.stream().map(QrProcessingFact::plant).distinct().sorted().toList();
            days.add(new CoverageReport.DayCoverage(
                    day.toString(), 1, plants.size(), received, saved, failed, plants));
        }

        List<String> notes = new ArrayList<>(scan.notes());
        if (!missing.isEmpty()) {
            notes.add("No qrcodeProcessingReport document exists for " + missing.size()
                    + " day(s) in this range even after a full client-side scan, "
                    + "so those days are genuinely absent from the source collection.");
        }

        return new CoverageReport(
                PROCESSING,
                String.valueOf(from),
                String.valueOf(to),
                DateValueSupport.daysBetween(from, to).size(),
                byDay.size(),
                scan.serverMatched(),
                scan.fallbackFound(),
                scan.facts().size(),
                missing,
                days,
                scan.problems(),
                notes);
    }

    private Bson buildProcessingDateFilter(String dateField, LocalDate from, LocalDate to) {
        List<Bson> perField = new ArrayList<>();
        for (String candidate : dateFieldCandidates(dateField)) {
            perField.add(DateValueSupport.rangeFilter(candidate, from, to));
        }
        return perField.size() == 1 ? perField.get(0) : Filters.or(perField);
    }

    private List<String> dateFieldCandidates(String configuredField) {
        Set<String> candidates = new LinkedHashSet<>();
        if (configuredField != null && !configuredField.isBlank()) {
            candidates.add(configuredField.trim());
        }
        candidates.addAll(DocumentValueSupport.STORAGE_DATE_FIELDS);
        return new ArrayList<>(candidates);
    }

    private Optional<LocalDate> parseAnyDate(Document doc, String configuredField) {
        for (String candidate : dateFieldCandidates(configuredField)) {
            Optional<LocalDate> parsed = DateValueSupport.toLocalDate(
                    DocumentValueSupport.valueAtPath(doc, candidate));
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }

    private boolean coversEveryDay(Iterable<Document> documents, String dateField, LocalDate from, LocalDate to) {
        List<LocalDate> expected = DateValueSupport.daysBetween(from, to);
        if (expected.isEmpty()) {
            return false;
        }
        Set<LocalDate> found = new LinkedHashSet<>();
        for (Document doc : documents) {
            parseAnyDate(doc, dateField).ifPresent(found::add);
        }
        return found.containsAll(expected);
    }

    private boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
        if (from != null && date.isBefore(from)) {
            return false;
        }
        return to == null || !date.isAfter(to);
    }

    // ------------------------------------------------------------------
    // qrCodesTracking / scanlog grouping
    // ------------------------------------------------------------------

    /**
     * Count qrCodesTracking documents grouped by a field, over a date range.
     *
     * <p>Tries a server-side {@code $group} first because this collection can be large, and falls
     * back to a bounded client-side scan when the aggregation is rejected.</p>
     */
    public GroupedCount countTrackingBy(String groupField, LocalDate from, LocalDate to) {
        AppProperties.Analytics analytics = analyticsConfig();
        String primaryDate = analytics.getTrackingDateField();
        String fallbackDate = analytics.getTrackingFallbackDateField();
        return countBy(TRACKING, groupField, primaryDate, fallbackDate, from, to);
    }

    /**
     * Count scanlog events grouped by a field (e.g. {@code location.country}) over a date range.
     */
    public GroupedCount countScansBy(String groupField, LocalDate from, LocalDate to) {
        return countBy(SCANLOG, groupField, "scanDate", null, from, to);
    }

    private GroupedCount countBy(
            String collectionName,
            String groupField,
            String primaryDateField,
            String fallbackDateField,
            LocalDate from,
            LocalDate to) {

        MongoCollection<Document> collection = database.getCollection(collectionName);
        List<String> notes = new ArrayList<>();
        Bson dateFilter = buildTrackingDateFilter(primaryDateField, fallbackDateField, from, to);

        try {
            List<Bson> pipeline = List.of(
                    Aggregates.match(dateFilter),
                    Aggregates.group("$" + groupField, Accumulators.sum("count", 1)),
                    Aggregates.sort(new Document("count", -1)));

            Map<String, Long> counts = new LinkedHashMap<>();
            long scanned = 0;
            for (Document row : collection.aggregate(pipeline).allowDiskUse(true)) {
                String key = DocumentValueSupport.stringValue(row.get("_id"));
                long count = DocumentValueSupport.longValue(row.get("count"));
                counts.merge(key.isBlank() ? "unknown" : key, count, Long::sum);
                scanned += count;
            }
            notes.add("Grouped server-side with $group on '" + groupField + "'.");
            return new GroupedCount(toBuckets(counts), (int) scanned, notes);
        } catch (RuntimeException ex) {
            log.warn("Aggregation on {} failed, falling back to a client-side scan: {}",
                    collectionName, ex.getMessage());
            notes.add("Server-side $group unavailable (" + ex.getClass().getSimpleName()
                    + "); counted with a bounded client-side scan.");
        }

        int scanLimit = Math.max(1, mongoConfig().getMaxScanDocuments());
        Map<String, Long> counts = new HashMap<>();
        int scanned = 0;
        for (Document doc : collection.find(dateFilter).limit(scanLimit)) {
            String key = DocumentValueSupport.stringValue(DocumentValueSupport.valueAtPath(doc, groupField));
            counts.merge(key.isBlank() ? "unknown" : key, 1L, Long::sum);
            scanned++;
        }
        if (scanned >= scanLimit) {
            notes.add("Client-side scan hit the limit of " + scanLimit + " documents; counts may be partial.");
        }
        return new GroupedCount(toBuckets(counts), scanned, notes);
    }

    private Bson buildTrackingDateFilter(String primary, String fallback, LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return new Document();
        }
        Bson primaryFilter = DateValueSupport.rangeFilter(primary, from, to);
        if (fallback == null || fallback.isBlank() || fallback.equals(primary)) {
            return primaryFilter;
        }
        // Documents missing the primary date field are matched on the fallback field instead.
        return Filters.or(
                primaryFilter,
                Filters.and(
                        Filters.exists(primary, false),
                        DateValueSupport.rangeFilter(fallback, from, to)));
    }

    private List<AnalyticsBucket> toBuckets(Map<String, Long> counts) {
        List<AnalyticsBucket> buckets = new ArrayList<>();
        counts.forEach((key, count) -> buckets.add(new AnalyticsBucket(key, 0, 0, 0, count, 0)));
        buckets.sort(Comparator.comparingLong(AnalyticsBucket::records).reversed());
        return buckets;
    }

    private String documentId(Document doc) {
        Object id = doc.get("_id");
        return id == null ? String.valueOf(System.identityHashCode(doc)) : String.valueOf(id);
    }

    private String excerpt(Document doc) {
        String json;
        try {
            json = doc.toJson();
        } catch (RuntimeException ex) {
            json = String.valueOf(doc);
        }
        return json.length() <= 400 ? json : json.substring(0, 400) + "...";
    }

    /** Result of reading qrcodeProcessingReport, facts plus how they were obtained. */
    public record ProcessingScan(
            List<QrProcessingFact> facts,
            int documentsScanned,
            int serverMatched,
            int fallbackFound,
            List<CoverageReport.DocumentProblem> problems,
            List<String> notes) {
    }

    /** Grouped counts plus how many documents were behind them. */
    public record GroupedCount(List<AnalyticsBucket> buckets, int documentsScanned, List<String> notes) {
    }
}
