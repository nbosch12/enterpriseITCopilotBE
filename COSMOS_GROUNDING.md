# Cosmos DB for MongoDB — retrieval and grounding

How the QR code data held in Azure Cosmos DB for MongoDB is answered through
`POST /api/copilot/ask`.

## The flow

```text
POST /api/copilot/ask   { question, sessionId, repositoryId, useHistory, historyTurns, topK }
        |
        v
CopilotOrchestrationService
        |  routes on keywords (or forceTool = "COSMOS_QRCODE")
        v
CosmosMongoTool                                  <- retrieval only, never answers
        |
        +--> counting / ranking question?
        |       QrCodeAnalyticsService.rank(...)  -> totals summed over the whole period
        |
        +--> record question?
        |       CosmosMongoGroundingService.retrieveRecords(...) -> curated query rows
        |
        +--> ranking question with no period named?
                -> asks for the period, runs no query, states no figures
        |
        v
   bounded grounding context  (ToolResult.summary, strictGrounding = true)
        |
        v
CopilotOrchestrationService.synthesizeResponse
        |  system prompt + strict grounding rules
        |  + conversation history
        |  + retrieved context
        |  + the original question
        v
SapAiCoreIncidentLlmClient.chat(...)             <- the grounding model
        |
        v
CopilotAskResponse { answer, toolsUsed, toolResults, sessionId, processingNotes }
```

The model writes the answer. Retrieved rows are never returned as the answer; they travel in
`toolResults[].rawPayload` for UI drill-down, which the response contract already carried for
every tool.

## What the model is told

When a tool marks its payload `strictGrounding`, the orchestrator appends these rules to the
system prompt:

- use only the supplied records; do not add figures, names, dates or totals that are not present
- do not estimate, extrapolate or infer values that were not retrieved
- if the records do not contain enough information, say so plainly and state what is missing
- if the retrieval reports missing days or records, mention that alongside any totals
- quote figures exactly as they appear

Tools that do not set the flag keep the original prompt, so the logs and documentation tools are
unaffected.

## Two retrieval shapes

**Counting and ranking** — "which plant received the highest number of QR codes in August 2026".
Computed in MongoDB across the whole period and handed over already summed and ranked. Top-k
similarity search cannot answer these: it shows the model a handful of chunks out of a month, so
the model picks a winner from a sample.

Supported periods: a month (`August 2026`, `2026-08`), a week of a month (`1st week of August
2026`, `week 3 of ...`), a single day (`2 August 2026`, `2026-08-02`), and relative forms (`last
month`, `yesterday`). Week boundaries follow `app.analytics.week-of-month-mode`: `DAY_BLOCKS`
(week 1 = days 1-7) or `CALENDAR` (Monday-Sunday).

**Record lookup** — "show me the scans from Germany in July 2026". Runs the curated query for the
collection the question is about and renders the matching rows as facts.

| Question mentions | Query type | Collection |
|---|---|---|
| scans *and* tracking | `SCAN_WITH_TRACKING` | `scanlog` + `qrCodesTracking` |
| scans | `SCAN_ACTIVITY` | `scanlog` |
| tracking, uuid, article number, application id, sourceName | `QR_TRACKING` | `qrCodesTracking` |
| packaging, storage items | `PACKAGING_SUMMARY` | `qrcodePackagingReport` |
| anything else (plants, received/saved/failed) | `PROCESSING_SUMMARY` | `qrcodeProcessingReport` |

## Bounds

A question must not be able to push an unbounded payload into the model prompt.

| Setting | Default | Purpose |
|---|---|---|
| `app.analytics.max-context-records` | 60 | rows rendered into the context |
| `app.analytics.max-context-characters` | 12000 | hard cap on context size; truncation is stated, not silent |
| `app.cosmos-mongo.max-query-results` | 500 | rows one curated query may return |
| `app.cosmos-mongo.max-scan-documents` | 50000 | analytics scan ceiling, so a monthly total is never silently truncated |

## Turning aggregation off

`app.analytics.enabled=false` disables the computed-totals path only. Cosmos DB questions are still
answered, from the underlying records rather than from summed rankings. It is an escape hatch if a
ranking result ever looks wrong, without losing access to the data. To disable Cosmos DB entirely,
use `app.cosmos-mongo.enabled=false` instead.

## Tracking date fields

`qrCodesTracking` analytics filter on `app.analytics.tracking-date-field` (default `packagingDate`),
falling back to `tracking-fallback-date-field` (default `lastModifiedDate`) when the primary date is
absent **or null**. MongoDB counts a null-valued field as present, so both cases need handling or
those documents match neither branch and vanish from the totals.

If your tracking documents never carry `packagingDate`, point the primary field at the one they do
use and clear the fallback:

```bash
ANALYTICS_TRACKING_DATE_FIELD=lastModifiedDate
ANALYTICS_TRACKING_FALLBACK_DATE_FIELD=
```

## Date handling

`storageDate` is not stored consistently across the collections. Values are read whether they are
ISO strings, BSON dates, epoch numbers or `dd-MM-yyyy` strings, and day coverage is verified after
the query: if a day in the range produced nothing, the collection is rescanned with a tolerant
parser before the day is reported as genuinely absent. Missing days travel with the totals so the
answer can qualify them rather than presenting an incomplete month as complete.

## Errors

| Condition | Behaviour |
|---|---|
| No rows matched | Successful retrieval; the model is told the query returned nothing and answers accordingly |
| Ranking question with no period | No query runs; the model is told to ask which period, and to state no figures |
| Database unreachable or timed out | `ToolResult.error`; the model still writes the reply. Connection strings and credentials are stripped from both the response and the log |
| Grounding model unreachable | The retrieved rows are **withheld** from the answer; the reply says the model could not be reached. Structured results remain in `toolResults` |

## Configuration

Connection details come from the environment; nothing secret is in source.

```bash
COSMOS_MONGO_ENABLED=true
MONGODB_URI='mongodb://<account>:<key>@<host>:10255/<db>?ssl=true&replicaSet=globaldb&retrywrites=false'
MONGODB_DATABASE=qrcode
```

With `COSMOS_MONGO_ENABLED=false` (the default) no Cosmos bean is registered, the tool is absent
from the registry, and every other question type behaves exactly as it did before this merge.

The `MONGODB_URI` default in `application.yml` is a syntactically valid localhost placeholder
rather than an empty string: the Mongo driver on the classpath makes Spring Boot auto-configure a
client at startup and it rejects an empty value. The client is lazy, so with Cosmos disabled
nothing connects.

## Suggested indexes

```javascript
db.qrcodeProcessingReport.createIndex({ storageDate: 1 })
db.qrcodePackagingReport.createIndex({ "_id.storageDate": 1 })
db.qrcodePackagingReport.createIndex({ "_id.applicationId": 1, "_id.articleNumber": 1 })
db.scanlog.createIndex({ scanDate: -1 })
db.scanlog.createIndex({ "location.country": 1, scanDate: -1 })
db.qrCodesTracking.createIndex({ packagingDate: 1, sourceName: 1 })
db.qrCodesTracking.createIndex({ articleNumber: 1 })
```

## Export path (unchanged from the old application)

`POST /api/mongodb/grounding/prepare` still writes MongoDB data into the object store for the
vector pipeline, with deterministic keys so a re-run overwrites rather than duplicating. That path
is independent of the live question flow above, which reads the database directly.
