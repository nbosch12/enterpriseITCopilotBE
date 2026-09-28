package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.CopilotAskRequest;
import com.bosch.demo.docgrounding.model.CopilotAskResponse;
import com.bosch.demo.docgrounding.model.ToolRequest;
import com.bosch.demo.docgrounding.model.ToolResult;
import com.bosch.demo.docgrounding.service.tools.CopilotTool;
import com.bosch.demo.docgrounding.service.tools.CosmosMongoTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the grounding half of the flow: the orchestrator must send the retrieved records to the
 * model and return the model's answer, never the raw rows.
 */
class CopilotOrchestrationGroundingTest {

    private static final String RETRIEVED_CONTEXT =
            "Aggregated QR code totals computed over every record in the period.\n"
            + "Collection: qrcodeProcessingReport\n"
            + "Period: August 2026\n"
            + "  1. packit - qrCodesReceived: 30980\n"
            + "  2. plantA - qrCodesReceived: 25300\n";

    private RecordingLlmClient llm;
    private ConversationMemoryService memory;
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        llm = new RecordingLlmClient();
        memory = new ConversationMemoryService();
        properties = new AppProperties();
        properties.getDocupedia().setDefaultRepositoryId("repo-1");
        properties.getDocupedia().setDefaultS3Prefix("prefix-1");
    }

    private CopilotOrchestrationService orchestrator(CopilotTool... tools) {
        ToolRegistryService registry = new ToolRegistryService(List.of(tools));
        invokeInit(registry);
        return new CopilotOrchestrationService(registry, llm, memory, properties);
    }

    /** ToolRegistryService builds its name index in a private @PostConstruct method. */
    private void invokeInit(ToolRegistryService registry) {
        try {
            var init = ToolRegistryService.class.getDeclaredMethod("init");
            init.setAccessible(true);
            init.invoke(registry);
        } catch (Exception ex) {
            throw new IllegalStateException("could not initialise the tool registry", ex);
        }
    }

    private CopilotAskRequest ask(String question) {
        return new CopilotAskRequest(question, "session-1", false, 6, null, null, null, null, null);
    }

    // -------------------------------------------------------------------------
    // The model is called with the retrieved records
    // -------------------------------------------------------------------------

    @Test
    void retrievedRecordsAreSentToTheGroundingModel() {
        CopilotAskResponse response = orchestrator(new FakeCosmosTool(groundedResult()))
                .ask(ask("which plant received the most QR codes in August 2026")).block();

        assertNotNull(llm.lastMessages, "the grounding model must be called after retrieval");

        String everything = llm.lastMessages.stream()
                .map(m -> String.valueOf(m.get("content")))
                .reduce("", (a, b) -> a + "\n" + b);

        assertTrue(everything.contains("30980"), "the retrieved figures must reach the model");
        assertTrue(everything.contains("qrcodeProcessingReport"), "the source collection must reach the model");
        assertTrue(everything.contains("which plant received the most QR codes in August 2026"),
                "the original question must reach the model");
    }

    @Test
    void theModelIsToldToUseOnlyTheSuppliedRecords() {
        orchestrator(new FakeCosmosTool(groundedResult()))
                .ask(ask("which plant received the most QR codes in August 2026")).block();

        String system = String.valueOf(llm.lastMessages.get(0).get("content"));
        assertEquals("system", llm.lastMessages.get(0).get("role"));
        assertTrue(system.contains("Use only the supplied records"), "strict grounding rule missing");
        assertTrue(system.contains("Do not estimate"), "the model must be told not to extrapolate");
        assertTrue(system.contains("say so plainly"),
                "the model must be told to admit when the records fall short");
    }

    @Test
    void ordinaryToolsKeepTheOriginalSystemPrompt() {
        ToolResult plain = ToolResult.ok("AZURE_LOGS", "Three timeouts in the last hour.", "{}", Map.of());

        orchestrator(new FakeTool("AZURE_LOGS", new String[]{"logs"}, plain))
                .ask(ask("any errors in the logs")).block();

        String system = String.valueOf(llm.lastMessages.get(0).get("content"));
        assertTrue(system.contains("enterprise IT support assistant"), "base prompt should be intact");
        assertFalse(system.contains("Use only the supplied records"),
                "strict grounding should not be applied to tools that did not retrieve records");
    }

    // -------------------------------------------------------------------------
    // The answer comes from the model, not from the rows
    // -------------------------------------------------------------------------

    @Test
    void theAnswerIsTheModelsAnswerNotTheRawRecords() {
        llm.reply = "Packit received the most QR codes in August 2026, with 30,980.";

        CopilotAskResponse response = orchestrator(new FakeCosmosTool(groundedResult()))
                .ask(ask("which plant received the most QR codes in August 2026")).block();

        assertEquals("Packit received the most QR codes in August 2026, with 30,980.", response.answer());
        assertFalse(response.answer().contains("Collection: qrcodeProcessingReport"),
                "raw retrieved context must not be returned as the answer");
        assertTrue(response.toolsUsed().contains("COSMOS_QRCODE"));
    }

    @Test
    void structuredResultsStillTravelAlongsideTheAnswerForDrillDown() {
        CopilotAskResponse response = orchestrator(new FakeCosmosTool(groundedResult()))
                .ask(ask("which plant received the most QR codes in August 2026")).block();

        assertEquals(1, response.toolResults().size());
        assertEquals("COSMOS_QRCODE", response.toolResults().get(0).toolName());
        assertNotNull(response.toolResults().get(0).rawPayload());
    }

    // -------------------------------------------------------------------------
    // Failure handling
    // -------------------------------------------------------------------------

    @Test
    void whenTheModelFailsTheRawRecordsAreNotPastedIntoTheAnswer() {
        llm.failure = new IllegalStateException("SAP AI Core chat call failed with HTTP 503");

        CopilotAskResponse response = orchestrator(new FakeCosmosTool(groundedResult()))
                .ask(ask("which plant received the most QR codes in August 2026")).block();

        assertNotNull(response);
        assertFalse(response.answer().contains("30980"),
                "retrieved rows must not become the user-facing answer when the model fails");
        assertTrue(response.answer().contains("could not be reached"), "the failure should be stated");
        assertTrue(response.processingNotes().stream().anyMatch(n -> n.contains("withheld")),
                "the response should record that the records were withheld");
        assertEquals(1, response.toolResults().size(), "structured results remain available");
    }

    @Test
    void aDatabaseFailureStillProducesAModelWrittenAnswer() {
        ToolResult failed = ToolResult.error("COSMOS_QRCODE", "The QR code database could not be queried.");
        llm.reply = "I could not reach the QR code database, so I have no figures for August 2026.";

        CopilotAskResponse response = orchestrator(new FakeCosmosTool(failed))
                .ask(ask("which plant received the most QR codes in August 2026")).block();

        assertEquals("I could not reach the QR code database, so I have no figures for August 2026.",
                response.answer());
        assertFalse(response.toolResults().get(0).success());
    }

    @Test
    void anEmptyRetrievalStillGoesThroughTheModel() {
        ToolResult empty = ToolResult.ok("COSMOS_QRCODE",
                "The query ran successfully against the QR code collections and returned no rows.",
                "[]",
                Map.of(CosmosMongoTool.STRICT_GROUNDING_FLAG, true, "emptyResult", true, "recordCount", 0));
        llm.reply = "There are no QR code records for that period.";

        CopilotAskResponse response = orchestrator(new FakeCosmosTool(empty))
                .ask(ask("which plant received the most QR codes in August 2026")).block();

        assertNotNull(llm.lastMessages, "the model should still be asked to phrase the empty result");
        assertEquals("There are no QR code records for that period.", response.answer());
    }

    // -------------------------------------------------------------------------
    // Contract preservation
    // -------------------------------------------------------------------------

    @Test
    void theResponseContractIsUnchanged() {
        llm.reply = "An answer.";

        CopilotAskResponse response = orchestrator(new FakeCosmosTool(groundedResult()))
                .ask(ask("which plant received the most QR codes in August 2026")).block();

        // The frontend reads answer and sessionId; the rest is drill-down and debug.
        assertNotNull(response.answer());
        assertEquals("session-1", response.sessionId());
        assertNotNull(response.toolsUsed());
        assertNotNull(response.toolResults());
        assertNotNull(response.processingNotes());
    }

    @Test
    void conversationHistoryIsRecordedForTheSession() {
        llm.reply = "Packit, with 30,980.";

        orchestrator(new FakeCosmosTool(groundedResult()))
                .ask(ask("which plant received the most QR codes in August 2026")).block();

        assertEquals(2, memory.getSessionSize("session-1"),
                "both the question and the grounded answer should be remembered");
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private ToolResult groundedResult() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(CosmosMongoTool.STRICT_GROUNDING_FLAG, true);
        metadata.put("retrievalMode", "AGGREGATE");
        metadata.put("collection", "qrcodeProcessingReport");
        metadata.put("rowCount", 2);
        return ToolResult.ok("COSMOS_QRCODE", RETRIEVED_CONTEXT, "{\"ranked\":[]}", metadata);
    }

    /** Captures the exact message list handed to the model. */
    private static final class RecordingLlmClient extends SapAiCoreIncidentLlmClient {
        List<Map<String, Object>> lastMessages;
        String reply = "grounded answer";
        RuntimeException failure;

        RecordingLlmClient() {
            super(org.springframework.web.reactive.function.client.WebClient.builder(),
                    propertiesWithApiUrl(),
                    null,
                    new com.fasterxml.jackson.databind.ObjectMapper());
        }

        private static AppProperties propertiesWithApiUrl() {
            AppProperties p = new AppProperties();
            p.getSapAiCore().setApiUrl("https://ai-core.invalid");
            return p;
        }

        @Override
        public Mono<String> chat(List<Map<String, Object>> messages) {
            this.lastMessages = new ArrayList<>(messages);
            if (failure != null) {
                return Mono.error(failure);
            }
            return Mono.just(reply);
        }
    }

    /** A tool that returns a fixed result, standing in for the real Cosmos retrieval. */
    private static class FakeTool implements CopilotTool {
        private final String name;
        private final String[] keywords;
        private final ToolResult result;

        FakeTool(String name, String[] keywords, ToolResult result) {
            this.name = name;
            this.keywords = keywords;
            this.result = result;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "test tool"; }
        @Override public String[] keywords() { return keywords; }
        @Override public Mono<ToolResult> execute(ToolRequest request) { return Mono.just(result); }
    }

    private static final class FakeCosmosTool extends FakeTool {
        FakeCosmosTool(ToolResult result) {
            super("COSMOS_QRCODE", new String[]{"qr", "plant", "database"}, result);
        }
    }
}
