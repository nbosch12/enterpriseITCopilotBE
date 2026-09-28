package com.bosch.demo.docgrounding.model;

import java.util.List;

/**
 * Unified response returned by the orchestration layer to the frontend.
 *
 * @param answer           The final synthesized answer from the LLM.
 * @param toolsUsed        Names of the tools that were invoked to produce the answer.
 * @param toolResults      Raw structured results from each tool (useful for UI drill-down / debug).
 * @param sessionId        Echo of the session ID so the frontend can persist it.
 * @param processingNotes  Any warnings or informational notes from the orchestration (e.g. a tool was skipped).
 */
public record CopilotAskResponse(
        String answer,
        List<String> toolsUsed,
        List<ToolResult> toolResults,
        String sessionId,
        List<String> processingNotes
) {}
