package com.bosch.demo.docgrounding.model;

import java.util.Map;

/**
 * Normalised output returned by any {@code CopilotTool} back to the orchestrator.
 *
 * @param toolName    Unique name of the tool that produced this result (e.g. "AZURE_LOGS").
 * @param success     Whether the tool executed without error.
 * @param summary     A short human-readable summary of what was found (included in LLM context).
 * @param rawPayload  The full structured payload as a string (JSON or plain text) for UI drill-down.
 * @param metadata    Optional key-value metadata (e.g. log count, matched chunks, time range used).
 * @param errorMessage Error detail if {@code success} is false.
 */
public record ToolResult(
        String toolName,
        boolean success,
        String summary,
        String rawPayload,
        Map<String, Object> metadata,
        String errorMessage
) {
    /** Factory for a successful result. */
    public static ToolResult ok(String toolName, String summary, String rawPayload, Map<String, Object> metadata) {
        return new ToolResult(toolName, true, summary, rawPayload, metadata, null);
    }

    /** Factory for a failed result. */
    public static ToolResult error(String toolName, String errorMessage) {
        return new ToolResult(toolName, false, "Tool execution failed: " + errorMessage, null, Map.of(), errorMessage);
    }
}
