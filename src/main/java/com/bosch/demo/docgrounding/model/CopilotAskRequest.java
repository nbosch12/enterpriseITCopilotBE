package com.bosch.demo.docgrounding.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Single unified request that the frontend sends to the orchestration layer.
 *
 * <p>The orchestrator will inspect the {@code question} and decide which tool(s) to invoke.
 * Callers can optionally hint at a preferred domain or pass extra tool-specific parameters.</p>
 *
 * @param question        The natural-language question from the user. Required.
 * @param sessionId       Optional session ID for conversation memory / multi-turn support.
 * @param useHistory      Whether to include prior conversation turns in the LLM context (default true).
 * @param historyTurns    How many past turns to include (default 6).
 * @param appName         Optional Azure app name hint – used when the logs tool is selected.
 * @param timeDuration    Optional log time range hint, e.g. "1h", "6h", "24h".
 * @param repositoryId    Optional vector-search repository ID hint – used when the grounding tool is selected.
 * @param s3Prefix        Optional S3 prefix hint for vector search.
 * @param forceTool       Optional tool name to force-invoke, bypassing the router (e.g. "AZURE_LOGS", "DOCUPEDIA").
 */
public record CopilotAskRequest(
        @NotBlank String question,
        String sessionId,
        Boolean useHistory,
        Integer historyTurns,
        String appName,
        String timeDuration,
        String repositoryId,
        String s3Prefix,
        String forceTool
) {}
