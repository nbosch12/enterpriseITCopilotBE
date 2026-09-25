package com.bosch.demo.docgrounding.service.tools;

import com.bosch.demo.docgrounding.model.ToolRequest;
import com.bosch.demo.docgrounding.model.ToolResult;
import com.bosch.demo.docgrounding.model.VectorAskRequest;
import com.bosch.demo.docgrounding.model.VectorAskResponse;
import com.bosch.demo.docgrounding.service.AiCoreVectorSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Orchestration tool that performs vector-based grounding against Docupedia knowledge base.
 *
 * <p>Activated when the user question contains documentation/knowledge keywords or when
 * {@code forceTool = "DOCUPEDIA"} is set in the request.</p>
 */
@Slf4j
@Component
public class DocupediaGroundingTool implements CopilotTool {

    private static final String TOOL_NAME = "DOCUPEDIA_GROUNDING";

    private final AiCoreVectorSearchService vectorSearchService;
    private final ObjectMapper objectMapper;

    public DocupediaGroundingTool(AiCoreVectorSearchService vectorSearchService,
                                  ObjectMapper objectMapper) {
        this.vectorSearchService = vectorSearchService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "Searches Docupedia knowledge base using vector similarity and returns grounded answers.";
    }

    @Override
    public String[] keywords() {
        return new String[]{
                "doc", "docs", "documentation", "docupedia", "wiki", "knowledge",
                "how", "what", "guide", "tutorial", "runbook", "procedure",
                "manual", "instructions", "help", "reference", "confluence"
        };
    }

    @Override
    public Mono<ToolResult> execute(ToolRequest request) {
        String repositoryId = request.param("repositoryId");
        String s3Prefix     = request.param("s3Prefix");
        String sessionId    = request.sessionId();
        int    maxChunks    = parseIntParam(request.param("maxChunks"), 5);
        int    topK         = parseIntParam(request.param("topK"), 10);
        boolean useHistory  = parseBoolParam(request.param("useHistory"), true);
        int historyTurns    = parseIntParam(request.param("historyTurns"), 6);

        log.info("[{}] Executing – repositoryId={}, sessionId={}, maxChunks={}, topK={}",
                TOOL_NAME, repositoryId, sessionId, maxChunks, topK);

        VectorAskRequest vectorRequest = new VectorAskRequest(
                request.question(),
                repositoryId,
                s3Prefix,
                maxChunks,
                topK,
                sessionId,
                useHistory,
                historyTurns
        );

        return vectorSearchService.ask(vectorRequest)
                .map(response -> buildResult(response, repositoryId, maxChunks))
                .onErrorResume(ex -> {
                    log.error("[{}] Tool execution failed", TOOL_NAME, ex);
                    return Mono.just(ToolResult.error(TOOL_NAME, ex.getMessage()));
                });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private ToolResult buildResult(VectorAskResponse response, String repositoryId, int maxChunks) {
        String summary = buildSummaryText(response);

        String rawPayload;
        try {
            rawPayload = objectMapper.writeValueAsString(response);
        } catch (Exception ex) {
            rawPayload = response.toString();
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("repositoryId", repositoryId != null ? repositoryId : "default");
        metadata.put("sessionId", response.sessionId());
        metadata.put("maxChunks", maxChunks);
        metadata.put("matchCount", response.matches() != null ? response.matches().size() : 0);

        return ToolResult.ok(TOOL_NAME, summary, rawPayload, metadata);
    }

    private String buildSummaryText(VectorAskResponse r) {
        StringBuilder sb = new StringBuilder();
        if (r.answer() != null && !r.answer().isBlank()) {
            sb.append("Grounded Answer: ").append(r.answer()).append("\n");
        }
        if (r.matches() != null && !r.matches().isEmpty()) {
            sb.append("Found ").append(r.matches().size()).append(" relevant document chunks");
        }
        return sb.toString().trim();
    }

    private int parseIntParam(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean parseBoolParam(String value, boolean defaultValue) {
        if (value == null) return defaultValue;
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
}
