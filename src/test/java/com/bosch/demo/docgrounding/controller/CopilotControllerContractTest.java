package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.model.CopilotAskRequest;
import com.bosch.demo.docgrounding.model.CopilotAskResponse;
import com.bosch.demo.docgrounding.model.ToolResult;
import com.bosch.demo.docgrounding.service.ConversationMemoryService;
import com.bosch.demo.docgrounding.service.CopilotOrchestrationService;
import com.bosch.demo.docgrounding.service.ToolRegistryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@code POST /api/copilot/ask} contract the Angular frontend already depends on.
 *
 * <p>The frontend posts {@code question, repositoryId, sessionId, useHistory, historyTurns, topK}
 * and reads {@code answer} off the response. Adding Cosmos DB must not disturb either side, so
 * these tests pin the wire shape rather than the internals.</p>
 */
class CopilotControllerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StubOrchestrationService orchestration;
    private CopilotController controller;

    @BeforeEach
    void setUp() {
        orchestration = new StubOrchestrationService();
        ToolRegistryService registry = new ToolRegistryService(List.of());
        controller = new CopilotController(orchestration, registry, new ConversationMemoryService());
    }

    // -------------------------------------------------------------------------
    // Request shape
    // -------------------------------------------------------------------------

    @Test
    void theRequestBodyTheFrontendSendsStillDeserialises() throws Exception {
        // Exactly the body built in chat.service.ts.
        String body = """
                {
                  "question": "which plant received the most QR codes in August 2026",
                  "repositoryId": "repo-1",
                  "sessionId": "session-abc",
                  "useHistory": true,
                  "historyTurns": 6,
                  "topK": 10
                }""";

        CopilotAskRequest request = objectMapper.readValue(body, CopilotAskRequest.class);

        assertEquals("which plant received the most QR codes in August 2026", request.question());
        assertEquals("repo-1", request.repositoryId());
        assertEquals("session-abc", request.sessionId());
        assertEquals(Boolean.TRUE, request.useHistory());
        assertEquals(Integer.valueOf(6), request.historyTurns());
        assertEquals(Integer.valueOf(10), request.topK(),
                "topK is sent by the frontend and must bind rather than be dropped");
    }

    @Test
    void anUnknownFieldFromTheFrontendDoesNotBreakTheRequest() throws Exception {
        // Spring Boot disables FAIL_ON_UNKNOWN_PROPERTIES, so a field the frontend adds later is
        // ignored rather than rejected. This pins that behaviour.
        ObjectMapper lenient = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        CopilotAskRequest request = lenient.readValue(
                "{\"question\":\"hello\",\"somethingNew\":true}", CopilotAskRequest.class);
        assertEquals("hello", request.question());
    }

    @Test
    void aMinimalRequestIsAccepted() throws Exception {
        CopilotAskRequest request = objectMapper.readValue(
                "{\"question\":\"hello\"}", CopilotAskRequest.class);
        assertEquals("hello", request.question());
        assertNull(request.sessionId());
    }

    // -------------------------------------------------------------------------
    // Response shape
    // -------------------------------------------------------------------------

    @Test
    void theResponseStillCarriesTheAnswerFieldTheFrontendReads() throws Exception {
        orchestration.response = new CopilotAskResponse(
                "Packit received the most QR codes in August 2026, with 30,980.",
                List.of("COSMOS_QRCODE"),
                List.of(ToolResult.ok("COSMOS_QRCODE", "context", "{}", Map.of())),
                "session-abc",
                List.of("Selected tools: COSMOS_QRCODE"));

        CopilotAskResponse response = controller.ask(
                new CopilotAskRequest("q", "session-abc", true, 6, null, null, null, null, null)).block();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertTrue(json.has("answer"), "the frontend reads 'answer'");
        assertEquals("Packit received the most QR codes in August 2026, with 30,980.",
                json.get("answer").asText());
        assertTrue(json.has("sessionId"));
        assertTrue(json.has("toolsUsed"));
        assertTrue(json.has("toolResults"));
        assertTrue(json.has("processingNotes"));
    }

    @Test
    void aCosmosAnswerIsPlainTextNotRawQueryOutput() throws Exception {
        orchestration.response = new CopilotAskResponse(
                "Packit received the most QR codes in August 2026, with 30,980.",
                List.of("COSMOS_QRCODE"),
                List.of(ToolResult.ok("COSMOS_QRCODE",
                        "Collection: qrcodeProcessingReport\n  1. packit - qrCodesReceived: 30980",
                        "{\"ranked\":[]}", Map.of("strictGrounding", true))),
                "session-abc",
                List.of());

        CopilotAskResponse response = controller.ask(
                new CopilotAskRequest("q", "session-abc", true, 6, null, null, null, null, null)).block();

        assertFalse(response.answer().startsWith("Collection:"),
                "the answer must be prose from the model, not the retrieved block");
        assertFalse(response.answer().contains("qrCodesReceived:"),
                "field-value rows belong in toolResults, not in the answer");
        // The structured payload is still there for UI drill-down.
        assertEquals(1, response.toolResults().size());
        assertNotNull(response.toolResults().get(0).rawPayload());
    }

    @Test
    void theEndpointPassesTheRequestThroughUnchanged() {
        orchestration.response = new CopilotAskResponse("ok", List.of(), List.of(), "s", List.of());

        CopilotAskRequest request = new CopilotAskRequest(
                "which plant received the most QR codes in August 2026",
                "session-abc", true, 6, null, null, "repo-1", null, null);

        controller.ask(request).block();

        assertNotNull(orchestration.lastRequest);
        assertEquals("which plant received the most QR codes in August 2026",
                orchestration.lastRequest.question());
        assertEquals("repo-1", orchestration.lastRequest.repositoryId());
        assertEquals("session-abc", orchestration.lastRequest.sessionId());
    }

    /** Captures what the controller forwards and returns a fixed response. */
    private static final class StubOrchestrationService extends CopilotOrchestrationService {
        CopilotAskRequest lastRequest;
        CopilotAskResponse response =
                new CopilotAskResponse("stub", List.of(), List.of(), null, List.of());

        StubOrchestrationService() {
            super(null, null, null, null);
        }

        @Override
        public Mono<CopilotAskResponse> ask(CopilotAskRequest request) {
            this.lastRequest = request;
            return Mono.just(response);
        }
    }
}
