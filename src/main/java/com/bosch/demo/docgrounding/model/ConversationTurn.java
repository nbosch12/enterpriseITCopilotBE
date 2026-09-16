package com.bosch.demo.docgrounding.model;

import java.time.Instant;

/**
 * Represents a single turn in a conversation (either user or assistant message).
 */
public record ConversationTurn(
        String role,      // "user" or "assistant"
        String content,
        Instant timestamp
) { }

