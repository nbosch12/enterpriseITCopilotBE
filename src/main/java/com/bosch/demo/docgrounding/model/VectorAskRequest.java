package com.bosch.demo.docgrounding.model;

import jakarta.validation.constraints.NotBlank;

/**
 * @param repositoryId     optional; ignored when app.grounding.enforce-single-repository is true,
 *                         because every query runs against the one configured repository
 * @param forceVectorSearch set true to skip MongoDB analytics routing and always use retrieval
 */
public record VectorAskRequest(
        @NotBlank String question,
        String repositoryId,
        String s3Prefix,
        Integer maxChunks,
        Integer topK,
        String sessionId,
        Boolean useHistory,
        Integer historyTurns,
        Boolean forceVectorSearch
) {
    /** Kept so existing callers and tests that omit forceVectorSearch still compile. */
    public VectorAskRequest(
            String question,
            String repositoryId,
            String s3Prefix,
            Integer maxChunks,
            Integer topK,
            String sessionId,
            Boolean useHistory,
            Integer historyTurns) {
        this(question, repositoryId, s3Prefix, maxChunks, topK, sessionId, useHistory, historyTurns, null);
    }
}
