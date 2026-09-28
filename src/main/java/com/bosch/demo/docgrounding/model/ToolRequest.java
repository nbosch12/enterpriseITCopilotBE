package com.bosch.demo.docgrounding.model;

import java.util.Map;

/**
 * Normalised input passed from the orchestrator to any {@code CopilotTool}.
 *
 * @param question      The user's original question (always present).
 * @param sessionId     Session ID for memory-aware tools.
 * @param parameters    Arbitrary extra parameters the orchestrator extracted from the
 *                      {@link CopilotAskRequest} and forwarded to the tool
 *                      (e.g. {@code appName}, {@code timeDuration}, {@code repositoryId}).
 */
public record ToolRequest(
        String question,
        String sessionId,
        Map<String, Object> parameters
) {
    /** Convenience getter – returns null if the key is absent. */
    public String param(String key) {
        if (parameters == null) return null;
        Object v = parameters.get(key);
        return v == null ? null : v.toString();
    }

    /** Convenience getter with a default fallback. */
    public String param(String key, String defaultValue) {
        String v = param(key);
        return v == null ? defaultValue : v;
    }
}
