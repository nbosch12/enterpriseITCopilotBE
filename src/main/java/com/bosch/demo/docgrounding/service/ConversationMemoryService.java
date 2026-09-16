package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.model.ConversationTurn;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory conversation memory service for storing and retrieving session-based conversation history.
 * Note: This is suitable for single-instance deployments. For cloud deployments with multiple instances,
 * consider migrating to Redis or a persistent database like HANA/PostgreSQL.
 */
@Service
@Slf4j
public class ConversationMemoryService {

    private static final int MAX_TURNS_PER_SESSION = 20;

    private final ConcurrentHashMap<String, Deque<ConversationTurn>> sessions = new ConcurrentHashMap<>();

    /**
     * Retrieve recent conversation turns for a session.
     *
     * @param sessionId the session ID
     * @param limit maximum number of recent turns to return
     * @return list of recent conversation turns (most recent last)
     */
    public List<ConversationTurn> getRecentTurns(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }

        Deque<ConversationTurn> turns = sessions.get(sessionId);
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }

        List<ConversationTurn> all = new ArrayList<>(turns);
        int fromIndex = Math.max(0, all.size() - limit);
        return all.subList(fromIndex, all.size());
    }

    /**
     * Add a user message to the session history.
     *
     * @param sessionId the session ID
     * @param content the user's message content
     */
    public void addUserTurn(String sessionId, String content) {
        addTurn(sessionId, new ConversationTurn("user", content, Instant.now()));
    }

    /**
     * Add an assistant message to the session history.
     *
     * @param sessionId the session ID
     * @param content the assistant's message content
     */
    public void addAssistantTurn(String sessionId, String content) {
        addTurn(sessionId, new ConversationTurn("assistant", content, Instant.now()));
    }

    /**
     * Internal method to add a turn and enforce max turns per session.
     */
    private void addTurn(String sessionId, ConversationTurn turn) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        Deque<ConversationTurn> deque = sessions.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(turn);
            while (deque.size() > MAX_TURNS_PER_SESSION) {
                deque.removeFirst();
            }
        }
        log.debug("Added turn to session {}. Total turns: {}", sessionId, deque.size());
    }

    /**
     * Clear all conversation history for a session.
     *
     * @param sessionId the session ID
     */
    public void clearSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessions.remove(sessionId);
            log.info("Cleared session {}", sessionId);
        }
    }

    /**
     * Get the number of turns in a session.
     *
     * @param sessionId the session ID
     * @return number of turns
     */
    public int getSessionSize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        Deque<ConversationTurn> turns = sessions.get(sessionId);
        return turns == null ? 0 : turns.size();
    }

    /**
     * Check if a session exists and has history.
     *
     * @param sessionId the session ID
     * @return true if session has at least one turn
     */
    public boolean hasSession(String sessionId) {
        return getSessionSize(sessionId) > 0;
    }

    /**
     * Get all sessions (useful for debugging/monitoring).
     *
     * @return count of active sessions
     */
    public int getActiveSessions() {
        return sessions.size();
    }
}

