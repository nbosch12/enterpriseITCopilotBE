package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.MongoGroundingQueryRequest;
import com.bosch.demo.docgrounding.model.MongoGroundingSyncResult;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsBucket;
import com.bosch.demo.docgrounding.model.analytics.AnalyticsPeriod;
import com.bosch.demo.docgrounding.model.analytics.CoverageReport;
import com.bosch.demo.docgrounding.model.analytics.QrProcessingFact;
import com.bosch.demo.docgrounding.support.DateValueSupport;
import com.bosch.demo.docgrounding.support.DocumentValueSupport;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns curated MongoDB queries into grounding chunks in S3.
 *
 * <p>Two things changed here and both matter for answer quality:</p>
 * <ul>
 *   <li><b>Deterministic keys.</b> Every record now has a stable S3 key derived from its natural
 *       identity (date + plant, uuid, and so on) instead of living under a fresh
 *       {@code mongodb/&lt;type&gt;/&lt;random-uuid&gt;/} folder per call. Re-running a sync
 *       overwrites; it no longer leaves the previous copy behind. Duplicated copies of the same day
 *       are a direct cause of wrong totals, because the retriever happily returns several of them
 *       and the model adds them up.</li>
 *   <li><b>Pre-computed rollups.</b> Alongside the daily rows, monthly / weekly / daily aggregate
 *       chunks are written with the ranking already resolved, so a retrieval-only question can land
 *       on one chunk that states the answer.</li>
 * </ul>
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "app.cosmos-mongo", name = "enabled", havingValue = "true")
public class CosmosMongoGroundingService {

    private static final String PROCESSING = QrCodeFactRepository.PROCESSING;
    private static final String PACKAGING = QrCodeFactRepository.PACKAGING;
    private static final String SCANLOG = QrCodeFactRepository.SCANLOG;
    private static final String TRACKING = QrCodeFactRepository.TRACKING;

    /** Logical root (below the configured grounding root prefix) for everything from MongoDB. */
    private static final String MONGO_ROOT = "mongodb/qrcode";

    private final QrCodeFactRepository factRepository;
    private final QrCodeAnalyticsService analyticsService;
    private final S3ObjectStoreService s3ObjectStoreService;
    private final ChunkingService chunkingService;
    private final GroundingRepositoryService repositoryService;
    private final AppProperties properties;

    public CosmosMongoGroundingService(
            QrCodeFactRepository factRepository,
            QrCodeAnalyticsService analyticsService,
            S3ObjectStoreService s3ObjectStoreService,
            ChunkingService chunkingService,
            GroundingRepositoryService repositoryService,
            AppProperties properties) {
        this.factRepository = factRepository;
        this.analyticsService = analyticsService;
        this.s3ObjectStoreService = s3ObjectStoreService;
        this.chunkingService = chunkingService;
        this.repositoryService = repositoryService;
        this.properties = properties;
    }

    public MongoGroundingSyncResult prepareGroundingData(MongoGroundingQueryRequest request) {
        QueryType queryType = QueryType.from(request.queryType());
        int limit = normalizeLimit(request.limit());
        String queryId = UUID.randomUUID().toString();

        if (queryType == QueryType.PROCESSING_SUMMARY) {
            return prepareProcessingSummary(request, queryId);
        }

        List<GroundingRecord> records = switch (queryType) {
            case PACKAGING_SUMMARY -> queryPackagingReports(request, limit);
            case SCAN_ACTIVITY -> queryScanActivity(request, limit);
            case QR_TRACKING -> queryTracking(request, limit);
            case SCAN_WITH_TRACKING -> queryScanWithTracking(request, limit);
            case PROCESSING_SUMMARY -> List.of();
        };

        String prefix = MONGO_ROOT + "/" + queryType.folder();
        if (Boolean.TRUE.equals(request.replaceExisting())) {
            int deleted = s3ObjectStoreService.deletePrefix(repositoryService.key(prefix + "/"));
            log.info("replaceExisting=true removed {} existing object(s) under {}", deleted, prefix);
        }

        int chunksUploaded = uploadRecords(queryId, queryType, records);
        List<String> collections = records.stream()
                .map(GroundingRecord::collection)
                .distinct()
                .toList();

        return new MongoGroundingSyncResult(
                queryId,
                queryType.name(),
                records.size(),
                chunksUploaded,
                repositoryService.key(prefix),
                repositoryService.includePath(),
                collections,
                safeRepositoryId(),
                0,
                null,
                List.of("All grounding data is written under the single include path "
                        + repositoryService.includePath() + "."));
    }

    /**
     * Folders written by the previous implementation, one random UUID sub-folder per prepare call.
     * They hold duplicate copies of the same records and must not stay inside the single
     * repository's include path, or the retriever will keep returning several copies of one day.
     */
    private static final List<String> LEGACY_QUERY_FOLDERS = List.of(
            "mongodb/processing_summary/",
            "mongodb/packaging_summary/",
            "mongodb/scan_activity/",
            "mongodb/qr_tracking/",
            "mongodb/scan_with_tracking/");

    /**
     * Report, and optionally delete, the legacy per-query UUID folders.
     *
     * @param dryRun when true only counts objects; nothing is deleted
     * @return object count per legacy folder
     */
    public Map<String, Integer> cleanupLegacyQueryFolders(boolean dryRun) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String folder : LEGACY_QUERY_FOLDERS) {
            // Legacy data was always written at the bucket root, never under a grounding root prefix.
            int count = dryRun
                    ? s3ObjectStoreService.listKeys(folder, 1_000_000).size()
                    : s3ObjectStoreService.deletePrefix(folder);
            result.put(folder, count);
        }
        log.info("Legacy Mongo grounding folders dryRun={} result={}", dryRun, result);
        return result;
    }

    // ------------------------------------------------------------------
    // PROCESSING_SUMMARY - the path that was losing records
    // ------------------------------------------------------------------

    /**
     * Ingest every qrcodeProcessingReport row in the range, one S3 object per day and plant, plus
     * the aggregate rollups.
     *
     * <p>Record selection now goes through {@link QrCodeFactRepository}, which tolerates the various
     * ways {@code storageDate} is stored and verifies day coverage afterwards, so a day is only ever
     * absent from the output when it is genuinely absent from the collection.</p>
     */
    private MongoGroundingSyncResult prepareProcessingSummary(MongoGroundingQueryRequest request, String queryId) {
        LocalDate from = parseDate(request.fromDate());
        LocalDate to = parseDate(request.toDate());
        if (from == null || to == null) {
            throw new IllegalArgumentException(
                    "PROCESSING_SUMMARY requires fromDate and toDate in yyyy-MM-dd format");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("fromDate must not be after toDate");
        }

        String dailyPrefix = MONGO_ROOT + "/processing/daily";
        String rollupPrefix = MONGO_ROOT + "/processing/rollup";

        if (Boolean.TRUE.equals(request.replaceExisting())) {
            int deleted = s3ObjectStoreService.deletePrefix(repositoryService.key(dailyPrefix + "/"));
            deleted += s3ObjectStoreService.deletePrefix(repositoryService.key(rollupPrefix + "/"));
            log.info("replaceExisting=true removed {} existing processing object(s)", deleted);
        }

        CoverageReport coverage = factRepository.coverage(from, to);
        QrCodeFactRepository.ProcessingScan scan = factRepository.scanProcessingReports(from, to);

        String plantFilter = request.plant() == null ? "" : request.plant().trim();
        int chunksUploaded = 0;

        for (QrProcessingFact fact : scan.facts()) {
            if (!plantFilter.isEmpty() && !fact.plant().equalsIgnoreCase(plantFilter)) {
                continue;
            }
            String key = "%s/%s/%s.txt".formatted(dailyPrefix, fact.date(), slug(fact.plant()));
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("source", "cosmos-mongodb");
            metadata.put("collection", PROCESSING);
            metadata.put("queryType", QueryType.PROCESSING_SUMMARY.name());
            metadata.put("recordType", "daily-plant-summary");
            metadata.put("storageDate", fact.date().toString());
            metadata.put("plant", truncate(fact.plant(), 200));

            chunksUploaded += uploadRecordText(key, dailyFactText(fact), metadata);
        }

        int rollupChunks = 0;
        boolean wantRollups = request.includeRollups() == null
                ? analyticsConfig().isUploadRollups()
                : request.includeRollups();

        if (wantRollups) {
            for (QrCodeAnalyticsService.Rollup rollup : analyticsService.buildProcessingRollups(from, to)) {
                if (rollup.buckets().isEmpty()) {
                    continue;
                }
                String key = "%s/%s/%s.txt".formatted(rollupPrefix, rollup.kind(), rollup.key());
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("source", "cosmos-mongodb");
                metadata.put("collection", PROCESSING);
                metadata.put("queryType", QueryType.PROCESSING_SUMMARY.name());
                metadata.put("recordType", rollup.kind() + "-aggregate");
                metadata.put("periodKey", rollup.key());

                rollupChunks += uploadRecordText(key, rollupText(rollup), metadata);
            }
        }

        List<String> notes = new ArrayList<>(scan.notes());
        notes.add("Deterministic keys: re-running this sync overwrites the same objects rather than "
                + "adding a second copy of the same day.");
        if (!coverage.missingDates().isEmpty()) {
            notes.add("Days with no source document: " + String.join(", ", coverage.missingDates()));
        }
        if (wantRollups) {
            notes.add("Uploaded " + rollupChunks + " aggregate chunk(s) so ranking questions can be "
                    + "answered from a single retrieved chunk.");
        }

        return new MongoGroundingSyncResult(
                queryId,
                QueryType.PROCESSING_SUMMARY.name(),
                scan.facts().size(),
                chunksUploaded,
                repositoryService.key(MONGO_ROOT + "/processing"),
                repositoryService.includePath(),
                List.of(PROCESSING),
                safeRepositoryId(),
                rollupChunks,
                coverage,
                notes);
    }

    private String dailyFactText(QrProcessingFact fact) {
        return """
                source: Azure Cosmos DB for MongoDB
                collection: qrcodeProcessingReport
                recordType: QR code processing daily plant summary
                storageDate: %s
                rawStorageDateValue: %s
                year: %d
                month: %s
                dayOfMonth: %d
                weekOfMonth: %d
                plant: %s
                sourceName: %s
                qrCodesReceived: %d
                qrCodesSaved: %d
                qrCodesFailed: %d
                successRate: %s
                processingTime: %s
                """.formatted(
                fact.date(),
                fact.rawDate(),
                fact.date().getYear(),
                "%04d-%02d".formatted(fact.date().getYear(), fact.date().getMonthValue()),
                fact.date().getDayOfMonth(),
                com.bosch.demo.docgrounding.support.PeriodSupport.weekNumberOf(
                        fact.date(), analyticsService.weekOfMonthMode()),
                fact.plant(),
                fact.plant(),
                fact.received(),
                fact.saved(),
                fact.failed(),
                percentage(fact.saved(), fact.received()),
                fact.processingTime());
    }

    /**
     * Aggregate chunk text with the ranking already resolved.
     *
     * <p>The alias line is there on purpose: people ask for "the 1st week of August 2026" in several
     * phrasings, and putting those phrasings in the chunk is what lets the retriever find it.</p>
     */
    private String rollupText(QrCodeAnalyticsService.Rollup rollup) {
        AnalyticsPeriod period = rollup.period();
        List<AnalyticsBucket> buckets = rollup.buckets();

        long totalReceived = buckets.stream().mapToLong(AnalyticsBucket::received).sum();
        long totalSaved = buckets.stream().mapToLong(AnalyticsBucket::saved).sum();
        long totalFailed = buckets.stream().mapToLong(AnalyticsBucket::failed).sum();

        AnalyticsBucket highest = buckets.get(0);
        AnalyticsBucket lowest = buckets.get(buckets.size() - 1);

        StringBuilder ranking = new StringBuilder();
        for (int i = 0; i < buckets.size(); i++) {
            AnalyticsBucket bucket = buckets.get(i);
            ranking.append("  ").append(i + 1).append(". ").append(bucket.key())
                    .append(" = ").append(bucket.received()).append(" received")
                    .append(" (saved ").append(bucket.saved())
                    .append(", failed ").append(bucket.failed())
                    .append(", days with data ").append(bucket.daysWithData()).append(")\n");
        }

        return """
                source: Azure Cosmos DB for MongoDB
                collection: qrcodeProcessingReport
                recordType: %s aggregate ranking of QR codes received per plant
                period: %s
                alsoKnownAs: %s
                periodType: %s
                periodKey: %s
                periodStart: %s
                periodEnd: %s
                plantsWithData: %d
                rankingByQrCodesReceived:
                %s
                highestReceivingPlant: %s
                highestReceivingPlantValue: %d
                lowestReceivingPlant: %s
                lowestReceivingPlantValue: %d
                totalQrCodesReceived: %d
                totalQrCodesSaved: %d
                totalQrCodesFailed: %d
                """.formatted(
                rollup.kind(),
                period.label(),
                aliasPhrases(period),
                rollup.kind(),
                rollup.key(),
                period.from(),
                period.to(),
                buckets.size(),
                ranking.toString().stripTrailing(),
                highest.key(),
                highest.received(),
                lowest.key(),
                lowest.received(),
                totalReceived,
                totalSaved,
                totalFailed);
    }

    private String aliasPhrases(AnalyticsPeriod period) {
        String label = period.label();
        if (!label.startsWith("week ")) {
            return label;
        }
        // "week 1 of August 2026" -> also "1st week of August 2026", "first week of August 2026"
        String[] parts = label.split(" ", 3);
        int number;
        try {
            number = Integer.parseInt(parts[1]);
        } catch (RuntimeException ex) {
            return label;
        }
        String rest = parts.length > 2 ? parts[2] : "";
        String ordinal = switch (number) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> number + "th";
        };
        String word = switch (number) {
            case 1 -> "first";
            case 2 -> "second";
            case 3 -> "third";
            case 4 -> "fourth";
            default -> "fifth";
        };
        return "%s; %s week %s; %s week %s".formatted(label, ordinal, rest, word, rest);
    }

    // ------------------------------------------------------------------
    // Other curated query types
    // ------------------------------------------------------------------

    private List<GroundingRecord> queryPackagingReports(MongoGroundingQueryRequest request, int limit) {
        MongoCollection<Document> collection = factRepository.database().getCollection(PACKAGING);
        List<Bson> filters = new ArrayList<>();
        addDateRangeFilters(filters, "_id.storageDate", request.fromDate(), request.toDate());
        addEquals(filters, "_id.applicationId", request.applicationId());
        addEquals(filters, "_id.articleNumber", request.articleNumber());

        FindIterable<Document> docs = collection.find(and(filters))
                .sort(Sorts.descending("_id.storageDate"))
                .limit(limit);

        List<GroundingRecord> results = new ArrayList<>();
        for (Document doc : docs) {
            Document id = doc.get("_id", Document.class);
            if (id == null) continue;
            String storageDate = DocumentValueSupport.stringValue(id.get("storageDate"));
            String applicationId = DocumentValueSupport.stringValue(id.get("applicationId"));
            String articleNumber = DocumentValueSupport.stringValue(id.get("articleNumber"));
            long storageItems = DocumentValueSupport.longValue(doc.get("storageItems"));

            @SuppressWarnings("unchecked")
            List<Document> packaging = (List<Document>) doc.getOrDefault("packaging", List.of());
            String packagingFacts = packaging.stream()
                    .map(p -> DocumentValueSupport.stringValue(p.get("packagingDate"))
                            + "=" + DocumentValueSupport.longValue(p.get("numberOfItems")))
                    .collect(Collectors.joining(", "));

            String content = """
                    source: Azure Cosmos DB for MongoDB
                    collection: qrcodePackagingReport
                    recordType: QR code packaging summary
                    storageDate: %s
                    applicationId: %s
                    articleNumber: %s
                    storageItems: %d
                    packagingBreakdown(packagingDate=numberOfItems): %s
                    processedDate: %s
                    """.formatted(storageDate, applicationId, articleNumber, storageItems,
                    packagingFacts, DocumentValueSupport.stringValue(doc.get("processedDate")));

            String recordKey = "%s/%s-%s".formatted(
                    DateValueSupport.toIsoDate(storageDate).isEmpty() ? "unknown-date"
                            : DateValueSupport.toIsoDate(storageDate),
                    slug(applicationId),
                    slug(articleNumber));
            results.add(new GroundingRecord(PACKAGING, recordKey, content));
        }
        return results;
    }

    private List<GroundingRecord> queryScanActivity(MongoGroundingQueryRequest request, int limit) {
        MongoCollection<Document> collection = factRepository.database().getCollection(SCANLOG);

        FindIterable<Document> docs = collection.find(and(buildScanFilters(request)))
                .sort(Sorts.descending("scanDate"))
                .limit(limit);

        List<GroundingRecord> results = new ArrayList<>();
        for (Document doc : docs) {
            results.add(toScanRecord(doc));
        }
        return results;
    }

    private List<GroundingRecord> queryTracking(MongoGroundingQueryRequest request, int limit) {
        MongoCollection<Document> collection = factRepository.database().getCollection(TRACKING);
        List<Bson> filters = new ArrayList<>();
        addEquals(filters, "_id", request.uuid());
        addEquals(filters, "articleNumber", request.articleNumber());
        addEquals(filters, "applicationid", request.applicationId());
        addEquals(filters, "sourceName", request.sourceName());

        FindIterable<Document> docs = collection.find(and(filters))
                .sort(Sorts.descending("lastModifiedDate"))
                .limit(limit);

        List<GroundingRecord> results = new ArrayList<>();
        for (Document doc : docs) {
            results.add(toTrackingRecord(doc));
        }
        return results;
    }

    private List<GroundingRecord> queryScanWithTracking(MongoGroundingQueryRequest request, int limit) {
        MongoCollection<Document> scanCollection = factRepository.database().getCollection(SCANLOG);
        MongoCollection<Document> trackingCollection = factRepository.database().getCollection(TRACKING);

        List<Document> scans = new ArrayList<>();
        scanCollection.find(and(buildScanFilters(request)))
                .sort(Sorts.descending("scanDate"))
                .limit(limit)
                .into(scans);

        List<String> uuids = scans.stream()
                .map(d -> DocumentValueSupport.stringValue(d.get("uuid")))
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();

        Map<String, Document> trackingByUuid = new HashMap<>();
        if (!uuids.isEmpty()) {
            List<Bson> trackingFilters = new ArrayList<>();
            trackingFilters.add(Filters.in("_id", uuids));
            addEquals(trackingFilters, "articleNumber", request.articleNumber());
            addEquals(trackingFilters, "applicationid", request.applicationId());
            addEquals(trackingFilters, "sourceName", request.sourceName());

            for (Document tracking : trackingCollection.find(and(trackingFilters)).limit(limit)) {
                trackingByUuid.put(DocumentValueSupport.stringValue(tracking.get("_id")), tracking);
            }
        }

        List<GroundingRecord> results = new ArrayList<>();
        for (Document scan : scans) {
            String uuid = DocumentValueSupport.stringValue(scan.get("uuid"));
            Document tracking = trackingByUuid.get(uuid);
            if (tracking == null && (notBlank(request.articleNumber())
                    || notBlank(request.applicationId())
                    || notBlank(request.sourceName()))) {
                continue;
            }

            Document client = scan.get("client", Document.class);
            Document location = scan.get("location", Document.class);
            String content = """
                    source: Azure Cosmos DB for MongoDB
                    recordType: joined QR code scan and tracking context
                    relationship: scanlog.uuid = qrCodesTracking._id
                    uuid: %s
                    scanDate: %s
                    scanApplication: %s
                    scanCountry: %s
                    scanCity: %s
                    clientUserAgent: %s
                    clientOperatingSystem: %s
                    articleNumber: %s
                    applicationId: %s
                    packagingDate: %s
                    sourceName: %s
                    numberOfScans: %s
                    lastModifiedDate: %s
                    """.formatted(
                    uuid,
                    DocumentValueSupport.stringValue(scan.get("scanDate")),
                    DocumentValueSupport.stringValue(scan.get("application")),
                    nestedString(location, "country"),
                    nestedString(location, "city"),
                    nestedString(client, "userAgent"),
                    nestedString(client, "operatingSystem"),
                    tracking == null ? "" : DocumentValueSupport.stringValue(tracking.get("articleNumber")),
                    tracking == null ? "" : DocumentValueSupport.stringValue(tracking.get("applicationid")),
                    tracking == null ? "" : DocumentValueSupport.stringValue(tracking.get("packagingDate")),
                    tracking == null ? "" : DocumentValueSupport.stringValue(tracking.get("sourceName")),
                    tracking == null ? "" : DocumentValueSupport.stringValue(tracking.get("numberOfScans")),
                    tracking == null ? "" : DocumentValueSupport.stringValue(tracking.get("lastModifiedDate")));

            String day = DateValueSupport.toIsoDate(scan.get("scanDate"));
            results.add(new GroundingRecord(
                    SCANLOG + "+" + TRACKING,
                    (day.isEmpty() ? "unknown-date" : day) + "/" + slug(uuid),
                    content));
        }
        return results;
    }

    private GroundingRecord toScanRecord(Document doc) {
        Document client = doc.get("client", Document.class);
        Document location = doc.get("location", Document.class);
        String uuid = DocumentValueSupport.stringValue(doc.get("uuid"));
        String scanDate = DocumentValueSupport.stringValue(doc.get("scanDate"));

        // Deliberately omit hashedIP and user from grounding text unless a future use case explicitly requires them.
        String content = """
                source: Azure Cosmos DB for MongoDB
                collection: scanlog
                recordType: QR code scan event
                uuid: %s
                scanDate: %s
                application: %s
                clientUserAgent: %s
                clientLanguage: %s
                clientOperatingSystem: %s
                country: %s
                countryCode: %s
                city: %s
                timezone: %s
                """.formatted(uuid, scanDate, DocumentValueSupport.stringValue(doc.get("application")),
                nestedString(client, "userAgent"), nestedString(client, "language"),
                nestedString(client, "operatingSystem"),
                nestedString(location, "country"), nestedString(location, "countryCode"),
                nestedString(location, "city"), nestedString(location, "timezone"));

        String day = DateValueSupport.toIsoDate(scanDate);
        String recordKey = (day.isEmpty() ? "unknown-date" : day) + "/" + slug(uuid);
        return new GroundingRecord(SCANLOG, recordKey, content);
    }

    private GroundingRecord toTrackingRecord(Document doc) {
        String uuid = DocumentValueSupport.stringValue(doc.get("_id"));
        String content = """
                source: Azure Cosmos DB for MongoDB
                collection: qrCodesTracking
                recordType: QR code tracking record
                uuid: %s
                qrcodeContent: %s
                fqdn: %s
                articleNumber: %s
                packagingDate: %s
                applicationId: %s
                labellingLevel: %s
                countChildLevelItems: %s
                sourceName: %s
                numberOfScans: %s
                lastModifiedDate: %s
                """.formatted(uuid,
                DocumentValueSupport.stringValue(doc.get("qrcodeContent")),
                DocumentValueSupport.stringValue(doc.get("fqdn")),
                DocumentValueSupport.stringValue(doc.get("articleNumber")),
                DocumentValueSupport.stringValue(doc.get("packagingDate")),
                DocumentValueSupport.stringValue(doc.get("applicationid")),
                DocumentValueSupport.stringValue(doc.get("labellingLevel")),
                DocumentValueSupport.stringValue(doc.get("countChildLevelItems")),
                DocumentValueSupport.stringValue(doc.get("sourceName")),
                DocumentValueSupport.stringValue(doc.get("numberOfScans")),
                DocumentValueSupport.stringValue(doc.get("lastModifiedDate")));
        return new GroundingRecord(TRACKING, slug(uuid), content);
    }

    private List<Bson> buildScanFilters(MongoGroundingQueryRequest request) {
        List<Bson> filters = new ArrayList<>();
        LocalDate from = parseDate(request.fromDate());
        LocalDate to = parseDate(request.toDate());
        if (from != null || to != null) {
            filters.add(DateValueSupport.rangeFilter("scanDate", from, to));
        }
        addEquals(filters, "uuid", request.uuid());
        addEquals(filters, "location.country", request.country());
        return filters;
    }

    // ------------------------------------------------------------------
    // Upload helpers
    // ------------------------------------------------------------------

    private int uploadRecords(String queryId, QueryType queryType, List<GroundingRecord> records) {
        int chunksUploaded = 0;
        for (GroundingRecord record : records) {
            String key = "%s/%s/%s.txt".formatted(MONGO_ROOT, queryType.folder(), record.recordKey());
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("source", "cosmos-mongodb");
            metadata.put("collection", record.collection());
            metadata.put("queryType", queryType.name());
            metadata.put("queryId", queryId);
            metadata.put("recordKey", truncate(record.recordKey(), 200));

            chunksUploaded += uploadRecordText(key, record.content(), metadata);
        }
        log.info("Prepared Mongo grounding data queryType={} records={} chunks={}",
                queryType, records.size(), chunksUploaded);
        return chunksUploaded;
    }

    /**
     * Write one record. Small records land in a single deterministic object; only oversized records
     * are split, and then the record folder is cleared first so stale parts cannot survive.
     */
    private int uploadRecordText(String logicalKey, String content, Map<String, String> metadata) {
        List<String> chunks = chunkingService.chunk(content);
        if (chunks.isEmpty()) {
            return 0;
        }
        if (chunks.size() == 1) {
            s3ObjectStoreService.uploadText(repositoryService.key(logicalKey), chunks.get(0), metadata);
            return 1;
        }

        String folder = logicalKey.endsWith(".txt")
                ? logicalKey.substring(0, logicalKey.length() - 4)
                : logicalKey;
        s3ObjectStoreService.deletePrefix(repositoryService.key(folder + "/"));
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, String> partMetadata = new LinkedHashMap<>(metadata);
            partMetadata.put("chunkIndex", String.valueOf(i));
            s3ObjectStoreService.uploadText(
                    repositoryService.key("%s/part-%02d.txt".formatted(folder, i)),
                    chunks.get(i),
                    partMetadata);
        }
        return chunks.size();
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private AppProperties.Analytics analyticsConfig() {
        AppProperties.Analytics analytics = properties.getAnalytics();
        return analytics == null ? new AppProperties.Analytics() : analytics;
    }

    private String safeRepositoryId() {
        return repositoryService.configuredRepositoryId();
    }

    private LocalDate parseDate(String value) {
        return DateValueSupport.toLocalDate(value).orElse(null);
    }

    private void addDateRangeFilters(List<Bson> filters, String field, String fromDate, String toDate) {
        LocalDate from = parseDate(fromDate);
        LocalDate to = parseDate(toDate);
        if (from != null || to != null) {
            filters.add(DateValueSupport.rangeFilter(field, from, to));
        }
    }

    private void addEquals(List<Bson> filters, String field, String value) {
        if (notBlank(value)) filters.add(Filters.eq(field, value.trim()));
    }

    private Bson and(List<Bson> filters) {
        return filters.isEmpty() ? new Document() : Filters.and(filters);
    }

    private int normalizeLimit(Integer requested) {
        int configuredMax = Math.max(1, properties.getCosmosMongo().getMaxQueryResults());
        if (requested == null) return Math.min(100, configuredMax);
        return Math.max(1, Math.min(requested, configuredMax));
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String nestedString(Document document, String field) {
        return document == null ? "" : DocumentValueSupport.stringValue(document.get(field));
    }

    private String percentage(long numerator, long denominator) {
        if (denominator <= 0) return "n/a";
        return BigDecimal.valueOf(numerator * 100.0 / denominator)
                .setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String slug(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String slug = value.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        return slug.length() <= 180 ? slug : slug.substring(0, 180);
    }

    private record GroundingRecord(String collection, String recordKey, String content) { }

    public enum QueryType {
        PROCESSING_SUMMARY("processing"),
        PACKAGING_SUMMARY("packaging"),
        SCAN_ACTIVITY("scanlog"),
        QR_TRACKING("tracking"),
        SCAN_WITH_TRACKING("scan-tracking");

        private final String folder;

        QueryType(String folder) {
            this.folder = folder;
        }

        public String folder() {
            return folder;
        }

        public static QueryType from(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("queryType must not be blank");
            }
            try {
                return QueryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unsupported queryType '" + value
                        + "'. Supported values: " + List.of(values()));
            }
        }
    }
}
