package com.bosch.demo.docgrounding.model;

import com.bosch.demo.docgrounding.model.analytics.AnalyticsResult;

import java.util.List;

/**
 * @param answerSource "mongo-analytics" when the answer was computed from MongoDB, "vector-search"
 *                     when it came from grounded retrieval. Aggregate questions must never be
 *                     answered by retrieval alone, so this makes the path explicit to the caller.
 * @param analytics    the full ranked breakdown when answerSource is "mongo-analytics"
 * @param repositoryId the repository actually queried
 */
public record VectorAskResponse(
        String answer,
        List<VectorMatch> matches,
        String rawModelResponse,
        String sessionId,
        String answerSource,
        AnalyticsResult analytics,
        String repositoryId
) {
    /** Kept so existing callers and tests using the original four-field shape still compile. */
    public VectorAskResponse(String answer, List<VectorMatch> matches, String rawModelResponse, String sessionId) {
        this(answer, matches, rawModelResponse, sessionId, "vector-search", null, null);
    }
}
