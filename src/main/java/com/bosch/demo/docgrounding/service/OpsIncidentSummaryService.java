package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.model.ExceptionLogEntry;
import com.bosch.demo.docgrounding.model.IncidentSummaryRequest;
import com.bosch.demo.docgrounding.model.IncidentSummaryResponse;
import com.bosch.demo.docgrounding.model.LogQueryResult;
import com.bosch.demo.docgrounding.model.NormalizedLogContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
public class OpsIncidentSummaryService {

    private static final int DEFAULT_LIMIT = 10;

    private final AzureSpringAppLogService azureSpringAppLogService;
    private final LogNormalizerService logNormalizerService;
    private final LogPromptBuilderService logPromptBuilderService;
    private final LogIncidentLlmClient logIncidentLlmClient;
    private final ObjectMapper objectMapper;

    public OpsIncidentSummaryService(
            AzureSpringAppLogService azureSpringAppLogService,
            LogNormalizerService logNormalizerService,
            LogPromptBuilderService logPromptBuilderService,
            LogIncidentLlmClient logIncidentLlmClient,
            ObjectMapper objectMapper) {
        this.azureSpringAppLogService = azureSpringAppLogService;
        this.logNormalizerService = logNormalizerService;
        this.logPromptBuilderService = logPromptBuilderService;
        this.logIncidentLlmClient = logIncidentLlmClient;
        this.objectMapper = objectMapper;
    }

    public Mono<IncidentSummaryResponse> summarizeIncident(IncidentSummaryRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            return Mono.just(buildInputErrorResponse("question must not be empty"));
        }

        int safeLimit = sanitizeLimit(request.limit());

        return azureSpringAppLogService
                .getAppLogs(request.appName(), safeLimit, request.keywordToSearch(), request.timeDuration())
                .flatMap(logs -> summarizeFromLogs(request.question(), logs));
    }

    private Mono<IncidentSummaryResponse> summarizeFromLogs(String question, LogQueryResult logs) {
        LogQueryResult effectiveLogs = logs == null ? emptyLogs() : logs;
        NormalizedLogContext normalized = logNormalizerService.normalize(effectiveLogs);

        if (effectiveLogs.isHasErrors()) {
            return Mono.just(buildFallbackResponse(question, effectiveLogs, normalized,
                    "LLM summary skipped because log retrieval returned an error."));
        }

        if (effectiveLogs.getTotalCount() == 0) {
            return Mono.just(buildFallbackResponse(question, effectiveLogs, normalized,
                    "No matching log entries were found for the selected criteria."));
        }

        String prompt = logPromptBuilderService.buildPrompt(question, normalized);

        return logIncidentLlmClient.summarizeIncident(prompt)
                .map(rawResponse -> parseStructuredResponse(rawResponse, question, effectiveLogs))
                .onErrorResume(ex -> {
                    log.error("Failed to summarize logs with LLM", ex);
                    return Mono.just(buildFallbackResponse(question, effectiveLogs, normalized,
                            "LLM summarization failed: " + ex.getMessage()));
                });
    }

    private IncidentSummaryResponse parseStructuredResponse(String rawResponse, String question, LogQueryResult sourceLogs) {
        try {
            String cleanedJson = stripMarkdownCodeFences(rawResponse);
            JsonNode root = objectMapper.readTree(cleanedJson);

            IncidentSummaryResponse response = new IncidentSummaryResponse();
            response.setQuestion(question);
            response.setTitle(textValue(root, "title", "Incident Summary"));
            response.setOverallSeverity(resolveSeverity(root));
            response.setSummary(textValue(root, "summary", "No summary generated."));
            response.setImpactedApps(readStringArray(root, "impactedApps"));
            response.setLikelyCauses(readStringArray(root, "likelyCauses"));
            response.setRecommendedActions(readStringArray(root, "recommendedActions"));
            response.setKeyObservations(readStringArray(root, "keyObservations"));
            response.setUsedFallback(false);
            response.setRawModelResponse(rawResponse);
            response.setSourceLogs(sourceLogs);
            ensureLists(response, sourceLogs);
            return response;
        } catch (Exception ex) {
            log.warn("Failed to parse incident summary JSON from LLM output", ex);
            return buildFallbackResponse(question, sourceLogs, logNormalizerService.normalize(sourceLogs),
                    "LLM output could not be parsed as structured JSON.");
        }
    }

    private IncidentSummaryResponse buildFallbackResponse(
            String question,
            LogQueryResult sourceLogs,
            NormalizedLogContext normalized,
            String fallbackReason) {

        IncidentSummaryResponse response = new IncidentSummaryResponse();
        response.setQuestion(question);
        response.setTitle(sourceLogs.getTotalCount() == 0 ? "No matching incidents" : "Incident Summary (Fallback)");
        response.setOverallSeverity(determineSeverity(sourceLogs));
        response.setSummary(buildFallbackSummary(sourceLogs, normalized, fallbackReason));
        response.setImpactedApps(new ArrayList<>(normalized.impactedApps()));
        response.setLikelyCauses(buildLikelyCauses(sourceLogs, normalized));
        response.setRecommendedActions(buildRecommendedActions(sourceLogs, normalized));
        response.setKeyObservations(buildKeyObservations(sourceLogs, normalized));
        response.setUsedFallback(true);
        response.setRawModelResponse(fallbackReason);
        response.setSourceLogs(sourceLogs);
        return response;
    }

    private IncidentSummaryResponse buildInputErrorResponse(String message) {
        IncidentSummaryResponse response = new IncidentSummaryResponse();
        response.setQuestion(null);
        response.setTitle("Invalid incident summary request");
        response.setOverallSeverity("INFO");
        response.setSummary(message);
        response.setImpactedApps(List.of());
        response.setLikelyCauses(List.of("User input validation failed."));
        response.setRecommendedActions(List.of("Provide a non-empty question in the request body."));
        response.setKeyObservations(List.of(message));
        response.setUsedFallback(true);
        response.setRawModelResponse(message);
        response.setSourceLogs(emptyLogs());
        return response;
    }

    private List<String> buildLikelyCauses(LogQueryResult sourceLogs, NormalizedLogContext normalized) {
        List<String> causes = new ArrayList<>();
        if (sourceLogs.getTotalCount() == 0) {
            causes.add("No matching incidents were found in the selected time window.");
            return causes;
        }

        normalized.topPatterns().stream()
                .limit(3)
                .forEach(pattern -> causes.add("Repeated pattern observed: " + pattern));

        if (causes.isEmpty()) {
            causes.add("No dominant recurring cause was identified from the available logs.");
        }

        return causes;
    }

    private List<String> buildRecommendedActions(LogQueryResult sourceLogs, NormalizedLogContext normalized) {
        List<String> actions = new ArrayList<>();
        if (sourceLogs.isHasErrors()) {
            actions.add("Fix Azure Monitor connectivity or query issues before relying on the summary.");
        }

        String severity = determineSeverity(sourceLogs);
        if ("CRITICAL".equals(severity) || "HIGH".equals(severity)) {
            actions.add("Investigate the latest high-severity errors immediately and correlate them with recent deployments or dependency outages.");
        }

        String normalizedText = normalized.normalizedText().toLowerCase(Locale.ROOT);
        if (normalizedText.contains("timeout")) {
            actions.add("Check downstream latency, network stability, and timeout settings for dependent services.");
        }
        if (normalizedText.contains("unauthorized") || normalizedText.contains("403") || normalizedText.contains("401")) {
            actions.add("Verify credentials, tokens, and access roles for the failing integration.");
        }
        if (normalizedText.contains("nullpointer") || normalizedText.contains("null pointer")) {
            actions.add("Inspect null handling around the failing operation and review recent code changes.");
        }

        if (actions.isEmpty()) {
            actions.add("Review the latest matching log events and correlate them with the affected application flow.");
        }

        return actions.stream().distinct().toList();
    }

    private List<String> buildKeyObservations(LogQueryResult sourceLogs, NormalizedLogContext normalized) {
        List<String> observations = new ArrayList<>();
        observations.add("Query period: " + normalized.queryPeriod());
        observations.add("Total matching log entries: " + sourceLogs.getTotalCount());
        normalized.impactedApps().stream().limit(3)
                .forEach(app -> observations.add("Impacted app: " + app));
        normalized.recentEvents().stream().limit(3)
                .forEach(event -> observations.add("Recent event: " + event));
        return observations;
    }

    private String buildFallbackSummary(LogQueryResult sourceLogs, NormalizedLogContext normalized, String fallbackReason) {
        if (sourceLogs.isHasErrors()) {
            return fallbackReason + " Workspace response: " + sourceLogs.getErrorMessage();
        }
        if (sourceLogs.getTotalCount() == 0) {
            return fallbackReason;
        }

        String topApp = normalized.impactedApps().isEmpty() ? "no specific app" : normalized.impactedApps().getFirst();
        String topPattern = normalized.topPatterns().isEmpty() ? "no dominant pattern" : normalized.topPatterns().getFirst();
        return fallbackReason
                + " Found " + sourceLogs.getTotalCount()
                + " matching log entries in " + normalized.queryPeriod()
                + ", with the heaviest impact on " + topApp
                + ". Most visible pattern: " + topPattern + ".";
    }

    private String determineSeverity(LogQueryResult sourceLogs) {
        int maxSeverity = 0;
        if (sourceLogs.getLatestExceptions() != null) {
            maxSeverity = sourceLogs.getLatestExceptions().stream()
                    .map(ExceptionLogEntry::getSeverityLevel)
                    .max(Comparator.naturalOrder())
                    .orElse(0);
        }

        if (sourceLogs.isHasErrors()) {
            return "HIGH";
        }
        if (maxSeverity >= 4 || sourceLogs.getTotalCount() >= 25) {
            return "CRITICAL";
        }
        if (maxSeverity == 3 || sourceLogs.getTotalCount() >= 10) {
            return "HIGH";
        }
        if (maxSeverity == 2 || sourceLogs.getTotalCount() >= 5) {
            return "MEDIUM";
        }
        if (sourceLogs.getTotalCount() > 0) {
            return "LOW";
        }
        return "INFO";
    }

    private void ensureLists(IncidentSummaryResponse response, LogQueryResult sourceLogs) {
        if (response.getImpactedApps() == null || response.getImpactedApps().isEmpty()) {
            response.setImpactedApps(defaultImpactedApps(sourceLogs));
        }
        if (response.getLikelyCauses() == null || response.getLikelyCauses().isEmpty()) {
            response.setLikelyCauses(List.of("The model did not return likely causes; inspect recurring log patterns in sourceLogs."));
        }
        if (response.getRecommendedActions() == null || response.getRecommendedActions().isEmpty()) {
            response.setRecommendedActions(List.of("Inspect the latest matching errors in sourceLogs and correlate them with dependent service health."));
        }
        if (response.getKeyObservations() == null || response.getKeyObservations().isEmpty()) {
            response.setKeyObservations(List.of("Source logs were analyzed for a structured incident summary."));
        }
    }

    private List<String> defaultImpactedApps(LogQueryResult sourceLogs) {
        if (sourceLogs.getCountByApp() == null || sourceLogs.getCountByApp().isEmpty()) {
            return List.of();
        }

        return sourceLogs.getCountByApp().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    private String resolveSeverity(JsonNode root) {
        String value = textValue(root, "overallSeverity", textValue(root, "severity", "MEDIUM"));
        String normalized = value.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL" -> normalized;
            default -> "MEDIUM";
        };
    }

    private List<String> readStringArray(JsonNode root, String fieldName) {
        JsonNode arrayNode = root.get(fieldName);
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        arrayNode.forEach(node -> values.add(node.asText()));
        return values;
    }

    private String textValue(JsonNode root, String fieldName, String defaultValue) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return defaultValue;
        }
        return node.asText();
    }

    private String stripMarkdownCodeFences(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?", "").trim();
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        return cleaned;
    }

    private int sanitizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, 100));
    }

    private LogQueryResult emptyLogs() {
        LogQueryResult result = new LogQueryResult();
        result.setTotalCount(0);
        result.setCountByApp(Map.of());
        result.setLatestExceptions(List.of());
        result.setQueryPeriod("Selected period");
        result.setHasErrors(false);
        result.setErrorMessage(null);
        return result;
    }
}