package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.model.ConversationTurn;
import com.bosch.demo.docgrounding.model.VectorAskRequest;
import com.bosch.demo.docgrounding.model.VectorAskResponse;
import com.bosch.demo.docgrounding.model.VectorMatch;
import com.bosch.demo.docgrounding.service.AiCoreVectorSearchService;
import com.bosch.demo.docgrounding.service.ConversationMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorSearchControllerTest {

    @Mock
    private AiCoreVectorSearchService vectorSearchService;

    @Mock
    private ConversationMemoryService conversationMemoryService;

    private VectorSearchController controller;

    @BeforeEach
    void setUp() {
        controller = new VectorSearchController(vectorSearchService, conversationMemoryService);
    }

    @Test
    void testAskCallsVectorSearchService() {
        VectorAskRequest request = new VectorAskRequest(
                "What is BTMEA?", "BTMEA", null, null, 5,
                "session-123", true, 6);

        VectorAskResponse mockResponse = new VectorAskResponse(
                "BTMEA is...",
                List.of(),
                "{}",
                "session-123");

        when(vectorSearchService.ask(request)).thenReturn(Mono.just(mockResponse));

        Mono<VectorAskResponse> result = controller.ask(request);

        assertNotNull(result);
        VectorAskResponse response = result.block();
        assertNotNull(response);
        assertEquals("BTMEA is...", response.answer());
        assertEquals("session-123", response.sessionId());

        verify(vectorSearchService, times(1)).ask(request);
    }

    @Test
    void testGetSessionHistory() {
        String sessionId = "session-456";
        List<ConversationTurn> mockHistory = List.of(
                new ConversationTurn("user", "What is this?", Instant.now()),
                new ConversationTurn("assistant", "This is that.", Instant.now()));

        when(conversationMemoryService.getRecentTurns(sessionId, 20))
                .thenReturn(mockHistory);

        Mono<List<ConversationTurn>> result = controller.getSessionHistory(sessionId);

        assertNotNull(result);
        List<ConversationTurn> history = result.block();
        assertNotNull(history);
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("What is this?", history.get(0).content());

        verify(conversationMemoryService, times(1)).getRecentTurns(sessionId, 20);
    }

    @Test
    void testGetSessionHistoryEmpty() {
        String sessionId = "nonexistent-session";

        when(conversationMemoryService.getRecentTurns(sessionId, 20))
                .thenReturn(List.of());

        Mono<List<ConversationTurn>> result = controller.getSessionHistory(sessionId);

        assertNotNull(result);
        List<ConversationTurn> history = result.block();
        assertNotNull(history);
        assertTrue(history.isEmpty());

        verify(conversationMemoryService, times(1)).getRecentTurns(sessionId, 20);
    }

    @Test
    void testClearSession() {
        String sessionId = "session-to-clear";

        Mono<Void> result = controller.clearSession(sessionId);

        assertNotNull(result);
        result.block();

        verify(conversationMemoryService, times(1)).clearSession(sessionId);
    }

    @Test
    void testGetActiveSessions() {
        when(conversationMemoryService.getActiveSessions()).thenReturn(3);

        Mono<Integer> result = controller.getActiveSessions();

        assertNotNull(result);
        Integer count = result.block();
        assertNotNull(count);
        assertEquals(3, count);

        verify(conversationMemoryService, times(1)).getActiveSessions();
    }

    @Test
    void testGetActiveSessionsZero() {
        when(conversationMemoryService.getActiveSessions()).thenReturn(0);

        Mono<Integer> result = controller.getActiveSessions();

        Integer count = result.block();
        assertEquals(0, count);
    }

    @Test
    void testAskWithNullSessionId() {
        VectorAskRequest request = new VectorAskRequest(
                "What is this?", "repo", null, null, null,
                null, false, 0);

        VectorAskResponse mockResponse = new VectorAskResponse(
                "Answer",
                List.of(),
                "{}",
                null);

        when(vectorSearchService.ask(request)).thenReturn(Mono.just(mockResponse));

        Mono<VectorAskResponse> result = controller.ask(request);
        VectorAskResponse response = result.block();

        assertNull(response.sessionId());
        verify(vectorSearchService, times(1)).ask(request);
    }

    @Test
    void testAskWithHistoryEnabled() {
        VectorAskRequest request = new VectorAskRequest(
                "Follow-up question?", "repo", null, null, 5,
                "session-789", true, 4);

        VectorAskResponse mockResponse = new VectorAskResponse(
                "Follow-up answer",
                List.of(),
                "{}",
                "session-789");

        when(vectorSearchService.ask(request)).thenReturn(Mono.just(mockResponse));

        Mono<VectorAskResponse> result = controller.ask(request);
        VectorAskResponse response = result.block();

        assertTrue(request.useHistory());
        assertEquals(4, request.historyTurns());
        assertEquals("Follow-up answer", response.answer());

        verify(vectorSearchService, times(1)).ask(request);
    }

    @Test
    void testAskWithHistoryDisabled() {
        VectorAskRequest request = new VectorAskRequest(
                "New question", "repo", null, null, null,
                "session-999", false, 0);

        VectorAskResponse mockResponse = new VectorAskResponse(
                "Answer without history",
                List.of(),
                "{}",
                "session-999");

        when(vectorSearchService.ask(request)).thenReturn(Mono.just(mockResponse));

        Mono<VectorAskResponse> result = controller.ask(request);
        VectorAskResponse response = result.block();

        assertFalse(request.useHistory());
        assertEquals("Answer without history", response.answer());

        verify(vectorSearchService, times(1)).ask(request);
    }

    @Test
    void testControllerEndpointAcceptanceFlow() {
        // Simulate a conversation flow
        String sessionId = "flow-test-session";

        // Step 1: First question
        VectorAskRequest req1 = new VectorAskRequest(
                "What is BTMEA?", "BTMEA", null, null, 5,
                sessionId, true, 6);

        VectorAskResponse resp1 = new VectorAskResponse(
                "BTMEA is an enterprise system.",
                List.of(),
                "{}",
                sessionId);

        when(vectorSearchService.ask(req1)).thenReturn(Mono.just(resp1));

        Mono<VectorAskResponse> result1 = controller.ask(req1);
        VectorAskResponse response1 = result1.block();

        assertNotNull(response1);
        assertEquals("BTMEA is an enterprise system.", response1.answer());

        // Step 2: Get history after first question
        List<ConversationTurn> mockHistory = List.of(
                new ConversationTurn("user", "What is BTMEA?", Instant.now()),
                new ConversationTurn("assistant", "BTMEA is an enterprise system.", Instant.now()));

        when(conversationMemoryService.getRecentTurns(sessionId, 20))
                .thenReturn(mockHistory);

        Mono<List<ConversationTurn>> historyResult = controller.getSessionHistory(sessionId);
        List<ConversationTurn> history = historyResult.block();

        assertNotNull(history);
        assertEquals(2, history.size());

        verify(vectorSearchService, times(1)).ask(req1);
        verify(conversationMemoryService, times(1)).getRecentTurns(sessionId, 20);
    }

    @Test
    void testControllerConstructorInjects() {
        assertNotNull(controller);
        // Verify the controller is properly constructed
    }

    @Test
    void testAskResponseHandling() {
        VectorAskRequest request = new VectorAskRequest(
                "Test?", "repo", null, null, 5,
                "session-resp", true, 3);

        VectorMatch match1 = new VectorMatch(
                "doc1", "page1", "Title 1", "http://url1",
                0, 0.95, "Excerpt 1");
        VectorMatch match2 = new VectorMatch(
                "doc2", "page2", "Title 2", "http://url2",
                1, 0.87, "Excerpt 2");

        VectorAskResponse mockResponse = new VectorAskResponse(
                "Test answer",
                List.of(match1, match2),
                "{\"result\": \"test\"}",
                "session-resp");

        when(vectorSearchService.ask(request)).thenReturn(Mono.just(mockResponse));

        Mono<VectorAskResponse> result = controller.ask(request);
        VectorAskResponse response = result.block();

        assertEquals("Test answer", response.answer());
        assertEquals(2, response.matches().size());
        assertEquals("Title 1", response.matches().get(0).pageTitle());
        assertEquals(0.95, response.matches().get(0).score());
    }
}

