package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.MongoGroundingQueryRequest;
import com.bosch.demo.docgrounding.model.MongoGroundingSyncResult;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "app.cosmos-mongo", name = "enabled", havingValue = "true")
public class CosmosMongoGroundingService {

    private static final String PROCESSING = "qrcodeProcessingReport";
    private static final String PACKAGING = "qrcodePackagingReport";
    private static final String SCANLOG = "scanlog";
    private static final String TRACKING = "qrCodesTracking";

    private final MongoDatabase database;
    private final S3ObjectStoreService s3ObjectStoreService;
    private final ChunkingService chunkingService;
    private final AppProperties properties;

    public CosmosMongoGroundingService(
            MongoClient mongoClient,
            S3ObjectStoreService s3ObjectStoreService,
            ChunkingService chunkingService,
            AppProperties properties) {
        this.properties = properties;
        this.s3ObjectStoreService = s3ObjectStoreService;
        this.chunkingService = chunkingService;

        String databaseName = properties.getCosmosMongo().getDatabase();
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalStateException("COSMOS_MONGO_DATABASE/app.cosmos-mongo.database must be configured");
        }
        this.database = mongoClient.getDatabase(databaseName);
    }

    public MongoGroundingSyncResult prepareGroundingData(MongoGroundingQueryRequest request) {
        QueryType queryType = QueryType.from(request.queryType());
        int limit = normalizeLimit(request.limit());

        List<GroundingRecord> records = switch (queryType) {
            case PROCESSING_SUMMARY -> queryProcessingReports(request, limit);
            case PACKAGING_SUMMARY -> queryPackagingReports(request, limit);
            case SCAN_ACTIVITY -> queryScanActivity(request, limit);
            case QR_TRACKING -> queryTracking(request, limit);
            case SCAN_WITH_TRACKING -> queryScanWithTracking(request, limit);
        };

        String queryId = UUID.randomUUID().toString();
        String prefix = "mongodb/" + queryType.name().toLowerCase(Locale.ROOT) + "/" + queryId;
        int chunksUploaded = uploadRecords(prefix, queryId, queryType, records);

        List<String> collections = records.stream()
                .map(GroundingRecord::collection)
                .distinct()
                .toList();

        return new MongoGroundingSyncResult(
                queryId,
                queryType.name(),
                records.size(),
                chunksUploaded,
                prefix,
                "/" + prefix,
                collections);
    }

    private List<GroundingRecord> queryProcessingReports(MongoGroundingQueryRequest request, int limit) {
        MongoCollection<Document> collection = database.getCollection(PROCESSING);
        Bson filter = dateRangeFilter("storageDate", request.fromDate(), request.toDate(), false);

        FindIterable<Document> docs = collection.find(filter)
                .sort(Sorts.ascending("storageDate"))
                .limit(limit);

        List<GroundingRecord> results = new ArrayList<>();
        for (Document doc : docs) {
            String storageDate = stringValue(doc.get("storageDate"));
            String processingTime = stringValue(doc.get("processingTime"));
            Document plantReports = doc.get("plantReports", Document.class);
            if (plantReports == null) continue;

            for (Map.Entry<String, Object> entry : plantReports.entrySet()) {
                String plant = entry.getKey();
                if (notBlank(request.plant()) && !plant.equalsIgnoreCase(request.plant())) continue;
                if (!(entry.getValue() instanceof Document plantReport)) continue;

                long received = longValue(plantReport.get("qrCodesReceived"));
                long saved = longValue(plantReport.get("qrCodesSaved"));
                long failed = longValue(plantReport.get("qrCodesFailed"));
                String successRate = percentage(saved, received);

                String content = """
                        source: Azure Cosmos DB for MongoDB
                        collection: qrcodeProcessingReport
                        recordType: QR code processing daily plant summary
                        storageDate: %s
                        plant: %s
                        qrCodesReceived: %d
                        qrCodesSaved: %d
                        qrCodesFailed: %d
                        successRate: %s
                        processingTime: %s
                        """.formatted(storageDate, plant, received, saved, failed, successRate, processingTime);

                results.add(new GroundingRecord(PROCESSING, storageDate + "-" + plant, content));
            }
        }
        return results;
    }

    private List<GroundingRecord> queryPackagingReports(MongoGroundingQueryRequest request, int limit) {
        MongoCollection<Document> collection = database.getCollection(PACKAGING);
        List<Bson> filters = new ArrayList<>();
        addDateRangeFilters(filters, "_id.storageDate", request.fromDate(), request.toDate(), false);
        addEquals(filters, "_id.applicationId", request.applicationId());
        addEquals(filters, "_id.articleNumber", request.articleNumber());

        FindIterable<Document> docs = collection.find(and(filters))
                .sort(Sorts.descending("_id.storageDate"))
                .limit(limit);

        List<GroundingRecord> results = new ArrayList<>();
        for (Document doc : docs) {
            Document id = doc.get("_id", Document.class);
            if (id == null) continue;
            String storageDate = stringValue(id.get("storageDate"));
            String applicationId = stringValue(id.get("applicationId"));
            String articleNumber = stringValue(id.get("articleNumber"));
            long storageItems = longValue(doc.get("storageItems"));

            @SuppressWarnings("unchecked")
            List<Document> packaging = (List<Document>) doc.getOrDefault("packaging", List.of());
            String packagingFacts = packaging.stream()
                    .map(p -> stringValue(p.get("packagingDate")) + "=" + longValue(p.get("numberOfItems")))
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
                    packagingFacts, stringValue(doc.get("processedDate")));

            results.add(new GroundingRecord(PACKAGING, storageDate + "-" + applicationId + "-" + articleNumber, content));
        }
        return results;
    }

    private List<GroundingRecord> queryScanActivity(MongoGroundingQueryRequest request, int limit) {
        MongoCollection<Document> collection = database.getCollection(SCANLOG);
        List<Bson> filters = buildScanFilters(request);

        FindIterable<Document> docs = collection.find(and(filters))
                .sort(Sorts.descending("scanDate"))
                .limit(limit);

        List<GroundingRecord> results = new ArrayList<>();
        for (Document doc : docs) {
            results.add(toScanRecord(doc));
        }
        return results;
    }

    private List<GroundingRecord> queryTracking(MongoGroundingQueryRequest request, int limit) {
        MongoCollection<Document> collection = database.getCollection(TRACKING);
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
        MongoCollection<Document> scanCollection = database.getCollection(SCANLOG);
        MongoCollection<Document> trackingCollection = database.getCollection(TRACKING);

        List<Document> scans = new ArrayList<>();
        scanCollection.find(and(buildScanFilters(request)))
                .sort(Sorts.descending("scanDate"))
                .limit(limit)
                .into(scans);

        List<String> uuids = scans.stream()
                .map(d -> stringValue(d.get("uuid")))
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
                trackingByUuid.put(stringValue(tracking.get("_id")), tracking);
            }
        }

        List<GroundingRecord> results = new ArrayList<>();
        for (Document scan : scans) {
            String uuid = stringValue(scan.get("uuid"));
            Document tracking = trackingByUuid.get(uuid);
            if (tracking == null && (notBlank(request.articleNumber()) || notBlank(request.applicationId()) || notBlank(request.sourceName()))) {
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
                    stringValue(scan.get("scanDate")),
                    stringValue(scan.get("application")),
                    nestedString(location, "country"),
                    nestedString(location, "city"),
                    nestedString(client, "userAgent"),
                    nestedString(client, "operatingSystem"),
                    tracking == null ? "" : stringValue(tracking.get("articleNumber")),
                    tracking == null ? "" : stringValue(tracking.get("applicationid")),
                    tracking == null ? "" : stringValue(tracking.get("packagingDate")),
                    tracking == null ? "" : stringValue(tracking.get("sourceName")),
                    tracking == null ? "" : stringValue(tracking.get("numberOfScans")),
                    tracking == null ? "" : stringValue(tracking.get("lastModifiedDate")));

            results.add(new GroundingRecord(SCANLOG + "+" + TRACKING, uuid + "-" + stringValue(scan.get("scanDate")), content));
        }
        return results;
    }

    private GroundingRecord toScanRecord(Document doc) {
        Document client = doc.get("client", Document.class);
        Document location = doc.get("location", Document.class);
        String uuid = stringValue(doc.get("uuid"));
        String scanDate = stringValue(doc.get("scanDate"));

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
                """.formatted(uuid, scanDate, stringValue(doc.get("application")),
                nestedString(client, "userAgent"), nestedString(client, "language"), nestedString(client, "operatingSystem"),
                nestedString(location, "country"), nestedString(location, "countryCode"), nestedString(location, "city"), nestedString(location, "timezone"));

        return new GroundingRecord(SCANLOG, uuid + "-" + scanDate, content);
    }

    private GroundingRecord toTrackingRecord(Document doc) {
        String uuid = stringValue(doc.get("_id"));
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
                """.formatted(uuid, stringValue(doc.get("qrcodeContent")), stringValue(doc.get("fqdn")),
                stringValue(doc.get("articleNumber")), stringValue(doc.get("packagingDate")), stringValue(doc.get("applicationid")),
                stringValue(doc.get("labellingLevel")), stringValue(doc.get("countChildLevelItems")), stringValue(doc.get("sourceName")),
                stringValue(doc.get("numberOfScans")), stringValue(doc.get("lastModifiedDate")));
        return new GroundingRecord(TRACKING, uuid, content);
    }

    private List<Bson> buildScanFilters(MongoGroundingQueryRequest request) {
        List<Bson> filters = new ArrayList<>();
        addDateRangeFilters(filters, "scanDate", request.fromDate(), request.toDate(), true);
        addEquals(filters, "uuid", request.uuid());
        addEquals(filters, "location.country", request.country());
        return filters;
    }

    private int uploadRecords(String prefix, String queryId, QueryType queryType, List<GroundingRecord> records) {
        int chunksUploaded = 0;
        for (int recordIndex = 0; recordIndex < records.size(); recordIndex++) {
            GroundingRecord record = records.get(recordIndex);
            List<String> chunks = chunkingService.chunk(record.content());
            for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
                String key = "%s/record-%04d-chunk-%02d.txt".formatted(prefix, recordIndex, chunkIndex);
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put("source", "cosmos-mongodb");
                metadata.put("collection", record.collection());
                metadata.put("queryType", queryType.name());
                metadata.put("queryId", queryId);
                metadata.put("recordKey", truncate(record.recordKey(), 200));

                s3ObjectStoreService.uploadText(key, chunks.get(chunkIndex), metadata);
                chunksUploaded++;
            }
        }
        log.info("Prepared Mongo grounding data queryType={} records={} chunks={} prefix={}",
                queryType, records.size(), chunksUploaded, prefix);
        return chunksUploaded;
    }

    private Bson dateRangeFilter(String field, String fromDate, String toDate, boolean timestamp) {
        List<Bson> filters = new ArrayList<>();
        addDateRangeFilters(filters, field, fromDate, toDate, timestamp);
        return and(filters);
    }

    private void addDateRangeFilters(List<Bson> filters, String field, String fromDate, String toDate, boolean timestamp) {
        if (notBlank(fromDate)) filters.add(Filters.gte(field, timestamp ? fromDate + "T00:00:00" : fromDate));
        if (notBlank(toDate)) filters.add(Filters.lte(field, timestamp ? toDate + "T23:59:59.999999999" : toDate));
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
        return document == null ? "" : stringValue(document.get(field));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ex) { return 0; }
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

    private record GroundingRecord(String collection, String recordKey, String content) { }

    public enum QueryType {
        PROCESSING_SUMMARY,
        PACKAGING_SUMMARY,
        SCAN_ACTIVITY,
        QR_TRACKING,
        SCAN_WITH_TRACKING;

        public static QueryType from(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("queryType must not be blank");
            }
            try {
                return QueryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Unsupported queryType '" + value + "'. Supported values: " + List.of(values()));
            }
        }
    }
}
