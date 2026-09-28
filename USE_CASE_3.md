# Use Case 3 — Query mock ServiceNow tickets, create a Jira ticket from one

ServiceNow stays fully mocked. Nothing in this use case contacts a ServiceNow instance: tickets
live in memory for the lifetime of the application. Jira is the one real outbound call, and it
reuses the client the existing pipeline already uses.

---

## 1. What was already implemented (unchanged)

These were present in the uploaded application and were reused as they are:

| Component | What it already did |
|---|---|
| `jira/model/JiraIssueRequest` | Jira REST v2 request body — `fields.project.key`, `summary`, `description`, `issuetype.name`, `priority.name`, `labels`. Already exactly the required structure; **not modified**. |
| `jira/JiraClient.createIssue(payload, projectKey, snId)` | Posts to `/rest/api/2/issue`, attaches the `SN-<number>` label for traceability, resolves the issue type against the project's real issue types, maps priority, retries on I/O errors. |
| `jira/JiraClient.findAlreadyProcessedIds(projectKey)` | Reads `SN-` labels back out of Jira — the durable duplicate guard. |
| `servicenow/MockServiceNowClient` | Five seeded demo tickets (`INC0010001` … `INC0010005`) and `fetchNewTickets()`, which hides tickets already synced to Jira. |
| `ai/model/JiraTicketPayload` | Summary / description / issue type / priority carrier. |
| `service/TicketHandoffService`, the scheduler, `POST /api/pipeline/trigger` | The existing batch path: poll the mock, classify, raise issues. Untouched. |
| `CopilotTool` / `ToolRegistryService` / `CopilotOrchestrationService` | Tool routing and the grounding call to the model. Untouched. |

No existing behaviour was changed. The scheduler still sees exactly what it saw before — proven
below.

---

## 2. What was added

Four new classes:

| File | Purpose |
|---|---|
| `servicenow/MockServiceNowTicketStore.java` | In-memory store for tickets posted at runtime, plus lookup across posted **and** seeded tickets. Generates `INC…` numbers when a caller omits one. |
| `servicenow/ServiceNowTicketService.java` | All the deterministic logic: find by number, find created on a day, and the Jira hand-off with its duplicate guard and error redaction. |
| `controller/MockServiceNowController.java` | `POST`/`GET` endpoints for creating and inspecting mock tickets, and a direct Jira hand-off endpoint for testing. |
| `service/tools/ServiceNowTool.java` | The copilot tool: parses the question, decides list vs. one ticket vs. create, and hands the model grounded context. |

Two new test classes — `ServiceNowTicketServiceTest` (17 tests) and `ServiceNowToolTest`
(13 tests) — cover selection, refusal, duplicate protection and redaction.

### Three small edits to existing files

1. **`servicenow/model/ServiceNowTicket.java`** — two optional fields, `issueType` and
   `projectKey`, so a ticket can name the Jira issue type and project to use. An 8-argument
   constructor was kept, so every existing call site (the five seeded tickets, the real
   `ServiceNowClientImpl`) compiles and behaves exactly as before, with both fields absent.

2. **`servicenow/MockServiceNowClient.java`** — one additive method, `allSeededTickets()`.
   `fetchNewTickets()` is the scheduler's view and deliberately hides tickets already synced to
   Jira; the copilot's "which tickets were created today" needs the full set. Polling behaviour is
   unchanged.

3. **`config/ServiceNowProperties.java`** + `application.yml` — a `timezone` property, default
   `Asia/Kolkata`, because ticket timestamps carry no zone and "created today" is only meaningful
   against a stated one.

`pom.xml` was not touched. No new dependency was added.

---

## 3. How the ticket is chosen — deterministic, not the model

The tool parses the question; Java does the deciding:

- A ticket number is matched by pattern (`INC|CHG|REQ|RITM|PRB|TASK` + digits, case-insensitive)
  and looked up in the store. Creation happens only for a number that exists.
- **If the question asks to create but names no number and more than one ticket matches, nothing is
  created.** The tool returns the candidate list and the model is told to ask which one — it must
  not choose by project key or creation date. (`ServiceNowToolTest.creationIsRefusedWhenNoTicketNumberIsNamedAndSeveralMatch`
  asserts zero Jira calls.)
- An unknown number is reported as unknown; no issue is raised and no details are invented.
- The same ticket cannot produce two issues: an in-memory map guards the run, and Jira's own `SN-`
  labels are consulted before creating. A Jira search failure does not block creation, but the
  in-memory guard still holds.
- Every result is handed to the model with `strictGrounding = true`, the same flag the Cosmos tool
  uses, so the orchestrator instructs the model to answer only from the supplied records and to say
  when they are insufficient. Raw records are never returned as the user-facing answer.
- Jira failures are summarised with bearer tokens and URI credentials stripped, and no stack trace
  reaches the user.

---

## 4. Creating a mock ServiceNow ticket

```
POST /api/servicenow/tickets
Content-Type: application/json

{
  "number":      "INC1000123",
  "summary":     "Payment service returning 500s",
  "description": "Checkout fails intermittently since the 09:00 deploy.",
  "issueType":   "Bug",
  "projectKey":  "ITSUP",
  "category":    "Application",
  "priority":    "HIGH",
  "requestedBy": "prem@example.com"
}
```

- `summary` is the only required field.
- `number` is generated (`INC…`) when omitted; `createdAt` defaults to now. Pass `createdOn`
  (`"2026-09-20"`) to place a ticket on an earlier day without inventing a clock time.
- `issueType` and `projectKey` are optional; without them the configured Jira defaults apply.
- Creating a ticket does **not** raise a Jira issue, and it does not enter the scheduler's queue —
  so posting a ticket never causes the background poller to raise one behind your back.

Response: `201 Created` with the stored ticket.

### Querying

REST:

| Request | Returns |
|---|---|
| `GET /api/servicenow/tickets` | every mock ticket, posted and seeded, newest first |
| `GET /api/servicenow/tickets/today` | tickets created today, with the date and zone used |
| `GET /api/servicenow/tickets/{number}` | one ticket, or `404` |

Through the copilot (`POST /api/copilot/ask`, the frontend's existing contract — unchanged):

- *"Which ServiceNow tickets were created today?"*
- *"Show me the ServiceNow tickets created today"*
- *"What is ServiceNow ticket INC1000123 about?"*

The answer lists each ticket's number, summary, issue type and project key, so the next prompt can
name one.

---

## 5. Creating a Jira ticket from a selected ServiceNow ticket

The prompt:

> **Create a Jira ticket for ServiceNow ticket INC1000123 created today**

The ticket number is what selects the ticket. Variants that work: *"raise a Jira issue for
INC1000123"*, *"open a Jira bug for ServiceNow ticket INC1000123"*.

A prompt with no number — *"create a Jira ticket for the ServiceNow ticket created today"* — creates
nothing while more than one ticket matches; the reply lists the candidates and asks which.

The Jira issue carries:

- summary `[INC1000123] Payment service returning 500s`
- description quoting the ServiceNow number, category, requester and creation time
- label `SN-INC1000123`
- issue type from the ticket's `issueType`, resolved against the project's real issue types
- project from the ticket's `projectKey`, else `jira.project-key`

Direct hand-off without the copilot, for testing:
`POST /api/servicenow/tickets/INC1000123/jira` → `201` created, `200` already created, `404` unknown
ticket, `502` Jira failed.

---

## 6. Jira configuration required

Existing properties, unchanged (`application.yml`, overridable by environment variable):

| Property | Env var | Meaning |
|---|---|---|
| `jira.base-url` | `JIRA_BASE_URL` | Jira base URL, e.g. `https://rb-tracker.bosch.com/tracker03` |
| `jira.pat` | `JIRA_PAT` | Personal access token, sent as `Bearer` |
| `jira.project-key` | `JIRA_PROJECT_KEY` | Default project when a ticket names none |
| `jira.username` | `JIRA_USERNAME` | Informational |

New property:

| Property | Env var | Default |
|---|---|---|
| `servicenow.timezone` | `SERVICENOW_TIMEZONE` | `Asia/Kolkata` — the zone "created today" is judged in |

Secrets stay out of source: set them with `cf set-env` (or a local `.env`), not in the YAML.

> **Note on the committed default:** `jira.pat` currently reads
> `${JIRA_PAT:JIRA_PAT:NTE5Mjg0Mjk1OTcxOg8opEW76mu/aLuiJ6UaSBJ2HzA5}` — the default value has the
> variable name doubled into it, and a real-looking token is committed. It works only because
> `JIRA_PAT` is set in the deployed environment. That token should be rotated and the default
> reduced to empty.

---

## 7. Verification

Compiled and run offline against the project's own dependency set (Maven Central is blocked from
this environment, so jars were taken from the built artifact):

```
main sources compiled: 92 files, 0 errors
tests: 96 passed, 0 failed
  ServiceNowTicketServiceTest, ServiceNowToolTest, CosmosMongoToolTest, TrackingDateFilterTest,
  CosmosMongoGroundingServiceMappingTest, CopilotOrchestrationGroundingTest,
  CopilotControllerContractTest, ChunkingServiceTest, ConversationMemoryServiceTest
  (4 pre-existing tests excluded: 3 need Mockito, 1 needs spring-boot-test — neither is in the
   offline jar set)
```

Application started, with these results:

```
Discovered 4 copilot tools: AZURE_LOGS, COSMOS_QRCODE, DOCUPEDIA_GROUNDING, SERVICENOW_JIRA

ROUTE SERVICENOW_JIRA      <- Which ServiceNow tickets were created today?
ROUTE SERVICENOW_JIRA      <- Create a Jira ticket for ServiceNow ticket INC1000123 created today
ROUTE COSMOS_QRCODE        <- which plant received the most QR codes in August 2026
ROUTE DOCUPEDIA_GROUNDING  <- How does the reporting microservice work?

zone=Asia/Kolkata  seededVisible=5  createdToday=5
scheduler view before=5 after=5  (must be equal)  queryable=true
```

The last line is the isolation check: a ticket posted through the new endpoint is queryable by the
copilot but does not appear in `fetchNewTickets()`, so the existing scheduler's behaviour is
untouched.

`"ticket"` and `"tickets"` were added to the tool's keyword list so that *"which ServiceNow tickets
were created today"* outranks the logs tool, which also matches the word "today". No other tool
claims ticket wording.
