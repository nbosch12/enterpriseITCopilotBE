package com.bosch.demo.docgrounding.controller;

import com.bosch.demo.docgrounding.model.ConversationTurn;
import com.bosch.demo.docgrounding.model.VectorAskRequest;
import com.bosch.demo.docgrounding.model.VectorAskResponse;
import com.bosch.demo.docgrounding.service.AiCoreVectorSearchService;
import com.bosch.demo.docgrounding.service.ConversationMemoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/vector")
public class VectorSearchController {

    private final AiCoreVectorSearchService vectorSearchService;
    private final ConversationMemoryService conversationMemoryService;

    public VectorSearchController(
            AiCoreVectorSearchService vectorSearchService,
            ConversationMemoryService conversationMemoryService) {
        this.vectorSearchService = vectorSearchService;
        this.conversationMemoryService = conversationMemoryService;
    }

    /**
     * Ask a question with optional session-aware conversation history.
     *
     * @param request the vector ask request containing question, repository, and session info
     * @return the response with answer, matches, and session ID
     */
    @PostMapping("/ask")
    public Mono<VectorAskResponse> ask(@Valid @RequestBody VectorAskRequest request) {
        return vectorSearchService.ask(request);
    }

    /**
     * Retrieve conversation history for a session.
     *
     * @param sessionId the session ID
     * @return list of conversation turns
     */
    @GetMapping("/session/{sessionId}")
    public Mono<List<ConversationTurn>> getSessionHistory(@PathVariable String sessionId) {
        return Mono.just(conversationMemoryService.getRecentTurns(sessionId, 20));
    }

    /**
     * Clear all conversation history for a session.
     *
     * @param sessionId the session ID
     * @return empty Mono on completion
     */
    @DeleteMapping("/session/{sessionId}")
    public Mono<Void> clearSession(@PathVariable String sessionId) {
        conversationMemoryService.clearSession(sessionId);
        return Mono.empty();
    }

    /**
     * Get the number of active sessions (debug/monitoring endpoint).
     *
     * @return count of sessions with history
     */
    @GetMapping("/sessions/count")
    public Mono<Integer> getActiveSessions() {
        return Mono.just(conversationMemoryService.getActiveSessions());
    }
}

