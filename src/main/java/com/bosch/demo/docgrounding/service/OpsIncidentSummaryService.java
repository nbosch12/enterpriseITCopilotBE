package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.ExceptionLogEntry;
import com.bosch.demo.docgrounding.model.IncidentSummaryRequest;
import com.bosch.demo.docgrounding.model.IncidentSummaryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OpsIncidentSummaryService {

    private static final int    DEFAULT_LIMIT    = 20;
    private static final String DEFAULT_DURATION = "1h";

    private final AzureSpringAppLogService azureSpringAppLogService;
    private final AiCoreTokenService       tokenService;
    private final AppProperties            properties;
    private final ObjectMapper             objectMapper;
    private final WebClient                webClient;

    public OpsIncidentSummaryService(
            AzureSpringAppLogService azureSpringAppLogService,
            AiCoreTokenService tokenService,
            AppProperties properties,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder) {
        this.azureSpringAppLogService = azureSpringAppLogService;
        this.tokenService  = tokenService;
        this.properties    = properties;
        this.objectMapper  = objectMapper;
        this.webClient     = webClientBuilder.baseUrl(properties.getSapAiCore().getApiUrl()).build();
    }

    public Mono<IncidentSummaryResponse> summarizeIncident(IncidentSummaryRequest request) {
        int    limit    = request.limit() != null ? Math.max(1, Math.min(request.limit(), 100)) : DEFAULT_LIMIT;
        String duration = request.timeDuration() != null && !request.timeDuration().isBlank()
                ? request.timeDuration() : DEFAULT_DURATION;

        return azureSpringAppLogService.getAppLogs(request.appName(), limit, null, duration)
                .flatMap(logResult -> {
                    List<ExceptionLogEntry> entries = logResult.getLatestExceptions();
                    if (logResult.isHasErrors()) {
                        return Mono.just(new IncidentSummaryResponse(
                                "Unable to fetch logs: " + logResult.getErrorMessage(),
                                request.question(), request.appName(), duration, 0));
                    }
                    if (entries == null || entries.isEmpty()) {
                        return Mono.just(new IncidentSummaryResponse(
                                "No log entries found for the specified criteria.",
                                request.question(), request.appName(), duration, 0));
                    }
                    String logsText = formatLogsForPrompt(entries);
                    int    logCount = entries.size();
                    return tokenService.getAccessToken()
                            .flatMap(token -> Mono.fromCallable(
                                    () -> callLlm(token, request.question(), logsText))
                                    .subscribeOn(Schedulers.boundedElastic()))
                            .map(summary -> new IncidentSummaryResponse(
                                    summary, request.question(), request.appName(), duration, logCount))
                            .onErrorResume(ex -> {
                                log.error("LLM summarization failed", ex);
                                return Mono.just(new IncidentSummaryResponse(
                                        "LLM summarization failed: " + ex.getMessage(),
                                        request.question(), request.appName(), duration, logCount));
                            });
                });
    }

    private String callLlm(String token, String question, String logsText) {
        AppProperties.SapAiCore ai = properties.getSapAiCore();
        Map<String, Object> llmConfig = Map.of(
                "model_name", ai.getLlmModelName(),
                "model_params", Map.of("max_tokens", 2000));
        List<Map<String, Object>> template = List.of(
                Map.of("role", "system", "content",
                        "You are an expert operations engineer. Analyse the application logs and answer the question concisely."),
                Map.of("role", "user", "content", "{{?user_prompt}}"));
        Map<String, Object> body = Map.of(
                "orchestration_config", Map.of("module_configurations", Map.of(
                        "llm_module_config", llmConfig,
                        "templating_module_config", Map.of("template", template))),
                "input_params", Map.of("user_prompt", "Question: " + question + "\n\nLogs:\n" + logsText));

        String resp = webClient.post()
                .uri("/v2/inference/deployments/{id}/completion", ai.getOrchestrationDeploymentId())
                .header("AI-Resource-Group", ai.getResourceGroup())
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        return extractAnswer(resp);
    }

    private String extractAnswer(String body) {
        if (body == null || body.isBlank()) return "No summary generated";
        try {
            JsonNode root    = objectMapper.readTree(body);
            JsonNode choices = root.path("orchestration_result").path("choices");
            if (choices.isMissingNode()) choices = root.path("choices");
            return choices.get(0).path("message").path("content").asText("No summary generated");
        } catch (Exception e) { return "Summary could not be extracted."; }
    }

    private String formatLogsForPrompt(List<ExceptionLogEntry> entries) {
        return entries.stream()
                .map(e -> String.format("[%s] [%s] sev=%d | %s",
                        e.getTimeGenerated(), e.getAppRoleName(),
                        e.getSeverityLevel(), truncate(e.getMessage(), 300)))
                .collect(Collectors.joining("\n"));
    }

    private String truncate(String t, int max) {
        if (t == null) return "";
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
