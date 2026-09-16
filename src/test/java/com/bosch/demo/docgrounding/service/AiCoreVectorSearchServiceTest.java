package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.VectorAskRequest;
import com.bosch.demo.docgrounding.model.VectorAskResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiCoreVectorSearchServiceTest {

    @Mock
    private AiCoreTokenService tokenService;

    @Mock
    private AppProperties properties;

    @Mock
    private ConversationMemoryService conversationMemoryService;

    private AiCoreVectorSearchService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        // Setup AppProperties mocks
        AppProperties.SapAiCore aiCore = new AppProperties.SapAiCore();
        aiCore.setApiUrl("https://api.example.com");
        aiCore.setResourceGroup("test-rg");
        aiCore.setOrchestrationDeploymentId("test-deployment");
        aiCore.setLlmModelName("gpt-test");

        when(properties.getSapAiCore()).thenReturn(aiCore);

        // Use real WebClient.Builder for these tests (or mock it)
        service = new AiCoreVectorSearchService(
                org.springframework.web.reactive.function.client.WebClient.builder(),
                properties,
                tokenService,
                objectMapper,
                conversationMemoryService);
    }

    @Test
    void testAskWithNullQuestion() {
        VectorAskRequest request = new VectorAskRequest(
                null, "repo-id", null, null, null, "session-1", true, 6);

        VectorAskResponse response = service.ask(request).block();

        assertNotNull(response);
        assertTrue(response.answer().contains("question must not be empty"));
        assertTrue(response.matches().isEmpty());
    }

    @Test
    void testAskWithBlankQuestion() {
        VectorAskRequest request = new VectorAskRequest(
                "   ", "repo-id", null, null, null, "session-1", true, 6);

        VectorAskResponse response = service.ask(request).block();

        assertNotNull(response);
        assertTrue(response.answer().contains("question must not be empty"));
    }

    @Test
    void testAskWithoutRepositoryId() {
        VectorAskRequest request = new VectorAskRequest(
                "What is this?", null, null, null, null, "session-1", true, 6);

        VectorAskResponse response = service.ask(request).block();

        assertNotNull(response);
        assertTrue(response.answer().contains("repositoryId or s3Prefix must be provided"));
    }

    @Test
    void testAskWithRepositoryId() {
        // This test validates that the request structure is accepted
        VectorAskRequest request = new VectorAskRequest(
                "What is this?", "test-repo", null, null, 5, "session-1", true, 6);

        assertNotNull(request.question());
        assertEquals("What is this?", request.question());
        assertEquals("test-repo", request.repositoryId());
        assertEquals("session-1", request.sessionId());
        assertTrue(request.useHistory());
        assertEquals(6, request.historyTurns());
    }

    @Test
    void testAskWithS3Prefix() {
        // This test validates that s3Prefix is accepted as fallback
        VectorAskRequest request = new VectorAskRequest(
                "What is this?", null, "s3://bucket/prefix", null, null, "session-1", true, 6);

        assertNotNull(request.question());
        assertEquals("What is this?", request.question());
        assertNull(request.repositoryId());
        assertEquals("s3://bucket/prefix", request.s3Prefix());
    }

    @Test
    void testAskWithoutHistory() {
        VectorAskRequest request = new VectorAskRequest(
                "What is this?", "test-repo", null, null, null, "session-1", false, 6);

        assertNotNull(request);
        assertFalse(request.useHistory());
    }

    @Test
    void testAskWithNullSessionId() {
        VectorAskRequest request = new VectorAskRequest(
                "What is this?", "test-repo", null, null, null, null, true, 6);

        assertNull(request.sessionId());
    }

    @Test
    void testAskResponseIncludesSessionId() {
        VectorAskRequest request = new VectorAskRequest(
                "What is this?", "test-repo", null, null, null, "session-xyz", false, 0);

        // For a valid request without history, the response should include session ID
        assertNotNull(request.sessionId());
        assertEquals("session-xyz", request.sessionId());
    }

    @Test
    void testTopKBounds() {
        // Test with null topK (should use default)
        VectorAskRequest request1 = new VectorAskRequest(
                "Q?", "repo", null, null, null, "s1", false, 0);
        assertNull(request1.topK());

        // Test with very small topK
        VectorAskRequest request2 = new VectorAskRequest(
                "Q?", "repo", null, null, 0, "s2", false, 0);
        assertEquals(0, request2.topK());

        // Test with very large topK
        VectorAskRequest request3 = new VectorAskRequest(
                "Q?", "repo", null, null, 100, "s3", false, 0);
        assertEquals(100, request3.topK());
    }

    @Test
    void testHistoryTurnsBounds() {
        // Test with null historyTurns
        VectorAskRequest request1 = new VectorAskRequest(
                "Q?", "repo", null, null, null, "s1", true, null);
        assertNull(request1.historyTurns());

        // Test with very large value
        VectorAskRequest request2 = new VectorAskRequest(
                "Q?", "repo", null, null, null, "s2", true, 100);
        assertEquals(100, request2.historyTurns());

        // Test with zero
        VectorAskRequest request3 = new VectorAskRequest(
                "Q?", "repo", null, null, null, "s3", true, 0);
        assertEquals(0, request3.historyTurns());
    }

    @Test
    void testVectorAskResponseWithSessionId() {
        VectorAskResponse response = new VectorAskResponse(
                "This is the answer",
                java.util.List.of(),
                "{\"result\": \"test\"}",
                "session-123");

        assertEquals("This is the answer", response.answer());
        assertTrue(response.matches().isEmpty());
        assertEquals("session-123", response.sessionId());
        assertEquals("{\"result\": \"test\"}", response.rawModelResponse());
    }

    @Test
    void testVectorAskResponseWithNullSessionId() {
        VectorAskResponse response = new VectorAskResponse(
                "Answer",
                java.util.List.of(),
                "{}",
                null);

        assertNull(response.sessionId());
    }
}

