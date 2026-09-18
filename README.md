# Enterprise Copilot Backend in SAP BTP with Spring Boot, S3 Object Store, and SAP AI Core

This is a ready-to-customize sample project for a document grounding / RAG backend.

## What this project does

1. Fetches Docupedia or Confluence-like pages using REST APIs.
2. Converts page HTML into plain text.
3. Splits text into chunks.
4. Uploads chunks to S3-compatible SAP BTP Object Store.
5. Creates a SAP AI Core Document Grounding pipeline using the S3 repository.
6. Provides an `/api/ask` API that can call SAP AI Core orchestration/retrieval endpoints.

## Replace only these values first

Edit `src/main/resources/application.yml` or set environment variables:

```yaml
app:
  docupedia:
    base-url: https://YOUR-DOCUPEDIA-HOST
    username: YOUR_USER
    api-token: YOUR_TOKEN
  s3:
    endpoint: https://YOUR-S3-ENDPOINT
    region: eu-central-1
    bucket: YOUR_BUCKET
    access-key: YOUR_ACCESS_KEY
    secret-key: YOUR_SECRET_KEY
  sap-ai-core:
    token-url: https://YOUR-AUTH-HOST/oauth/token
    client-id: YOUR_CLIENT_ID
    client-secret: YOUR_CLIENT_SECRET
    api-url: https://api.ai.YOUR-CLUSTER.aws.ml.hana.ondemand.com
    resource-group: default
    s3-generic-secret-name: YOUR_AI_CORE_GENERIC_SECRET_FOR_S3
    embedding-deployment-id: YOUR_EMBEDDING_DEPLOYMENT_ID
    vector-search-prefix: docupedia
```

## Run locally

```bash
mvn clean spring-boot:run
```

## Test endpoints

### Health

```bash
curl http://localhost:8080/actuator/health
```

### Sync Docupedia space to S3

```bash
curl -X POST "http://localhost:8080/api/docupedia/sync?spaceKey=QR_CODE"
```

Optional query parameters:

- `childDepth` controls how many child levels to include during sync.
- Supported range: `0` to `3`.
- Default: `3`.
- `rootPageId` lets you sync only one specific page subtree (the page + its children up to `childDepth`).
- `mode` controls sync strategy: `delta` (default) or `full`.

Sync response includes debug fields:

- `mode`: resolved sync mode used by backend.
- `checkpointKeyUsed`: S3 key used for delta checkpoint (`""` for full mode).
- `selectedPageIds`: page IDs selected for this run after delta filtering.

Examples:

```bash
# Whole space root pages only
curl -X POST "http://localhost:8080/api/docupedia/sync?spaceKey=QR_CODE&childDepth=0"

# Whole space with 3 child levels (default)
curl -X POST "http://localhost:8080/api/docupedia/sync?spaceKey=QR_CODE&childDepth=3"

# Page-specific sync: only this page subtree (depth 3)
curl -X POST "http://localhost:8080/api/docupedia/sync?spaceKey=BTMEA&rootPageId=7452462128&childDepth=3"

# Delta sync (indexes only new/changed pages and removes deleted pages from S3)
curl -X POST "http://localhost:8080/api/docupedia/sync?spaceKey=BTMEA&mode=delta"
```

### Create SAP AI Core S3 grounding pipeline

```bash
curl -X POST http://localhost:8080/api/grounding/pipeline \
  -H "Content-Type: application/json" \
  -d '{"includePath":"/docupedia/QR_CODE"}'
```

### Ask a grounded question

```bash
curl -X POST http://localhost:8080/api/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"How does reporting microservice work?","repositoryId":"YOUR_REPOSITORY_OR_PIPELINE_ID"}'
```

### Ask from already chunked S3 data using vector search

This endpoint reads chunk files from S3, generates embeddings via SAP AI Core embedding deployment, ranks chunks by cosine similarity, and optionally asks your orchestration deployment to synthesize the answer from top matches.

```bash
curl -X POST http://localhost:8080/api/vector/ask \
  -H "Content-Type: application/json" \
  -d '{
    "question":"How does SAP BTP document grounding pipeline work?",
    "s3Prefix":"docupedia/QR_CODE",
    "maxChunks":200,
    "topK":5
  }'
```

Request fields:

- `question` (required): user question.
- `s3Prefix` (optional): S3 folder/prefix where chunks are stored. Defaults to `app.sap-ai-core.vector-search-prefix`.
- `maxChunks` (optional): max number of chunks loaded from S3 (default 200, max 1000).
- `topK` (optional): number of best matches returned (default 5, max 20).

## Important notes

- S3/Object Store is used for raw text chunks and source material.
- SAP AI Core Document Grounding is expected to create and manage the vector index.
- The `/api/ask` implementation contains a generic SAP AI Core orchestration payload. Adjust it to your exact deployment URL and orchestration contract.
- The `/api/vector/ask` implementation uses SAP AI Core's **Document Grounding Service** module integrated directly with your vector data repository for end-to-end retrieval and LLM synthesis.
- Keep Docupedia authorization in mind. Do not index restricted pages into a shared repository unless access control is handled.

## Architecture: Document Grounding Service Flow

```
User Question
    ↓
POST /api/vector/ask with repositoryId or s3Prefix
    ↓
SAP AI Core Orchestration API
    ├─ Grounding Module (Document Grounding Service)
    │  ├─ Retrieves top-K chunks from vector repository
    │  └─ Embeds results in prompt context
    │
    └─ LLM Module (GPT-4O)
       └─ Generates grounded answer using context
    ↓
Response: { answer, matches[], rawModelResponse }
```


## Azure Cosmos DB for MongoDB grounding

The project now also supports curated queries over `qrcodeProcessingReport`, `qrcodePackagingReport`, `scanlog`, and `qrCodesTracking`, then reuses the existing S3 -> SAP AI Core Document Grounding -> orchestration flow.

See [MONGODB_GROUNDING.md](MONGODB_GROUNDING.md) for configuration, query examples, API calls, parent/child joining, and suggested indexes.
