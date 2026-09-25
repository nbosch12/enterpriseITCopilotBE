package com.bosch.demo.docgrounding.service.tools;

import com.bosch.demo.docgrounding.model.IncidentSummaryRequest;
import com.bosch.demo.docgrounding.model.IncidentSummaryResponse;
import com.bosch.demo.docgrounding.model.ToolRequest;
import com.bosch.demo.docgrounding.model.ToolResult;
import com.bosch.demo.docgrounding.service.AzureSpringAppLogService;
import com.bosch.demo.docgrounding.service.OpsIncidentSummaryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Orchestration tool that fetches Azure Spring App logs and returns an incident summary.
 *
 * <p>Activated when the user question contains ops/incident keywords or when
 * {@code forceTool = "AZURE_LOGS"} is set in the request.</p>
 */
@Slf4j
@Component
public class AzureLogsTool implements CopilotTool {

    private static final String TOOL_NAME = "AZURE_LOGS";

    private final AzureSpringAppLogService logService;
    private final OpsIncidentSummaryService summaryService;
    private final ObjectMapper objectMapper;

    public AzureLogsTool(AzureSpringAppLogService logService,
                         OpsIncidentSummaryService summaryService,
                         ObjectMapper objectMapper) {
        this.logService = logService;
        this.summaryService = summaryService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "Fetches Azure Spring App exception logs and produces an incident summary using the LLM.";
    }

    @Override
    public String[] keywords() {
        return new String[]{
                "log", "logs", "error", "errors", "exception", "incident",
                "production", "prod", "crash", "failure", "alert", "outage",
                "today", "recent", "latest", "issue", "issues", "azure", "spring"
        };
    }

    @Override
    public Mono<ToolResult> execute(ToolRequest request) {
        String appName      = request.param("appName");
        String timeDuration = request.param("timeDuration", "1h");
        int    limit        = parseIntParam(request.param("limit"), 20);

        log.info("[{}] Executing – app={}, timeDuration={}, limit={}", TOOL_NAME, appName, timeDuration, limit);

        IncidentSummaryRequest summaryRequest = new IncidentSummaryRequest(
                request.question(),
                appName,
                limit,
                null,          // keywordToSearch – let the LLM decide from the full logs
                timeDuration
        );

        return summaryService.summarizeIncident(summaryRequest)
                .map(response -> buildResult(response, appName, timeDuration, limit))
                .onErrorResume(ex -> {
                    log.error("[{}] Tool execution failed", TOOL_NAME, ex);
                    return Mono.just(ToolResult.error(TOOL_NAME, ex.getMessage()));
                });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private ToolResult buildResult(IncidentSummaryResponse response,
                                   String appName, String timeDuration, int limit) {
        String summary = buildSummaryText(response);

        String rawPayload;
        try {
            rawPayload = objectMapper.writeValueAsString(response);
        } catch (Exception ex) {
            rawPayload = response.toString();
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("appName", appName != null ? appName : "all");
        metadata.put("timeDuration", timeDuration);
        metadata.put("logLimit", limit);
        if (response.getOverallSeverity() != null)   metadata.put("severity", response.getOverallSeverity());
        if (response.getImpactedApps() != null) metadata.put("impactedApps", response.getImpactedApps());

        return ToolResult.ok(TOOL_NAME, summary, rawPayload, metadata);
    }

    private String buildSummaryText(IncidentSummaryResponse r) {
        StringBuilder sb = new StringBuilder();
        if (r.getSummary() != null && !r.getSummary().isBlank()) {
            sb.append("Incident Summary: ").append(r.getSummary()).append("\n");
        }
        if (r.getOverallSeverity() != null) {
            sb.append("Severity: ").append(r.getOverallSeverity()).append("\n");
        }
        if (r.getLikelyCauses() != null && !r.getLikelyCauses().isEmpty()) {
            sb.append("Likely Causes: ").append(String.join("; ", r.getLikelyCauses())).append("\n");
        }
        if (r.getRecommendedActions() != null && !r.getRecommendedActions().isEmpty()) {
            sb.append("Recommended Actions: ").append(String.join("; ", r.getRecommendedActions())).append("\n");
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
}
