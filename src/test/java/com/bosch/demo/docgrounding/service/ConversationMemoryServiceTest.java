package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.model.ConversationTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryServiceTest {

    private ConversationMemoryService service;

    @BeforeEach
    void setUp() {
        service = new ConversationMemoryService();
    }

    @Test
    void testAddUserTurn() {
        String sessionId = "session-123";
        String content = "What is this?";

        service.addUserTurn(sessionId, content);

        List<ConversationTurn> turns = service.getRecentTurns(sessionId, 10);
        assertEquals(1, turns.size());
        assertEquals("user", turns.get(0).role());
        assertEquals(content, turns.get(0).content());
    }

    @Test
    void testAddAssistantTurn() {
        String sessionId = "session-456";
        String content = "This is an answer.";

        service.addAssistantTurn(sessionId, content);

        List<ConversationTurn> turns = service.getRecentTurns(sessionId, 10);
        assertEquals(1, turns.size());
        assertEquals("assistant", turns.get(0).role());
        assertEquals(content, turns.get(0).content());
    }

    @Test
    void testMultipleTurnsInOrder() {
        String sessionId = "session-789";

        service.addUserTurn(sessionId, "Question 1");
        service.addAssistantTurn(sessionId, "Answer 1");
        service.addUserTurn(sessionId, "Question 2");
        service.addAssistantTurn(sessionId, "Answer 2");

        List<ConversationTurn> turns = service.getRecentTurns(sessionId, 10);
        assertEquals(4, turns.size());
        assertEquals("user", turns.get(0).role());
        assertEquals("Question 1", turns.get(0).content());
        assertEquals("assistant", turns.get(1).role());
        assertEquals("Answer 1", turns.get(1).content());
        assertEquals("user", turns.get(2).role());
        assertEquals("Question 2", turns.get(2).content());
        assertEquals("assistant", turns.get(3).role());
        assertEquals("Answer 2", turns.get(3).content());
    }

    @Test
    void testGetRecentTurnsWithLimit() {
        String sessionId = "session-limit";

        // Add 10 turns
        for (int i = 0; i < 10; i++) {
            service.addUserTurn(sessionId, "Q" + i);
        }

        // Request only last 3
        List<ConversationTurn> turns = service.getRecentTurns(sessionId, 3);
        assertEquals(3, turns.size());
        assertEquals("Q7", turns.get(0).content());
        assertEquals("Q8", turns.get(1).content());
        assertEquals("Q9", turns.get(2).content());
    }

    @Test
    void testMaxTurnsPerSession() {
        String sessionId = "session-max";

        // Add more than MAX_TURNS_PER_SESSION (20)
        for (int i = 0; i < 30; i++) {
            service.addUserTurn(sessionId, "Q" + i);
        }

        List<ConversationTurn> turns = service.getRecentTurns(sessionId, 100);
        assertEquals(20, turns.size()); // Should only keep last 20
        assertEquals("Q10", turns.get(0).content()); // First should be Q10
        assertEquals("Q29", turns.get(19).content()); // Last should be Q29
    }

    @Test
    void testGetRecentTurnsNullSessionId() {
        List<ConversationTurn> turns = service.getRecentTurns(null, 10);
        assertTrue(turns.isEmpty());
    }

    @Test
    void testGetRecentTurnsBlankSessionId() {
        List<ConversationTurn> turns = service.getRecentTurns("   ", 10);
        assertTrue(turns.isEmpty());
    }

    @Test
    void testGetRecentTurnsNonexistentSession() {
        List<ConversationTurn> turns = service.getRecentTurns("nonexistent", 10);
        assertTrue(turns.isEmpty());
    }

    @Test
    void testClearSession() {
        String sessionId = "session-clear";
        service.addUserTurn(sessionId, "Q1");
        service.addAssistantTurn(sessionId, "A1");

        assertEquals(2, service.getSessionSize(sessionId));

        service.clearSession(sessionId);

        assertEquals(0, service.getSessionSize(sessionId));
        assertTrue(service.getRecentTurns(sessionId, 10).isEmpty());
    }

    @Test
    void testClearSessionNullId() {
        // Should not throw
        assertDoesNotThrow(() -> service.clearSession(null));
    }

    @Test
    void testGetSessionSize() {
        String sessionId = "session-size";

        assertEquals(0, service.getSessionSize(sessionId));

        service.addUserTurn(sessionId, "Q1");
        assertEquals(1, service.getSessionSize(sessionId));

        service.addAssistantTurn(sessionId, "A1");
        assertEquals(2, service.getSessionSize(sessionId));
    }

    @Test
    void testHasSession() {
        String sessionId = "session-has";

        assertFalse(service.hasSession(sessionId));

        service.addUserTurn(sessionId, "Q1");
        assertTrue(service.hasSession(sessionId));

        service.clearSession(sessionId);
        assertFalse(service.hasSession(sessionId));
    }

    @Test
    void testGetActiveSessions() {
        assertEquals(0, service.getActiveSessions());

        service.addUserTurn("session-1", "Q1");
        assertEquals(1, service.getActiveSessions());

        service.addUserTurn("session-2", "Q2");
        assertEquals(2, service.getActiveSessions());

        service.clearSession("session-1");
        assertEquals(1, service.getActiveSessions());
    }

    @Test
    void testMultipleSessions() {
        service.addUserTurn("session-a", "Q-A");
        service.addUserTurn("session-b", "Q-B");
        service.addUserTurn("session-c", "Q-C");

        List<ConversationTurn> turnsA = service.getRecentTurns("session-a", 10);
        List<ConversationTurn> turnsB = service.getRecentTurns("session-b", 10);
        List<ConversationTurn> turnsC = service.getRecentTurns("session-c", 10);

        assertEquals(1, turnsA.size());
        assertEquals("Q-A", turnsA.get(0).content());

        assertEquals(1, turnsB.size());
        assertEquals("Q-B", turnsB.get(0).content());

        assertEquals(1, turnsC.size());
        assertEquals("Q-C", turnsC.get(0).content());

        assertEquals(3, service.getActiveSessions());
    }

    @Test
    void testAddTurnNullSessionId() {
        // Should not throw
        assertDoesNotThrow(() -> service.addUserTurn(null, "content"));
    }

    @Test
    void testAddTurnBlankSessionId() {
        // Should not throw
        assertDoesNotThrow(() -> service.addUserTurn("   ", "content"));
    }

    @Test
    void testTimestampIsRecorded() {
        String sessionId = "session-time";
        Instant before = Instant.now();

        service.addUserTurn(sessionId, "Q1");

        Instant after = Instant.now();

        List<ConversationTurn> turns = service.getRecentTurns(sessionId, 10);
        assertEquals(1, turns.size());

        Instant timestamp = turns.get(0).timestamp();
        assertTrue(timestamp.isAfter(before) || timestamp.equals(before));
        assertTrue(timestamp.isBefore(after) || timestamp.equals(after));
    }
}

