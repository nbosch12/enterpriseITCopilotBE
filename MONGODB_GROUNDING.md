# Azure Cosmos DB for MongoDB -> SAP AI Core Grounding

This implementation adds a curated, query-driven ingestion path for structured QR Code data.
It intentionally does **not** accept arbitrary Mongo JSON from the caller or let the LLM generate MongoDB queries.

## One repository for everything

All grounding data, Docupedia and MongoDB alike, is written under a single S3 include path and
queried through a single SAP AI Core vector repository:

```yaml
app:
  grounding:
    repository-id: f0bf939b-59ef-4211-b8f0-db053c46cbad   # GROUNDING_REPOSITORY_ID
    root-prefix: ""                                        # "" = bucket root -> include path "/"
    enforce-single-repository: true                        # repositoryId on requests is ignored
```

- `POST /api/vector/ask` no longer needs `repositoryId`; the configured one is always used.
- `GET /api/grounding/repository` shows the repository id and the include path its pipeline must use.
- `POST /api/grounding/pipeline/single` creates a pipeline on that include path if you need a new one.

S3 keys are **deterministic** (`mongodb/qrcode/processing/daily/2026-08-02/packit.txt`). Re-running a
sync overwrites the same objects. The previous implementation wrote each call to a new
`mongodb/<queryType>/<random-uuid>/` folder, which left several copies of the same day in the index
and inflated every total. Remove those folders once:

```bash
curl -X POST "http://localhost:8055/api/mongodb/grounding/cleanup-legacy"               # dry run: counts only
curl -X POST "http://localhost:8055/api/mongodb/grounding/cleanup-legacy?dryRun=false"  # delete
```

Then trigger the pipeline so the repository drops the deleted documents.

## Counting and ranking questions are answered from MongoDB, not retrieval

Questions such as

- "Which sourceName received the highest number of QR codes in August 2026?"
- "Which plant received the highest number of records in the 1st week of August 2026?"
- "Which plant received the highest number of records on 2 August 2026?"

cannot be answered reliably by top-k vector retrieval: the model is shown a handful of chunks out of
a month of daily rows and picks a winner from that sample. `/api/vector/ask` now recognises these
questions and computes the answer exactly over the full period. The response carries
`answerSource: "mongo-analytics"` and the full ranked breakdown in `analytics`. Everything else still
goes to vector search (`answerSource: "vector-search"`). Send `"forceVectorSearch": true` to bypass.

Supported periods: a month (`August 2026`, `2026-08`), a week of a month (`1st week of August 2026`,
`first week`, `week 3 of ...`), a day (`2 August 2026`, `August 2, 2026`, `2026-08-02`), and relative
forms (`last month`, `this month`, `yesterday`). A ranking question with no concrete period gets a
request for the period instead of a guess.

Week of month (`app.analytics.week-of-month-mode`):
- `DAY_BLOCKS` (default): week 1 = days 1-7, week 2 = 8-14, week 3 = 15-21, week 4 = 22-28, week 5 = 29-end.
- `CALENDAR`: Monday-to-Sunday weeks; week 1 is the partial week containing the 1st.

`GET /api/mongodb/analytics/weeks?month=2026-08` shows the exact boundaries in use.

"Received" numbers exist only in `qrcodeProcessingReport.plantReports`, keyed by plant/source name,
so received/saved/failed questions are always read from there, even when the question says
"sourceName". `records` questions about tracked codes read `qrCodesTracking`, and `scans` questions
read `scanlog`. The collection used is returned in `analytics.dataSource`.

### Analytics endpoints

```bash
# Natural language
curl -X POST http://localhost:8055/api/mongodb/analytics/ask -H "Content-Type: application/json" \
  -d '{"question":"Which plant received the highest number of records in the 1st week of August 2026?"}'

# Structured: one of date | month (+ weekOfMonth) | fromDate+toDate
curl -X POST http://localhost:8055/api/mongodb/analytics/rank -H "Content-Type: application/json" \
  -d '{"dimension":"PLANT","metric":"RECEIVED","month":"2026-08","weekOfMonth":1,"order":"desc"}'
```

## Why some days used to report "no data"

`storageDate` was filtered with a plain string comparison (`$gte "2026-08-01"`, `$lte "2026-08-31"`).
That silently misses any document whose date is stored as a BSON `Date`, an epoch number, or a
non-ISO string such as `02-08-2026`, and it excludes timestamps like `2026-08-31T22:10:00` at the
upper bound. `plantReports` stored as an array also crashed the old parser.

Now:
1. A typed server-side filter matches string, BSON `Date` and numeric storage, with an exclusive
   upper bound of the next day.
2. Day-by-day coverage is checked. If any day is missing (`date-filter-strategy: AUTO`), the
   collection is rescanned and every date is parsed in the application.
3. `plantReports` is read as a map or an array, with alternate field names tolerated.
4. Documents that still cannot be read are reported with a reason instead of being dropped.

Check any range:

```bash
curl "http://localhost:8055/api/mongodb/diagnostics/coverage?fromDate=2026-08-01&toDate=2026-08-31"
```

`missingDates` lists days with no source document at all. `problems` lists documents that exist but
could not be parsed. `documentsFoundByFallbackScan > 0` means some dates are stored in a format the
server-side filter could not match; that is handled, but worth normalising at the source.

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
S3 prefix: mongodb/qrcode/<type>/... (deterministic, single include path)
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
  "queryId": "<uuid, for tracing only>",
  "queryType": "PROCESSING_SUMMARY",
  "recordsFound": 90,
  "chunksUploaded": 90,
  "s3Prefix": "mongodb/qrcode/processing",
  "groundingIncludePath": "/",
  "collections": ["qrcodeProcessingReport"],
  "repositoryId": "f0bf939b-59ef-4211-b8f0-db053c46cbad",
  "rollupChunksUploaded": 36,
  "coverage": { "expectedDays": 31, "daysWithData": 30, "missingDates": ["2026-08-20"], "...": "..." },
  "notes": ["..."]
}
```

For `PROCESSING_SUMMARY`, omit `plant` to ingest every plant and keep `includeRollups` on (default):
monthly, weekly and daily ranking chunks are uploaded alongside the daily rows so retrieval can also
find totals in a single chunk. `replaceExisting: true` clears the deterministic prefix first.

### 2. Refresh the single repository

Do **not** create a pipeline per query. Trigger the existing pipeline that feeds the configured
repository (its include path must be the `pipelineIncludePath` from `GET /api/grounding/repository`):

```bash
curl -X POST http://localhost:8055/api/grounding/pipeline/<pipeline-id>/trigger
```

Only if no such pipeline exists yet: `POST /api/grounding/pipeline/single`, then set
`GROUNDING_REPOSITORY_ID` to the repository it produces.

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
    "topK":10,
    "sessionId":"mongo-demo-1"
  }'
```

## Suggested indexes

Validate these against your existing shard keys and workload before creating them:

```javascript
// qrcodeProcessingReport (one document per day - analytics scans are cheap)
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
db.qrCodesTracking.createIndex({ packagingDate: 1, sourceName: 1 })   // sourceName analytics by period
```

For Cosmos DB, index choice and cross-partition queries affect RU consumption. Prefer filters that align with your partition/shard key when available.

## Security notes

- Mongo/S3/SAP/Docupedia/Azure credentials must be runtime secrets only.
- `application.yml` still contains real-looking credentials as `${ENV:default}` fallbacks (S3 keys,
  SAP AI Core client secret, Docupedia token). Move them to runtime secrets and rotate them.
- Keep Mongo access read-only for this grounding component where possible.
