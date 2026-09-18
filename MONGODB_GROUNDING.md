# Azure Cosmos DB for MongoDB -> SAP AI Core Grounding

This implementation adds a curated, query-driven ingestion path for structured QR Code data.
It intentionally does **not** accept arbitrary Mongo JSON from the caller or let the LLM generate MongoDB queries.

## Flow

```text
Natural-language use case / UI filters
        |
        v
POST /api/mongodb/grounding/prepare
        |
        +--> Azure Cosmos DB for MongoDB
        |      |- qrcodeProcessingReport
        |      |- qrcodePackagingReport
        |      |- scanlog
        |      `- qrCodesTracking
        |
        +--> normalize structured rows into semantic text facts
        +--> ChunkingService
        +--> S3ObjectStoreService
        v
S3 prefix: mongodb/<query-type>/<query-id>/...
        |
        v
POST /api/grounding/pipeline
        |
        v
SAP AI Core Document Grounding pipeline / vector repository
        |
        v
POST /api/vector/ask
        |
        v
Grounded LLM answer
```

## Why curated query types

- predictable Cosmos DB RU consumption
- no arbitrary collection access from users/LLMs
- easier index design and performance tuning
- stable text representation for grounding
- avoids depending on advanced `$lookup` behavior

For the relation `scanlog.uuid = qrCodesTracking._id`, `SCAN_WITH_TRACKING` performs a bounded service-side join.

## Configuration

Set these values in your runtime environment / BTP secret configuration:

```bash
COSMOS_MONGO_ENABLED=true
COSMOS_MONGO_URI='mongodb://<account>:<key>@<host>:10255/<database>?ssl=true&replicaSet=globaldb&retrywrites=false&maxIdleTimeMS=120000'
COSMOS_MONGO_DATABASE='<database>'
COSMOS_MONGO_MAX_QUERY_RESULTS=500
```

Use the exact connection string provided by Azure Portal for your Cosmos DB Mongo account rather than manually reconstructing it where possible.

## Supported query types

### PROCESSING_SUMMARY
Reads `qrcodeProcessingReport` by `storageDate`, optionally for one plant.

```json
{
  "queryType": "PROCESSING_SUMMARY",
  "fromDate": "2026-08-01",
  "toDate": "2026-08-31",
  "plant": "packit",
  "limit": 31
}
```

Grounding facts include received, saved, failed, success rate, processing time, plant, and date.

### PACKAGING_SUMMARY
Reads `qrcodePackagingReport` by storage date, application ID, and/or article number.

```json
{
  "queryType": "PACKAGING_SUMMARY",
  "fromDate": "2025-08-01",
  "toDate": "2025-08-31",
  "applicationId": "00136849",
  "articleNumber": "0204114896EE9",
  "limit": 100
}
```

### SCAN_ACTIVITY
Reads `scanlog` by date range, UUID, and/or country.

```json
{
  "queryType": "SCAN_ACTIVITY",
  "fromDate": "2026-07-01",
  "toDate": "2026-07-31",
  "country": "Germany",
  "limit": 200
}
```

`hashedIP` and `user` are deliberately not written to grounding text.

### QR_TRACKING
Reads `qrCodesTracking` by UUID, article number, application ID, and/or source.

```json
{
  "queryType": "QR_TRACKING",
  "uuid": "65de2ff5-4ec9-4d0b-846c-34a0fcce1456",
  "limit": 10
}
```

### SCAN_WITH_TRACKING
Reads scan events and joins matching QR tracking data in the Java service using:

```text
scanlog.uuid == qrCodesTracking._id
```

```json
{
  "queryType": "SCAN_WITH_TRACKING",
  "fromDate": "2026-07-01",
  "toDate": "2026-07-31",
  "country": "Germany",
  "articleNumber": "F002H249304AR",
  "limit": 200
}
```

## REST workflow

### 1. Prepare Mongo data for grounding

```bash
curl -X POST http://localhost:8055/api/mongodb/grounding/prepare \
  -H "Content-Type: application/json" \
  -d '{
    "queryType":"PROCESSING_SUMMARY",
    "fromDate":"2026-08-01",
    "toDate":"2026-08-31",
    "plant":"packit",
    "limit":31
  }'
```

Example response shape:

```json
{
  "queryId": "<uuid>",
  "queryType": "PROCESSING_SUMMARY",
  "recordsFound": 31,
  "chunksUploaded": 31,
  "s3Prefix": "mongodb/processing_summary/<uuid>",
  "groundingIncludePath": "/mongodb/processing_summary/<uuid>",
  "collections": ["qrcodeProcessingReport"]
}
```

### 2. Create the SAP AI Core grounding pipeline

Pass `groundingIncludePath` from step 1:

```bash
curl -X POST http://localhost:8055/api/grounding/pipeline \
  -H "Content-Type: application/json" \
  -d '{"includePath":"/mongodb/processing_summary/<uuid>"}'
```

### 3. Wait until the pipeline is ready

```bash
curl http://localhost:8055/api/grounding/pipeline/<pipeline-id>/status
```

Use the vector data repository ID produced/associated by your SAP AI Core Document Grounding setup once the pipeline is ready.

### 4. Ask a grounded question

```bash
curl -X POST http://localhost:8055/api/vector/ask \
  -H "Content-Type: application/json" \
  -d '{
    "question":"Which plant had QR code failures in August 2026 and on which days?",
    "repositoryId":"<vector-data-repository-id>",
    "topK":10,
    "sessionId":"mongo-demo-1"
  }'
```

## Suggested indexes

Validate these against your existing shard keys and workload before creating them:

```javascript
// qrcodeProcessingReport
db.qrcodeProcessingReport.createIndex({ storageDate: 1 })

// qrcodePackagingReport
db.qrcodePackagingReport.createIndex({ "_id.storageDate": 1 })
db.qrcodePackagingReport.createIndex({ "_id.applicationId": 1, "_id.articleNumber": 1 })

// scanlog
db.scanlog.createIndex({ uuid: 1 })
db.scanlog.createIndex({ scanDate: -1 })
db.scanlog.createIndex({ "location.country": 1, scanDate: -1 })

// qrCodesTracking
// _id is already indexed
db.qrCodesTracking.createIndex({ articleNumber: 1 })
db.qrCodesTracking.createIndex({ applicationid: 1 })
db.qrCodesTracking.createIndex({ sourceName: 1 })
```

For Cosmos DB, index choice and cross-partition queries affect RU consumption. Prefer filters that align with your partition/shard key when available.

## Security notes

- Mongo/S3/SAP/Docupedia/Azure credentials must be runtime secrets only.
- The uploaded project contained credentials as YAML defaults; these defaults were removed from the modified copy.
- Rotate any credentials that were committed or shared in source history.
- Keep Mongo access read-only for this grounding component where possible.
